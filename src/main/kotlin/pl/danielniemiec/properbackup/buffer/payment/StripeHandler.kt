package pl.danielniemiec.properbackup.buffer.payment

import com.google.gson.Gson
import com.stripe.Stripe
import com.stripe.exception.SignatureVerificationException
import com.stripe.model.Customer
import com.stripe.model.Event
import com.stripe.model.Invoice
import com.stripe.model.Price
import com.stripe.model.Product
import com.stripe.model.Refund
import com.stripe.model.Subscription
import com.stripe.model.checkout.Session
import com.stripe.net.RequestOptions
import com.stripe.net.Webhook
import com.stripe.param.CustomerCreateParams
import com.stripe.param.CustomerListPaymentMethodsParams
import com.stripe.param.InvoiceListParams
import com.stripe.param.PriceCreateParams
import com.stripe.param.PriceListParams
import com.stripe.param.ProductCreateParams
import com.stripe.param.ProductListParams
import com.stripe.param.RefundCreateParams
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
    val keyProvider: StripeKeyProvider,
    panelUrlOverride: String? = null,
) {
    private val log = LoggerFactory.getLogger(StripeHandler::class.java)
    private val gson = Gson()

    private val panelUrl = panelUrlOverride ?: System.getenv("PROPERBACKUP_PANEL_URL") ?: "http://localhost:5175"

    companion object {
        const val MONTHLY_PLAN = "monthly"
        const val ANNUAL_PLAN = "annual"
        // Prices are GROSS (brutto, VAT-inclusive). Stripe charges this exact amount.
        // VAT is decomposed downstream for display/invoice purposes (see decomposeGross).
        const val MONTHLY_PRICE_PLN = 1900L   // 19.00 PLN brutto in grosz
        const val ANNUAL_PRICE_PLN = 19000L   // 190.00 PLN brutto in grosz
        const val MONTHLY_TOTAL_DAYS = 31L
        const val ANNUAL_TOTAL_DAYS = 365L
        const val TRIAL_DAYS = 30L
        const val STORAGE_LIMIT_BYTES = 2199023255552L // 2TB
        const val PRODUCT_NAME = "ProperBackup"

        /**
         * Decompose a gross (brutto) price into net + VAT using rounded reverse calculation.
         * The displayed gross MUST equal what Stripe actually charges — that's the invariant.
         * Returns Pair(netGrosze, vatGrosze) where netGrosze + vatGrosze == grossGrosze.
         */
        fun decomposeGross(grossGrosze: Long, vatRate: Double): Pair<Long, Long> {
            if (grossGrosze <= 0L || vatRate <= 0.0) return grossGrosze to 0L
            val net = Math.round(grossGrosze.toDouble() / (1.0 + vatRate))
            val vat = grossGrosze - net
            return net to vat
        }

        /**
         * Compute the prorated discount when transitioning to a new plan.
         *
         * Rules:
         * - Trial-only user (no paid plan): proration uses NEW plan's per-day price as a "fair
         *   trial credit" toward the first invoice. Capped at the new plan's base.
         * - Paid plan user: proration preserves the VALUE of remaining paid days from the
         *   CURRENT plan. This is what guarantees a downgrade (annual→monthly) doesn't silently
         *   forfeit value. Anything in excess of newPlanBase is returned as `overflowCreditGrosze`,
         *   which callers credit to the Stripe customer.balance.
         *
         * Returns Pair(discountToApplyAsCouponGrosze, overflowCreditGrosze).
         */
        fun calculateProratedDiscount(
            currentPlan: String?,
            daysRemaining: Long,
            newPlanBaseGrosze: Long,
            newPlanTotalDays: Long,
        ): Pair<Long, Long> {
            if (daysRemaining <= 0L) return 0L to 0L
            val remainingValueGrosze: Long = when (currentPlan) {
                ANNUAL_PLAN -> ANNUAL_PRICE_PLN * daysRemaining / ANNUAL_TOTAL_DAYS
                MONTHLY_PLAN -> MONTHLY_PRICE_PLN * daysRemaining / MONTHLY_TOTAL_DAYS
                else -> newPlanBaseGrosze * daysRemaining / newPlanTotalDays
            }
            val capped = minOf(newPlanBaseGrosze, remainingValueGrosze)
            val overflow = if (remainingValueGrosze > newPlanBaseGrosze) remainingValueGrosze - newPlanBaseGrosze else 0L
            return capped to overflow
        }

        /**
         * Generate a deterministic Stripe idempotency key for a checkout session.
         *
         * Two requests from the same user, for the same plan + promo + base price, within
         * the same time bucket return the SAME key. Stripe then guarantees only one Checkout
         * Session is created (subsequent requests retrieve the original). This kills the
         * double-click double-billing class of bug. Cross-bucket the keys differ, so a user
         * intentionally retrying after a minute is not blocked.
         *
         * Default window is 30 seconds — long enough to absorb network retries and
         * accidental double-clicks, short enough not to block a deliberate retry.
         */
        fun checkoutIdempotencyKey(
            userId: String,
            plan: String,
            basePriceGrosze: Long,
            promoCode: String?,
            nowEpochSeconds: Long,
            windowSeconds: Long = 30L,
        ): String {
            val bucket = nowEpochSeconds / windowSeconds
            val promoFragment = (promoCode ?: "none").uppercase()
            val raw = "checkout|$userId|$plan|$basePriceGrosze|$promoFragment|$bucket"
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(40)
        }
    }

    private var monthlyPriceId: String? = null
    private var annualPriceId: String? = null

    init {
        if (keyProvider.hasAnyKey()) {
            log.info("Stripe initialized via StripeKeyProvider (per-user key isolation active)")
            initPriceIds()
        } else {
            log.warn("No Stripe keys configured — Stripe payments disabled")
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

    private fun defaultRequestOptions(): RequestOptions = keyProvider.requestOptionsForMode(true)

    private fun ensureStripePrices() {
        val opts = defaultRequestOptions()
        val productId = findOrCreateProduct(opts)

        if (monthlyPriceId == null) {
            monthlyPriceId = findOrCreatePrice(productId, MONTHLY_PLAN, MONTHLY_PRICE_PLN, PriceCreateParams.Recurring.Interval.MONTH, opts)
            savePriceId(MONTHLY_PLAN, monthlyPriceId!!)
        }

        if (annualPriceId == null) {
            annualPriceId = findOrCreatePrice(productId, ANNUAL_PLAN, ANNUAL_PRICE_PLN, PriceCreateParams.Recurring.Interval.YEAR, opts)
            savePriceId(ANNUAL_PLAN, annualPriceId!!)
        }
    }

    private fun findOrCreateProduct(opts: RequestOptions): String {
        val existing = Product.list(ProductListParams.builder().setLimit(100).build(), opts)
        val found = existing.data.firstOrNull { it.name == PRODUCT_NAME && it.active }
        if (found != null) return found.id

        val params = ProductCreateParams.builder()
            .setName(PRODUCT_NAME)
            .setDescription("ProperBackup subscription — encrypted cloud backup service")
            .build()
        val product = Product.create(params, opts)
        log.info("Created Stripe Product: id={} name={}", product.id, product.name)
        return product.id
    }

    private fun findOrCreatePrice(
        productId: String,
        planKey: String,
        amountGrosze: Long,
        interval: PriceCreateParams.Recurring.Interval,
        opts: RequestOptions,
    ): String {
        val existing = Price.list(
            PriceListParams.builder()
                .setProduct(productId)
                .setActive(true)
                .setLimit(100)
                .build(),
            opts,
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
        val price = Price.create(params, opts)
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

        if (!keyProvider.hasAnyKey()) {
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
        val totalDays = if (plan == ANNUAL_PLAN) ANNUAL_TOTAL_DAYS else MONTHLY_TOTAL_DAYS

        val daysRemaining = calculateDaysRemaining(user)
        val (proratedDiscount, overflowCredit) = calculateProratedDiscount(
            user.subscriptionPlan, daysRemaining, basePriceGrosze, totalDays,
        )

        var promoDiscount = 0L
        if (!promoCode.isNullOrBlank() && promoCodeHandler != null) {
            // Re-validate the promo at checkout time. If the validate→checkout window stretched
            // long enough for the promo to expire / be deactivated / hit max_uses / lose its
            // first-order eligibility for this user, we must refuse — otherwise calculateDiscount
            // would silently return 0 and the user would be charged the full price after they
            // clicked "Pay" believing the promo applied.
            val reason = promoCodeHandler.validateForCheckout(promoCode, userId)
            if (reason != null) {
                log.info("Stripe checkout REJECTED — promo no longer valid: userId={} code={} reason={}", userId, promoCode, reason)
                logAudit(userId, "checkout_rejected_promo", mapOf("code" to promoCode, "reason" to reason))
                ctx.status(410).contentType("application/json").result(
                    gson.toJson(mapOf(
                        "error" to "promo_no_longer_valid",
                        "reason" to reason,
                        "message" to "The promo code is no longer valid — please remove it or apply a different one.",
                    )),
                )
                return
            }
            promoDiscount = promoCodeHandler.calculateDiscount(promoCode, basePriceGrosze)
        }

        val totalDiscount = minOf(basePriceGrosze, proratedDiscount + promoDiscount)

        log.info(
            "Stripe subscription checkout: userId={} plan={} base={} prorated=-{} promo=-{} totalDiscount={} overflowCredit={} promoCode={}",
            userId, plan, basePriceGrosze, proratedDiscount, promoDiscount, totalDiscount, overflowCredit, promoCode,
        )

        logAudit(userId, "checkout_started", mapOf(
            "plan" to plan, "basePriceGrosze" to basePriceGrosze,
            "proratedDiscount" to proratedDiscount, "promoDiscount" to promoDiscount,
            "totalDiscount" to totalDiscount, "overflowCredit" to overflowCredit,
            "promoCode" to (promoCode ?: ""),
            "mode" to "subscription",
        ))

        try {
            val userOpts = keyProvider.requestOptionsFor(userId)
            val customerId = getOrCreateStripeCustomer(userId, user.email, userOpts)

            // If the user is downgrading (e.g. annual→monthly) and remaining value exceeds the new
            // plan's base, persist the excess as a credit on the Stripe customer balance. Stripe
            // automatically applies negative balances to subsequent invoices.
            if (overflowCredit > 0) {
                try {
                    val customer = Customer.retrieve(customerId, userOpts)
                    customer.balanceTransactions().create(
                        com.stripe.param.CustomerBalanceTransactionCollectionCreateParams.builder()
                            .setAmount(-overflowCredit)
                            .setCurrency("pln")
                            .setDescription("ProperBackup downgrade credit (preserved value)")
                            .putMetadata("userId", userId)
                            .putMetadata("reason", "downgrade_overflow")
                            .putMetadata("daysRemaining", daysRemaining.toString())
                            .putMetadata("fromPlan", user.subscriptionPlan ?: "none")
                            .putMetadata("toPlan", plan)
                            .build(),
                        userOpts,
                    )
                    log.info(
                        "Stripe customer balance credited: customerId={} amount=-{} (downgrade overflow)",
                        customerId, overflowCredit,
                    )
                    logAudit(userId, "downgrade_credit_applied", mapOf(
                        "customerId" to customerId, "overflowCredit" to overflowCredit,
                        "fromPlan" to (user.subscriptionPlan ?: "none"), "toPlan" to plan,
                        "daysRemaining" to daysRemaining,
                    ))
                } catch (e: Exception) {
                    log.warn("Failed to apply downgrade credit: userId={} amount={}: {}", userId, overflowCredit, e.message)
                }
            }

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
                .putMetadata("overflowCredit", overflowCredit.toString())
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
                val couponId = createOneTimeCoupon(totalDiscount, basePriceGrosze, plan, userId, userOpts)
                sessionBuilder.addDiscount(
                    SessionCreateParams.Discount.builder()
                        .setCoupon(couponId)
                        .build(),
                )
            }

            // Stripe idempotency: if the user double-clicks "Pay" we MUST NOT create
            // two checkout sessions. Stripe returns the original session for the same
            // idempotency-key, so the user lands on the same checkout URL instead of
            // being billed twice. See P2-7 in the audit.
            val idempotencyKey = checkoutIdempotencyKey(
                userId = userId,
                plan = plan,
                basePriceGrosze = basePriceGrosze,
                promoCode = promoCode,
                nowEpochSeconds = Instant.now().epochSecond,
            )
            val requestOptions = RequestOptions.builder()
                .setApiKey(keyProvider.secretKeyFor(userId))
                .setIdempotencyKey(idempotencyKey)
                .build()
            val session = Session.create(sessionBuilder.build(), requestOptions)

            log.info(
                "Stripe Subscription Checkout created: sessionId={} userId={} plan={} priceId={} idempKey={}",
                session.id, userId, plan, resolvedPriceId, idempotencyKey,
            )

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

    private fun createOneTimeCoupon(discountGrosze: Long, basePriceGrosze: Long, plan: String, userId: String, opts: RequestOptions): String {
        val params = com.stripe.param.CouponCreateParams.builder()
            .setAmountOff(discountGrosze)
            .setCurrency("pln")
            .setDuration(com.stripe.param.CouponCreateParams.Duration.ONCE)
            .setName("PB discount $plan")
            .putMetadata("userId", userId)
            .putMetadata("plan", plan)
            .putMetadata("type", "prorated_and_promo")
            .build()

        val coupon = com.stripe.model.Coupon.create(params, opts)
        log.info("Created one-time coupon: id={} amountOff={} for userId={}", coupon.id, discountGrosze, userId)
        return coupon.id
    }

    private fun cancelExistingStripeSubscription(user: UserStore.User) {
        val subId = user.stripeSubscriptionId
        if (subId.isNullOrBlank()) return

        val opts = keyProvider.requestOptionsFor(user.id)
        try {
            val sub = Subscription.retrieve(subId, opts)
            if (sub.status == "active" || sub.status == "trialing" || sub.status == "past_due") {
                refundRecentInvoiceIfAny(subId, user.id, opts)
                sub.cancel(com.stripe.param.SubscriptionCancelParams.builder().build(), opts)
                log.info("Cancelled existing Stripe subscription: subId={} userId={}", subId, user.id)
            }
        } catch (e: Exception) {
            log.warn("Failed to cancel existing subscription subId={}: {}", subId, e.message)
        }

        userStore.updateStripeSubscriptionId(user.id, null)
    }

    /**
     * If the given subscription has an invoice paid in the last `windowSeconds`, refund the full
     * charge to the user's card. Used by [cancelExistingStripeSubscription] to prevent
     * double-billing when a user creates a second checkout session for an already-paid plan
     * (e.g. concurrent tabs, rapid plan switch). 24h window is wide enough to cover human-scale
     * mistakes without grabbing genuinely old invoices.
     */
    private fun refundRecentInvoiceIfAny(subscriptionId: String, userId: String, opts: RequestOptions, windowSeconds: Long = 86_400L) {
        try {
            val invoices = Invoice.list(
                InvoiceListParams.builder()
                    .setSubscription(subscriptionId)
                    .setLimit(1L)
                    .build(),
                opts,
            )
            val invoice = invoices.data.firstOrNull() ?: return
            val chargeId = invoice.charge ?: return
            val amountPaid = invoice.amountPaid ?: return
            val paidAtEpoch = invoice.statusTransitions?.paidAt ?: invoice.created ?: return
            val nowEpoch = Instant.now().epochSecond
            if (amountPaid > 0 && (nowEpoch - paidAtEpoch) <= windowSeconds) {
                log.info(
                    "Refunding superseded subscription invoice: userId={} subId={} invoiceId={} charge={} amount={}",
                    userId, subscriptionId, invoice.id, chargeId, amountPaid,
                )
                Refund.create(
                    RefundCreateParams.builder()
                        .setCharge(chargeId)
                        .setAmount(amountPaid)
                        .putMetadata("userId", userId)
                        .putMetadata("reason", "superseded_by_new_subscription")
                        .putMetadata("originalSubscriptionId", subscriptionId)
                        .build(),
                    opts,
                )
            }
        } catch (e: Exception) {
            log.warn("refundRecentInvoiceIfAny failed: subId={} userId={}: {}", subscriptionId, userId, e.message)
        }
    }

    fun handleWebhook(ctx: Context) {
        // Fail-closed: refuse to process webhooks if no signing secret is configured.
        val candidates = keyProvider.webhookSecretCandidates()
        if (candidates.isEmpty()) {
            log.error("Stripe webhook rejected: no webhook secret configured")
            ctx.status(503).result("Webhook secret not configured")
            return
        }

        val payload = ctx.body()
        val sigHeader = ctx.header("Stripe-Signature")

        if (sigHeader == null) {
            log.warn("Stripe webhook: missing Stripe-Signature header")
            ctx.status(400).result("Missing signature")
            return
        }

        // Try each configured webhook secret. Test mode is attempted first.
        var event: Event? = null
        var resolvedTestMode = true
        for ((testMode, secret) in candidates) {
            try {
                event = Webhook.constructEvent(payload, sigHeader, secret)
                resolvedTestMode = testMode
                break
            } catch (_: SignatureVerificationException) {
                // Try next candidate
            } catch (e: Exception) {
                log.error("Stripe webhook: failed to parse event", e)
                ctx.status(400).result("Invalid payload")
                return
            }
        }

        if (event == null) {
            log.warn("Stripe webhook: signature verification failed against all configured secrets")
            ctx.status(400).result("Invalid signature")
            return
        }

        log.info("Stripe webhook received: type={} id={} mode={}", event.type, event.id, keyProvider.modeLabelForMode(resolvedTestMode))

        // Idempotency: Stripe retries webhooks on any non-2xx (and on network failures). Claim the
        // event.id atomically before running any handler. If we've already processed it, return 200
        // so Stripe stops retrying — do NOT re-run handlers (would double-extend subscription, etc.).
        if (!tryClaimStripeEventId(event.id, event.type)) {
            log.info("Stripe webhook duplicate (already processed): type={} id={}", event.type, event.id)
            ctx.status(200).result("OK (duplicate)")
            return
        }

        val webhookOpts = keyProvider.requestOptionsForMode(resolvedTestMode)
        when (event.type) {
            "checkout.session.completed" -> handleCheckoutCompleted(event, webhookOpts)
            "invoice.paid" -> handleInvoicePaid(event, webhookOpts)
            "invoice.payment_failed" -> handleInvoicePaymentFailed(event)
            "customer.subscription.deleted" -> handleSubscriptionDeleted(event)
            "customer.subscription.updated" -> handleSubscriptionUpdated(event, webhookOpts)
            else -> log.debug("Stripe webhook: unhandled event type={}", event.type)
        }

        ctx.status(200).result("OK")
    }

    /**
     * Claim a Stripe event.id atomically. Returns true if this is the first time we see this
     * event, false if it was already processed. Used to guarantee at-most-once webhook handling.
     */
    fun tryClaimStripeEventId(eventId: String?, eventType: String? = null): Boolean {
        if (eventId.isNullOrBlank()) return true // can't dedupe without an id; let handler proceed
        return try {
            db.getConnection().use { conn ->
                conn.prepareStatement(
                    """INSERT INTO stripe_event_idempotency (stripe_event_id, event_type)
                       VALUES (?, ?)
                       ON CONFLICT (stripe_event_id) DO NOTHING""",
                ).use { ps ->
                    ps.setString(1, eventId)
                    if (eventType != null) ps.setString(2, eventType) else ps.setNull(2, java.sql.Types.VARCHAR)
                    val rows = ps.executeUpdate()
                    rows > 0
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to claim stripe event id={}: {}", eventId, e.message)
            // On DB failure, refuse to process — better to retry than to double-apply.
            false
        }
    }

    fun getPublicKey(ctx: Context) {
        val userId = ctx.attribute<String>("userId")
        val publicKey = if (userId != null) {
            keyProvider.publicKeyFor(userId)
        } else {
            keyProvider.publicKeyForMode(true)
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

    private fun handleCheckoutCompleted(event: Event, opts: RequestOptions) {
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
            // Pull the canonical period_end from Stripe so our DB never drifts (P2-9).
            val stripePeriodEnd = fetchStripeCurrentPeriodEnd(subscriptionId, opts)
            activateSubscription(userId, plan, stripePeriodEnd)
        } else if (session.mode == "payment" || session.mode == "setup") {
            // Fallback for legacy one-time payments (no recurring period_end available).
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
    private fun handleInvoicePaid(event: Event, opts: RequestOptions) {
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
        val plan = determinePlanFromSubscription(subscriptionId, opts)

        log.info(
            "Stripe invoice.paid: userId={} subscriptionId={} plan={} amountPaid={}",
            user.id, subscriptionId, plan, invoice.amountPaid,
        )

        // Extend subscription to the exact next period_end from Stripe (P2-9). Falls back to
        // 31/365-day stacking only if we can't resolve the canonical timestamp.
        val periodEnd = invoice.lines?.data?.firstOrNull()?.period?.end?.let { Instant.ofEpochSecond(it) }
            ?: fetchStripeCurrentPeriodEnd(subscriptionId, opts)
        activateSubscription(user.id, plan, periodEnd)
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
    private fun handleSubscriptionUpdated(event: Event, opts: RequestOptions) {
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
                val plan = determinePlanFromSubscription(subscription.id, opts)
                userStore.updateSubscription(user.id, plan, expiresAt, STORAGE_LIMIT_BYTES)
            }
        }

        logAudit(user.id, "subscription_updated", mapOf(
            "subscriptionId" to subscription.id,
            "cancelAtPeriodEnd" to cancelAtEnd,
            "status" to (subscription.status ?: "unknown"),
        ))
    }

    private fun determinePlanFromSubscription(subscriptionId: String, opts: RequestOptions): String {
        return try {
            val sub = Subscription.retrieve(subscriptionId, opts)
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

    /**
     * Activate (or extend) a paid plan for the user.
     *
     * If [stripePeriodEnd] is provided (from Stripe's `subscription.current_period_end` or
     * `invoice.period_end`), DB expiry is set to that exact instant so the local record never
     * drifts from Stripe's billing cycle. This matters because trial-to-pay or any out-of-band
     * activation can happen mid-month — adding a hardcoded 31/365 days would silently desynchronize
     * the DB from Stripe and the user could be charged again before our DB thinks the period ended.
     *
     * Fallback (when [stripePeriodEnd] is null, e.g. during legacy LemonSqueezy webhooks or
     * manual reactivation paths): preserve any remaining trial/subscription days by stacking
     * the new plan's nominal duration on top of the current expiry. This is a best-effort
     * fallback and should not be relied on for Stripe billing accuracy.
     *
     * See P2-9 in the audit (`audyt-platnosci-i-testow.md`).
     */
    fun activateSubscription(userId: String, plan: String, stripePeriodEnd: Instant? = null) {
        val now = Instant.now()

        val expiresAt = if (stripePeriodEnd != null) {
            stripePeriodEnd
        } else {
            val durationSeconds = if (plan == ANNUAL_PLAN) ANNUAL_TOTAL_DAYS * 24 * 60 * 60 else MONTHLY_TOTAL_DAYS * 24 * 60 * 60
            val user = userStore.findById(userId).orElse(null)
            val currentExpiry = user?.subscriptionExpiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: user?.trialExpiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val baseTime = if (currentExpiry != null && currentExpiry.isAfter(now)) currentExpiry else now
            baseTime.plusSeconds(durationSeconds)
        }

        userStore.updateSubscription(userId, plan, expiresAt, STORAGE_LIMIT_BYTES)
        userStore.markEverSubscribed(userId)
        log.info(
            "Subscription activated: userId={} plan={} expiresAt={} source={}",
            userId, plan, expiresAt,
            if (stripePeriodEnd != null) "stripe_period_end" else "local_fallback",
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

    private fun getOrCreateStripeCustomer(userId: String, email: String, opts: RequestOptions): String {
        val existing = getStripeCustomerId(userId)
        if (existing != null) return existing

        val params = CustomerCreateParams.builder()
            .setEmail(email)
            .putMetadata("userId", userId)
            .build()

        val customer = Customer.create(params, opts)
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

    /**
     * Returns the current Stripe customer balance for the user, in grosze.
     *
     * Stripe's `customer.balance` is signed:
     *   - **negative** = credit owed to the customer (auto-applied to next invoice)
     *   - **positive** = customer owes Stripe (e.g. from a failed invoice)
     *   - **zero**     = no credit/debit
     *
     * We return the absolute value of NEGATIVE balances as positive grosze so the UI can
     * display "Twój kredyt: X PLN". Returns 0 for zero/positive balances (positive means the
     * customer is in arrears — that's surfaced via invoice.payment_failed, not the balance line).
     *
     * Errors (no Stripe customer, blank API key, network failure) are mapped to 0 — the
     * balance field is informational, never a blocker for checkout flow.
     */
    fun getCustomerBalanceCreditGrosze(userId: String): Long {
        if (!keyProvider.hasAnyKey()) return 0L
        val customerId = getStripeCustomerId(userId) ?: return 0L
        val opts = keyProvider.requestOptionsFor(userId)
        return try {
            val balance = Customer.retrieve(customerId, opts).balance ?: 0L
            if (balance < 0L) -balance else 0L
        } catch (e: Exception) {
            log.warn("Stripe getCustomerBalance failed: userId={} err={}", userId, e.message)
            0L
        }
    }

    fun getCardInfo(ctx: Context) {
        val userId = ctx.attribute<String>("userId")
        if (userId == null) {
            ctx.status(401).result("Unauthorized")
            return
        }

        // Disable HTTP caching so a browser-level cache never shows a stale card after the
        // user updates their payment method via Stripe Checkout (P2-10).
        ctx.header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
        ctx.header("Pragma", "no-cache")

        val customerId = getStripeCustomerId(userId)
        val userTestMode = keyProvider.isTestMode(userId)
        if (customerId == null || !keyProvider.hasAnyKey()) {
            ctx.contentType("application/json").result(gson.toJson(mapOf(
                "hasCard" to false,
                "isTestCard" to userTestMode,
            )))
            return
        }

        val userOpts = keyProvider.requestOptionsFor(userId)
        try {
            val params = CustomerListPaymentMethodsParams.builder()
                .setType(CustomerListPaymentMethodsParams.Type.CARD)
                .setLimit(1)
                .build()
            val methods = Customer.retrieve(customerId, userOpts).listPaymentMethods(params, userOpts)
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
                            "isTestCard" to userTestMode,
                        ),
                    ),
                )
            } else {
                ctx.contentType("application/json").result(gson.toJson(mapOf(
                    "hasCard" to false,
                    "isTestCard" to userTestMode,
                )))
            }
        } catch (e: Exception) {
            log.warn("Failed to get card info for userId={}: {}", userId, e.message)
            ctx.contentType("application/json").result(gson.toJson(mapOf(
                "hasCard" to false,
                "isTestCard" to userTestMode,
            )))
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
            val userOpts = keyProvider.requestOptionsFor(userId)
            try {
                val params = SubscriptionUpdateParams.builder()
                    .setCancelAtPeriodEnd(true)
                    .build()
                Subscription.retrieve(subId, userOpts).update(params, userOpts)
                log.info("Stripe subscription cancel_at_period_end=true: userId={} subId={}", userId, subId)
            } catch (e: Exception) {
                log.warn("Failed to update Stripe subscription for cancel: subId={} err={}", subId, e.message)
            }
        }

        userStore.setCancelAtPeriodEnd(userId, true)
        logAudit(userId, "plan_cancelled", mapOf("plan" to (user.subscriptionPlan ?: "")))

        ctx.contentType("application/json").result(
            gson.toJson(mapOf("status" to "cancelled", "expiresAt" to user.subscriptionExpiresAt)),
        )
    }

    /**
     * POST /payment/stripe/cancel-immediate
     *
     * Immediate cancellation with a prorated refund (P2-8). The user explicitly elects to
     * terminate now rather than at period end — EU consumer law ("right of withdrawal") gives
     * users 14 days from purchase to back out and recover funds, so we must support this path.
     *
     * Behaviour:
     *   1. Cancel the Stripe subscription immediately (status → `canceled`).
     *   2. Compute the prorated refund = remaining_days / total_days × paid amount (gross).
     *   3. Issue a real `Refund` against the latest invoice's charge so the money returns to the
     *      same card. If the refund call fails, fall back to crediting `customer.balance` so we
     *      never silently swallow the value — the user can still claim it via support.
     *   4. Deactivate locally.
     *
     * Body: `{ "confirm": true }` — required so a stray POST cannot blow up someone's billing.
     */
    fun cancelSubscriptionImmediate(ctx: Context) {
        val userId = ctx.attribute<String>("userId")
        if (userId == null) {
            ctx.status(401).result("Unauthorized")
            return
        }

        val body = try { gson.fromJson(ctx.body(), Map::class.java) } catch (_: Exception) { null }
        if (body?.get("confirm") != true) {
            ctx.status(400).contentType("application/json").result(
                gson.toJson(mapOf("error" to "confirmation_required",
                    "hint" to "POST { confirm: true } — explicit acknowledgement required")),
            )
            return
        }

        val user = userStore.findById(userId).orElse(null)
        if (user == null || user.subscriptionPlan == null) {
            ctx.status(400).contentType("application/json").result(
                gson.toJson(mapOf("error" to "no_active_subscription")),
            )
            return
        }
        val plan = user.subscriptionPlan!!
        val subId = user.stripeSubscriptionId
        val daysRemaining = calculateDaysRemaining(user)

        logAudit(userId, "cancel_immediate_requested", mapOf("plan" to plan, "daysRemaining" to daysRemaining))

        // Compute the prorated refund using the SAME math as proration on plan switch — keeps
        // the value-preservation invariant intact across cancel/switch boundaries.
        val (refundGrosze, _) = calculateProratedDiscount(
            currentPlan = plan,
            daysRemaining = daysRemaining,
            // Pass a sentinel huge "new plan base" so the cap doesn't truncate; we want the full
            // value of remaining days.
            newPlanBaseGrosze = Long.MAX_VALUE,
            newPlanTotalDays = if (plan == ANNUAL_PLAN) ANNUAL_TOTAL_DAYS else MONTHLY_TOTAL_DAYS,
        )

        var stripeStatus = "skipped"
        var refundIssued = 0L
        var creditedToBalance = 0L

        if (!subId.isNullOrBlank() && keyProvider.hasAnyKey()) {
            val userOpts = keyProvider.requestOptionsFor(userId)
            // Step 1: cancel Stripe subscription immediately.
            try {
                Subscription.retrieve(subId, userOpts).cancel(com.stripe.param.SubscriptionCancelParams.builder().build(), userOpts)
                stripeStatus = "cancelled"
                log.info("Stripe subscription cancelled immediately: userId={} subId={}", userId, subId)
            } catch (e: Exception) {
                log.warn("Stripe cancel(immediate) failed: subId={} err={}", subId, e.message)
                stripeStatus = "cancel_failed: ${e.message}"
            }

            // Step 2: refund prorated amount onto the original card (preferred) OR credit balance.
            if (refundGrosze > 0) {
                val refundedViaCharge = try {
                    val invoices = Invoice.list(
                        InvoiceListParams.builder()
                            .setSubscription(subId)
                            .setLimit(1L)
                            .build(),
                        userOpts,
                    )
                    val chargeId = invoices.data.firstOrNull()?.charge
                    if (!chargeId.isNullOrBlank()) {
                        Refund.create(
                            RefundCreateParams.builder()
                                .setCharge(chargeId)
                                .setAmount(refundGrosze)
                                .putMetadata("userId", userId)
                                .putMetadata("reason", "immediate_cancellation_proration")
                                .putMetadata("plan", plan)
                                .putMetadata("daysRemaining", daysRemaining.toString())
                                .build(),
                            userOpts,
                        )
                        refundGrosze
                    } else 0L
                } catch (e: Exception) {
                    log.warn("Stripe refund failed: userId={} amount={}: {}", userId, refundGrosze, e.message)
                    0L
                }
                refundIssued = refundedViaCharge
                if (refundedViaCharge == 0L) {
                    val customerId = getStripeCustomerId(userId)
                    if (!customerId.isNullOrBlank()) {
                        try {
                            Customer.retrieve(customerId, userOpts).balanceTransactions().create(
                                com.stripe.param.CustomerBalanceTransactionCollectionCreateParams.builder()
                                    .setAmount(-refundGrosze)
                                    .setCurrency("pln")
                                    .setDescription("ProperBackup immediate-cancel refund (fallback to balance)")
                                    .putMetadata("userId", userId)
                                    .putMetadata("reason", "immediate_cancellation_proration_fallback")
                                    .putMetadata("daysRemaining", daysRemaining.toString())
                                    .build(),
                                userOpts,
                            )
                            creditedToBalance = refundGrosze
                        } catch (e: Exception) {
                            log.error("CRITICAL: Refund AND balance credit both failed: userId={} amount={}: {}", userId, refundGrosze, e.message)
                        }
                    }
                }
            }
        }

        userStore.deactivateSubscription(userId)
        logAudit(userId, "cancel_immediate_completed", mapOf(
            "plan" to plan,
            "daysRemaining" to daysRemaining,
            "refundGrosze" to refundIssued,
            "creditedToBalance" to creditedToBalance,
            "stripeStatus" to stripeStatus,
        ))

        ctx.contentType("application/json").result(
            gson.toJson(mapOf(
                "status" to "cancelled_immediate",
                "plan" to plan,
                "daysRemaining" to daysRemaining,
                "refundGrosze" to refundIssued,
                "creditedToBalance" to creditedToBalance,
                "stripeStatus" to stripeStatus,
            )),
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
     *
     * Reactivation only makes sense when the subscription is **still active** in Stripe and merely
     * marked `cancel_at_period_end=true`. If Stripe has already canceled the subscription (status
     * `canceled` / `incomplete_expired`) or our local expiry has passed, we cannot just flip a
     * flag — there's no Stripe subscription to flip the flag on. In that case we report
     * `409 expired_must_recheckout` so the frontend can redirect the user back through
     * /payment/stripe/checkout to create a fresh subscription. See P2-12 in the audit.
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

        // DB-level expiry check first — cheap and authoritative if we believe our records.
        val localExpiry = user.subscriptionExpiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (localExpiry != null && !localExpiry.isAfter(Instant.now())) {
            log.info("Reactivate rejected (locally expired): userId={} expiredAt={}", userId, localExpiry)
            ctx.status(409).contentType("application/json").result(
                gson.toJson(mapOf(
                    "error" to "expired_must_recheckout",
                    "expiredAt" to user.subscriptionExpiresAt,
                    "message" to "Subscription has already expired — please start a new checkout.",
                )),
            )
            return
        }

        val subId = user.stripeSubscriptionId
        if (!subId.isNullOrBlank() && keyProvider.hasAnyKey()) {
            val userOpts = keyProvider.requestOptionsFor(userId)
            try {
                val sub = Subscription.retrieve(subId, userOpts)
                val status = sub.status ?: ""
                val reactivatable = status == "active" || status == "trialing" || status == "past_due"
                if (!reactivatable) {
                    log.info("Reactivate rejected (Stripe status={} not reactivatable): userId={} subId={}", status, userId, subId)
                    ctx.status(409).contentType("application/json").result(
                        gson.toJson(mapOf(
                            "error" to "expired_must_recheckout",
                            "stripeStatus" to status,
                            "message" to "Stripe subscription is $status — start a new checkout to resume.",
                        )),
                    )
                    return
                }

                val params = SubscriptionUpdateParams.builder()
                    .setCancelAtPeriodEnd(false)
                    .build()
                sub.update(params, userOpts)
                log.info("Stripe subscription cancel_at_period_end=false (reactivated): userId={} subId={}", userId, subId)
            } catch (e: com.stripe.exception.StripeException) {
                log.warn("Stripe reactivate failed: subId={} code={} msg={}", subId, e.code, e.message)
                ctx.status(409).contentType("application/json").result(
                    gson.toJson(mapOf(
                        "error" to "expired_must_recheckout",
                        "stripeError" to (e.code ?: ""),
                        "message" to "Stripe could not modify the subscription — start a new checkout.",
                    )),
                )
                return
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
            val userOpts = keyProvider.requestOptionsFor(userId)
            val session = Session.retrieve(sessionId, userOpts)
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
                    // Resolve the canonical Stripe period_end (P2-9) so the verify path is
                    // consistent with the webhook path.
                    val periodEnd = if (!subscriptionId.isNullOrBlank()) fetchStripeCurrentPeriodEnd(subscriptionId, userOpts) else null
                    activateSubscription(userId, plan, periodEnd)
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

    /**
     * Retrieve the canonical `current_period_end` from a Stripe Subscription.
     *
     * Returns null when:
     *   - Stripe API is not configured (blank key)
     *   - The subscription doesn't exist
     *   - The Stripe call fails (network, throttle, etc.)
     *
     * Callers should treat a null result as "use the local fallback duration" — never fail
     * the whole webhook because of a transient Stripe lookup hiccup; the next invoice.paid
     * webhook will heal the drift.
     */
    private fun fetchStripeCurrentPeriodEnd(subscriptionId: String, opts: RequestOptions): Instant? {
        if (!keyProvider.hasAnyKey()) return null
        return try {
            val sub = Subscription.retrieve(subscriptionId, opts)
            sub.currentPeriodEnd?.let { Instant.ofEpochSecond(it) }
        } catch (e: Exception) {
            log.warn("Could not fetch Stripe current_period_end: subId={} err={}", subscriptionId, e.message)
            null
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

    fun getStripeModeForUser(userId: String): String = keyProvider.modeLabel(userId)

    fun getStripeMode(): String = "test"

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
