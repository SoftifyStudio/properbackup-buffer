package pl.danielniemiec.properbackup.buffer.subscription

import io.javalin.http.Context
import io.javalin.http.Handler
import pl.danielniemiec.properbackup.buffer.auth.UserStore
import java.time.Instant

class SubscriptionGuard(private val userStore: UserStore) : Handler {
    override fun handle(ctx: Context) {
        val userId = ctx.attribute<String>("userId") ?: return
        val isAdmin = java.lang.Boolean.TRUE == ctx.attribute<Boolean>("isSystemAdmin")
        if (isAdmin) return

        val user = userStore.findById(userId).orElse(null) ?: return

        val now = Instant.now()
        val subscriptionActive = user.subscriptionPlan != null &&
            user.subscriptionExpiresAt != null &&
            Instant.parse(user.subscriptionExpiresAt).isAfter(now)
        if (subscriptionActive) return

        val trialActive = user.trialExpiresAt != null &&
            Instant.parse(user.trialExpiresAt).isAfter(now)
        if (trialActive) return

        ctx.status(402).contentType("application/json").result(
            """{"error":"subscription_expired","message":"Your trial or subscription has expired. Please upgrade to continue."}""",
        )
    }
}
