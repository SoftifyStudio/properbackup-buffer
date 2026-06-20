package pl.danielniemiec.properbackup.buffer.notifications

import com.google.gson.Gson
import io.javalin.http.Context

class NotificationSettingsHandler(
    private val settingsStore: NotificationSettingsStore,
) {
    private val gson = Gson()

    private fun jsonResponse(ctx: Context, data: Any) {
        ctx.contentType("application/json").result(gson.toJson(data))
    }

    fun getSettings(ctx: Context) {
        val userId = ctx.attribute<String>("userId")
        if (userId == null) { ctx.status(401); jsonResponse(ctx, mapOf("error" to "Unauthorized")); return }
        val settings = settingsStore.get(userId)
        if (settings != null) {
            jsonResponse(
                ctx,
                mapOf(
                    "enabled" to settings.enabled,
                    "email" to settings.email,
                    "onAgentOffline" to settings.onAgentOffline,
                    "onStorageWarning" to settings.onStorageWarning,
                    "onSubscriptionExpiry" to settings.onSubscriptionExpiry,
                ),
            )
        } else {
            jsonResponse(
                ctx,
                mapOf(
                    "enabled" to false,
                    "email" to "",
                    "onAgentOffline" to true,
                    "onStorageWarning" to true,
                    "onSubscriptionExpiry" to true,
                ),
            )
        }
    }

    fun saveSettings(ctx: Context) {
        val userId = ctx.attribute<String>("userId")
        if (userId == null) { ctx.status(401); jsonResponse(ctx, mapOf("error" to "Unauthorized")); return }

        data class Req(
            val enabled: Boolean = false,
            val email: String = "",
            val onAgentOffline: Boolean = true,
            val onStorageWarning: Boolean = true,
            val onSubscriptionExpiry: Boolean = true,
        )

        val req = try {
            gson.fromJson(ctx.body(), Req::class.java)
        } catch (e: Exception) {
            ctx.status(400); jsonResponse(ctx, mapOf("error" to "Invalid JSON")); return
        }

        settingsStore.upsert(
            NotificationSettings(
                userId = userId,
                enabled = req.enabled,
                email = req.email.trim(),
                onAgentOffline = req.onAgentOffline,
                onStorageWarning = req.onStorageWarning,
                onSubscriptionExpiry = req.onSubscriptionExpiry,
            ),
        )
        jsonResponse(ctx, mapOf("ok" to true))
    }
}
