package pl.danielniemiec.properbackup.buffer.notifications

import com.google.gson.Gson
import io.javalin.http.Context

class NotificationSettingsHandler(
    private val settingsStore: NotificationSettingsStore,
) {
    private val gson = Gson()

    fun getSettings(ctx: Context) {
        val userId = ctx.attribute<String>("userId")
        if (userId == null) { ctx.status(401).json(mapOf("error" to "Unauthorized")); return }
        val settings = settingsStore.get(userId)
        if (settings != null) {
            ctx.json(
                mapOf(
                    "enabled" to settings.enabled,
                    "email" to settings.email,
                    "onAgentOffline" to settings.onAgentOffline,
                    "onStorageWarning" to settings.onStorageWarning,
                    "onSubscriptionExpiry" to settings.onSubscriptionExpiry,
                ),
            )
        } else {
            ctx.json(
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
        if (userId == null) { ctx.status(401).json(mapOf("error" to "Unauthorized")); return }

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
            ctx.status(400).json(mapOf("error" to "Invalid JSON")); return
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
        ctx.json(mapOf("ok" to true))
    }
}
