package pl.danielniemiec.properbackup.buffer.flush

import pl.danielniemiec.properbackup.buffer.auth.UserStore
import pl.danielniemiec.properbackup.buffer.db.Database
import pl.danielniemiec.properbackup.buffer.payment.SubscriptionAuditLog

class StorageQuotaGuard(
    private val db: Database,
    private val userStore: UserStore,
    private val auditLog: SubscriptionAuditLog? = null,
) {

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

    /**
     * Returns false (fail-closed) if:
     * - DB is unavailable (cannot verify quota)
     * - User not found
     * - Quota would be exceeded
     *
     * Per [TDD-G1c]: MUST fail-closed when DB unreachable.
     */
    fun canUpload(userId: String, additionalBytes: Long): Boolean {
        return try {
            val status = checkQuota(userId)
            val allowed = (status.usedBytes + additionalBytes) <= status.limitBytes
            if (!allowed) {
                auditLog?.append(userId, "agent_blocked_quota_exceeded",
                    "used=${status.usedBytes} limit=${status.limitBytes} additional=$additionalBytes")
            }
            allowed
        } catch (e: Exception) {
            System.err.println("[StorageQuotaGuard] FAIL-CLOSED: DB unavailable during canUpload check: ${e.message}")
            auditLog?.let {
                try { it.append(userId, "agent_blocked_db_unavailable", e.message ?: "unknown") } catch (_: Exception) {}
            }
            false
        }
    }

    private fun totalStorageUsed(userId: String): Long {
        return db.getConnection().use { conn ->
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
    }
}
