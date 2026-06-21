package pl.danielniemiec.properbackup.buffer.storage

import pl.danielniemiec.properbackup.buffer.db.Database
import java.sql.SQLException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Records daily storage usage per user in [user_storage_daily] table.
 * Detects spikes > 50 GB/1h per user and triggers soft block + alert.
 */
class StorageUsageMonitor(
    private val db: Database,
    private val spikeThresholdBytes: Long = SPIKE_THRESHOLD_BYTES,
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "storage-usage-monitor").apply { isDaemon = true }
    }

    fun start() {
        scheduler.scheduleAtFixedRate({ recordDailyUsage() }, 1, 60, TimeUnit.MINUTES)
        scheduler.scheduleAtFixedRate({ checkSpikes() }, 5, 15, TimeUnit.MINUTES)
        println("[storage-monitor] started (daily usage every 60min, spike check every 15min)")
    }

    fun stop() {
        scheduler.shutdown()
    }

    fun recordDailyUsage() {
        try {
            db.getConnection().use { conn ->
                conn.prepareStatement(
                    """INSERT INTO user_storage_daily (user_id, day, used_bytes, pack_count, recorded_at)
                       SELECT s.user_id, CURRENT_DATE,
                              COALESCE(SUM(a.size_bytes), 0),
                              COUNT(DISTINCT bp.pack_name),
                              NOW()
                       FROM servers s
                       LEFT JOIN archive_snapshot a ON a.server_id = s.id
                       LEFT JOIN buffer_pack bp ON bp.server_id = s.id
                       GROUP BY s.user_id
                       ON CONFLICT (user_id, day) DO UPDATE
                       SET used_bytes = EXCLUDED.used_bytes,
                           pack_count = EXCLUDED.pack_count,
                           recorded_at = EXCLUDED.recorded_at"""
                ).use { ps ->
                    val rows = ps.executeUpdate()
                    if (rows > 0) println("[storage-monitor] recorded daily usage for $rows users")
                }
            }
        } catch (e: SQLException) {
            System.err.println("[storage-monitor] daily usage error: ${e.message}")
        }
    }

    fun checkSpikes() {
        try {
            val oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS)
            db.getConnection().use { conn ->
                conn.prepareStatement(
                    """SELECT s.user_id, SUM(a.size_bytes) AS bytes_last_hour
                       FROM archive_snapshot a
                       JOIN servers s ON s.id = a.server_id
                       WHERE a.sealed_at > ?
                       GROUP BY s.user_id
                       HAVING SUM(a.size_bytes) > ?"""
                ).use { ps ->
                    ps.setObject(1, oneHourAgo)
                    ps.setLong(2, spikeThresholdBytes)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            val userId = rs.getString("user_id")
                            val bytesInWindow = rs.getLong("bytes_last_hour")
                            recordSpikeAlert(userId, bytesInWindow, oneHourAgo, Instant.now())
                        }
                    }
                }
            }
        } catch (e: SQLException) {
            System.err.println("[storage-monitor] spike check error: ${e.message}")
        }
    }

    private fun recordSpikeAlert(userId: String, bytesInWindow: Long, windowStart: Instant, windowEnd: Instant) {
        try {
            db.getConnection().use { conn ->
                conn.prepareStatement(
                    """INSERT INTO storage_spike_alert (user_id, bytes_in_window, window_start, window_end, action_taken)
                       VALUES (?, ?, ?, ?, 'soft_block')"""
                ).use { ps ->
                    ps.setString(1, userId)
                    ps.setLong(2, bytesInWindow)
                    ps.setObject(3, windowStart)
                    ps.setObject(4, windowEnd)
                    ps.executeUpdate()
                }
            }
            val gb = bytesInWindow / (1024L * 1024 * 1024)
            System.err.println("[storage-monitor] SPIKE ALERT: user=$userId uploaded ${gb}GB in last hour — soft block applied")
        } catch (e: SQLException) {
            System.err.println("[storage-monitor] spike alert record error: ${e.message}")
        }
    }

    fun isUserSoftBlocked(userId: String): Boolean {
        return try {
            db.getConnection().use { conn ->
                conn.prepareStatement(
                    """SELECT COUNT(*) FROM storage_spike_alert
                       WHERE user_id = ? AND created_at > NOW() - INTERVAL '1 hour'"""
                ).use { ps ->
                    ps.setString(1, userId)
                    ps.executeQuery().use { rs ->
                        rs.next() && rs.getInt(1) > 0
                    }
                }
            }
        } catch (e: SQLException) {
            false
        }
    }

    companion object {
        const val SPIKE_THRESHOLD_BYTES = 50L * 1024 * 1024 * 1024 // 50 GB
    }
}
