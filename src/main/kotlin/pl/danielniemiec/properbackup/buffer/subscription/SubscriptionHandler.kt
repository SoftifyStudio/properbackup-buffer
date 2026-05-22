package pl.danielniemiec.properbackup.buffer.subscription

import com.google.gson.Gson
import io.javalin.http.Context
import pl.danielniemiec.properbackup.buffer.auth.UserStore
import pl.danielniemiec.properbackup.buffer.flush.StorageQuotaGuard
import pl.danielniemiec.properbackup.buffer.payment.StripeHandler
import java.time.Duration
import java.time.Instant

class SubscriptionHandler(
    private val userStore: UserStore,
    private val storageQuotaGuard: StorageQuotaGuard? = null,
    private val stripeHandler: StripeHandler? = null,
) {
    private val gson = Gson()

    companion object {
        const val PLAN_HOBBY = "hobby"
        const val PLAN_PRO = "pro"
        const val HOBBY_PRICE_USD = 49
        const val PRO_PRICE_USD = 129
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
            trialExpiresAt != null && !trialExpiresAt.isAfter(now) -> {
                status = "trial_expired"
                daysRemaining = 0
                active = false
            }
            else -> {
                status = "pending_payment"
                daysRemaining = 0
                active = false
            }
        }

        val quotaStatus = storageQuotaGuard?.checkQuota(userId)

        val monthlyPriceGrosze = StripeHandler.MONTHLY_PRICE_PLN
        val annualPriceGrosze = StripeHandler.ANNUAL_PRICE_PLN

        val monthlyProratedGrosze = if (daysRemaining > 0) {
            minOf(monthlyPriceGrosze, monthlyPriceGrosze * daysRemaining / 31)
        } else 0L

        val annualProratedGrosze = if (daysRemaining > 0) {
            minOf(annualPriceGrosze, annualPriceGrosze * daysRemaining / 365)
        } else 0L

        ctx.contentType("application/json").result(
            gson.toJson(
                mapOf(
                    "status" to status,
                    "plan" to (user.subscriptionPlan ?: "trial"),
                    "daysRemaining" to daysRemaining,
                    "active" to active,
                    "cancelAtPeriodEnd" to user.subscriptionCancelAtPeriodEnd,
                    "trialExpiresAt" to user.trialExpiresAt,
                    "subscriptionExpiresAt" to user.subscriptionExpiresAt,
                    "storageLimitBytes" to user.storageLimitBytes,
                    "storageLimitLabel" to formatStorageLimit(user.storageLimitBytes),
                    "storageUsedBytes" to (quotaStatus?.usedBytes ?: 0L),
                    "storageUsedLabel" to formatStorageLimit(quotaStatus?.usedBytes ?: 0L),
                    "storageUsedPercent" to (quotaStatus?.usedPercent ?: 0.0),
                    "storageExceeded" to (quotaStatus?.exceeded ?: false),
                    "vatRate" to (stripeHandler?.getVatRate() ?: 0.23),
                    "stripeMode" to (stripeHandler?.getStripeMode() ?: "test"),
                    "pricing" to mapOf(
                        "monthly" to mapOf(
                            "basePriceGrosze" to monthlyPriceGrosze,
                            "proratedDiscountGrosze" to monthlyProratedGrosze,
                            "totalDays" to 31,
                        ),
                        "annual" to mapOf(
                            "basePriceGrosze" to annualPriceGrosze,
                            "proratedDiscountGrosze" to annualProratedGrosze,
                            "totalDays" to 365,
                        ),
                    ),
                    "plans" to listOf(
                        mapOf(
                            "id" to PLAN_HOBBY,
                            "name" to "Hobby",
                            "priceUsd" to HOBBY_PRICE_USD,
                            "storageLimitBytes" to HOBBY_STORAGE_BYTES,
                            "storageLimitLabel" to "2 TB",
                        ),
                        mapOf(
                            "id" to PLAN_PRO,
                            "name" to "Pro",
                            "priceUsd" to PRO_PRICE_USD,
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
