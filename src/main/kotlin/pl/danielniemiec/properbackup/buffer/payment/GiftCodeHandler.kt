package pl.danielniemiec.properbackup.buffer.payment

import com.google.gson.Gson
import io.javalin.http.Context
import org.slf4j.LoggerFactory
import pl.danielniemiec.properbackup.buffer.auth.UserStore
import pl.danielniemiec.properbackup.buffer.db.Database
import java.sql.Timestamp
import java.time.Instant

class GiftCodeHandler(
    private val db: Database,
    private val userStore: UserStore,
) {
    private val log = LoggerFactory.getLogger(GiftCodeHandler::class.java)
    private val gson = Gson()

    companion object {
        private const val STORAGE_LIMIT_BYTES = 2199023255552L // 2TB
    }

    fun redeemGiftCode(ctx: Context) {
        val userId = ctx.attribute<String>("userId")
        if (userId == null) {
            ctx.status(401).result("Unauthorized")
            return
        }

        val body = gson.fromJson(ctx.body(), Map::class.java) ?: emptyMap<String, Any>()
        val code = (body["code"] as? String)?.trim()

        if (code.isNullOrBlank()) {
            log.warn("Gift code redeem attempt with empty code by userId={}", userId)
            ctx.status(400).contentType("application/json").result(
                gson.toJson(mapOf("error" to "code_required", "message" to "Gift code is required")),
            )
            return
        }

        log.info("Gift code redeem attempt: userId={} code={}", userId, code)

        val giftCode = findAvailableCode(code)
        if (giftCode == null) {
            log.warn("Gift code redeem REJECTED: code={} userId={} reason=not_found_or_used", code, userId)
            ctx.status(404).contentType("application/json").result(
                gson.toJson(mapOf("error" to "invalid_code", "message" to "Gift code not found, already used, or expired")),
            )
            return
        }

        val user = userStore.findById(userId)
        if (!user.isPresent) {
            log.error("Gift code redeem ERROR: userId={} not found in users table", userId)
            ctx.status(404).contentType("application/json").result(
                gson.toJson(mapOf("error" to "user_not_found", "message" to "User not found")),
            )
            return
        }

        val u = user.get()
        val email = u.email
        val plan = giftCode["plan"] as String
        val durationDays = (giftCode["duration_days"] as Number).toInt()
        val codeId = (giftCode["id"] as Number).toLong()

        // Collision guard: refuse the gift if the user already has an ACTIVE Stripe
        // subscription. Otherwise updateSubscription below would silently clobber the paid
        // expiry/plan and the user would lose value (e.g. annual sub with 200 days → gift sub
        // with 30 days). Forcing the user to first cancel their paid subscription keeps the
        // accounting clean. NOTE: a trial (`subscription_plan == null`) is fine to replace,
        // as it is free.
        if (!u.stripeSubscriptionId.isNullOrBlank()) {
            log.warn("Gift code redeem REJECTED: userId={} already has Stripe sub={} — must cancel first", userId, u.stripeSubscriptionId)
            ctx.status(409).contentType("application/json").result(
                gson.toJson(mapOf(
                    "error" to "active_paid_subscription",
                    "message" to "You already have an active paid subscription. Cancel it first to redeem this gift code.",
                    "stripeSubscriptionId" to u.stripeSubscriptionId,
                )),
            )
            return
        }

        val redeemed = markCodeRedeemed(codeId, userId, email)
        if (!redeemed) {
            log.error("Gift code redeem RACE CONDITION: code={} userId={} — code was claimed between check and update", code, userId)
            ctx.status(409).contentType("application/json").result(
                gson.toJson(mapOf("error" to "already_redeemed", "message" to "Gift code was just redeemed by someone else")),
            )
            return
        }

        // Stack the gift duration on top of any REMAINING gift/manual subscription window.
        // The previous behaviour replaced the entire expiry which silently swallowed leftover
        // days from earlier gift redemptions. Now: if current expiry is still in the future
        // and not Stripe-managed (already gated above), extend from there.
        val now = Instant.now()
        val durationSeconds = durationDays.toLong() * 24 * 60 * 60
        val currentExpiry = u.subscriptionExpiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val baseTime = if (currentExpiry != null && currentExpiry.isAfter(now)) currentExpiry else now
        val expiresAt = baseTime.plusSeconds(durationSeconds)
        userStore.updateSubscription(userId, plan, expiresAt, STORAGE_LIMIT_BYTES)

        log.info(
            "Gift code REDEEMED: code={} userId={} email={} plan={} durationDays={} subscriptionExpiresAt={}",
            code, userId, email, plan, durationDays, expiresAt,
        )

        ctx.contentType("application/json").result(
            gson.toJson(
                mapOf(
                    "status" to "redeemed",
                    "code" to code,
                    "plan" to plan,
                    "durationDays" to durationDays,
                    "expiresAt" to expiresAt.toString(),
                    "email" to email,
                ),
            ),
        )
    }

    fun listGiftCodes(ctx: Context) {
        val isAdmin = java.lang.Boolean.TRUE == ctx.attribute<Boolean>("isSystemAdmin")
        if (!isAdmin) {
            ctx.status(403).result("Forbidden — admin only")
            return
        }

        db.getConnection().use { conn ->
            conn.prepareStatement(
                """SELECT id, code, plan, duration_days, redeemed_by, redeemed_email, redeemed_at, created_at, expires_at
                   FROM gift_code ORDER BY created_at DESC LIMIT 100""",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val codes = mutableListOf<Map<String, Any?>>()
                    while (rs.next()) {
                        codes.add(
                            mapOf(
                                "id" to rs.getLong("id"),
                                "code" to rs.getString("code"),
                                "plan" to rs.getString("plan"),
                                "durationDays" to rs.getInt("duration_days"),
                                "redeemedBy" to rs.getString("redeemed_by"),
                                "redeemedEmail" to rs.getString("redeemed_email"),
                                "redeemedAt" to rs.getTimestamp("redeemed_at")?.toInstant()?.toString(),
                                "createdAt" to rs.getTimestamp("created_at").toInstant().toString(),
                                "expiresAt" to rs.getTimestamp("expires_at")?.toInstant()?.toString(),
                                "available" to (rs.getString("redeemed_by") == null),
                            ),
                        )
                    }
                    log.info("Gift codes listed by admin: count={}", codes.size)
                    ctx.contentType("application/json").result(gson.toJson(mapOf("giftCodes" to codes)))
                }
            }
        }
    }

    private fun findAvailableCode(code: String): Map<String, Any>? {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """SELECT id, code, plan, duration_days
                   FROM gift_code
                   WHERE code = ?
                     AND redeemed_by IS NULL
                     AND (expires_at IS NULL OR expires_at > NOW())""",
            ).use { ps ->
                ps.setString(1, code)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) {
                        mapOf(
                            "id" to rs.getLong("id"),
                            "code" to rs.getString("code"),
                            "plan" to rs.getString("plan"),
                            "duration_days" to rs.getInt("duration_days"),
                        )
                    } else {
                        null
                    }
                }
            }
        }
    }

    private fun markCodeRedeemed(codeId: Long, userId: String, email: String): Boolean {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """UPDATE gift_code
                   SET redeemed_by = ?, redeemed_email = ?, redeemed_at = ?
                   WHERE id = ? AND redeemed_by IS NULL""",
            ).use { ps ->
                ps.setString(1, userId)
                ps.setString(2, email)
                ps.setTimestamp(3, Timestamp.from(Instant.now()))
                ps.setLong(4, codeId)
                return ps.executeUpdate() > 0
            }
        }
    }
}
