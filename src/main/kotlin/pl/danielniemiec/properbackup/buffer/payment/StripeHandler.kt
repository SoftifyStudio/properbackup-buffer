package pl.danielniemiec.properbackup.buffer.payment

import com.google.gson.Gson
import com.stripe.Stripe
import com.stripe.exception.SignatureVerificationException
import com.stripe.model.Event
import com.stripe.model.checkout.Session
import com.stripe.net.Webhook
import com.stripe.param.CustomerCreateParams
import com.stripe.param.checkout.SessionCreateParams
import io.javalin.http.Context
import org.slf4j.LoggerFactory
import pl.danielniemiec.properbackup.buffer.auth.UserStore
import pl.danielniemiec.properbackup.buffer.db.Database
import java.time.Instant

class StripeHandler(
    private val db: Database,
    private val userStore: UserStore,
) {
    private val log = LoggerFactory.getLogger(StripeHandler::class.java)
    private val gson = Gson()

    private val stripeSecretKey = System.getenv("STRIPE_SECRET_KEY") ?: ""
    private val stripeWebhookSecret = System.getenv("STRIPE_WEBHOOK_SECRET") ?: ""
    private val panelUrl = System.getenv("PROPERBACKUP_PANEL_URL") ?: "https://panel.properbackup.com"

    companion object {
        const val MONTHLY_PLAN = "monthly"
        const val ANNUAL_PLAN = "annual"
        const val MONTHLY_PRICE_PLN = 1900L   // 19.00 PLN in grosz
        const val ANNUAL_PRICE_PLN = 19000L   // 190.00 PLN in grosz
        const val TRIAL_DAYS = 30L
    }

    init {
        if (stripeSecretKey.isNotBlank()) {
            Stripe.apiKey = stripeSecretKey
            log.info("Stripe initialized (key={}...)", stripeSecretKey.take(12))
        } else {
            log.warn("STRIPE_SECRET_KEY not set — Stripe payments disabled")
        }
    }

    /**
     * Creates a Stripe Checkout Session in "setup" mode.
     * This verifies the customer's card without charging them.
     * After successful setup, the webhook activates the trial.
     */
    fun createCheckoutSession(ctx: Context) {
        val userId = ctx.attribute<String>("userId")
        if (userId == null) {
            ctx.status(401).result("Unauthorized")
            return
        }

        if (stripeSecretKey.isBlank()) {
            ctx.status(503).result("Stripe not configured")
            return
        }

        val user = userStore.findById(userId).orElse(null)
        if (user == null) {
            ctx.status(404).result("User not found")
            return
        }

        val plan = ctx.queryParam("plan") ?: MONTHLY_PLAN

        try {
            val customerId = getOrCreateStripeCustomer(userId, user.email)

            val sessionParams = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SETUP)
                .setCustomer(customerId)
                .setSuccessUrl("$panelUrl/subscription?stripe=success&session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl("$panelUrl/subscription?stripe=cancelled")
                .putMetadata("userId", userId)
                .putMetadata("plan", plan)
                .addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD)
                .build()

            val session = Session.create(sessionParams)

            log.info("Stripe Checkout session created: sessionId={} userId={} plan={}", session.id, userId, plan)

            ctx.contentType("application/json").result(
                gson.toJson(
                    mapOf(
                        "sessionId" to session.id,
                        "checkoutUrl" to session.url,
                    ),
                ),
            )
        } catch (e: Exception) {
            log.error("Failed to create Stripe checkout session: userId={}", userId, e)
            ctx.status(500).contentType("application/json").result(
                gson.toJson(mapOf("error" to (e.message ?: "Stripe error"))),
            )
        }
    }

    /**
     * Handles Stripe webhook events.
     * Key event: checkout.session.completed (setup mode) → activate trial.
     */
    fun handleWebhook(ctx: Context) {
        val payload = ctx.body()
        val sigHeader = ctx.header("Stripe-Signature")

        if (sigHeader == null) {
            log.warn("Stripe webhook: missing Stripe-Signature header")
            ctx.status(400).result("Missing signature")
            return
        }

        val event: Event
        try {
            if (stripeWebhookSecret.isNotBlank()) {
                event = Webhook.constructEvent(payload, sigHeader, stripeWebhookSecret)
            } else {
                log.warn("Stripe webhook: STRIPE_WEBHOOK_SECRET not set, parsing without verification")
                event = Event.GSON.fromJson(payload, Event::class.java)
            }
        } catch (e: SignatureVerificationException) {
            log.warn("Stripe webhook: signature verification failed", e)
            ctx.status(400).result("Invalid signature")
            return
        } catch (e: Exception) {
            log.error("Stripe webhook: failed to parse event", e)
            ctx.status(400).result("Invalid payload")
            return
        }

        log.info("Stripe webhook received: type={} id={}", event.type, event.id)

        when (event.type) {
            "checkout.session.completed" -> handleCheckoutCompleted(event)
            "invoice.payment_succeeded" -> handleInvoicePaymentSucceeded(event)
            "invoice.payment_failed" -> handleInvoicePaymentFailed(event)
            "customer.subscription.deleted" -> handleSubscriptionDeleted(event)
            else -> log.debug("Stripe webhook: unhandled event type={}", event.type)
        }

        ctx.status(200).result("OK")
    }

    /**
     * Returns the Stripe public key to the frontend.
     */
    fun getPublicKey(ctx: Context) {
        val publicKey = System.getenv("STRIPE_PUBLIC_KEY") ?: ""
        ctx.contentType("application/json").result(
            gson.toJson(mapOf("publicKey" to publicKey)),
        )
    }

    /**
     * Returns the user's payment status (has card, trial status, etc.)
     */
    fun getPaymentStatus(ctx: Context) {
        val userId = ctx.attribute<String>("userId")
        if (userId == null) {
            ctx.status(401).result("Unauthorized")
            return
        }

        val user = userStore.findById(userId).orElse(null)
        if (user == null) {
            ctx.status(404).result("User not found")
            return
        }

        val stripeCustomerId = getStripeCustomerId(userId)
        val hasPaymentMethod = stripeCustomerId != null

        ctx.contentType("application/json").result(
            gson.toJson(
                mapOf(
                    "hasPaymentMethod" to hasPaymentMethod,
                    "stripeCustomerId" to stripeCustomerId,
                    "trialExpiresAt" to user.trialExpiresAt,
                    "subscriptionPlan" to user.subscriptionPlan,
                    "subscriptionExpiresAt" to user.subscriptionExpiresAt,
                ),
            ),
        )
    }

    private fun handleCheckoutCompleted(event: Event) {
        val dataObj = event.dataObjectDeserializer.`object`.orElse(null)
        if (dataObj == null || dataObj !is Session) {
            log.warn("Stripe checkout.session.completed: could not deserialize session")
            return
        }

        val session = dataObj
        val userId = session.metadata?.get("userId")
        val plan = session.metadata?.get("plan") ?: MONTHLY_PLAN

        if (userId.isNullOrBlank()) {
            log.warn("Stripe checkout.session.completed: missing userId in metadata, sessionId={}", session.id)
            return
        }

        log.info(
            "Stripe checkout completed: sessionId={} userId={} plan={} mode={}",
            session.id, userId, plan, session.mode,
        )

        if (session.mode == "setup") {
            activateTrialAfterCardVerification(userId, plan)
        }

        saveStripeEvent(userId, "checkout_completed", session.id, plan)
    }

    private fun handleInvoicePaymentSucceeded(event: Event) {
        log.info("Stripe invoice.payment_succeeded: eventId={}", event.id)
    }

    private fun handleInvoicePaymentFailed(event: Event) {
        log.warn("Stripe invoice.payment_failed: eventId={}", event.id)
    }

    private fun handleSubscriptionDeleted(event: Event) {
        log.info("Stripe customer.subscription.deleted: eventId={}", event.id)
    }

    /**
     * After card verification (Stripe Checkout setup mode),
     * activate the trial for the user.
     */
    private fun activateTrialAfterCardVerification(userId: String, plan: String) {
        val user = userStore.findById(userId).orElse(null)
        if (user == null) {
            log.error("Cannot activate trial: user not found userId={}", userId)
            return
        }

        if (user.subscriptionPlan != null) {
            log.info("User already has subscription, skipping trial activation: userId={} plan={}", userId, user.subscriptionPlan)
            return
        }

        val trialExpiresAt = Instant.now().plusSeconds(TRIAL_DAYS * 24 * 60 * 60)
        userStore.activateTrial(userId, trialExpiresAt)
        log.info(
            "Trial activated after card verification: userId={} plan={} trialExpiresAt={}",
            userId, plan, trialExpiresAt,
        )
    }

    private fun getOrCreateStripeCustomer(userId: String, email: String): String {
        val existing = getStripeCustomerId(userId)
        if (existing != null) return existing

        val params = CustomerCreateParams.builder()
            .setEmail(email)
            .putMetadata("userId", userId)
            .build()

        val customer = com.stripe.model.Customer.create(params)
        saveStripeCustomerId(userId, customer.id)
        log.info("Stripe customer created: customerId={} userId={}", customer.id, userId)
        return customer.id
    }

    private fun getStripeCustomerId(userId: String): String? {
        db.getConnection().use { conn ->
            conn.prepareStatement("SELECT stripe_customer_id FROM users WHERE id = ? AND stripe_customer_id IS NOT NULL").use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.getString("stripe_customer_id") else null
                }
            }
        }
    }

    private fun saveStripeCustomerId(userId: String, customerId: String) {
        db.getConnection().use { conn ->
            conn.prepareStatement("UPDATE users SET stripe_customer_id = ? WHERE id = ?").use { ps ->
                ps.setString(1, customerId)
                ps.setString(2, userId)
                ps.executeUpdate()
            }
        }
    }

    private fun saveStripeEvent(userId: String, eventType: String, sessionId: String, plan: String) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO stripe_event (user_id, event_type, stripe_session_id, plan) VALUES (?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, userId)
                ps.setString(2, eventType)
                ps.setString(3, sessionId)
                ps.setString(4, plan)
                ps.executeUpdate()
            }
        }
        log.info("Stripe event saved: userId={} type={} sessionId={}", userId, eventType, sessionId)
    }
}
