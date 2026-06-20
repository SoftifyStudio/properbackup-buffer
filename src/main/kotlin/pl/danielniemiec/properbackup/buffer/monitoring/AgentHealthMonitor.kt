package pl.danielniemiec.properbackup.buffer.monitoring

import pl.danielniemiec.properbackup.buffer.db.Database
import pl.danielniemiec.properbackup.buffer.server.ServerStore
import pl.danielniemiec.properbackup.buffer.notifications.NotificationScheduler
import java.time.Duration
import java.time.Instant
import kotlin.concurrent.thread

/**
 * Background daemon that periodically checks all servers for heartbeat staleness.
 * Classification: ONLINE (≤5min), WARNING (5-30min), OFFLINE (>30min).
 * When a server transitions to OFFLINE, it triggers a notification via [NotificationScheduler].
 */
class AgentHealthMonitor(
    private val db: Database,
    private val serverStore: ServerStore,
    private val notificationScheduler: NotificationScheduler?,
    private val checkIntervalMs: Long = 60_000,
    private val warningThresholdMinutes: Long = 5,
    private val offlineThresholdMinutes: Long = 30,
) {

    enum class HealthStatus { ONLINE, WARNING, OFFLINE }

    data class ServerHealth(
        val serverId: String,
        val serverName: String,
        val userId: String,
        val lastSeenAt: Instant?,
        val status: HealthStatus,
        val minutesSinceLastSeen: Long,
    )

    private val knownOffline = mutableSetOf<String>()

    fun start() {
        thread(name = "agent-health-monitor", isDaemon = true) {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(checkIntervalMs)
                    checkAll()
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    System.err.println("[AgentHealthMonitor] error: ${e.message}")
                }
            }
        }
    }

    fun checkAll(): List<ServerHealth> {
        val servers = serverStore.listAll()
        val now = Instant.now()
        val results = mutableListOf<ServerHealth>()

        for (srv in servers) {
            val lastSeen = try { Instant.parse(srv.lastSeen) } catch (_: Exception) { null }
            val minutesAgo = if (lastSeen != null) Duration.between(lastSeen, now).toMinutes() else Long.MAX_VALUE
            val status = classify(minutesAgo)

            results.add(
                ServerHealth(
                    serverId = srv.id,
                    serverName = srv.name,
                    userId = srv.userId,
                    lastSeenAt = lastSeen,
                    status = status,
                    minutesSinceLastSeen = minutesAgo,
                ),
            )

            if (status == HealthStatus.OFFLINE && srv.id !in knownOffline) {
                knownOffline.add(srv.id)
                notificationScheduler?.scheduleAgentOfflineAlert(srv.id, srv.name, srv.userId)
                println("[AgentHealthMonitor] Server ${srv.name} (${srv.id}) is OFFLINE — ${minutesAgo}min since last heartbeat")
            } else if (status != HealthStatus.OFFLINE) {
                knownOffline.remove(srv.id)
            }
        }

        return results
    }

    fun classify(minutesSinceLastSeen: Long): HealthStatus = when {
        minutesSinceLastSeen <= warningThresholdMinutes -> HealthStatus.ONLINE
        minutesSinceLastSeen <= offlineThresholdMinutes -> HealthStatus.WARNING
        else -> HealthStatus.OFFLINE
    }

    fun getHealth(serverId: String): ServerHealth? {
        val srv = serverStore.findById(serverId).orElse(null) ?: return null
        val now = Instant.now()
        val lastSeen = try { Instant.parse(srv.lastSeen) } catch (_: Exception) { null }
        val minutesAgo = if (lastSeen != null) Duration.between(lastSeen, now).toMinutes() else Long.MAX_VALUE
        return ServerHealth(
            serverId = srv.id,
            serverName = srv.name,
            userId = srv.userId,
            lastSeenAt = lastSeen,
            status = classify(minutesAgo),
            minutesSinceLastSeen = minutesAgo,
        )
    }
}
