package pl.danielniemiec.properbackup.buffer.recovery

import pl.danielniemiec.properbackup.buffer.db.Database
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

class RecoverySessionStore(private val db: Database) {

    fun create(
        serverId: String,
        userId: String,
        targetSnapshotId: Long,
    ): RecoverySession {
        val id = UUID.randomUUID().toString()
        val now = Instant.now()
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO recovery_session (id, server_id, user_id, target_snapshot_id, state, created_at)
                   VALUES (?, ?, ?, ?, 'REQUESTED', ?)""",
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, serverId)
                ps.setString(3, userId)
                ps.setLong(4, targetSnapshotId)
                ps.setTimestamp(5, Timestamp.from(now))
                ps.executeUpdate()
            }
        }
        return RecoverySession(
            id = id,
            serverId = serverId,
            userId = userId,
            targetSnapshotId = targetSnapshotId,
            state = RecoveryState.REQUESTED,
            createdAt = now,
        )
    }

    fun byId(id: String): RecoverySession? {
        db.getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM recovery_session WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) mapRow(rs) else null
                }
            }
        }
    }

    fun activeForServer(serverId: String): RecoverySession? {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT * FROM recovery_session WHERE server_id = ? AND state NOT IN ('DONE', 'FAILED', 'CANCELLED') LIMIT 1",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) mapRow(rs) else null
                }
            }
        }
    }

    fun activeForUser(userId: String): List<RecoverySession> {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT * FROM recovery_session WHERE user_id = ? AND state NOT IN ('DONE', 'FAILED', 'CANCELLED') ORDER BY created_at DESC",
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<RecoverySession>()
                    while (rs.next()) out.add(mapRow(rs))
                    return out
                }
            }
        }
    }

    fun historyForServer(serverId: String, limit: Int = 100): List<RecoverySession> {
        val lim = limit.coerceIn(1, 500)
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT * FROM recovery_session WHERE server_id = ? ORDER BY created_at DESC LIMIT ?",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.setInt(2, lim)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<RecoverySession>()
                    while (rs.next()) out.add(mapRow(rs))
                    return out
                }
            }
        }
    }

    fun transition(
        id: String,
        from: RecoveryState,
        to: RecoveryState,
        meta: Map<String, Any?> = emptyMap(),
    ): RecoverySession {
        RecoveryStateMachine.ensureLegal(from, to)

        val timestampCol = when (to) {
            RecoveryState.AWAITING_USER_CONFIRM -> null
            RecoveryState.THAWING -> "thaw_started_at"
            RecoveryState.READY -> "thaw_completed_at"
            RecoveryState.AGENT_RESTORING -> "agent_started_at"
            RecoveryState.VERIFYING -> "verifying_started_at"
            RecoveryState.DONE -> "completed_at"
            RecoveryState.FAILED -> "failed_at"
            RecoveryState.CANCELLED -> "cancelled_at"
            else -> null
        }

        val now = Instant.now()
        db.getConnection().use { conn ->
            val setClauses = mutableListOf("state = ?")
            val params = mutableListOf<Any?>(to.name)
            if (timestampCol != null) {
                setClauses.add("$timestampCol = ?")
                params.add(Timestamp.from(now))
            }
            if (to == RecoveryState.FAILED) {
                setClauses.add("failure_reason = ?")
                params.add(meta["reason"] as? String)
            }
            if (to == RecoveryState.CANCELLED) {
                setClauses.add("cancellation_reason = ?")
                params.add(meta["reason"] as? String)
            }
            if (from == RecoveryState.AGENT_RESTORING && to == RecoveryState.VERIFYING) {
                setClauses.add("agent_completed_at = ?")
                params.add(Timestamp.from(now))
            }
            if (to == RecoveryState.AWAITING_USER_CONFIRM) {
                val ip = meta["ip"] as? String
                val ua = meta["user_agent"] as? String
                if (ip != null) {
                    setClauses.add("user_confirmed_via_ip = ?")
                    params.add(ip)
                }
                if (ua != null) {
                    setClauses.add("user_confirmed_via_user_agent = ?")
                    params.add(ua)
                }
            }
            params.add(id)
            params.add(from.name)

            val sql = "UPDATE recovery_session SET ${setClauses.joinToString(", ")} WHERE id = ? AND state = ?"
            conn.prepareStatement(sql).use { ps ->
                for ((i, v) in params.withIndex()) {
                    if (v == null) ps.setNull(i + 1, java.sql.Types.VARCHAR)
                    else when (v) {
                        is String -> ps.setString(i + 1, v)
                        is Timestamp -> ps.setTimestamp(i + 1, v)
                        else -> ps.setString(i + 1, v.toString())
                    }
                }
                val updated = ps.executeUpdate()
                if (updated == 0) {
                    val current = byId(id)
                    throw IllegalStateException(
                        "State transition failed: expected $from but current is ${current?.state ?: "NOT_FOUND"}",
                    )
                }
            }
        }
        return byId(id) ?: throw IllegalStateException("Recovery session $id not found after transition")
    }

    fun setDryRun(
        id: String,
        filesToRestore: Int,
        filesToDelete: Int,
        filesUnchanged: Int,
        totalBytes: Long,
        sampleRestore: List<String>,
        sampleDelete: List<String>,
        criticalProtected: Int,
        estimatedDurationSec: Int?,
    ) {
        val dryRunId = UUID.randomUUID().toString()
        db.getConnection().use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """INSERT INTO recovery_dry_run
                       (id, recovery_session_id, files_to_restore_count, files_to_restore_size,
                        files_to_delete_count, files_to_delete_size, files_unchanged_count,
                        critical_files_protected_count, sample_files_to_restore, sample_files_to_delete,
                        estimated_duration_seconds)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                ).use { ps ->
                    ps.setString(1, dryRunId)
                    ps.setString(2, id)
                    ps.setInt(3, filesToRestore)
                    ps.setLong(4, totalBytes)
                    ps.setInt(5, filesToDelete)
                    ps.setLong(6, 0)
                    ps.setInt(7, filesUnchanged)
                    ps.setInt(8, criticalProtected)
                    ps.setString(9, sampleRestore.joinToString("\n"))
                    ps.setString(10, sampleDelete.joinToString("\n"))
                    if (estimatedDurationSec != null) ps.setInt(11, estimatedDurationSec)
                    else ps.setNull(11, java.sql.Types.INTEGER)
                    ps.executeUpdate()
                }
                conn.prepareStatement(
                    """UPDATE recovery_session SET
                       dry_run_files_to_restore = ?, dry_run_files_to_delete = ?,
                       dry_run_files_unchanged = ?, dry_run_total_bytes = ?
                       WHERE id = ?""",
                ).use { ps ->
                    ps.setInt(1, filesToRestore)
                    ps.setInt(2, filesToDelete)
                    ps.setInt(3, filesUnchanged)
                    ps.setLong(4, totalBytes)
                    ps.setString(5, id)
                    ps.executeUpdate()
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun setPreRecoverySnapshot(id: String, snapshotId: Long) {
        db.getConnection().use { conn ->
            conn.prepareStatement("UPDATE recovery_session SET pre_recovery_snapshot_id = ? WHERE id = ?").use { ps ->
                ps.setLong(1, snapshotId)
                ps.setString(2, id)
                ps.executeUpdate()
            }
        }
    }

    fun incrementProgress(id: String, filesRestoredDelta: Int, filesDeletedDelta: Int, bytesDelta: Long) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """UPDATE recovery_session SET
                   files_restored = files_restored + ?,
                   files_deleted = files_deleted + ?,
                   bytes_transferred = bytes_transferred + ?
                   WHERE id = ?""",
            ).use { ps ->
                ps.setInt(1, filesRestoredDelta)
                ps.setInt(2, filesDeletedDelta)
                ps.setLong(3, bytesDelta)
                ps.setString(4, id)
                ps.executeUpdate()
            }
        }
    }

    fun markFailed(id: String, reason: String, atPhase: String) {
        val session = byId(id) ?: throw IllegalStateException("Recovery session $id not found")
        transition(id, session.state, RecoveryState.FAILED, mapOf("reason" to "$atPhase: $reason"))
    }

    fun markCancelled(id: String, reason: String, atPhase: String) {
        val session = byId(id) ?: throw IllegalStateException("Recovery session $id not found")
        transition(id, session.state, RecoveryState.CANCELLED, mapOf("reason" to "$atPhase: $reason"))
    }

    fun markDone(id: String) {
        val session = byId(id) ?: throw IllegalStateException("Recovery session $id not found")
        transition(id, session.state, RecoveryState.DONE)
    }

    fun setConfirmed(id: String, ip: String?, userAgent: String?) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE recovery_session SET confirmed_at = ?, user_confirmed_via_ip = ?, user_confirmed_via_user_agent = ? WHERE id = ?",
            ).use { ps ->
                ps.setTimestamp(1, Timestamp.from(Instant.now()))
                if (ip != null) ps.setString(2, ip) else ps.setNull(2, java.sql.Types.VARCHAR)
                if (userAgent != null) ps.setString(3, userAgent) else ps.setNull(3, java.sql.Types.VARCHAR)
                ps.setString(4, id)
                ps.executeUpdate()
            }
        }
    }

    private fun mapRow(rs: ResultSet): RecoverySession = RecoverySession(
        id = rs.getString("id"),
        serverId = rs.getString("server_id"),
        userId = rs.getString("user_id"),
        targetSnapshotId = rs.getLong("target_snapshot_id"),
        state = RecoveryState.valueOf(rs.getString("state")),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        confirmedAt = rs.getTimestamp("confirmed_at")?.toInstant(),
        thawStartedAt = rs.getTimestamp("thaw_started_at")?.toInstant(),
        thawCompletedAt = rs.getTimestamp("thaw_completed_at")?.toInstant(),
        agentStartedAt = rs.getTimestamp("agent_started_at")?.toInstant(),
        agentCompletedAt = rs.getTimestamp("agent_completed_at")?.toInstant(),
        verifyingStartedAt = rs.getTimestamp("verifying_started_at")?.toInstant(),
        completedAt = rs.getTimestamp("completed_at")?.toInstant(),
        cancelledAt = rs.getTimestamp("cancelled_at")?.toInstant(),
        failedAt = rs.getTimestamp("failed_at")?.toInstant(),
        preRecoverySnapshotId = rs.getLong("pre_recovery_snapshot_id").let { if (rs.wasNull()) null else it },
        dryRunFilesToRestore = rs.getInt("dry_run_files_to_restore").let { if (rs.wasNull()) null else it },
        dryRunFilesToDelete = rs.getInt("dry_run_files_to_delete").let { if (rs.wasNull()) null else it },
        dryRunFilesUnchanged = rs.getInt("dry_run_files_unchanged").let { if (rs.wasNull()) null else it },
        dryRunTotalBytes = rs.getLong("dry_run_total_bytes").let { if (rs.wasNull()) null else it },
        filesRestored = rs.getInt("files_restored"),
        filesDeleted = rs.getInt("files_deleted"),
        bytesTransferred = rs.getLong("bytes_transferred"),
        failureReason = rs.getString("failure_reason"),
        cancellationReason = rs.getString("cancellation_reason"),
        userConfirmedViaIp = rs.getString("user_confirmed_via_ip"),
        userConfirmedViaUserAgent = rs.getString("user_confirmed_via_user_agent"),
    )

    fun getDryRunDetail(sessionId: String): Map<String, Any?>? {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """SELECT files_to_restore_count, files_to_restore_size,
                          files_to_delete_count, files_to_delete_size,
                          files_unchanged_count, critical_files_protected_count,
                          sample_files_to_restore, sample_files_to_delete,
                          estimated_duration_seconds, computed_at
                   FROM recovery_dry_run WHERE recovery_session_id = ?
                   ORDER BY computed_at DESC LIMIT 1""",
            ).use { ps ->
                ps.setString(1, sessionId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return mapOf(
                        "filesToRestore" to rs.getInt("files_to_restore_count"),
                        "filesToRestoreBytes" to rs.getLong("files_to_restore_size"),
                        "filesToDelete" to rs.getInt("files_to_delete_count"),
                        "filesToDeleteBytes" to rs.getLong("files_to_delete_size"),
                        "filesUnchanged" to rs.getInt("files_unchanged_count"),
                        "criticalFilesProtected" to rs.getInt("critical_files_protected_count"),
                        "sampleFilesToRestore" to (rs.getString("sample_files_to_restore")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()),
                        "sampleFilesToDelete" to (rs.getString("sample_files_to_delete")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()),
                        "estimatedDurationSeconds" to rs.getInt("estimated_duration_seconds").let { if (rs.wasNull()) null else it },
                        "computedAt" to rs.getTimestamp("computed_at").toInstant().toString(),
                    )
                }
            }
        }
    }
}
