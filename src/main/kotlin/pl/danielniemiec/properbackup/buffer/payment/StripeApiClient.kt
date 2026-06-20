package pl.danielniemiec.properbackup.buffer.payment

import com.stripe.Stripe
import com.stripe.model.checkout.Session as StripeSession
import com.stripe.model.Subscription as StripeSubscription
import com.stripe.param.checkout.SessionCreateParams
import com.stripe.param.SubscriptionUpdateParams
import com.stripe.net.RequestOptions
import pl.danielniemiec.properbackup.buffer.db.Database

/**
 * Stripe API client for subscription management.
 * Per master-tdd-plan.md Groups B1, C, D.
 *
 * Handles: checkout session creation, cancel, reactivate, plan change.
 * Uses idempotency keys for all mutating operations.
 */
class StripeApiClient(
    private val db: Database,
    private val stripeSecretKey: String,
    private val successUrl: String,
    private val cancelUrl: String,
) {
    init {
        Stripe.apiKey = stripeSecretKey
    }

    data class CheckoutResult(val url: String, val sessionId: String)
    data class PriceConfig(val priceId: String, val plan: String)

    fun createCheckoutSession(
        userId: String,
        email: String,
        plan: String,
        trialDays: Int?,
        customerId: String?,
    ): CheckoutResult {
        val priceId = getPriceId(plan)
        val idempotencyKey = buildIdempotencyKey(userId, plan)

        val builder = SessionCreateParams.builder()
            .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
            .setSuccessUrl("$successUrl?session_id={CHECKOUT_SESSION_ID}")
            .setCancelUrl(cancelUrl)
            .setClientReferenceId(userId)
            .setCustomerEmail(if (customerId == null) email else null)
            .addLineItem(
                SessionCreateParams.LineItem.builder()
                    .setPrice(priceId)
                    .setQuantity(1L)
                    .build()
            )
            .putMetadata("userId", userId)
            .putMetadata("plan", plan)

        if (customerId != null) {
            builder.setCustomer(customerId)
        }

        if (trialDays != null && trialDays > 0) {
            builder.setSubscriptionData(
                SessionCreateParams.SubscriptionData.builder()
                    .setTrialPeriodDays(trialDays.toLong())
                    .build()
            )
        }

        val options = RequestOptions.builder()
            .setIdempotencyKey(idempotencyKey)
            .build()

        val session = StripeSession.create(builder.build(), options)
        return CheckoutResult(session.url, session.id)
    }

    fun cancelAtPeriodEnd(subscriptionId: String, userId: String) {
        val idempotencyKey = "cancel_${userId}_${timeBucket()}"
        val params = SubscriptionUpdateParams.builder()
            .setCancelAtPeriodEnd(true)
            .build()
        val options = RequestOptions.builder()
            .setIdempotencyKey(idempotencyKey)
            .build()
        StripeSubscription.retrieve(subscriptionId).update(params, options)
    }

    fun reactivate(subscriptionId: String, userId: String) {
        val idempotencyKey = "reactivate_${userId}_${timeBucket()}"
        val params = SubscriptionUpdateParams.builder()
            .setCancelAtPeriodEnd(false)
            .build()
        val options = RequestOptions.builder()
            .setIdempotencyKey(idempotencyKey)
            .build()
        StripeSubscription.retrieve(subscriptionId).update(params, options)
    }

    fun changePlan(
        subscriptionId: String,
        userId: String,
        newPlan: String,
        preserveTrialEnd: Boolean,
    ) {
        val priceId = getPriceId(newPlan)
        val idempotencyKey = "change_plan_${userId}_${newPlan}_${timeBucket()}"

        val subscription = StripeSubscription.retrieve(subscriptionId)
        val itemId = subscription.items.data.firstOrNull()?.id
            ?: throw IllegalStateException("No subscription items found")

        val builder = SubscriptionUpdateParams.builder()
            .addItem(
                SubscriptionUpdateParams.Item.builder()
                    .setId(itemId)
                    .setPrice(priceId)
                    .build()
            )

        if (preserveTrialEnd) {
            builder.setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.NONE)
        } else {
            builder.setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.CREATE_PRORATIONS)
        }

        val options = RequestOptions.builder()
            .setIdempotencyKey(idempotencyKey)
            .build()

        subscription.update(builder.build(), options)
    }

    private fun getPriceId(plan: String): String {
        return db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT stripe_price_id FROM stripe_price_config WHERE plan_key = ? LIMIT 1"
            ).use { stmt ->
                stmt.setString(1, plan)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString("stripe_price_id")
                    else throw IllegalArgumentException("No price configured for plan: $plan")
                }
            }
        }
    }

    fun buildIdempotencyKey(userId: String, plan: String): String =
        "checkout_${userId}_${plan}_${timeBucket()}"

    private fun timeBucket(): Long = System.currentTimeMillis() / 60_000 // 60s window
}
