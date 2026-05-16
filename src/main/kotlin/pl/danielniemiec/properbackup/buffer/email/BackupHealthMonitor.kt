package pl.danielniemiec.properbackup.buffer.email

import pl.danielniemiec.properbackup.buffer.db.Database
import java.time.Duration
import java.time.Instant
import kotlin.concurrent.thread

/**
 * Periodically checks for servers whose last backup is older than a threshold
 * and sends an email alert to the server owner.
 */
class BackupHealthMonitor(
    private val db: Database,
    private val emailService: EmailService,
    private val staleHoursThreshold: Long = 24,
) {
    fun start() {
        thread(name = "backup-health-monitor", isDaemon = true) {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(60_000 * 30) // check every 30 minutes
                    checkStaleBackups()
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    System.err.println("[BackupHealthMonitor] error: ${e.message}")
                }
            }
        }
    }

    fun checkStaleBackups() {
        if (!emailService.isEnabled()) return

        val threshold = Instant.now().minus(Duration.ofHours(staleHoursThreshold))

        db.getConnection().use { conn ->
            conn.prepareStatement(
                """SELECT s.id AS server_id, s.name AS server_name, u.email AS owner_email, s.last_seen_at
                   FROM servers s
                   JOIN users u ON u.id = s.user_id
                   WHERE s.last_seen_at IS NOT NULL
                     AND s.last_seen_at < ?
                     AND (s.stale_notified_at IS NULL OR s.stale_notified_at < ?)""",
            ).use { ps ->
                ps.setTimestamp(1, java.sql.Timestamp.from(threshold))
                ps.setTimestamp(2, java.sql.Timestamp.from(threshold))
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val serverId = rs.getString("server_id")
                        val serverName = rs.getString("server_name") ?: serverId
                        val ownerEmail = rs.getString("owner_email")
                        val lastSeen = rs.getTimestamp("last_seen_at").toInstant()
                        val hoursSince = Duration.between(lastSeen, Instant.now()).toHours()

                        emailService.send(
                            ownerEmail,
                            "ProperBackup: $serverName hasn't backed up in ${hoursSince}h",
                            EmailTemplates.backupStale(serverName, serverId, hoursSince),
                        )

                        conn.prepareStatement("UPDATE servers SET stale_notified_at = NOW() WHERE id = ?").use { up ->
                            up.setString(1, serverId)
                            up.executeUpdate()
                        }

                        println("[BackupHealthMonitor] Alert sent for $serverId ($serverName) — $hoursSince hours since last backup")
                    }
                }
            }
        }
    }
}
