package pl.danielniemiec.properbackup.buffer.payment

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.javalin.http.Context
import pl.danielniemiec.properbackup.buffer.auth.UserStore
import pl.danielniemiec.properbackup.buffer.db.Database
import pl.danielniemiec.properbackup.buffer.subscription.DunningNotifier
import pl.danielniemiec.properbackup.buffer.sse.UserSseEventBus
import java.sql.Timestamp
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Stripe webhook handler with signature verification, idempotency,
 * out-of-order protection, and DLQ fallback.
 *
 * Per master-tdd-plan.md §7 [TDD-B2]:
 * - Signature: HMAC-SHA256 per Stripe protocol (t=ts,v1=sig)
 * - Idempotency: WebhookIdempotency.tryClaimStripeEventId()
 * - Out-of-order: WebhookIdempotency.isStaleEvent()
 * - DLQ: WebhookDlq.enqueue() on processing failure
 * - Audit: SubscriptionAuditLog.append() on state changes
 */
class StripeHandler(
    private val db: Database,
    private val userStore: UserStore,
    private val webhookSecret: String,
    private val idempotency: WebhookIdempotency,
    private val dlq: WebhookDlq,
    private val auditLog: SubscriptionAuditLog,
    private val dunningNotifier: DunningNotifier? = null,
    private val userSseBus: UserSseEventBus? = null,
) {
    private val gson = Gson()

    companion object {
        const val SIGNATURE_TOLERANCE_SECONDS = 300L // 5 minutes
        const val PLAN_MONTHLY = "monthly"
        const val PLAN_ANNUAL = "annual"
        val MONTHLY_STORAGE_BYTES = 2199023255552L  // 2 TB
        val ANNUAL_STORAGE_BYTES = 2199023255552L   // 2 TB
    }

    fun handleWebhook(ctx: Context) {
        val signatureHeader = ctx.header("Stripe-Signature") ?: ""
        val body = ctx.body()

        // Fail-closed: no webhook secret configured → reject all
        if (webhookSecret.isBlank()) {
            println("[Stripe] STRIPE_WEBHOOK_SECRET not configured — rejecting webhook (fail-closed)")
            ctx.status(503).result("Webhook secret not configured")
            return
        }

        // Verify signature
        if (!verifySignature(body, signatureHeader)) {
            println("[Stripe] Invalid webhook signature")
            ctx.status(400).result("Invalid signature")
            return
        }

        // Parse event JSON
        val event = try {
            JsonParser.parseString(body).asJsonObject
        } catch (e: Exception) {
            ctx.status(400).result("Invalid JSON")
            return
        }

        val eventId = event.get("id")?.asString ?: run {
            ctx.status(400).result("Missing event id")
            return
        }
        val eventType = event.get("type")?.asString ?: ""
        val eventCreated = event.get("created")?.asLong?.let { Instant.ofEpochSecond(it) }

        // Idempotency: deduplicate
        if (!idempotency.tryClaimStripeEventId(eventId, eventType)) {
            println("[Stripe] Duplicate event $eventId ($eventType) — skipping")
            ctx.status(200).result("ok (duplicate)")
            return
        }

        // Process event with DLQ fallback
        try {
            processEvent(event, eventId, eventType, eventCreated)
        } catch (e: Exception) {
            println("[Stripe] Error processing $eventId ($eventType): ${e.message}")
            try {
                dlq.enqueue(eventId, eventType, body, signatureHeader, e)
            } catch (dlqError: Exception) {
                println("[Stripe] CRITICAL: Failed to enqueue to DLQ: ${dlqError.message}")
            }
        }

        // Always 200 to Stripe to prevent retries (event is in DLQ if failed)
        ctx.status(200).result("ok")
    }

    private fun processEvent(event: JsonObject, eventId: String, eventType: String, eventCreated: Instant?) {
        val dataObj = event.getAsJsonObject("data")?.getAsJsonObject("object") ?: return

        when (eventType) {
            "checkout.session.completed" -> handleCheckoutCompleted(dataObj, eventCreated)
            "customer.subscription.updated" -> handleSubscriptionUpdated(dataObj, eventCreated)
            "customer.subscription.deleted" -> handleSubscriptionDeleted(dataObj, eventCreated)
            "invoice.paid" -> handleInvoicePaid(dataObj, eventCreated)
            "invoice.payment_failed" -> handleInvoicePaymentFailed(dataObj, eventCreated)
            else -> println("[Stripe] Unhandled event type: $eventType")
        }
    }

    private fun handleCheckoutCompleted(session: JsonObject, eventCreated: Instant?) {
        val customerId = session.get("customer")?.asString ?: return
        val subscriptionId = session.get("subscription")?.asString ?: return
        val clientReferenceId = session.get("client_reference_id")?.asString ?: return
        val userId = clientReferenceId

        val user = userStore.findById(userId).orElse(null) ?: run {
            println("[Stripe] User not found: $userId")
            return
        }

        // Determine plan from metadata or line items
        val metadata = session.getAsJsonObject("metadata")
        val plan = metadata?.get("plan")?.asString ?: PLAN_MONTHLY
        val storageLimitBytes = if (plan == PLAN_ANNUAL) ANNUAL_STORAGE_BYTES else MONTHLY_STORAGE_BYTES

        // Calculate subscription end (30 days for trial, depends on plan)
        val periodEnd = session.get("current_period_end")?.asLong?.let { Instant.ofEpochSecond(it) }
            ?: Instant.now().plusSeconds(30L * 24 * 60 * 60)

        // Update user subscription
        updateSubscription(userId, plan, subscriptionId, customerId, periodEnd, storageLimitBytes)

        // Out-of-order protection
        if (eventCreated != null) {
            idempotency.updateLastStripeEventAt(userId, eventCreated)
        }

        auditLog.append(userId, "checkout_completed", "plan=$plan, subscription=$subscriptionId")
        println("[Stripe] Checkout completed: user=$userId plan=$plan subscription=$subscriptionId")

        // Publish SSE event for ProcessingScreen (§9.3 [TDD-F1])
        userSseBus?.publish(userId, "subscription", mapOf(
            "type" to "subscription_activated",
            "plan" to plan,
            "subscriptionId" to subscriptionId,
        ))
    }

    private fun handleSubscriptionUpdated(subscription: JsonObject, eventCreated: Instant?) {
        val subscriptionId = subscription.get("id")?.asString ?: return
        val customerId = subscription.get("customer")?.asString ?: return
        val status = subscription.get("status")?.asString ?: return
        val cancelAtPeriodEnd = subscription.get("cancel_at_period_end")?.asBoolean ?: false

        val userId = findUserByStripeSubscriptionId(subscriptionId) ?: run {
            println("[Stripe] No user found for subscription: $subscriptionId")
            return
        }

        // Out-of-order check
        if (eventCreated != null && idempotency.isStaleEvent(userId, eventCreated)) {
            println("[Stripe] Stale event for user $userId (out-of-order) — skipping mutation")
            auditLog.append(userId, "stale_event_skipped", "subscription_updated, created=$eventCreated")
            return
        }

        val periodEnd = subscription.get("current_period_end")?.asLong?.let { Instant.ofEpochSecond(it) }

        // Map Stripe status to our payment_status
        val paymentStatus = when (status) {
            "active" -> null  // healthy
            "trialing" -> null  // healthy
            "past_due" -> "past_due"
            "unpaid" -> "unpaid"
            "canceled" -> null  // handled by subscription.deleted
            "incomplete" -> "incomplete"
            "incomplete_expired" -> "incomplete"
            else -> null
        }

        db.getConnection().use { conn ->
            conn.prepareStatement(
                """UPDATE users SET 
                   cancel_at_period_end = ?,
                   subscription_payment_status = ?,
                   current_period_end = ?,
                   stripe_customer_id = ?
                   WHERE id = ?"""
            ).use { stmt ->
                stmt.setBoolean(1, cancelAtPeriodEnd)
                stmt.setString(2, paymentStatus)
                stmt.setTimestamp(3, periodEnd?.let { Timestamp.from(it) })
                stmt.setString(4, customerId)
                stmt.setString(5, userId)
                stmt.executeUpdate()
            }
        }

        if (eventCreated != null) {
            idempotency.updateLastStripeEventAt(userId, eventCreated)
        }

        val action = if (cancelAtPeriodEnd) "cancel_at_period_end" else "subscription_updated"
        auditLog.append(userId, action, "status=$status, cancelAtPeriodEnd=$cancelAtPeriodEnd")
        println("[Stripe] Subscription updated: user=$userId status=$status cancelAtPeriodEnd=$cancelAtPeriodEnd")
    }

    private fun handleSubscriptionDeleted(subscription: JsonObject, eventCreated: Instant?) {
        val subscriptionId = subscription.get("id")?.asString ?: return

        val userId = findUserByStripeSubscriptionId(subscriptionId) ?: run {
            println("[Stripe] No user found for deleted subscription: $subscriptionId")
            return
        }

        // Out-of-order check
        if (eventCreated != null && idempotency.isStaleEvent(userId, eventCreated)) {
            println("[Stripe] Stale event for user $userId (out-of-order) — skipping mutation")
            return
        }

        // Clear subscription but keep user data (anti-hostage: restore always allowed)
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """UPDATE users SET 
                   subscription_plan = NULL,
                   subscription_expires_at = current_period_end,
                   cancel_at_period_end = FALSE,
                   subscription_payment_status = NULL,
                   stripe_subscription_id = NULL,
                   past_due_started_at = NULL,
                   last_past_due_notification = NULL
                   WHERE id = ?"""
            ).use { stmt ->
                stmt.setString(1, userId)
                stmt.executeUpdate()
            }
        }

        if (eventCreated != null) {
            idempotency.updateLastStripeEventAt(userId, eventCreated)
        }

        auditLog.append(userId, "subscription_deleted", "subscription=$subscriptionId")

        // Send final cancellation dunning email
        val user = userStore.findById(userId).orElse(null)
        if (user != null && dunningNotifier != null) {
            dunningNotifier.sendFinalCancellation(userId, user.email)
        }

        println("[Stripe] Subscription deleted: user=$userId subscription=$subscriptionId")
    }

    private fun handleInvoicePaid(invoice: JsonObject, eventCreated: Instant?) {
        val subscriptionId = invoice.get("subscription")?.asString ?: return

        val userId = findUserByStripeSubscriptionId(subscriptionId) ?: run {
            println("[Stripe] No user found for invoice subscription: $subscriptionId")
            return
        }

        // Out-of-order check
        if (eventCreated != null && idempotency.isStaleEvent(userId, eventCreated)) {
            println("[Stripe] Stale invoice.paid event — skipping")
            return
        }

        val periodEnd = invoice.getAsJsonObject("lines")
            ?.getAsJsonArray("data")?.firstOrNull()?.asJsonObject
            ?.get("period")?.asJsonObject
            ?.get("end")?.asLong?.let { Instant.ofEpochSecond(it) }

        // Clear any payment issues, extend period, reset dunning state
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """UPDATE users SET 
                   subscription_payment_status = NULL,
                   past_due_started_at = NULL,
                   last_past_due_notification = NULL,
                   current_period_end = COALESCE(?, current_period_end),
                   subscription_expires_at = COALESCE(?, subscription_expires_at)
                   WHERE id = ?"""
            ).use { stmt ->
                stmt.setTimestamp(1, periodEnd?.let { Timestamp.from(it) })
                stmt.setTimestamp(2, periodEnd?.let { Timestamp.from(it) })
                stmt.setString(3, userId)
                stmt.executeUpdate()
            }
        }

        if (eventCreated != null) {
            idempotency.updateLastStripeEventAt(userId, eventCreated)
        }

        auditLog.append(userId, "invoice_paid", "subscription=$subscriptionId")
        println("[Stripe] Invoice paid: user=$userId")
    }

    private fun handleInvoicePaymentFailed(invoice: JsonObject, eventCreated: Instant?) {
        val subscriptionId = invoice.get("subscription")?.asString ?: return

        val userId = findUserByStripeSubscriptionId(subscriptionId) ?: run {
            println("[Stripe] No user found for failed invoice: $subscriptionId")
            return
        }

        // Out-of-order check
        if (eventCreated != null && idempotency.isStaleEvent(userId, eventCreated)) {
            return
        }

        // Set payment status to past_due + record when it started
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """UPDATE users SET 
                   subscription_payment_status = 'past_due',
                   past_due_started_at = COALESCE(past_due_started_at, NOW())
                   WHERE id = ?"""
            ).use { stmt ->
                stmt.setString(1, userId)
                stmt.executeUpdate()
            }
        }

        if (eventCreated != null) {
            idempotency.updateLastStripeEventAt(userId, eventCreated)
        }

        auditLog.append(userId, "invoice_payment_failed", "subscription=$subscriptionId")

        // Trigger immediate T+0 dunning email
        val user = userStore.findById(userId).orElse(null)
        if (user != null && dunningNotifier != null) {
            dunningNotifier.sendImmediateT0(userId, user.email)
        }

        println("[Stripe] Invoice payment failed: user=$userId subscription=$subscriptionId")
    }

    private fun updateSubscription(
        userId: String,
        plan: String,
        subscriptionId: String,
        customerId: String,
        periodEnd: Instant,
        storageLimitBytes: Long,
    ) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """UPDATE users SET 
                   subscription_plan = ?,
                   stripe_subscription_id = ?,
                   stripe_customer_id = ?,
                   subscription_expires_at = ?,
                   current_period_end = ?,
                   storage_limit_bytes = ?,
                   ever_subscribed = TRUE,
                   subscription_payment_status = NULL,
                   cancel_at_period_end = FALSE
                   WHERE id = ?"""
            ).use { stmt ->
                stmt.setString(1, plan)
                stmt.setString(2, subscriptionId)
                stmt.setString(3, customerId)
                stmt.setTimestamp(4, Timestamp.from(periodEnd))
                stmt.setTimestamp(5, Timestamp.from(periodEnd))
                stmt.setLong(6, storageLimitBytes)
                stmt.setString(7, userId)
                stmt.executeUpdate()
            }
        }
    }

    private fun findUserByStripeSubscriptionId(subscriptionId: String): String? {
        return db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT id FROM users WHERE stripe_subscription_id = ?"
            ).use { stmt ->
                stmt.setString(1, subscriptionId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString("id") else null
                }
            }
        }
    }

    /**
     * Verifies Stripe webhook signature per their protocol:
     * Header format: t=timestamp,v1=signature[,v1=signature...]
     * Signed payload: "{timestamp}.{body}"
     * Algorithm: HMAC-SHA256
     */
    fun verifySignature(body: String, signatureHeader: String): Boolean {
        if (signatureHeader.isBlank()) return false

        val parts = signatureHeader.split(",").associate { part ->
            val (key, value) = part.split("=", limit = 2)
            key to value
        }

        val timestamp = parts["t"] ?: return false
        val signatures = signatureHeader.split(",")
            .filter { it.startsWith("v1=") }
            .map { it.removePrefix("v1=") }

        if (signatures.isEmpty()) return false

        // Check timestamp tolerance (prevent replay attacks)
        val eventTime = timestamp.toLongOrNull() ?: return false
        val now = Instant.now().epochSecond
        if (Math.abs(now - eventTime) > SIGNATURE_TOLERANCE_SECONDS) {
            println("[Stripe] Signature timestamp outside tolerance: event=$eventTime now=$now")
            return false
        }

        // Compute expected signature
        val signedPayload = "$timestamp.$body"
        val expectedSignature = computeHmacSha256(signedPayload, webhookSecret)

        // Compare with provided signatures (at least one must match)
        return signatures.any { it == expectedSignature }
    }

    private fun computeHmacSha256(data: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hash = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
