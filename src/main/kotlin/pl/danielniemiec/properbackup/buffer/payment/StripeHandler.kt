package pl.danielniemiec.properbackup.buffer.payment

import com.google.gson.Gson
import com.stripe.Stripe
import com.stripe.exception.SignatureVerificationException
import com.stripe.model.Customer
import com.stripe.model.Event
import com.stripe.model.Invoice
import com.stripe.model.Price
import com.stripe.model.Product
import com.stripe.model.Subscription
import com.stripe.model.checkout.Session
import com.stripe.net.Webhook
import com.stripe.param.CustomerCreateParams
import com.stripe.param.CustomerListPaymentMethodsParams
import com.stripe.param.PriceCreateParams
import com.stripe.param.PriceListParams
import com.stripe.param.ProductCreateParams
import com.stripe.param.ProductListParams
import com.stripe.param.SubscriptionUpdateParams
import com.stripe.param.checkout.SessionCreateParams
import io.javalin.http.Context
import org.slf4j.LoggerFactory
import pl.danielniemiec.properbackup.buffer.auth.UserStore
import pl.danielniemiec.properbackup.buffer.db.Database
import java.time.Duration
import java.time.Instant

class StripeHandler(
    private val db: Database,
    private val userStore: UserStore,
    private val promoCodeHandler: PromoCodeHandler? = null,
) {
    private val log = LoggerFactory.getLogger(StripeHandler::class.java)
    private val gson = Gson()

    private val stripeMode = System.getenv("STRIPE_MODE") ?: "test"
    private val stripeSecretKey = when (stripeMode) {
        "live" -> System.getenv("STRIPE_LIVE_SECRET_KEY") ?: System.getenv("STRIPE_SECRET_KEY") ?: ""
        else -> System.getenv("STRIPE_TEST_SECRET_KEY") ?: System.getenv("STRIPE_SECRET_KEY") ?: ""
    }
    private val stripeWebhookSecret = System.getenv("STRIPE_WEBHOOK_SECRET") ?: ""
    private val panelUrl = System.getenv("PROPERBACKUP_PANEL_URL") ?: "http://localhost:5175"

    companion object {
        const val MONTHLY_PLAN = "monthly"
        const val ANNUAL_PLAN = "annual"
        const val MONTHLY_PRICE_PLN = 1900L   // 19.00 PLN in grosz
        const val ANNUAL_PRICE_PLN = 19000L   // 190.00 PLN in grosz
        const val TRIAL_DAYS = 30L
        const val STORAGE_LIMIT_BYTES = 2199023255552L // 2TB
        const val PRODUCT_NAME = "ProperBackup"
    }

    private var monthlyPriceId: String? = null
    private var annualPriceId: String? = null

    init {
        if (stripeSecretKey.isNotBlank()) {
            Stripe.apiKey = stripeSecretKey
            log.info("Stripe initialized: mode={} key={}...", stripeMode, stripeSecretKey.take(12))
            initPriceIds()
        } else {
            log.warn("STRIPE_SECRET_KEY not set — Stripe payments disabled")
        }
    }

    private fun initPriceIds() {
        try {
            monthlyPriceId = loadPriceId(MONTHLY_PLAN)
            annualPriceId = loadPriceId(ANNUAL_PLAN)

            if (monthlyPriceId == null || annualPriceId == null) {
                ensureStripePrices()
            }

            log.info("Stripe Prices: monthly={} annual={}", monthlyPriceId, annualPriceId)
        } catch (e: Exception) {
            log.warn("Failed to init Stripe Prices (will create on first use): {}", e.message)
        }
    }

    private fun loadPriceId(planKey: String): String? {
        return try {
            db.getConnection().use { conn ->
                conn.prepareStatement("SELECT stripe_price_id FROM stripe_price_config WHERE plan_key = ?").use { ps ->
                    ps.setString(1, planKey)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rs.getString("stripe_price_id") else null
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun savePriceId(planKey: String, priceId: String) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO stripe_price_config (plan_key, stripe_price_id) VALUES (?, ?) ON CONFLICT (plan_key) DO UPDATE SET stripe_price_id = ?, updated_at = NOW()",
            ).use { ps ->
                ps.setString(1, planKey)
                ps.setString(2, priceId)
                ps.setString(3, priceId)
                ps.executeUpdate()
            }
        }
    }

    private fun ensureStripePrices() {
        val productId = findOrCreateProduct()

        if (monthlyPriceId == null) {
            monthlyPriceId = findOrCreatePrice(productId, MONTHLY_PLAN, MONTHLY_PRICE_PLN, PriceCreateParams.Recurring.Interval.MONTH)
            savePriceId(MONTHLY_PLAN, monthlyPriceId!!)
        }

        if (annualPriceId == null) {
            annualPriceId = findOrCreatePrice(productId, ANNUAL_PLAN, ANNUAL_PRICE_PLN, PriceCreateParams.Recurring.Interval.YEAR)
            savePriceId(ANNUAL_PLAN, annualPriceId!!)
        }
    }

    private fun findOrCreateProduct(): String {
        val existing = Product.list(ProductListParams.builder().setLimit(100).build())
        val found = existing.data.firstOrNull { it.name == PRODUCT_NAME && it.active }
        if (found != null) return found.id

        val params = ProductCreateParams.builder()
            .setName(PRODUCT_NAME)
            .setDescription("ProperBackup subscription — encrypted cloud backup service")
            .build()
        val product = Product.create(params)
        log.info("Created Stripe Product: id={} name={}", product.id, product.name)
        return product.id
    }

    private fun findOrCreatePrice(
        productId: String,
        planKey: String,
        amountGrosze: Long,
        interval: PriceCreateParams.Recurring.Interval,
    ): String {
        val existing = Price.list(
            PriceListParams.builder()
                .setProduct(productId)
                .setActive(true)
                .setLimit(100)
                .build(),
        )
        val found = existing.data.firstOrNull { price ->
            price.unitAmount == amountGrosze &&
                price.currency == "pln" &&
                price.recurring?.interval == interval.name.lowercase()
        }
        if (found != null) {
            log.info("Found existing Stripe Price for {}: id={}", planKey, found.id)
            return found.id
        }

        val params = PriceCreateParams.builder()
            .setProduct(productId)
            .setCurrency("pln")
            .setUnitAmount(amountGrosze)
            .setRecurring(
                PriceCreateParams.Recurring.builder()
                    .setInterval(interval)
                    .build(),
            )
            .putMetadata("planKey", planKey)
            .build()
        val price = Price.create(params)
        log.info("Created Stripe Price: id={} plan={} amount={} interval={}", price.id, planKey, amountGrosze, interval)
        return price.id
    }

    /**
     * Creates a Stripe Checkout Session in subscription mode.
     * Applies prorated discount as a one-time coupon on the first invoice.
     * Promo code discount is applied as a separate coupon.
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

        val body = try { gson.fromJson(ctx.body(), Map::class.java) } catch (_: Exception) { null }
        val plan = (body?.get("plan") as? String) ?: ctx.queryParam("plan") ?: MONTHLY_PLAN
        val promoCode = (body?.get("promoCode") as? String)?.trim()?.uppercase()

        val priceId = if (plan == ANNUAL_PLAN) annualPriceId else monthlyPriceId
        if (priceId == null) {
            ensureStripePrices()
        }
        val resolvedPriceId = (if (plan == ANNUAL_PLAN) annualPriceId else monthlyPriceId)
            ?: run {
                ctx.status(503).contentType("application/json").result(
                    gson.toJson(mapOf("error" to "Stripe prices not configured")),
                )
                return
            }

        val basePriceGrosze = if (plan == ANNUAL_PLAN) ANNUAL_PRICE_PLN else MONTHLY_PRICE_PLN
        val totalDays = if (plan == ANNUAL_PLAN) 365L else 31L

        val daysRemaining = calculateDaysRemaining(user)
        val proratedDiscount = if (daysRemaining > 0) minOf(basePriceGrosze, basePriceGrosze * daysRemaining / totalDays) else 0L

        var promoDiscount = 0L
        if (!promoCode.isNullOrBlank() && promoCodeHandler != null) {
            promoDiscount = promoCodeHandler.calculateDiscount(promoCode, basePriceGrosze)
        }

        val totalDiscount = minOf(basePriceGrosze, proratedDiscount + promoDiscount)

        log.info(
            "Stripe subscription checkout: userId={} plan={} base={} prorated=-{} promo=-{} totalDiscount={} promoCode={}",
            userId, plan, basePriceGrosze, proratedDiscount, promoDiscount, totalDiscount, promoCode,
        )

        logAudit(userId, "checkout_started", mapOf(
            "plan" to plan, "basePriceGrosze" to basePriceGrosze,
            "proratedDiscount" to proratedDiscount, "promoDiscount" to promoDiscount,
            "totalDiscount" to totalDiscount, "promoCode" to (promoCode ?: ""),
            "mode" to "subscription",
        ))

        try {
            val customerId = getOrCreateStripeCustomer(userId, user.email)

            val sessionBuilder = SessionCreateParams.builder()
                .setCustomer(customerId)
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setSuccessUrl("$panelUrl/account/subscription?stripe=success&session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl("$panelUrl/account/subscription?stripe=cancelled")
                .putMetadata("userId", userId)
                .putMetadata("plan", plan)
                .putMetadata("basePriceGrosze", basePriceGrosze.toString())
                .putMetadata("proratedDiscount", proratedDiscount.toString())
                .putMetadata("promoDiscount", promoDiscount.toString())
                .addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD)

            if (!promoCode.isNullOrBlank()) {
                sessionBuilder.putMetadata("promoCode", promoCode)
            }

            val lineItemBuilder = SessionCreateParams.LineItem.builder()
                .setPrice(resolvedPriceId)
                .setQuantity(1)
            sessionBuilder.addLineItem(lineItemBuilder.build())

            // Apply discount as coupon on the subscription
            if (totalDiscount > 0) {
                val couponId = createOneTimeCoupon(totalDiscount, basePriceGrosze, plan, userId)
                sessionBuilder.addDiscount(
                    SessionCreateParams.Discount.builder()
                        .setCoupon(couponId)
                        .build(),
                )
            }

            val session = Session.create(sessionBuilder.build())

            log.info("Stripe Subscription Checkout created: sessionId={} userId={} plan={} priceId={}", session.id, userId, plan, resolvedPriceId)

            ctx.contentType("application/json").result(
                gson.toJson(
                    mapOf(
                        "sessionId" to session.id,
                        "checkoutUrl" to session.url,
                        "mode" to "subscription",
                    ),
                ),
            )
        } catch (e: Exception) {
            log.error("Failed to create Stripe subscription checkout: userId={}", userId, e)
            ctx.status(500).contentType("application/json").result(
                gson.toJson(mapOf("error" to (e.message ?: "Stripe error"))),
            )
        }
    }

    private fun createOneTimeCoupon(discountGrosze: Long, basePriceGrosze: Long, plan: String, userId: String): String {
        val params = com.stripe.param.CouponCreateParams.builder()
            .setAmountOff(discountGrosze)
            .setCurrency("pln")
            .setDuration(com.stripe.param.CouponCreateParams.Duration.ONCE)
            .setName("PB discount $plan")
            .putMetadata("userId", userId)
            .putMetadata("plan", plan)
            .putMetadata("type", "prorated_and_promo")
            .build()

        val coupon = com.stripe.model.Coupon.create(params)
        log.info("Created one-time coupon: id={} amountOff={} for userId={}", coupon.id, discountGrosze, userId)
        return coupon.id
    }

    private fun cancelExistingStripeSubscription(user: UserStore.User) {
        val subId = user.stripeSubscriptionId
        if (subId.isNullOrBlank()) return

        try {
            val sub = Subscription.retrieve(subId)
            if (sub.status == "active" || sub.status == "trialing" || sub.status == "past_due") {
                sub.cancel()
                log.info("Cancelled existing Stripe subscription: subId={} userId={}", subId, user.id)
            }
        } catch (e: Exception) {
            log.warn("Failed to cancel existing subscription subId={}: {}", subId, e.message)
        }

        userStore.updateStripeSubscriptionId(user.id, null)
    }

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
            "invoice.paid" -> handleInvoicePaid(event)
            "invoice.payment_failed" -> handleInvoicePaymentFailed(event)
            "customer.subscription.deleted" -> handleSubscriptionDeleted(event)
            "customer.subscription.updated" -> handleSubscriptionUpdated(event)
            else -> log.debug("Stripe webhook: unhandled event type={}", event.type)
        }

        ctx.status(200).result("OK")
    }

    fun getPublicKey(ctx: Context) {
        val publicKey = when (stripeMode) {
            "live" -> System.getenv("STRIPE_LIVE_PUBLIC_KEY")
                ?: System.getenv("STRIPE_PUBLIC_KEY")
                ?: ""
            else -> System.getenv("STRIPE_TEST_PUBLIC_KEY")
                ?: System.getenv("STRIPE_PUBLIC_KEY")
                ?: ""
        }
        ctx.contentType("application/json").result(
            gson.toJson(mapOf("publicKey" to publicKey)),
        )
    }

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
                    "stripeSubscriptionId" to user.stripeSubscriptionId,
                ),
            ),
        )
    }

    // ============================================================================
    // Webhook handlers
    // ============================================================================

    private fun handleCheckoutCompleted(event: Event) {
        val deserializer = event.dataObjectDeserializer
        val dataObj = try {
            deserializer.`object`.orElseGet { deserializer.deserializeUnsafe() }
        } catch (e: Exception) {
            log.warn("Stripe checkout.session.completed: deserialization failed", e)
            null
        }
        if (dataObj == null || dataObj !is Session) {
            log.warn("Stripe checkout.session.completed: could not deserialize session")
            return
        }

        val session = dataObj
        val userId = session.metadata?.get("userId")
        val plan = session.metadata?.get("plan") ?: MONTHLY_PLAN
        val promoCode = session.metadata?.get("promoCode")
        val subscriptionId = session.subscription

        if (userId.isNullOrBlank()) {
            log.warn("Stripe checkout.session.completed: missing userId in metadata, sessionId={}", session.id)
            return
        }

        log.info(
            "Stripe checkout completed: sessionId={} userId={} plan={} mode={} subscriptionId={} promoCode={}",
            session.id, userId, plan, session.mode, subscriptionId, promoCode,
        )

        if (session.mode == "subscription" && !subscriptionId.isNullOrBlank()) {
            // Cancel previous Stripe subscription only after new one is confirmed
            val existingUser = userStore.findById(userId).orElse(null)
            if (existingUser != null) {
                cancelExistingStripeSubscription(existingUser)
            }
            // Save the new Stripe subscription ID
            userStore.updateStripeSubscriptionId(userId, subscriptionId)
            // Activate plan in our DB
            activateSubscription(userId, plan)
        } else if (session.mode == "payment" || session.mode == "setup") {
            // Fallback for legacy one-time payments
            activateSubscription(userId, plan)
        }

        if (!promoCode.isNullOrBlank() && promoCodeHandler != null) {
            promoCodeHandler.markPromoCodeUsed(promoCode, userId, session.id)
        }

        saveStripeEvent(userId, "checkout_completed", session.id, plan)
        logAudit(userId, "checkout_completed", mapOf(
            "plan" to plan, "sessionId" to session.id,
            "subscriptionId" to (subscriptionId ?: ""),
            "promoCode" to (promoCode ?: ""),
            "mode" to (session.mode ?: "unknown"),
        ))
    }

    /**
     * invoice.paid — Stripe confirms recurring payment succeeded.
     * Extends the subscription for the next billing period.
     */
    private fun handleInvoicePaid(event: Event) {
        val deserializer = event.dataObjectDeserializer
        val dataObj = try {
            deserializer.`object`.orElseGet { deserializer.deserializeUnsafe() }
        } catch (e: Exception) {
            log.warn("Stripe invoice.paid: deserialization failed", e)
            null
        }
        if (dataObj == null || dataObj !is Invoice) {
            log.warn("Stripe invoice.paid: could not deserialize invoice")
            return
        }

        val invoice = dataObj
        val subscriptionId = invoice.subscription
        val customerId = invoice.customer

        if (subscriptionId.isNullOrBlank()) {
            log.debug("Stripe invoice.paid: no subscription (one-time payment), skipping")
            return
        }

        val user = userStore.findByStripeSubscriptionId(subscriptionId).orElse(null)
            ?: userStore.findByStripeCustomerId(customerId ?: "").orElse(null)

        if (user == null) {
            log.warn("Stripe invoice.paid: could not find user for subscriptionId={} customerId={}", subscriptionId, customerId)
            return
        }

        // Determine plan from the subscription's price
        val plan = determinePlanFromSubscription(subscriptionId)

        log.info(
            "Stripe invoice.paid: userId={} subscriptionId={} plan={} amountPaid={}",
            user.id, subscriptionId, plan, invoice.amountPaid,
        )

        // Extend subscription
        activateSubscription(user.id, plan)
        userStore.updateStripeSubscriptionId(user.id, subscriptionId)
        userStore.setCancelAtPeriodEnd(user.id, false)

        logAudit(user.id, "invoice_paid", mapOf(
            "subscriptionId" to subscriptionId,
            "plan" to plan,
            "amountPaid" to (invoice.amountPaid ?: 0L),
            "invoiceId" to (invoice.id ?: ""),
        ))
    }

    /**
     * invoice.payment_failed — Stripe could not charge the customer.
     * Stripe will auto-retry (configurable in Stripe Dashboard).
     */
    private fun handleInvoicePaymentFailed(event: Event) {
        val deserializer = event.dataObjectDeserializer
        val dataObj = try {
            deserializer.`object`.orElseGet { deserializer.deserializeUnsafe() }
        } catch (e: Exception) {
            log.warn("Stripe invoice.payment_failed: deserialization failed", e)
            null
        }
        if (dataObj == null || dataObj !is Invoice) {
            log.warn("Stripe invoice.payment_failed: could not deserialize invoice")
            return
        }

        val invoice = dataObj
        val subscriptionId = invoice.subscription
        val customerId = invoice.customer

        val user = if (!subscriptionId.isNullOrBlank()) {
            userStore.findByStripeSubscriptionId(subscriptionId).orElse(null)
        } else null
            ?: userStore.findByStripeCustomerId(customerId ?: "").orElse(null)

        log.warn(
            "Stripe invoice.payment_failed: userId={} subscriptionId={} customerId={} attemptCount={}",
            user?.id, subscriptionId, customerId, invoice.attemptCount,
        )

        if (user != null) {
            logAudit(user.id, "invoice_payment_failed", mapOf(
                "subscriptionId" to (subscriptionId ?: ""),
                "attemptCount" to (invoice.attemptCount ?: 0L),
                "invoiceId" to (invoice.id ?: ""),
            ))
        }
    }

    /**
     * customer.subscription.deleted — subscription fully cancelled (after period end or immediate).
     * Deactivate subscription in our DB.
     */
    private fun handleSubscriptionDeleted(event: Event) {
        val deserializer = event.dataObjectDeserializer
        val dataObj = try {
            deserializer.`object`.orElseGet { deserializer.deserializeUnsafe() }
        } catch (e: Exception) {
            log.warn("Stripe customer.subscription.deleted: deserialization failed", e)
            null
        }
        if (dataObj == null || dataObj !is Subscription) {
            log.warn("Stripe customer.subscription.deleted: could not deserialize subscription")
            return
        }

        val subscription = dataObj
        val user = userStore.findByStripeSubscriptionId(subscription.id).orElse(null)
            ?: userStore.findByStripeCustomerId(subscription.customer ?: "").orElse(null)

        if (user == null) {
            log.warn("Stripe customer.subscription.deleted: could not find user for subId={}", subscription.id)
            return
        }

        log.info("Stripe subscription deleted: userId={} subId={}", user.id, subscription.id)

        userStore.deactivateSubscription(user.id)

        logAudit(user.id, "subscription_deleted", mapOf(
            "subscriptionId" to subscription.id,
            "reason" to (subscription.cancellationDetails?.reason ?: "unknown"),
        ))
    }

    /**
     * customer.subscription.updated — subscription changed (e.g., cancel_at_period_end toggled).
     * Sync cancel_at_period_end state to our DB.
     */
    private fun handleSubscriptionUpdated(event: Event) {
        val deserializer = event.dataObjectDeserializer
        val dataObj = try {
            deserializer.`object`.orElseGet { deserializer.deserializeUnsafe() }
        } catch (e: Exception) {
            log.warn("Stripe customer.subscription.updated: deserialization failed", e)
            null
        }
        if (dataObj == null || dataObj !is Subscription) {
            log.warn("Stripe customer.subscription.updated: could not deserialize subscription")
            return
        }

        val subscription = dataObj
        val user = userStore.findByStripeSubscriptionId(subscription.id).orElse(null)
            ?: userStore.findByStripeCustomerId(subscription.customer ?: "").orElse(null)

        if (user == null) {
            log.debug("Stripe customer.subscription.updated: user not found for subId={}", subscription.id)
            return
        }

        val cancelAtEnd = subscription.cancelAtPeriodEnd ?: false
        log.info("Stripe subscription updated: userId={} subId={} cancelAtPeriodEnd={} status={}", user.id, subscription.id, cancelAtEnd, subscription.status)

        userStore.setCancelAtPeriodEnd(user.id, cancelAtEnd)

        if (subscription.status == "active" || subscription.status == "trialing") {
            val periodEnd = subscription.currentPeriodEnd
            if (periodEnd != null) {
                val expiresAt = Instant.ofEpochSecond(periodEnd)
                val plan = determinePlanFromSubscription(subscription.id)
                userStore.updateSubscription(user.id, plan, expiresAt, STORAGE_LIMIT_BYTES)
            }
        }

        logAudit(user.id, "subscription_updated", mapOf(
            "subscriptionId" to subscription.id,
            "cancelAtPeriodEnd" to cancelAtEnd,
            "status" to (subscription.status ?: "unknown"),
        ))
    }

    private fun determinePlanFromSubscription(subscriptionId: String): String {
        return try {
            val sub = Subscription.retrieve(subscriptionId)
            val priceId = sub.items?.data?.firstOrNull()?.price?.id
            when (priceId) {
                annualPriceId -> ANNUAL_PLAN
                monthlyPriceId -> MONTHLY_PLAN
                else -> {
                    val amount = sub.items?.data?.firstOrNull()?.price?.unitAmount
                    if (amount != null && amount >= 10000L) ANNUAL_PLAN else MONTHLY_PLAN
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to determine plan from subscription {}: {}", subscriptionId, e.message)
            MONTHLY_PLAN
        }
    }

    // ============================================================================
    // Subscription management endpoints
    // ============================================================================

    private fun activateSubscription(userId: String, plan: String) {
        val durationSeconds = if (plan == ANNUAL_PLAN) 365L * 24 * 60 * 60 else 31L * 24 * 60 * 60
        val now = Instant.now()

        val user = userStore.findById(userId).orElse(null)
        val currentExpiry = user?.subscriptionExpiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: user?.trialExpiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }

        val baseTime = if (currentExpiry != null && currentExpiry.isAfter(now)) currentExpiry else now
        val expiresAt = baseTime.plusSeconds(durationSeconds)

        userStore.updateSubscription(userId, plan, expiresAt, STORAGE_LIMIT_BYTES)
        log.info(
            "Subscription activated: userId={} plan={} baseTime={} expiresAt={} (preserved {} remaining days)",
            userId, plan, baseTime, expiresAt,
            if (currentExpiry != null && currentExpiry.isAfter(now)) Duration.between(now, currentExpiry).toDays() else 0,
        )
    }

    fun calculateDaysRemaining(user: UserStore.User): Long {
        val now = Instant.now()
        val trialExpiresAt = user.trialExpiresAt?.let { Instant.parse(it) }
        val subscriptionExpiresAt = user.subscriptionExpiresAt?.let { Instant.parse(it) }

        return when {
            user.subscriptionPlan != null && subscriptionExpiresAt != null && subscriptionExpiresAt.isAfter(now) ->
                Duration.between(now, subscriptionExpiresAt).toDays()
            trialExpiresAt != null && trialExpiresAt.isAfter(now) ->
                Duration.between(now, trialExpiresAt).toDays()
            else -> 0L
        }
    }

    private fun getOrCreateStripeCustomer(userId: String, email: String): String {
        val existing = getStripeCustomerId(userId)
        if (existing != null) return existing

        val params = CustomerCreateParams.builder()
            .setEmail(email)
            .putMetadata("userId", userId)
            .build()

        val customer = Customer.create(params)
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

    fun getCardInfo(ctx: Context) {
        val userId = ctx.attribute<String>("userId")
        if (userId == null) {
            ctx.status(401).result("Unauthorized")
            return
        }

        val customerId = getStripeCustomerId(userId)
        if (customerId == null || stripeSecretKey.isBlank()) {
            ctx.contentType("application/json").result(gson.toJson(mapOf("hasCard" to false)))
            return
        }

        try {
            val params = CustomerListPaymentMethodsParams.builder()
                .setType(CustomerListPaymentMethodsParams.Type.CARD)
                .setLimit(1)
                .build()
            val methods = Customer.retrieve(customerId).listPaymentMethods(params)
            val pm = methods.data.firstOrNull()

            if (pm?.card != null) {
                ctx.contentType("application/json").result(
                    gson.toJson(
                        mapOf(
                            "hasCard" to true,
                            "brand" to (pm.card.brand ?: "unknown"),
                            "last4" to (pm.card.last4 ?: "0000"),
                            "expMonth" to pm.card.expMonth,
                            "expYear" to pm.card.expYear,
                        ),
                    ),
                )
            } else {
                ctx.contentType("application/json").result(gson.toJson(mapOf("hasCard" to false)))
            }
        } catch (e: Exception) {
            log.warn("Failed to get card info for userId={}: {}", userId, e.message)
            ctx.contentType("application/json").result(gson.toJson(mapOf("hasCard" to false)))
        }
    }

    /**
     * POST /payment/stripe/cancel-subscription
     * Sets cancel_at_period_end=true on the Stripe Subscription.
     * The actual full cancellation happens via webhook when the period ends.
     */
    fun cancelSubscription(ctx: Context) {
        val userId = ctx.attribute<String>("userId")
        if (userId == null) {
            ctx.status(401).result("Unauthorized")
            return
        }

        val user = userStore.findById(userId).orElse(null)
        if (user == null || user.subscriptionPlan == null) {
            ctx.status(400).contentType("application/json").result(
                gson.toJson(mapOf("error" to "no_active_subscription")),
            )
            return
        }

        logAudit(userId, "cancel_requested", mapOf("plan" to (user.subscriptionPlan ?: "")))

        val subId = user.stripeSubscriptionId
        if (!subId.isNullOrBlank()) {
            try {
                val params = SubscriptionUpdateParams.builder()
                    .setCancelAtPeriodEnd(true)
                    .build()
                Subscription.retrieve(subId).update(params)
                log.info("Stripe subscription cancel_at_period_end=true: userId={} subId={}", userId, subId)
            } catch (e: Exception) {
                log.warn("Failed to update Stripe subscription for cancel: subId={} err={}", subId, e.message)
                // Fall back to local flag
            }
        }

        userStore.setCancelAtPeriodEnd(userId, true)
        logAudit(userId, "plan_cancelled", mapOf("plan" to (user.subscriptionPlan ?: "")))

        ctx.contentType("application/json").result(
            gson.toJson(mapOf("status" to "cancelled", "expiresAt" to user.subscriptionExpiresAt)),
        )
    }

    /**
     * POST /payment/stripe/renew
     * Re-activates renewal for a cancelled subscription (redirect to Stripe Checkout).
     */
    fun renewSubscription(ctx: Context) {
        val userId = ctx.attribute<String>("userId")
        if (userId == null) {
            ctx.status(401).result("Unauthorized")
            return
        }

        logAudit(userId, "renew_requested", emptyMap())
        createCheckoutSession(ctx)
    }

    /**
     * POST /payment/stripe/reactivate
     * Undoes cancel_at_period_end via Stripe API.
     */
    fun reactivateSubscription(ctx: Context) {
        val userId = ctx.attribute<String>("userId")
        if (userId == null) {
            ctx.status(401).result("Unauthorized")
            return
        }

        val user = userStore.findById(userId).orElse(null)
        if (user == null || user.subscriptionPlan == null) {
            ctx.status(400).contentType("application/json").result(
                gson.toJson(mapOf("error" to "no_active_subscription")),
            )
            return
        }

        val subId = user.stripeSubscriptionId
        if (!subId.isNullOrBlank()) {
            try {
                val params = SubscriptionUpdateParams.builder()
                    .setCancelAtPeriodEnd(false)
                    .build()
                Subscription.retrieve(subId).update(params)
                log.info("Stripe subscription cancel_at_period_end=false (reactivated): userId={} subId={}", userId, subId)
            } catch (e: Exception) {
                log.warn("Failed to reactivate Stripe subscription: subId={} err={}", subId, e.message)
            }
        }

        userStore.setCancelAtPeriodEnd(userId, false)
        logAudit(userId, "subscription_reactivated", mapOf("plan" to (user.subscriptionPlan ?: "")))

        ctx.contentType("application/json").result(
            gson.toJson(mapOf("status" to "reactivated")),
        )
    }

    /**
     * POST /payment/stripe/verify-session
     * Verifies a Checkout Session after redirect and activates the plan if paid.
     * Works for both subscription and payment mode sessions.
     */
    fun verifySession(ctx: Context) {
        val userId = ctx.attribute<String>("userId")
        if (userId == null) {
            ctx.status(401).result("Unauthorized")
            return
        }

        val body = try { gson.fromJson(ctx.body(), Map::class.java) } catch (_: Exception) { null }
        val sessionId = (body?.get("sessionId") as? String)?.trim()
        if (sessionId.isNullOrBlank()) {
            ctx.status(400).contentType("application/json").result(
                gson.toJson(mapOf("error" to "missing sessionId")),
            )
            return
        }

        try {
            val session = Session.retrieve(sessionId)
            val metaUserId = session.metadata?.get("userId")
            if (metaUserId != userId) {
                log.warn("verify-session: userId mismatch meta={} auth={}", metaUserId, userId)
                ctx.status(403).contentType("application/json").result(
                    gson.toJson(mapOf("error" to "session does not belong to this user")),
                )
                return
            }

            val plan = session.metadata?.get("plan") ?: MONTHLY_PLAN
            val promoCode = session.metadata?.get("promoCode")
            val subscriptionId = session.subscription
            val paid = session.paymentStatus == "paid" || session.mode == "setup"

            if (paid) {
                val user = userStore.findById(userId).orElse(null)
                val alreadyActive = user?.subscriptionPlan == plan && user.stripeSubscriptionId == subscriptionId

                if (!alreadyActive) {
                    activateSubscription(userId, plan)
                    if (!subscriptionId.isNullOrBlank()) {
                        userStore.updateStripeSubscriptionId(userId, subscriptionId)
                    }
                    if (!promoCode.isNullOrBlank() && promoCodeHandler != null) {
                        promoCodeHandler.markPromoCodeUsed(promoCode, userId, sessionId)
                    }
                    saveStripeEvent(userId, "checkout_verified", sessionId, plan)
                    logAudit(userId, "checkout_verified", mapOf(
                        "plan" to plan, "sessionId" to sessionId,
                        "subscriptionId" to (subscriptionId ?: ""),
                    ))
                }

                ctx.contentType("application/json").result(
                    gson.toJson(mapOf("status" to "active", "plan" to plan, "subscriptionId" to subscriptionId)),
                )
            } else {
                ctx.contentType("application/json").result(
                    gson.toJson(mapOf("status" to "unpaid", "paymentStatus" to session.paymentStatus)),
                )
            }
        } catch (e: Exception) {
            log.error("verify-session failed: sessionId={} userId={}", sessionId, userId, e)
            ctx.status(500).contentType("application/json").result(
                gson.toJson(mapOf("error" to (e.message ?: "Failed to verify session"))),
            )
        }
    }

    fun getVatRate(): Double {
        return try {
            db.getConnection().use { conn ->
                conn.prepareStatement("SELECT value FROM subscription_config WHERE key = 'vat_rate'").use { ps ->
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rs.getString("value").toDouble() else 0.23
                    }
                }
            }
        } catch (_: Exception) {
            0.23
        }
    }

    fun getStripeMode(): String = stripeMode

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
    }
}
