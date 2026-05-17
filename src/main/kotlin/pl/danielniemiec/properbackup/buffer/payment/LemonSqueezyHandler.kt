package pl.danielniemiec.properbackup.buffer.payment

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.javalin.http.Context
import org.slf4j.LoggerFactory
import pl.danielniemiec.properbackup.buffer.auth.UserStore
import pl.danielniemiec.properbackup.buffer.subscription.SubscriptionHandler
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class LemonSqueezyHandler(
    private val userStore: UserStore,
    private val webhookSecret: String,
) {
    private val log = LoggerFactory.getLogger(LemonSqueezyHandler::class.java)
    private val gson = Gson()

    private val storeId = System.getenv("LEMONSQUEEZY_STORE_ID") ?: ""
    private val hobbyVariantId = System.getenv("LEMONSQUEEZY_HOBBY_VARIANT_ID") ?: ""
    private val proVariantId = System.getenv("LEMONSQUEEZY_PRO_VARIANT_ID") ?: ""

    fun getCheckoutUrl(ctx: Context) {
        val userId = ctx.attribute<String>("userId")!!
        val user = userStore.findById(userId).orElse(null)
        if (user == null) {
            ctx.status(401).result("User not found")
            return
        }

        val planId = ctx.queryParam("plan") ?: "hobby"
        val variantId = when (planId) {
            "pro" -> proVariantId
            else -> hobbyVariantId
        }

        if (variantId.isBlank()) {
            ctx.status(503).contentType("application/json").result(
                gson.toJson(mapOf("error" to "Payment provider not configured yet. Contact support.")),
            )
            return
        }

        val checkoutUrl = buildString {
            append("https://properbackup.lemonsqueezy.com/checkout/buy/")
            append(variantId)
            append("?checkout[email]=")
            append(java.net.URLEncoder.encode(user.email, "UTF-8"))
            append("&checkout[custom][user_id]=")
            append(java.net.URLEncoder.encode(userId, "UTF-8"))
        }

        ctx.contentType("application/json").result(
            gson.toJson(mapOf("checkoutUrl" to checkoutUrl)),
        )
    }

    fun handleWebhook(ctx: Context) {
        val signature = ctx.header("X-Signature") ?: ctx.header("x-signature")
        val body = ctx.body()

        if (webhookSecret.isNotBlank() && !verifySignature(body, signature)) {
            log.warn("Invalid webhook signature")
            ctx.status(401).result("Invalid signature")
            return
        }

        val json = try {
            JsonParser.parseString(body).asJsonObject
        } catch (e: Exception) {
            ctx.status(400).result("Invalid JSON")
            return
        }

        val meta = json.getAsJsonObject("meta") ?: run {
            ctx.status(400).result("Missing meta")
            return
        }
        val eventName = meta.get("event_name")?.asString ?: ""
        val customData = meta.getAsJsonObject("custom_data")
        val userId = customData?.get("user_id")?.asString

        log.info("Webhook: event={} userId={}", eventName, userId)

        when (eventName) {
            "order_created", "subscription_created" -> {
                if (userId.isNullOrBlank()) {
                    log.warn("No user_id in custom_data — skipping")
                    ctx.status(200).result("ok (no user_id)")
                    return
                }

                val data = json.getAsJsonObject("data") ?: run {
                    ctx.status(200).result("ok (no data)")
                    return
                }
                val attrs = data.getAsJsonObject("attributes") ?: run {
                    ctx.status(200).result("ok (no attributes)")
                    return
                }

                val variantId = attrs.get("first_order_item")?.asJsonObject
                    ?.get("variant_id")?.asString
                    ?: attrs.get("variant_id")?.asString
                    ?: ""

                val plan: String
                val storageLimitBytes: Long
                if (variantId == proVariantId && proVariantId.isNotBlank()) {
                    plan = SubscriptionHandler.PLAN_PRO
                    storageLimitBytes = SubscriptionHandler.PRO_STORAGE_BYTES
                } else {
                    plan = SubscriptionHandler.PLAN_HOBBY
                    storageLimitBytes = SubscriptionHandler.HOBBY_STORAGE_BYTES
                }

                val expiresAt = Instant.now().plusSeconds(365L * 24 * 60 * 60)
                userStore.updateSubscription(userId, plan, expiresAt, storageLimitBytes)
                log.info("Activated {} for {} until {}", plan, userId, expiresAt)
            }

            "subscription_updated" -> {
                if (userId.isNullOrBlank()) {
                    ctx.status(200).result("ok")
                    return
                }
                val data = json.getAsJsonObject("data")
                val attrs = data?.getAsJsonObject("attributes")
                val status = attrs?.get("status")?.asString

                if (status == "cancelled" || status == "expired") {
                    log.info("Subscription {} for {}", status, userId)
                }
            }

            "subscription_cancelled" -> {
                log.info("Subscription cancelled for {}", userId)
            }
        }

        ctx.status(200).result("ok")
    }

    private fun verifySignature(body: String, signature: String?): Boolean {
        if (signature.isNullOrBlank()) return false
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(webhookSecret.toByteArray(), "HmacSHA256"))
            val computed = mac.doFinal(body.toByteArray())
            val hex = computed.joinToString("") { "%02x".format(it) }
            hex.equals(signature, ignoreCase = true)
        } catch (e: Exception) {
            log.error("Signature verification error: {}", e.message)
            false
        }
    }
}
