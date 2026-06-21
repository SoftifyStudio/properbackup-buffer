package pl.danielniemiec.properbackup.buffer.monitoring

import pl.danielniemiec.properbackup.buffer.db.Database
import pl.danielniemiec.properbackup.buffer.notifications.EmailNotifier
import pl.danielniemiec.properbackup.buffer.server.ServerStore
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import kotlin.concurrent.thread

/**
 * Monitors agent heartbeats and detects OFFLINE/WARNING servers.
 * Runs a background thread that checks every 60s.
 *
 * Thresholds:
 *   - last_seen >5 min  → WARNING
 *   - last_seen >30 min → OFFLINE (triggers email if enabled)
 */
class AgentHealthMonitor(
    private val db: Database,
    private val serverStore: ServerStore,
    private val emailNotifier: EmailNotifier?,
) {
    companion object {
        val WARNING_THRESHOLD: Duration = Duration.ofMinutes(5)
        val OFFLINE_THRESHOLD: Duration = Duration.ofMinutes(30)
        private const val CHECK_INTERVAL_MS = 60_000L
    }

    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = thread(name = "agent-health-monitor", isDaemon = true) {
            while (running && !Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(CHECK_INTERVAL_MS)
                    checkAll()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    System.err.println("[AgentHealthMonitor] Error: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
    }

    fun checkAll() {
        val servers = serverStore.listAll()
        val now = Instant.now()
        for (server in servers) {
            val lastSeen = try { Instant.parse(server.lastSeen) } catch (_: Exception) { continue }
            val elapsed = Duration.between(lastSeen, now)
            val status = when {
                elapsed > OFFLINE_THRESHOLD -> "OFFLINE"
                elapsed > WARNING_THRESHOLD -> "WARNING"
                else -> "ONLINE"
            }
            upsertHealthStatus(server.id, status, lastSeen, now)
            if (status == "OFFLINE") {
                handleOfflineServer(server.id, server.name, server.userId, lastSeen)
            }
        }
    }

    fun getHealthStatus(serverId: String): Map<String, Any?>? {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT server_id, status, last_seen_at, checked_at FROM agent_health_status WHERE server_id = ?",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return linkedMapOf(
                        "serverId" to rs.getString("server_id"),
                        "status" to rs.getString("status"),
                        "lastSeenAt" to rs.getTimestamp("last_seen_at").toInstant().toString(),
                        "checkedAt" to rs.getTimestamp("checked_at").toInstant().toString(),
                    )
                }
            }
        }
    }

    fun getHealthStatusForUser(userId: String): List<Map<String, Any?>> {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """SELECT h.server_id, h.status, h.last_seen_at, h.checked_at, s.name AS server_name
                   FROM agent_health_status h
                   JOIN servers s ON s.id = h.server_id
                   WHERE s.user_id = ?
                   ORDER BY h.status DESC, s.name""",
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    val out = ArrayList<Map<String, Any?>>()
                    while (rs.next()) {
                        out.add(
                            linkedMapOf(
                                "serverId" to rs.getString("server_id"),
                                "serverName" to rs.getString("server_name"),
                                "status" to rs.getString("status"),
                                "lastSeenAt" to rs.getTimestamp("last_seen_at").toInstant().toString(),
                                "checkedAt" to rs.getTimestamp("checked_at").toInstant().toString(),
                            ),
                        )
                    }
                    return out
                }
            }
        }
    }

    private fun upsertHealthStatus(serverId: String, status: String, lastSeen: Instant, checkedAt: Instant) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO agent_health_status (server_id, status, last_seen_at, checked_at)
                   VALUES (?, ?, ?, ?)
                   ON CONFLICT (server_id) DO UPDATE SET
                     status = EXCLUDED.status,
                     last_seen_at = EXCLUDED.last_seen_at,
                     checked_at = EXCLUDED.checked_at""",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.setString(2, status)
                ps.setTimestamp(3, Timestamp.from(lastSeen))
                ps.setTimestamp(4, Timestamp.from(checkedAt))
                ps.executeUpdate()
            }
        }
    }

    private fun handleOfflineServer(serverId: String, serverName: String, userId: String, lastSeen: Instant) {
        if (emailNotifier == null) return
        if (wasAlertSentRecently(serverId)) return
        val settings = getNotificationSettings(userId) ?: return
        if (!settings.emailEnabled) return
        val email = settings.emailAddress ?: return

        emailNotifier.sendAgentOfflineAlert(email, serverName, serverId, lastSeen)
        recordAlertSent(serverId, "agent_offline")
    }

    private fun wasAlertSentRecently(serverId: String): Boolean {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT 1 FROM alert_history WHERE server_id = ? AND alert_type = 'agent_offline' AND sent_at > NOW() - INTERVAL '10 minutes' LIMIT 1",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.executeQuery().use { rs -> return rs.next() }
            }
        }
    }

    private fun recordAlertSent(serverId: String, alertType: String) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO alert_history (server_id, alert_type, sent_at) VALUES (?, ?, NOW())",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.setString(2, alertType)
                ps.executeUpdate()
            }
        }
    }

    private fun getNotificationSettings(userId: String): NotificationSettings? {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT user_id, email_enabled, email_address, sms_enabled, sms_number FROM notification_settings WHERE user_id = ?",
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return NotificationSettings(
                        userId = rs.getString("user_id"),
                        emailEnabled = rs.getBoolean("email_enabled"),
                        emailAddress = rs.getString("email_address"),
                        smsEnabled = rs.getBoolean("sms_enabled"),
                        smsNumber = rs.getString("sms_number"),
                    )
                }
            }
        }
    }
}

data class NotificationSettings(
    val userId: String,
    val emailEnabled: Boolean,
    val emailAddress: String?,
    val smsEnabled: Boolean,
    val smsNumber: String?,
)
