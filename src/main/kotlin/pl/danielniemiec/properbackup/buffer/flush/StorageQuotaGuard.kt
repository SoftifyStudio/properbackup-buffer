package pl.danielniemiec.properbackup.buffer.flush

import org.slf4j.LoggerFactory
import pl.danielniemiec.properbackup.buffer.auth.UserStore
import pl.danielniemiec.properbackup.buffer.db.Database

class StorageQuotaGuard(
    private val db: Database,
    private val userStore: UserStore,
) {
    private val log = LoggerFactory.getLogger(StorageQuotaGuard::class.java)

    data class QuotaStatus(
        val usedBytes: Long,
        val limitBytes: Long,
        val remainingBytes: Long,
        val usedPercent: Double,
        val exceeded: Boolean,
    )

    fun checkQuota(userId: String): QuotaStatus {
        val user = userStore.findById(userId).orElse(null) ?: return QuotaStatus(0, 0, 0, 0.0, true)
        val limitBytes = user.storageLimitBytes
        val usedBytes = totalStorageUsed(userId)
        val remaining = maxOf(0L, limitBytes - usedBytes)
        val pct = if (limitBytes > 0) (usedBytes.toDouble() / limitBytes * 100.0) else 100.0
        return QuotaStatus(usedBytes, limitBytes, remaining, pct, usedBytes >= limitBytes)
    }

    fun canUpload(userId: String, additionalBytes: Long): Boolean {
        val status = checkQuota(userId)
        return (status.usedBytes + additionalBytes) <= status.limitBytes
    }

    private fun totalStorageUsed(userId: String): Long {
        return try {
            db.getConnection().use { conn ->
                conn.prepareStatement(
                    """SELECT COALESCE(SUM(a.size_bytes), 0)
                       FROM archive_snapshot a
                       JOIN servers s ON s.id = a.server_id
                       WHERE s.user_id = ?""",
                ).use { ps ->
                    ps.setString(1, userId)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rs.getLong(1) else 0L
                    }
                }
            }
        } catch (e: java.sql.SQLException) {
            log.error("DB error querying storage usage for user {}: {}", userId, e.message)
            // Fail-safe: treat DB errors as quota exceeded to prevent unmetered uploads
            Long.MAX_VALUE
        }
    }
}
