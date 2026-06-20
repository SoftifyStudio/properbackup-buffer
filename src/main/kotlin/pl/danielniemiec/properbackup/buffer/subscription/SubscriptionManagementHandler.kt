package pl.danielniemiec.properbackup.buffer.subscription

import com.google.gson.Gson
import io.javalin.http.Context
import pl.danielniemiec.properbackup.buffer.auth.UserStore
import pl.danielniemiec.properbackup.buffer.payment.StripeApiClient
import pl.danielniemiec.properbackup.buffer.payment.SubscriptionAuditLog
import java.time.Instant

/**
 * HTTP handlers for subscription management per master-tdd-plan.md Groups B1, C, D.
 *
 * POST /account/subscription/checkout  — [TDD-B1] Create Stripe Checkout session
 * POST /account/subscription/cancel    — [TDD-C1] Cancel at period end
 * POST /account/subscription/reactivate — [TDD-C2] Undo cancel
 * POST /account/subscription/change-plan — [TDD-D1] Monthly <-> Annual
 */
class SubscriptionManagementHandler(
    private val userStore: UserStore,
    private val stripeApi: StripeApiClient?,
    private val auditLog: SubscriptionAuditLog,
) {
    private val gson = Gson()

    companion object {
        const val TRIAL_DAYS = 30
    }

    fun checkout(ctx: Context) {
        val userId = ctx.attribute<String>("userId")!!
        val user = userStore.findById(userId).orElse(null) ?: run {
            ctx.status(401).result("User not found")
            return
        }

        val body = gson.fromJson(ctx.body(), Map::class.java)
        val plan = (body?.get("plan") as? String) ?: "monthly"

        if (stripeApi == null) {
            ctx.status(503).contentType("application/json")
                .result("""{"error":"STRIPE_NOT_CONFIGURED","message":"Stripe API key not set"}""")
            return
        }

        // Determine trial eligibility
        val trialDays: Int? = if (!user.everSubscribed) TRIAL_DAYS else null

        try {
            val result = stripeApi.createCheckoutSession(
                userId = userId,
                email = user.email,
                plan = plan,
                trialDays = trialDays,
                customerId = null, // Let Stripe create customer
            )

            auditLog.append(userId, "checkout_session_created", "plan=$plan, trial=${trialDays ?: 0}")
            ctx.contentType("application/json").result(gson.toJson(mapOf(
                "url" to result.url,
                "sessionId" to result.sessionId,
            )))
        } catch (e: Exception) {
            println("[SubscriptionManagement] Checkout error: ${e.message}")
            ctx.status(500).contentType("application/json")
                .result("""{"error":"CHECKOUT_FAILED","message":"${e.message?.replace("\"", "'")}"}""")
        }
    }

    fun cancel(ctx: Context) {
        val userId = ctx.attribute<String>("userId")!!
        val user = userStore.findById(userId).orElse(null) ?: run {
            ctx.status(401).result("User not found")
            return
        }

        val subscriptionId = findStripeSubscriptionId(userId)
        if (subscriptionId == null) {
            ctx.status(404).contentType("application/json")
                .result("""{"error":"NO_SUBSCRIPTION","message":"No active subscription to cancel"}""")
            return
        }

        if (stripeApi == null) {
            ctx.status(503).result("Stripe not configured")
            return
        }

        try {
            stripeApi.cancelAtPeriodEnd(subscriptionId, userId)
            auditLog.append(userId, "cancel_requested", "subscription=$subscriptionId")
            ctx.contentType("application/json").result("""{"status":"cancelled_at_period_end"}""")
        } catch (e: Exception) {
            println("[SubscriptionManagement] Cancel error: ${e.message}")
            ctx.status(500).contentType("application/json")
                .result("""{"error":"CANCEL_FAILED","message":"${e.message?.replace("\"", "'")}"}""")
        }
    }

    fun reactivate(ctx: Context) {
        val userId = ctx.attribute<String>("userId")!!
        val user = userStore.findById(userId).orElse(null) ?: run {
            ctx.status(401).result("User not found")
            return
        }

        // Check if subscription is locally expired
        if (user.subscriptionExpiresAt != null) {
            val expiresAt = Instant.parse(user.subscriptionExpiresAt)
            if (expiresAt.isBefore(Instant.now())) {
                ctx.status(409).contentType("application/json")
                    .result("""{"error":"EXPIRED_MUST_RECHECKOUT","message":"Subscription expired. Please create a new checkout session."}""")
                return
            }
        }

        val subscriptionId = findStripeSubscriptionId(userId)
        if (subscriptionId == null) {
            ctx.status(404).contentType("application/json")
                .result("""{"error":"NO_SUBSCRIPTION","message":"No subscription to reactivate"}""")
            return
        }

        if (stripeApi == null) {
            ctx.status(503).result("Stripe not configured")
            return
        }

        try {
            stripeApi.reactivate(subscriptionId, userId)
            auditLog.append(userId, "reactivate_requested", "subscription=$subscriptionId")
            ctx.contentType("application/json").result("""{"status":"reactivated"}""")
        } catch (e: Exception) {
            println("[SubscriptionManagement] Reactivate error: ${e.message}")
            ctx.status(500).contentType("application/json")
                .result("""{"error":"REACTIVATE_FAILED","message":"${e.message?.replace("\"", "'")}"}""")
        }
    }

    fun changePlan(ctx: Context) {
        val userId = ctx.attribute<String>("userId")!!
        val user = userStore.findById(userId).orElse(null) ?: run {
            ctx.status(401).result("User not found")
            return
        }

        val body = gson.fromJson(ctx.body(), Map::class.java)
        val newPlan = (body?.get("plan") as? String) ?: run {
            ctx.status(400).result("Missing 'plan' field")
            return
        }

        if (user.subscriptionPlan == newPlan) {
            ctx.status(409).contentType("application/json")
                .result("""{"error":"SAME_PLAN","message":"Already on $newPlan plan"}""")
            return
        }

        val subscriptionId = findStripeSubscriptionId(userId)
        if (subscriptionId == null) {
            ctx.status(404).contentType("application/json")
                .result("""{"error":"NO_SUBSCRIPTION","message":"No active subscription to change"}""")
            return
        }

        if (stripeApi == null) {
            ctx.status(503).result("Stripe not configured")
            return
        }

        // Preserve trial if user is currently in trial
        val inTrial = user.trialExpiresAt != null &&
            Instant.parse(user.trialExpiresAt).isAfter(Instant.now())

        try {
            stripeApi.changePlan(subscriptionId, userId, newPlan, preserveTrialEnd = inTrial)
            auditLog.append(userId, "plan_change_to_$newPlan",
                "from=${user.subscriptionPlan}, inTrial=$inTrial")
            ctx.contentType("application/json").result(gson.toJson(mapOf(
                "status" to "plan_changed",
                "newPlan" to newPlan,
                "prorated" to !inTrial,
            )))
        } catch (e: Exception) {
            println("[SubscriptionManagement] Plan change error: ${e.message}")
            ctx.status(500).contentType("application/json")
                .result("""{"error":"PLAN_CHANGE_FAILED","message":"${e.message?.replace("\"", "'")}"}""")
        }
    }

    private fun findStripeSubscriptionId(userId: String): String? {
        val user = userStore.findById(userId).orElse(null) ?: return null
        return userStore.getStripeSubscriptionId(userId)
    }
}
