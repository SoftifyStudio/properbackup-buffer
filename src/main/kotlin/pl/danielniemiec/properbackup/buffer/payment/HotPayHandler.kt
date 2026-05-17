package pl.danielniemiec.properbackup.buffer.payment

import com.google.gson.Gson
import io.javalin.http.Context
import org.slf4j.LoggerFactory
import pl.danielniemiec.properbackup.buffer.auth.UserStore
import pl.danielniemiec.properbackup.buffer.db.Database
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

class HotPayHandler(
    private val db: Database,
    private val userStore: UserStore,
) {
    private val log = LoggerFactory.getLogger(HotPayHandler::class.java)
    private val gson = Gson()

    private val hotpaySecret = System.getenv("HOTPAY_SECRET") ?: ""
    private val hotpayNotificationPassword = System.getenv("HOTPAY_NOTIFICATION_PASSWORD") ?: ""
    private val testMode = System.getenv("HOTPAY_TEST_MODE")?.lowercase() != "false"
    private val panelUrl = System.getenv("PROPERBACKUP_PANEL_URL") ?: "https://panel.properbackup.com"

    companion object {
        const val MONTHLY_PLAN = "monthly"
        const val ANNUAL_PLAN = "annual"
        const val MONTHLY_PRICE = "19.00"
        const val ANNUAL_PRICE = "190.00"
    }

    fun initiateCheckout(ctx: Context) {
        val userId = ctx.attribute<String>("userId")
        if (userId == null) {
            ctx.status(401).result("Unauthorized")
            return
        }
        val plan = ctx.queryParam("plan") ?: MONTHLY_PLAN
        val price = if (plan == ANNUAL_PLAN) ANNUAL_PRICE else MONTHLY_PRICE
        val planLabel = if (plan == ANNUAL_PLAN) "ProperBackup Annual" else "ProperBackup Monthly"

        val orderId = "pb-${UUID.randomUUID().toString().take(8)}"

        createOrder(orderId, userId, plan, price.toBigDecimal())

        if (testMode) {
            log.info("HotPay TEST MODE: orderId={} plan={} price={} PLN", orderId, plan, price)
            ctx.contentType("application/json").result(
                gson.toJson(
                    mapOf(
                        "testMode" to true,
                        "orderId" to orderId,
                        "plan" to plan,
                        "price" to price,
                        "currency" to "PLN",
                        "simulateUrl" to "$panelUrl/payment/simulate?orderId=$orderId",
                    ),
                ),
            )
            return
        }

        val returnUrl = "$panelUrl/account/subscription?payment=complete"
        val hash = sha256("$hotpayNotificationPassword;$price;$planLabel;$returnUrl;$orderId;$hotpaySecret")

        ctx.contentType("application/json").result(
            gson.toJson(
                mapOf(
                    "testMode" to false,
                    "checkoutUrl" to "https://platnosc.hotpay.pl/",
                    "formData" to mapOf(
                        "SEKRET" to hotpaySecret,
                        "KWOTA" to price,
                        "NAZWA_USLUGI" to planLabel,
                        "ADRES_WWW" to returnUrl,
                        "ID_ZAMOWIENIA" to orderId,
                        "HASH" to hash,
                    ),
                ),
            ),
        )
    }

    fun simulatePayment(ctx: Context) {
        if (!testMode) {
            ctx.status(403).result("Simulation only available in test mode")
            return
        }

        val body = gson.fromJson(ctx.body(), Map::class.java) ?: emptyMap<String, Any>()
        val orderId = (body["orderId"] as? String)?.trim()
        val method = (body["method"] as? String) ?: "blik"

        if (orderId.isNullOrBlank()) {
            ctx.status(400).result("orderId required")
            return
        }

        log.info("HotPay SIMULATE: orderId={} method={}", orderId, method)

        val order = findOrder(orderId)
        if (order == null) {
            ctx.status(404).result("Order not found")
            return
        }

        updateOrderStatus(orderId, "success", "sim-${UUID.randomUUID().toString().take(8)}")

        val storageLimitBytes = 2199023255552L // 2TB
        val plan = order["plan"] as String
        val duration = if (plan == ANNUAL_PLAN) 365L * 24 * 60 * 60 else 30L * 24 * 60 * 60
        val expiresAt = Instant.now().plusSeconds(duration)

        userStore.updateSubscription(order["user_id"] as String, plan, expiresAt, storageLimitBytes)
        log.info("Subscription activated: userId={} plan={} expires={}", order["user_id"], plan, expiresAt)

        ctx.contentType("application/json").result(
            gson.toJson(
                mapOf(
                    "status" to "success",
                    "orderId" to orderId,
                    "plan" to plan,
                    "method" to method,
                    "expiresAt" to expiresAt.toString(),
                ),
            ),
        )
    }

    fun handleNotification(ctx: Context) {
        val kwota = ctx.formParam("KWOTA")
        val idPlatnosci = ctx.formParam("ID_PLATNOSCI")
        val idZamowienia = ctx.formParam("ID_ZAMOWIENIA")
        val status = ctx.formParam("STATUS")
        val sekret = ctx.formParam("SEKRET")
        val secure = ctx.formParam("SECURE")
        val hash = ctx.formParam("HASH")

        if (kwota == null || idPlatnosci == null || idZamowienia == null || status == null || sekret == null || secure == null || hash == null) {
            ctx.status(400).result("Missing parameters")
            return
        }

        val expectedHash = sha256("$hotpayNotificationPassword;$kwota;$idPlatnosci;$idZamowienia;$status;$secure;$sekret")
        if (expectedHash != hash) {
            log.warn("HotPay notification hash mismatch for order {}", idZamowienia)
            ctx.status(403).result("Hash mismatch")
            return
        }

        log.info("HotPay notification: order={} status={} paymentId={}", idZamowienia, status, idPlatnosci)

        if (status == "SUCCESS") {
            updateOrderStatus(idZamowienia, "success", idPlatnosci)
            val order = findOrder(idZamowienia)
            if (order != null) {
                val plan = order["plan"] as String
                val duration = if (plan == ANNUAL_PLAN) 365L * 24 * 60 * 60 else 30L * 24 * 60 * 60
                val expiresAt = Instant.now().plusSeconds(duration)
                userStore.updateSubscription(order["user_id"] as String, plan, expiresAt, 2199023255552L)
                log.info("Subscription activated via HotPay: userId={} plan={}", order["user_id"], plan)
            }
        } else if (status == "FAILURE") {
            updateOrderStatus(idZamowienia, "failed", idPlatnosci)
        }

        ctx.status(200).result("OK")
    }

    fun listOrders(ctx: Context) {
        val userId = ctx.attribute<String>("userId")
        if (userId == null) {
            ctx.status(401).result("Unauthorized")
            return
        }
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT id, plan, amount_pln, status, hotpay_payment_id, created_at, updated_at FROM payment_order WHERE user_id = ? ORDER BY created_at DESC LIMIT 20",
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    val orders = mutableListOf<Map<String, Any?>>()
                    while (rs.next()) {
                        orders.add(
                            mapOf(
                                "id" to rs.getString("id"),
                                "plan" to rs.getString("plan"),
                                "amountPln" to rs.getBigDecimal("amount_pln"),
                                "status" to rs.getString("status"),
                                "createdAt" to rs.getTimestamp("created_at").toInstant().toString(),
                            ),
                        )
                    }
                    ctx.contentType("application/json").result(gson.toJson(mapOf("orders" to orders, "testMode" to testMode)))
                }
            }
        }
    }

    private fun createOrder(orderId: String, userId: String, plan: String, amount: java.math.BigDecimal) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO payment_order (id, user_id, plan, amount_pln) VALUES (?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, orderId)
                ps.setString(2, userId)
                ps.setString(3, plan)
                ps.setBigDecimal(4, amount)
                ps.executeUpdate()
            }
        }
    }

    private fun updateOrderStatus(orderId: String, status: String, paymentId: String?) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE payment_order SET status = ?, hotpay_payment_id = ?, updated_at = NOW() WHERE id = ?",
            ).use { ps ->
                ps.setString(1, status)
                ps.setString(2, paymentId)
                ps.setString(3, orderId)
                ps.executeUpdate()
            }
        }
    }

    private fun findOrder(orderId: String): Map<String, Any>? {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT id, user_id, plan, amount_pln, status FROM payment_order WHERE id = ?",
            ).use { ps ->
                ps.setString(1, orderId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) {
                        mapOf(
                            "id" to rs.getString("id"),
                            "user_id" to rs.getString("user_id"),
                            "plan" to rs.getString("plan"),
                            "amount_pln" to rs.getBigDecimal("amount_pln").toString(),
                            "status" to rs.getString("status"),
                        )
                    } else {
                        null
                    }
                }
            }
        }
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
