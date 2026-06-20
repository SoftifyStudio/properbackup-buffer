package pl.danielniemiec.properbackup.buffer.payment

import com.google.gson.Gson
import io.javalin.Javalin
import io.javalin.testtools.JavalinTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import pl.danielniemiec.properbackup.buffer.auth.UserStore
import pl.danielniemiec.properbackup.buffer.db.Database
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Integration tests for Stripe webhook handler.
 * Per master-tdd-plan.md [TDD-B2]:
 * - Signature verification
 * - Idempotency (duplicate rejection)
 * - Out-of-order protection
 * - DLQ fallback
 * - Event handling: checkout.session.completed, subscription.updated, invoice.paid/failed
 */
@Testcontainers
class StripeHandlerTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine").apply {
            withDatabaseName("properbackup_test")
            withUsername("test")
            withPassword("test")
        }

        const val TEST_WEBHOOK_SECRET = "whsec_test_secret_12345"
        private val JSON = "application/json".toMediaType()
    }

    private lateinit var db: Database
    private lateinit var userStore: UserStore
    private lateinit var idempotency: WebhookIdempotency
    private lateinit var dlq: WebhookDlq
    private lateinit var auditLog: SubscriptionAuditLog
    private lateinit var handler: StripeHandler
    private val gson = Gson()

    @BeforeEach
    fun setUp() {
        db = Database(postgres.jdbcUrl, postgres.username, postgres.password)
        db.getConnection().use { conn ->
            conn.createStatement().execute("DELETE FROM subscription_audit_log")
            conn.createStatement().execute("DELETE FROM stripe_event_idempotency")
            conn.createStatement().execute("DELETE FROM stripe_webhook_dlq")
            conn.createStatement().execute("DELETE FROM users")
        }
        userStore = UserStore(db)
        idempotency = WebhookIdempotency(db)
        dlq = WebhookDlq(db)
        auditLog = SubscriptionAuditLog(db)
        handler = StripeHandler(db, userStore, TEST_WEBHOOK_SECRET, idempotency, dlq, auditLog)
    }

    private fun createTestUser(email: String = "stripe-test@properbackup.dev"): UserStore.User {
        return userStore.register(email, "TestPassword#2026!")
    }

    private fun signPayload(body: String, timestamp: Long = Instant.now().epochSecond): String {
        val signedPayload = "$timestamp.$body"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(TEST_WEBHOOK_SECRET.toByteArray(), "HmacSHA256"))
        val hash = mac.doFinal(signedPayload.toByteArray())
        val signature = hash.joinToString("") { "%02x".format(it) }
        return "t=$timestamp,v1=$signature"
    }

    private fun buildCheckoutEvent(userId: String, eventId: String = "evt_checkout_001"): String {
        return gson.toJson(mapOf(
            "id" to eventId,
            "type" to "checkout.session.completed",
            "created" to Instant.now().epochSecond,
            "data" to mapOf(
                "object" to mapOf(
                    "customer" to "cus_test_123",
                    "subscription" to "sub_test_456",
                    "client_reference_id" to userId,
                    "current_period_end" to (Instant.now().epochSecond + 30 * 24 * 3600),
                    "metadata" to mapOf("plan" to "monthly"),
                ),
            ),
        ))
    }

    private fun buildSubscriptionUpdatedEvent(
        subscriptionId: String = "sub_test_456",
        status: String = "active",
        cancelAtPeriodEnd: Boolean = false,
        eventId: String = "evt_sub_update_001",
        created: Long = Instant.now().epochSecond,
    ): String {
        return gson.toJson(mapOf(
            "id" to eventId,
            "type" to "customer.subscription.updated",
            "created" to created,
            "data" to mapOf(
                "object" to mapOf(
                    "id" to subscriptionId,
                    "customer" to "cus_test_123",
                    "status" to status,
                    "cancel_at_period_end" to cancelAtPeriodEnd,
                    "current_period_end" to (Instant.now().epochSecond + 30 * 24 * 3600),
                ),
            ),
        ))
    }

    private fun buildInvoicePaymentFailedEvent(
        subscriptionId: String = "sub_test_456",
        eventId: String = "evt_invoice_fail_001",
        created: Long = Instant.now().epochSecond,
    ): String {
        return gson.toJson(mapOf(
            "id" to eventId,
            "type" to "invoice.payment_failed",
            "created" to created,
            "data" to mapOf(
                "object" to mapOf(
                    "subscription" to subscriptionId,
                ),
            ),
        ))
    }

    private fun createAppWithHandler(): Javalin {
        return Javalin.create().apply {
            post("/payment/stripe/webhook", handler::handleWebhook)
        }
    }

    private fun postWebhook(client: io.javalin.testtools.HttpClient, body: String, signature: String?): okhttp3.Response {
        return client.request("/payment/stripe/webhook") { req ->
            req.post(body.toRequestBody(JSON))
            if (signature != null) {
                req.header("Stripe-Signature", signature)
            }
        }
    }

    // ==========================================
    // TDD-B2 Signature verification tests
    // ==========================================

    @Test
    fun `TDD-B2 valid signature returns 200`() {
        val user = createTestUser()
        val body = buildCheckoutEvent(user.id)
        val signature = signPayload(body)

        JavalinTest.test(createAppWithHandler()) { _, client ->
            val response = postWebhook(client, body, signature)
            assertEquals(200, response.code)
        }
    }

    @Test
    fun `TDD-B2 tampered signature returns 400`() {
        val user = createTestUser()
        val body = buildCheckoutEvent(user.id)

        JavalinTest.test(createAppWithHandler()) { _, client ->
            val response = postWebhook(client, body, "t=1234567890,v1=invalid_signature_tampered")
            assertEquals(400, response.code)
        }
    }

    @Test
    fun `TDD-B2 missing signature returns 400`() {
        val body = buildCheckoutEvent("some-user-id")

        JavalinTest.test(createAppWithHandler()) { _, client ->
            val response = postWebhook(client, body, null)
            assertEquals(400, response.code)
        }
    }

    @Test
    fun `TDD-B2 blank webhook secret returns 503 fail-closed`() {
        val blankSecretHandler = StripeHandler(db, userStore, "", idempotency, dlq, auditLog)
        val app = Javalin.create().apply {
            post("/payment/stripe/webhook", blankSecretHandler::handleWebhook)
        }

        JavalinTest.test(app) { _, client ->
            val response = client.request("/payment/stripe/webhook") { req ->
                req.post("""{"id":"evt_1"}""".toRequestBody(JSON))
                req.header("Stripe-Signature", "t=123,v1=abc")
            }
            assertEquals(503, response.code)
        }
    }

    @Test
    fun `TDD-B2 expired timestamp returns 400 replay protection`() {
        val user = createTestUser()
        val body = buildCheckoutEvent(user.id)
        val oldTimestamp = Instant.now().epochSecond - 360 // 6 minutes ago
        val signature = signPayload(body, oldTimestamp)

        JavalinTest.test(createAppWithHandler()) { _, client ->
            val response = postWebhook(client, body, signature)
            assertEquals(400, response.code)
        }
    }

    @Test
    fun `TDD-B2 timestamp within tolerance returns 200`() {
        val user = createTestUser()
        val body = buildCheckoutEvent(user.id)
        val recentTimestamp = Instant.now().epochSecond - 240 // 4 minutes ago
        val signature = signPayload(body, recentTimestamp)

        JavalinTest.test(createAppWithHandler()) { _, client ->
            val response = postWebhook(client, body, signature)
            assertEquals(200, response.code)
        }
    }

    // ==========================================
    // TDD-B2 Idempotency tests
    // ==========================================

    @Test
    fun `TDD-B2 duplicate event is deduplicated`() {
        val user = createTestUser()
        val body = buildCheckoutEvent(user.id, "evt_dup_test")
        val signature = signPayload(body)

        JavalinTest.test(createAppWithHandler()) { _, client ->
            // First call — processes event
            val resp1 = postWebhook(client, body, signature)
            assertEquals(200, resp1.code)

            // Second call — same event ID, deduplicated
            val resp2 = postWebhook(client, body, signature)
            assertEquals(200, resp2.code)
            assertTrue(resp2.body?.string()?.contains("duplicate") == true)

            // Audit log should have exactly 1 entry (not 2)
            val audits = auditLog.listByUser(user.id)
            assertEquals(1, audits.size)
            assertEquals("checkout_completed", audits[0].action)
        }
    }

    // ==========================================
    // TDD-B2 checkout.session.completed
    // ==========================================

    @Test
    fun `TDD-B2 checkout completed activates subscription`() {
        val user = createTestUser()
        val body = buildCheckoutEvent(user.id)
        val signature = signPayload(body)

        JavalinTest.test(createAppWithHandler()) { _, client ->
            val response = postWebhook(client, body, signature)
            assertEquals(200, response.code)

            // Verify subscription activated
            val updated = userStore.findById(user.id).get()
            assertEquals("monthly", updated.subscriptionPlan)
            assertNotNull(updated.subscriptionExpiresAt)

            // Verify Stripe IDs stored
            db.getConnection().use { conn ->
                conn.prepareStatement("SELECT stripe_subscription_id, stripe_customer_id, ever_subscribed FROM users WHERE id = ?").use { stmt ->
                    stmt.setString(1, user.id)
                    stmt.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        assertEquals("sub_test_456", rs.getString("stripe_subscription_id"))
                        assertEquals("cus_test_123", rs.getString("stripe_customer_id"))
                        assertTrue(rs.getBoolean("ever_subscribed"))
                    }
                }
            }

            // Verify audit log
            val audits = auditLog.listByUser(user.id)
            assertEquals(1, audits.size)
            assertEquals("checkout_completed", audits[0].action)
            assertTrue(audits[0].detail!!.contains("monthly"))
        }
    }

    // ==========================================
    // TDD-B2 Out-of-order protection
    // ==========================================

    @Test
    fun `TDD-B2 out-of-order event is rejected as stale`() {
        val user = createTestUser()

        // First: process checkout (sets subscription + last_stripe_event_at)
        val checkoutBody = buildCheckoutEvent(user.id, "evt_first")
        val checkoutSig = signPayload(checkoutBody)

        JavalinTest.test(createAppWithHandler()) { _, client ->
            postWebhook(client, checkoutBody, checkoutSig)

            // Now send subscription.updated with OLDER timestamp (stale)
            val staleCreated = Instant.now().epochSecond - 100
            val staleBody = buildSubscriptionUpdatedEvent(
                eventId = "evt_stale",
                status = "past_due",
                created = staleCreated,
            )
            val staleSig = signPayload(staleBody)

            val resp = postWebhook(client, staleBody, staleSig)
            assertEquals(200, resp.code)

            // Verify payment_status is NOT set to past_due (stale event rejected)
            db.getConnection().use { conn ->
                conn.prepareStatement("SELECT subscription_payment_status FROM users WHERE id = ?").use { stmt ->
                    stmt.setString(1, user.id)
                    stmt.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        assertNull(rs.getString("subscription_payment_status"))
                    }
                }
            }
        }
    }

    // ==========================================
    // TDD-B2 invoice.payment_failed -> past_due
    // ==========================================

    @Test
    fun `TDD-B2 invoice payment failed sets past_due status`() {
        val user = createTestUser()

        // Setup: activate subscription first
        val checkoutBody = buildCheckoutEvent(user.id, "evt_setup_1")
        val checkoutSig = signPayload(checkoutBody)

        JavalinTest.test(createAppWithHandler()) { _, client ->
            postWebhook(client, checkoutBody, checkoutSig)

            // Now: payment fails (use future timestamp to avoid stale)
            val failBody = buildInvoicePaymentFailedEvent(
                eventId = "evt_fail_1",
                created = Instant.now().epochSecond + 60,
            )
            val failSig = signPayload(failBody)

            val resp = postWebhook(client, failBody, failSig)
            assertEquals(200, resp.code)

            // Verify past_due status
            db.getConnection().use { conn ->
                conn.prepareStatement("SELECT subscription_payment_status FROM users WHERE id = ?").use { stmt ->
                    stmt.setString(1, user.id)
                    stmt.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        assertEquals("past_due", rs.getString("subscription_payment_status"))
                    }
                }
            }

            // Verify audit log
            val audits = auditLog.listByUser(user.id)
            assertTrue(audits.any { it.action == "invoice_payment_failed" })
        }
    }

    // ==========================================
    // TDD-B2 customer.subscription.updated (cancel_at_period_end)
    // ==========================================

    @Test
    fun `TDD-B2 subscription updated with cancel_at_period_end`() {
        val user = createTestUser()

        // Setup: activate subscription first
        val checkoutBody = buildCheckoutEvent(user.id, "evt_setup_2")
        val checkoutSig = signPayload(checkoutBody)

        JavalinTest.test(createAppWithHandler()) { _, client ->
            postWebhook(client, checkoutBody, checkoutSig)

            // Now: user cancels (use future timestamp)
            val cancelBody = buildSubscriptionUpdatedEvent(
                eventId = "evt_cancel_1",
                status = "active",
                cancelAtPeriodEnd = true,
                created = Instant.now().epochSecond + 60,
            )
            val cancelSig = signPayload(cancelBody)

            val resp = postWebhook(client, cancelBody, cancelSig)
            assertEquals(200, resp.code)

            // Verify cancel_at_period_end
            db.getConnection().use { conn ->
                conn.prepareStatement("SELECT cancel_at_period_end FROM users WHERE id = ?").use { stmt ->
                    stmt.setString(1, user.id)
                    stmt.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        assertTrue(rs.getBoolean("cancel_at_period_end"))
                    }
                }
            }

            // Verify audit log
            val audits = auditLog.listByUser(user.id)
            assertTrue(audits.any { it.action == "cancel_at_period_end" })
        }
    }

    // ==========================================
    // TDD-B2 DLQ on processing failure
    // ==========================================

    @Test
    fun `TDD-B2 graceful handling when user not found`() {
        val body = gson.toJson(mapOf(
            "id" to "evt_dlq_test",
            "type" to "checkout.session.completed",
            "created" to Instant.now().epochSecond,
            "data" to mapOf(
                "object" to mapOf(
                    "customer" to "cus_nonexistent",
                    "subscription" to "sub_nonexistent",
                    "client_reference_id" to "nonexistent-user-id",
                    "metadata" to mapOf("plan" to "monthly"),
                ),
            ),
        ))
        val signature = signPayload(body)

        JavalinTest.test(createAppWithHandler()) { _, client ->
            val response = postWebhook(client, body, signature)
            assertEquals(200, response.code)

            // Event handled gracefully (no crash), DLQ empty
            val dlqEntries = dlq.listUnresolved()
            assertEquals(0, dlqEntries.size)
        }
    }

    // ==========================================
    // Signature verification unit tests
    // ==========================================

    @Test
    fun `verifySignature computes correct HMAC-SHA256`() {
        val body = """{"test":"data"}"""
        val timestamp = Instant.now().epochSecond
        val signedPayload = "$timestamp.$body"

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(TEST_WEBHOOK_SECRET.toByteArray(), "HmacSHA256"))
        val expectedSig = mac.doFinal(signedPayload.toByteArray()).joinToString("") { "%02x".format(it) }

        val header = "t=$timestamp,v1=$expectedSig"
        assertTrue(handler.verifySignature(body, header))
    }

    @Test
    fun `verifySignature rejects wrong secret`() {
        val body = """{"test":"data"}"""
        val timestamp = Instant.now().epochSecond

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("wrong_secret".toByteArray(), "HmacSHA256"))
        val wrongSig = mac.doFinal("$timestamp.$body".toByteArray()).joinToString("") { "%02x".format(it) }

        val header = "t=$timestamp,v1=$wrongSig"
        assertFalse(handler.verifySignature(body, header))
    }
}
