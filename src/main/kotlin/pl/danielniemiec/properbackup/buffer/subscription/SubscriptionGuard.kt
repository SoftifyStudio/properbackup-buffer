package pl.danielniemiec.properbackup.buffer.subscription

import io.javalin.http.Context
import io.javalin.http.Handler
import pl.danielniemiec.properbackup.buffer.auth.UserStore
import java.time.Instant

/**
 * SubscriptionGuard — single source of truth for access control.
 * Per master-tdd-plan.md §5.2/§5.3.
 *
 * Anti-hostage policy (D-1 decision): canRestore=true in ALL states.
 * Users never lose access to their data — only upload is gated.
 */
class SubscriptionGuard(private val userStore: UserStore) : Handler {

    enum class AccessState {
        NONE,                    // no trial, no subscription
        TRIALING,                // trial active
        ACTIVE,                  // paid subscription active
        ACTIVE_CANCELLED,        // active but cancel_at_period_end=true
        PAST_DUE_GRACE,          // payment failed, Stripe retrying (upload allowed)
        PAST_DUE_SUSPENDED,      // Stripe gave up / unpaid (upload blocked)
        EXPIRED,                 // subscription ended / cancelled past period end
    }

    data class AccessResult(
        val state: AccessState,
        val canUpload: Boolean,
        val canRestore: Boolean,
    )

    companion object {
        fun resolveStatus(user: UserStore.User, now: Instant = Instant.now()): String =
            computeAccess(user, now).state.name.lowercase()

        fun computeAccess(user: UserStore.User, now: Instant = Instant.now()): AccessResult {
            val trialActive = user.trialExpiresAt != null &&
                Instant.parse(user.trialExpiresAt).isAfter(now)
            val subActive = user.subscriptionPlan != null &&
                user.subscriptionExpiresAt != null &&
                Instant.parse(user.subscriptionExpiresAt).isAfter(now)

            val state = when {
                // Past-due states (subscription exists but payment failing)
                subActive && user.subscriptionPaymentStatus == "unpaid" ->
                    AccessState.PAST_DUE_SUSPENDED
                subActive && user.subscriptionPaymentStatus == "past_due" ->
                    AccessState.PAST_DUE_GRACE
                // Active states
                subActive && user.cancelAtPeriodEnd ->
                    AccessState.ACTIVE_CANCELLED
                subActive ->
                    AccessState.ACTIVE
                // Trial
                trialActive ->
                    AccessState.TRIALING
                // Expired / no subscription
                user.everSubscribed || user.subscriptionExpiresAt != null ->
                    AccessState.EXPIRED
                else ->
                    AccessState.NONE
            }

            val canUpload = state in setOf(
                AccessState.TRIALING,
                AccessState.ACTIVE,
                AccessState.ACTIVE_CANCELLED,
                AccessState.PAST_DUE_GRACE,
            )

            // Anti-hostage: canRestore is ALWAYS true (GDPR art. 20, data portability)
            val canRestore = true

            return AccessResult(state, canUpload, canRestore)
        }
    }

    override fun handle(ctx: Context) {
        val userId = ctx.attribute<String>("userId") ?: return
        val isAdmin = java.lang.Boolean.TRUE == ctx.attribute<Boolean>("isSystemAdmin")
        if (isAdmin) return

        val user = userStore.findById(userId).orElse(null) ?: return
        val access = computeAccess(user)

        // Store access info in context for downstream handlers
        ctx.attribute("accessState", access.state.name)
        ctx.attribute("canUpload", access.canUpload)
        ctx.attribute("canRestore", access.canRestore)

        // Block upload operations when access is denied
        if (!access.canUpload) {
            val errorCode = when (access.state) {
                AccessState.PAST_DUE_SUSPENDED -> "SUBSCRIPTION_PAST_DUE"
                AccessState.EXPIRED -> "SUBSCRIPTION_EXPIRED"
                AccessState.NONE -> "NO_ACTIVE_SUBSCRIPTION"
                else -> "SUBSCRIPTION_EXPIRED"
            }
            ctx.status(403).contentType("application/json").result(
                """{"error":"$errorCode","message":"Upload blocked. Please update your subscription.","canRestore":true}"""
            )
        }
    }
}
