package pl.danielniemiec.properbackup.buffer.notifications

import pl.danielniemiec.properbackup.buffer.db.Database
import java.sql.Timestamp
import java.time.Instant

data class NotificationSettings(
    val userId: String,
    val enabled: Boolean,
    val email: String,
    val onAgentOffline: Boolean,
    val onStorageWarning: Boolean,
    val onSubscriptionExpiry: Boolean,
)

class NotificationSettingsStore(private val db: Database) {

    fun get(userId: String): NotificationSettings? {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT user_id, enabled, email, on_agent_offline, on_storage_warning, on_subscription_expiry FROM notification_settings WHERE user_id = ?",
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return NotificationSettings(
                        userId = rs.getString("user_id"),
                        enabled = rs.getBoolean("enabled"),
                        email = rs.getString("email") ?: "",
                        onAgentOffline = rs.getBoolean("on_agent_offline"),
                        onStorageWarning = rs.getBoolean("on_storage_warning"),
                        onSubscriptionExpiry = rs.getBoolean("on_subscription_expiry"),
                    )
                }
            }
        }
    }

    fun upsert(settings: NotificationSettings) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO notification_settings (user_id, enabled, email, on_agent_offline, on_storage_warning, on_subscription_expiry, updated_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT (user_id) DO UPDATE SET
                     enabled = EXCLUDED.enabled,
                     email = EXCLUDED.email,
                     on_agent_offline = EXCLUDED.on_agent_offline,
                     on_storage_warning = EXCLUDED.on_storage_warning,
                     on_subscription_expiry = EXCLUDED.on_subscription_expiry,
                     updated_at = EXCLUDED.updated_at""",
            ).use { ps ->
                ps.setString(1, settings.userId)
                ps.setBoolean(2, settings.enabled)
                ps.setString(3, settings.email)
                ps.setBoolean(4, settings.onAgentOffline)
                ps.setBoolean(5, settings.onStorageWarning)
                ps.setBoolean(6, settings.onSubscriptionExpiry)
                ps.setTimestamp(7, Timestamp.from(Instant.now()))
                ps.executeUpdate()
            }
        }
    }

    fun listEnabledForAlert(alertType: String): List<NotificationSettings> {
        val column = when (alertType) {
            "agent_offline" -> "on_agent_offline"
            "storage_warning" -> "on_storage_warning"
            "subscription_expiry" -> "on_subscription_expiry"
            else -> return emptyList()
        }
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT user_id, enabled, email, on_agent_offline, on_storage_warning, on_subscription_expiry FROM notification_settings WHERE enabled = TRUE AND $column = TRUE AND email != ''",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val out = ArrayList<NotificationSettings>()
                    while (rs.next()) {
                        out.add(
                            NotificationSettings(
                                userId = rs.getString("user_id"),
                                enabled = rs.getBoolean("enabled"),
                                email = rs.getString("email") ?: "",
                                onAgentOffline = rs.getBoolean("on_agent_offline"),
                                onStorageWarning = rs.getBoolean("on_storage_warning"),
                                onSubscriptionExpiry = rs.getBoolean("on_subscription_expiry"),
                            ),
                        )
                    }
                    return out
                }
            }
        }
    }
}
