package pl.danielniemiec.properbackup.buffer.notifications

import pl.danielniemiec.properbackup.buffer.db.Database
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.concurrent.thread

/**
 * Schedules and deduplicates notification delivery.
 * Prevents alert spam by tracking last-sent timestamps per (userId, alertType, subjectKey).
 * Minimum interval between same alerts: 1 hour.
 */
class NotificationScheduler(
    private val db: Database,
    private val settingsStore: NotificationSettingsStore,
    private val emailNotifier: EmailNotifier,
    private val cooldownMinutes: Long = 60,
) {

    fun start() {
        thread(name = "notification-scheduler", isDaemon = true) {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(5 * 60_000) // process pending notifications every 5 min
                    processPending()
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    System.err.println("[NotificationScheduler] error: ${e.message}")
                }
            }
        }
    }

    fun scheduleAgentOfflineAlert(serverId: String, serverName: String, userId: String) {
        enqueue(userId, "agent_offline", serverId, mapOf("serverName" to serverName, "serverId" to serverId))
    }

    fun scheduleStorageWarningAlert(userId: String, usedPercent: Double, usedLabel: String, limitLabel: String) {
        enqueue(userId, "storage_warning", "storage", mapOf("usedPercent" to usedPercent.toString(), "usedLabel" to usedLabel, "limitLabel" to limitLabel))
    }

    fun scheduleRetentionWarningAlert(userId: String, daysLeft: Int, deadlineDate: String) {
        enqueue(userId, "retention_warning", "retention_$daysLeft", mapOf("daysLeft" to daysLeft.toString(), "deadlineDate" to deadlineDate))
    }

    private fun enqueue(userId: String, alertType: String, subjectKey: String, payload: Map<String, String>) {
        val now = Instant.now()
        if (wasSentRecently(userId, alertType, subjectKey)) {
            return
        }
        try {
            db.getConnection().use { conn ->
                conn.prepareStatement(
                    """INSERT INTO notification_queue (user_id, alert_type, subject_key, payload, created_at, status)
                       VALUES (?, ?, ?, ?::jsonb, ?, 'PENDING')
                       ON CONFLICT DO NOTHING""",
                ).use { ps ->
                    ps.setString(1, userId)
                    ps.setString(2, alertType)
                    ps.setString(3, subjectKey)
                    ps.setString(4, com.google.gson.Gson().toJson(payload))
                    ps.setTimestamp(5, Timestamp.from(now))
                    ps.executeUpdate()
                }
            }
        } catch (e: Exception) {
            System.err.println("[NotificationScheduler] enqueue error: ${e.message}")
        }
    }

    private fun wasSentRecently(userId: String, alertType: String, subjectKey: String): Boolean {
        return try {
            db.getConnection().use { conn ->
                conn.prepareStatement(
                    "SELECT COUNT(*) FROM notification_log WHERE user_id = ? AND alert_type = ? AND subject_key = ? AND sent_at > ?",
                ).use { ps ->
                    ps.setString(1, userId)
                    ps.setString(2, alertType)
                    ps.setString(3, subjectKey)
                    ps.setTimestamp(4, Timestamp.from(Instant.now().minus(cooldownMinutes, ChronoUnit.MINUTES)))
                    ps.executeQuery().use { rs ->
                        rs.next() && rs.getLong(1) > 0
                    }
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    fun processPending() {
        try {
            db.getConnection().use { conn ->
                val pending = conn.prepareStatement(
                    "SELECT id, user_id, alert_type, subject_key, payload FROM notification_queue WHERE status = 'PENDING' ORDER BY created_at LIMIT 50",
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        val items = ArrayList<PendingNotification>()
                        while (rs.next()) {
                            items.add(
                                PendingNotification(
                                    id = rs.getLong("id"),
                                    userId = rs.getString("user_id"),
                                    alertType = rs.getString("alert_type"),
                                    subjectKey = rs.getString("subject_key"),
                                    payload = rs.getString("payload") ?: "{}",
                                ),
                            )
                        }
                        items
                    }
                }

                for (item in pending) {
                    val settings = settingsStore.get(item.userId)
                    if (settings == null || !settings.enabled || settings.email.isBlank()) {
                        markProcessed(conn, item.id, "SKIPPED")
                        continue
                    }

                    val shouldSend = when (item.alertType) {
                        "agent_offline" -> settings.onAgentOffline
                        "storage_warning" -> settings.onStorageWarning
                        "retention_warning" -> settings.onSubscriptionExpiry
                        else -> false
                    }

                    if (!shouldSend) {
                        markProcessed(conn, item.id, "SKIPPED")
                        continue
                    }

                    @Suppress("UNCHECKED_CAST")
                    val payload = try {
                        com.google.gson.Gson().fromJson(item.payload, Map::class.java) as Map<String, String>
                    } catch (_: Exception) {
                        emptyMap<String, String>()
                    }

                    val sent: Boolean = when (item.alertType) {
                        "agent_offline" -> emailNotifier.sendAgentOfflineAlert(
                            settings.email,
                            payload["serverName"] ?: "Unknown",
                            payload["serverId"] ?: "",
                        )
                        "storage_warning" -> emailNotifier.sendStorageWarningAlert(
                            settings.email,
                            payload["usedPercent"]?.toDoubleOrNull() ?: 0.0,
                            payload["usedLabel"] ?: "",
                            payload["limitLabel"] ?: "",
                        )
                        "retention_warning" -> emailNotifier.sendRetentionWarningAlert(
                            settings.email,
                            payload["daysLeft"]?.toIntOrNull() ?: 0,
                            payload["deadlineDate"] ?: "",
                        )
                        else -> false
                    }

                    markProcessed(conn, item.id, if (sent) "SENT" else "FAILED")
                    if (sent) {
                        logSent(conn, item.userId, item.alertType, item.subjectKey)
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[NotificationScheduler] processPending error: ${e.message}")
        }
    }

    private fun markProcessed(conn: java.sql.Connection, id: Long, status: String) {
        conn.prepareStatement("UPDATE notification_queue SET status = ?, processed_at = ? WHERE id = ?").use { ps ->
            ps.setString(1, status)
            ps.setTimestamp(2, Timestamp.from(Instant.now()))
            ps.setLong(3, id)
            ps.executeUpdate()
        }
    }

    private fun logSent(conn: java.sql.Connection, userId: String, alertType: String, subjectKey: String) {
        conn.prepareStatement(
            "INSERT INTO notification_log (user_id, alert_type, subject_key, sent_at) VALUES (?, ?, ?, ?)",
        ).use { ps ->
            ps.setString(1, userId)
            ps.setString(2, alertType)
            ps.setString(3, subjectKey)
            ps.setTimestamp(4, Timestamp.from(Instant.now()))
            ps.executeUpdate()
        }
    }

    private data class PendingNotification(
        val id: Long,
        val userId: String,
        val alertType: String,
        val subjectKey: String,
        val payload: String,
    )
}
