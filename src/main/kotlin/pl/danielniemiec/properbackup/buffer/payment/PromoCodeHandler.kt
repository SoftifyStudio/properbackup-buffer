package pl.danielniemiec.properbackup.buffer.payment

import com.google.gson.Gson
import io.javalin.http.Context
import org.slf4j.LoggerFactory
import pl.danielniemiec.properbackup.buffer.auth.UserStore
import pl.danielniemiec.properbackup.buffer.db.Database
import java.sql.Timestamp
import java.time.Instant

class PromoCodeHandler(
    private val db: Database,
    private val userStore: UserStore,
) {
    private val log = LoggerFactory.getLogger(PromoCodeHandler::class.java)
    private val gson = Gson()

    /**
     * POST /payment/validate-promo
     * Validates a promo code without marking it as used.
     * Returns discount info so the frontend can recalculate prices.
     */
    fun validatePromoCode(ctx: Context) {
        val userId = ctx.attribute<String>("userId")
        if (userId == null) {
            ctx.status(401).result("Unauthorized")
            return
        }

        val body = gson.fromJson(ctx.body(), Map::class.java) ?: emptyMap<String, Any>()
        val code = (body["code"] as? String)?.trim()?.uppercase()

        if (code.isNullOrBlank()) {
            ctx.status(400).contentType("application/json").result(
                gson.toJson(mapOf("valid" to false, "reason" to "code_required")),
            )
            return
        }

        log.info("Promo code validation: userId={} code={}", userId, code)

        val promo = findValidPromoCode(code)
        if (promo == null) {
            log.info("Promo code INVALID: code={} userId={}", code, userId)
            logAudit(userId, "promo_rejected", mapOf("code" to code, "reason" to "not_found_or_expired"))
            ctx.contentType("application/json").result(
                gson.toJson(mapOf("valid" to false, "reason" to "not_found_or_expired")),
            )
            return
        }

        val type = promo["type"] as String

        if (type == "first_order" && userHadSubscription(userId)) {
            log.info("Promo code REJECTED (first_order, user already had subscription): code={} userId={}", code, userId)
            logAudit(userId, "promo_rejected", mapOf("code" to code, "reason" to "not_eligible_first_order"))
            ctx.contentType("application/json").result(
                gson.toJson(mapOf("valid" to false, "reason" to "not_eligible_first_order")),
            )
            return
        }

        if (type == "one_time" && userAlreadyUsedCode(promo["id"] as Long, userId)) {
            log.info("Promo code REJECTED (one_time, already used by user): code={} userId={}", code, userId)
            logAudit(userId, "promo_rejected", mapOf("code" to code, "reason" to "already_used_by_user"))
            ctx.contentType("application/json").result(
                gson.toJson(mapOf("valid" to false, "reason" to "already_used_by_user")),
            )
            return
        }

        val discountPercent = promo["discount_percent"] as? Int
        val discountGrosze = promo["discount_grosz"] as? Int
        val applicablePlan = promo["applicable_plan"] as? String

        log.info(
            "Promo code VALID: code={} userId={} type={} discountPercent={} discountGrosze={}",
            code, userId, type, discountPercent, discountGrosze,
        )

        logAudit(userId, "promo_validated", mapOf(
            "code" to code, "type" to type,
            "discountPercent" to (discountPercent ?: 0), "discountGrosze" to (discountGrosze ?: 0),
        ))

        ctx.contentType("application/json").result(
            gson.toJson(
                mapOf(
                    "valid" to true,
                    "code" to code,
                    "type" to type,
                    "discountPercent" to discountPercent,
                    "discountGrosze" to discountGrosze,
                    "applicablePlan" to applicablePlan,
                ),
            ),
        )
    }

    /**
     * Mark a promo code as used (called after successful Stripe payment).
     *
     * Atomically: (1) increments used_count only if still under max_uses (CAS in SQL),
     * (2) inserts the per-user usage row protected by UNIQUE(promo_code_id, user_id).
     * Returns true if the code was claimed by this call, false if it was already used by this
     * user OR the max_uses limit has been reached. Safe under concurrent checkouts.
     */
    fun markPromoCodeUsed(code: String, userId: String, stripeSession: String?): Boolean {
        db.getConnection().use { conn ->
            conn.autoCommit = false
            try {
                val promoId = conn.prepareStatement("SELECT id FROM promo_code WHERE code = ?").use { ps ->
                    ps.setString(1, code.uppercase())
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getLong("id") else null }
                }
                if (promoId == null) {
                    log.info("Promo code mark-used: code={} not found", code)
                    return false
                }

                // Atomic check + increment: only succeeds if there is still capacity.
                val updated = conn.prepareStatement(
                    "UPDATE promo_code SET used_count = used_count + 1 WHERE id = ? AND used_count < max_uses",
                ).use { ps ->
                    ps.setLong(1, promoId)
                    ps.executeUpdate()
                }
                if (updated == 0) {
                    log.info("Promo code mark-used REJECTED (max_uses reached): code={} userId={}", code, userId)
                    conn.rollback()
                    return false
                }

                // Per-user uniqueness is enforced by UNIQUE(promo_code_id, user_id).
                try {
                    conn.prepareStatement(
                        "INSERT INTO promo_code_usage (promo_code_id, user_id, stripe_session, used_at) VALUES (?, ?, ?, ?)",
                    ).use { ps ->
                        ps.setLong(1, promoId)
                        ps.setString(2, userId)
                        ps.setString(3, stripeSession)
                        ps.setTimestamp(4, Timestamp.from(Instant.now()))
                        ps.executeUpdate()
                    }
                } catch (e: java.sql.SQLException) {
                    log.info("Promo code mark-used REJECTED (already used by user): code={} userId={}", code, userId)
                    conn.rollback()
                    return false
                }

                conn.commit()
                log.info("Promo code USED: code={} userId={} stripeSession={}", code, userId, stripeSession)
                return true
            } catch (e: Exception) {
                conn.rollback()
                log.warn("Promo code mark-used failed: code={} userId={}: {}", code, userId, e.message)
                return false
            } finally {
                conn.autoCommit = true
            }
        }
    }

    /**
     * Calculate discount amount in grosze for a given promo code and base price.
     */
    fun calculateDiscount(code: String, basePriceGrosze: Long): Long {
        val promo = findValidPromoCode(code) ?: return 0L
        val discountPercent = promo["discount_percent"] as? Int
        val discountGrosze = promo["discount_grosz"] as? Int

        return when {
            discountPercent != null && discountPercent > 0 -> (basePriceGrosze * discountPercent / 100)
            discountGrosze != null && discountGrosze > 0 -> discountGrosze.toLong()
            else -> 0L
        }
    }

    /**
     * Re-validates a promo code at checkout time (server-side guard against the validate→checkout
     * race). Returns `null` when the code is still valid for this user, otherwise a structured
     * reason string the API can pass through to the UI.
     *
     * Caller is expected to abort the checkout with HTTP 410 + the reason, so the user re-applies
     * a fresh promo instead of getting silently charged full price.
     */
    fun validateForCheckout(code: String, userId: String): String? {
        val normalized = code.trim().uppercase()
        if (normalized.isBlank()) return "code_required"
        val promo = findValidPromoCode(normalized) ?: return "not_found_or_expired"
        val type = promo["type"] as String
        if (type == "first_order" && userHadSubscription(userId)) return "not_eligible_first_order"
        if (type == "one_time" && userAlreadyUsedCode(promo["id"] as Long, userId)) return "already_used_by_user"
        return null
    }

    // ---- Admin CRUD ----

    fun listPromoCodes(ctx: Context) {
        requireAdmin(ctx) ?: return

        db.getConnection().use { conn ->
            conn.prepareStatement(
                """SELECT id, code, type, discount_percent, discount_grosz, applicable_plan,
                          max_uses, used_count, active, expires_at, created_at
                   FROM promo_code ORDER BY created_at DESC LIMIT 200""",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val codes = mutableListOf<Map<String, Any?>>()
                    while (rs.next()) {
                        codes.add(
                            mapOf(
                                "id" to rs.getLong("id"),
                                "code" to rs.getString("code"),
                                "type" to rs.getString("type"),
                                "discountPercent" to rs.getObject("discount_percent"),
                                "discountGrosze" to rs.getObject("discount_grosz"),
                                "applicablePlan" to rs.getString("applicable_plan"),
                                "maxUses" to rs.getInt("max_uses"),
                                "usedCount" to rs.getInt("used_count"),
                                "active" to rs.getBoolean("active"),
                                "expiresAt" to rs.getTimestamp("expires_at")?.toInstant()?.toString(),
                                "createdAt" to rs.getTimestamp("created_at").toInstant().toString(),
                            ),
                        )
                    }
                    ctx.contentType("application/json").result(gson.toJson(mapOf("promoCodes" to codes)))
                }
            }
        }
    }

    fun createPromoCode(ctx: Context) {
        requireAdmin(ctx) ?: return

        val body = gson.fromJson(ctx.body(), Map::class.java) ?: emptyMap<String, Any>()
        val code = (body["code"] as? String)?.trim()?.uppercase()
        val type = (body["type"] as? String) ?: "percentage"
        val discountPercent = (body["discountPercent"] as? Number)?.toInt()
        val discountGrosze = (body["discountGrosze"] as? Number)?.toInt()
        val applicablePlan = body["applicablePlan"] as? String
        val maxUses = (body["maxUses"] as? Number)?.toInt() ?: 1
        val expiresAt = (body["expiresAt"] as? String)?.let { Instant.parse(it) }

        if (code.isNullOrBlank()) {
            ctx.status(400).contentType("application/json").result(
                gson.toJson(mapOf("error" to "code_required")),
            )
            return
        }

        db.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO promo_code (code, type, discount_percent, discount_grosz, applicable_plan, max_uses, expires_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?)""",
            ).use { ps ->
                ps.setString(1, code)
                ps.setString(2, type)
                if (discountPercent != null) ps.setInt(3, discountPercent) else ps.setNull(3, java.sql.Types.INTEGER)
                if (discountGrosze != null) ps.setInt(4, discountGrosze) else ps.setNull(4, java.sql.Types.INTEGER)
                if (applicablePlan != null) ps.setString(5, applicablePlan) else ps.setNull(5, java.sql.Types.VARCHAR)
                ps.setInt(6, maxUses)
                if (expiresAt != null) ps.setTimestamp(7, Timestamp.from(expiresAt)) else ps.setNull(7, java.sql.Types.TIMESTAMP)
                ps.executeUpdate()
            }
        }

        log.info("Promo code CREATED: code={} type={} discountPercent={} discountGrosze={}", code, type, discountPercent, discountGrosze)
        ctx.status(201).contentType("application/json").result(gson.toJson(mapOf("status" to "created", "code" to code)))
    }

    fun updatePromoCode(ctx: Context) {
        requireAdmin(ctx) ?: return

        val id = ctx.pathParam("id").toLongOrNull()
        if (id == null) {
            ctx.status(400).result("Invalid id")
            return
        }

        val body = gson.fromJson(ctx.body(), Map::class.java) ?: emptyMap<String, Any>()
        val active = body["active"] as? Boolean
        val maxUses = (body["maxUses"] as? Number)?.toInt()
        val expiresAt = (body["expiresAt"] as? String)?.let { Instant.parse(it) }

        // Parameterized UPDATE — see P3-13 in the audit. Previously the SET fragments were built
        // via string interpolation (e.g. "active = $active"). Even though the values came through
        // typed parsing (Boolean/Int/Instant), mixing data into SQL text is a bad-habit smell;
        // any future refactor that loosens the parsing would create an SQL-injection sink.
        // Build the fragments with placeholders and bind everything via PreparedStatement.
        val fragments = mutableListOf<String>()
        val binders = mutableListOf<(java.sql.PreparedStatement, Int) -> Unit>()
        if (active != null) {
            fragments.add("active = ?")
            binders.add { ps, i -> ps.setBoolean(i, active) }
        }
        if (maxUses != null) {
            fragments.add("max_uses = ?")
            binders.add { ps, i -> ps.setInt(i, maxUses) }
        }
        if (expiresAt != null) {
            fragments.add("expires_at = ?")
            binders.add { ps, i -> ps.setTimestamp(i, Timestamp.from(expiresAt)) }
        }

        if (fragments.isEmpty()) {
            ctx.status(400).result("Nothing to update")
            return
        }

        db.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE promo_code SET ${fragments.joinToString(", ")} WHERE id = ?",
            ).use { ps ->
                binders.forEachIndexed { idx, bind -> bind(ps, idx + 1) }
                ps.setLong(fragments.size + 1, id)
                val rows = ps.executeUpdate()
                if (rows == 0) {
                    ctx.status(404).result("Not found")
                } else {
                    ctx.contentType("application/json").result(gson.toJson(mapOf("status" to "updated")))
                }
            }
        }
    }

    fun deletePromoCode(ctx: Context) {
        requireAdmin(ctx) ?: return

        val id = ctx.pathParam("id").toLongOrNull()
        if (id == null) {
            ctx.status(400).result("Invalid id")
            return
        }

        db.getConnection().use { conn ->
            conn.prepareStatement("UPDATE promo_code SET active = FALSE WHERE id = ?").use { ps ->
                ps.setLong(1, id)
                val rows = ps.executeUpdate()
                if (rows == 0) ctx.status(404).result("Not found")
                else ctx.contentType("application/json").result(gson.toJson(mapOf("status" to "deactivated")))
            }
        }
    }

    // ---- Private helpers ----

    private fun findValidPromoCode(code: String): Map<String, Any?>? {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """SELECT id, code, type, discount_percent, discount_grosz, applicable_plan, max_uses, used_count
                   FROM promo_code
                   WHERE code = ?
                     AND active = TRUE
                     AND used_count < max_uses
                     AND (expires_at IS NULL OR expires_at > NOW())""",
            ).use { ps ->
                ps.setString(1, code)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) {
                        mapOf(
                            "id" to rs.getLong("id"),
                            "code" to rs.getString("code"),
                            "type" to rs.getString("type"),
                            "discount_percent" to rs.getObject("discount_percent") as? Int,
                            "discount_grosz" to rs.getObject("discount_grosz") as? Int,
                            "applicable_plan" to rs.getString("applicable_plan"),
                            "max_uses" to rs.getInt("max_uses"),
                            "used_count" to rs.getInt("used_count"),
                        )
                    } else {
                        null
                    }
                }
            }
        }
    }

    private fun userHadSubscription(userId: String): Boolean {
        // Use `ever_subscribed` flag rather than the current subscription_plan: after a user
        // cancels and the period ends, subscription_plan is NULL but they're not eligible for a
        // "first order" promo any more. ever_subscribed is set on first activateSubscription().
        val user = userStore.findById(userId).orElse(null) ?: return false
        return user.everSubscribed || user.subscriptionPlan != null
    }

    private fun userAlreadyUsedCode(promoCodeId: Long, userId: String): Boolean {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM promo_code_usage WHERE promo_code_id = ? AND user_id = ?",
            ).use { ps ->
                ps.setLong(1, promoCodeId)
                ps.setString(2, userId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1) > 0
                }
            }
        }
    }

    private fun requireAdmin(ctx: Context): Boolean? {
        val isAdmin = java.lang.Boolean.TRUE == ctx.attribute<Boolean>("isSystemAdmin")
        if (!isAdmin) {
            ctx.status(403).result("Forbidden — admin only")
            return null
        }
        return true
    }

    private fun logAudit(userId: String, action: String, details: Map<String, Any>) {
        try {
            db.getConnection().use { conn ->
                conn.prepareStatement(
                    "INSERT INTO subscription_audit_log (user_id, action, details) VALUES (?, ?, ?)",
                ).use { ps ->
                    ps.setString(1, userId)
                    ps.setString(2, action)
                    ps.setString(3, gson.toJson(details))
                    ps.executeUpdate()
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to log audit: userId={} action={}: {}", userId, action, e.message)
        }
    }
}
