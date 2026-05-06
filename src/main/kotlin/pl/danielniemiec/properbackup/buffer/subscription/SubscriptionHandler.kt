package pl.danielniemiec.properbackup.buffer.subscription

import com.google.gson.Gson
import io.javalin.http.Context
import pl.danielniemiec.properbackup.buffer.auth.UserStore
import java.time.Duration
import java.time.Instant

class SubscriptionHandler(
    private val userStore: UserStore,
) {
    private val gson = Gson()

    companion object {
        const val PLAN_HOBBY = "hobby"
        const val PLAN_PRO = "pro"
        const val HOBBY_PRICE_PLN = 179
        const val PRO_PRICE_PLN = 399
        const val HOBBY_STORAGE_BYTES = 2199023255552L   // 2 TB
        const val PRO_STORAGE_BYTES = 5497558138880L     // 5 TB
        const val TRIAL_DAYS = 30
        val NOTIFICATION_THRESHOLDS = listOf(14, 7, 2, 1)
    }

    fun getSubscription(ctx: Context) {
        val userId = ctx.attribute<String>("userId")!!
        val user = userStore.findById(userId).orElse(null)
        if (user == null) {
            ctx.status(401).result("User not found")
            return
        }

        val now = Instant.now()
        val trialExpiresAt = user.trialExpiresAt?.let { Instant.parse(it) }
        val subscriptionExpiresAt = user.subscriptionExpiresAt?.let { Instant.parse(it) }

        val status: String
        val daysRemaining: Long
        val active: Boolean

        when {
            user.subscriptionPlan != null && subscriptionExpiresAt != null && subscriptionExpiresAt.isAfter(now) -> {
                status = "active"
                daysRemaining = Duration.between(now, subscriptionExpiresAt).toDays()
                active = true
            }
            user.subscriptionPlan != null && subscriptionExpiresAt != null && !subscriptionExpiresAt.isAfter(now) -> {
                status = "expired"
                daysRemaining = 0
                active = false
            }
            trialExpiresAt != null && trialExpiresAt.isAfter(now) -> {
                status = "trial"
                daysRemaining = Duration.between(now, trialExpiresAt).toDays()
                active = true
            }
            else -> {
                status = "trial_expired"
                daysRemaining = 0
                active = false
            }
        }

        ctx.contentType("application/json").result(
            gson.toJson(
                mapOf(
                    "status" to status,
                    "plan" to (user.subscriptionPlan ?: "trial"),
                    "daysRemaining" to daysRemaining,
                    "active" to active,
                    "trialExpiresAt" to user.trialExpiresAt,
                    "subscriptionExpiresAt" to user.subscriptionExpiresAt,
                    "storageLimitBytes" to user.storageLimitBytes,
                    "storageLimitLabel" to formatStorageLimit(user.storageLimitBytes),
                    "plans" to listOf(
                        mapOf(
                            "id" to PLAN_HOBBY,
                            "name" to "Hobby",
                            "pricePln" to HOBBY_PRICE_PLN,
                            "storageLimitBytes" to HOBBY_STORAGE_BYTES,
                            "storageLimitLabel" to "2 TB",
                        ),
                        mapOf(
                            "id" to PLAN_PRO,
                            "name" to "Pro",
                            "pricePln" to PRO_PRICE_PLN,
                            "storageLimitBytes" to PRO_STORAGE_BYTES,
                            "storageLimitLabel" to "5 TB",
                        ),
                    ),
                ),
            ),
        )
    }

    private fun formatStorageLimit(bytes: Long): String {
        val tb = bytes / (1024.0 * 1024 * 1024 * 1024)
        return if (tb >= 1.0) "${tb.toLong()} TB" else "${(bytes / (1024 * 1024 * 1024))} GB"
    }
}
