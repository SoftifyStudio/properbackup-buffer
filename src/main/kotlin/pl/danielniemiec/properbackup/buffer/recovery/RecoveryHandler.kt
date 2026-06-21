package pl.danielniemiec.properbackup.buffer.recovery

import com.google.gson.Gson
import io.javalin.http.Context

class RecoveryHandler(
    private val store: RecoverySessionStore,
    private val guard: RecoveryGuard,
    private val dryRun: DryRunComputer,
    private val preSnapshot: PreRecoverySnapshotCreator,
    private val auditLog: RecoveryAuditLog,
    private val eventBus: RecoveryEventBus,
    private val gson: Gson = Gson(),
) {
    companion object {
        const val MAX_FILES_PER_BATCH = 100
    }

    fun startRecovery(ctx: Context) {
        val userId = ctx.attribute<String>("userId")!!
        val body = gson.fromJson(ctx.body(), Map::class.java)
        val serverId = (body["server_id"] as? String)?.trim()
        val targetSnapshotId = (body["target_snapshot_id"] as? Number)?.toLong()

        if (serverId.isNullOrBlank() || targetSnapshotId == null) {
            ctx.status(400).contentType("application/json")
                .result(gson.toJson(mapOf("code" to "INVALID_REQUEST", "message" to "server_id and target_snapshot_id required")))
            return
        }

        val existing = store.activeForServer(serverId)
        if (existing != null) {
            ctx.status(409).contentType("application/json")
                .result(gson.toJson(mapOf(
                    "code" to "RECOVERY_ALREADY_ACTIVE",
                    "sessionId" to existing.id,
                    "state" to existing.state.name,
                )))
            return
        }

        try {
            val session = store.create(serverId, userId, targetSnapshotId)
            auditLog.log(session.id, "RECOVERY_REQUESTED", "user=$userId, server=$serverId, snapshot=$targetSnapshotId")
            eventBus.emitStateChange(session)
            dryRun.requestDryRun(session)
            ctx.status(201).contentType("application/json").result(gson.toJson(mapOf("session_id" to session.id)))
        } catch (e: Exception) {
            if (e.message?.contains("uq_recovery_session_server_active") == true ||
                e.message?.contains("23505") == true
            ) {
                ctx.status(409).contentType("application/json")
                    .result(gson.toJson(mapOf("code" to "RECOVERY_ALREADY_ACTIVE")))
            } else {
                throw e
            }
        }
    }

    fun getSession(ctx: Context) {
        val id = ctx.pathParam("id")
        val session = store.byId(id)
        if (session == null) {
            ctx.status(404).contentType("application/json")
                .result(gson.toJson(mapOf("code" to "NOT_FOUND")))
            return
        }
        val userId = ctx.attribute<String>("userId")!!
        val isAdmin = ctx.attribute<Boolean>("isSystemAdmin") == true
        if (session.userId != userId && !isAdmin) {
            ctx.status(403).contentType("application/json")
                .result(gson.toJson(mapOf("code" to "FORBIDDEN")))
            return
        }
        ctx.status(200).contentType("application/json").result(gson.toJson(sessionToMap(session)))
    }

    fun confirmRecovery(ctx: Context) {
        val id = ctx.pathParam("id")
        val session = store.byId(id)
        if (session == null) {
            ctx.status(404).contentType("application/json").result(gson.toJson(mapOf("code" to "NOT_FOUND")))
            return
        }
        val userId = ctx.attribute<String>("userId")!!
        if (session.userId != userId) {
            ctx.status(403).contentType("application/json").result(gson.toJson(mapOf("code" to "FORBIDDEN")))
            return
        }
        if (session.state != RecoveryState.AWAITING_USER_CONFIRM) {
            ctx.status(400).contentType("application/json")
                .result(gson.toJson(mapOf("code" to "INVALID_STATE", "currentState" to session.state.name)))
            return
        }
        val body = gson.fromJson(ctx.body(), Map::class.java)
        val confirmed = body["user_confirmation"] as? Boolean ?: false
        if (!confirmed) {
            ctx.status(400).contentType("application/json")
                .result(gson.toJson(mapOf("code" to "CONFIRMATION_REQUIRED")))
            return
        }

        val ip = ctx.ip()
        val userAgent = ctx.header("User-Agent")
        store.setConfirmed(id, ip, userAgent)

        val isColdTier = body["cold_tier"] as? Boolean ?: false
        val nextState = if (isColdTier) RecoveryState.THAWING else RecoveryState.READY
        val updated = store.transition(id, RecoveryState.AWAITING_USER_CONFIRM, nextState)
        auditLog.log(id, "RECOVERY_USER_CONFIRMED", "ip=$ip, ua=$userAgent")
        eventBus.emitStateChange(updated)

        if (!isColdTier) {
            preSnapshot.requestPreRecoverySnapshot(updated)
        }

        ctx.status(200).contentType("application/json").result(gson.toJson(mapOf("state" to updated.state.name)))
    }

    fun cancelRecovery(ctx: Context) {
        val id = ctx.pathParam("id")
        val session = store.byId(id)
        if (session == null) {
            ctx.status(404).contentType("application/json").result(gson.toJson(mapOf("code" to "NOT_FOUND")))
            return
        }
        val userId = ctx.attribute<String>("userId")!!
        if (session.userId != userId) {
            ctx.status(403).contentType("application/json").result(gson.toJson(mapOf("code" to "FORBIDDEN")))
            return
        }
        if (session.state.isTerminal) {
            ctx.status(400).contentType("application/json")
                .result(gson.toJson(mapOf("code" to "ALREADY_TERMINAL", "state" to session.state.name)))
            return
        }
        if (session.state == RecoveryState.VERIFYING) {
            ctx.status(400).contentType("application/json")
                .result(gson.toJson(mapOf("code" to "CANCEL_NOT_ALLOWED_DURING_VERIFICATION")))
            return
        }

        val body = try { gson.fromJson(ctx.body(), Map::class.java) } catch (_: Exception) { emptyMap<String, Any>() }
        val reason = (body["reason"] as? String) ?: ""

        if (session.state == RecoveryState.AGENT_RESTORING) {
            eventBus.emitRecoveryCommand(
                session.serverId, "rollback", session.id,
                mapOf("preRecoverySnapshotId" to session.preRecoverySnapshotId),
            )
        }

        val updated = store.transition(id, session.state, RecoveryState.CANCELLED, mapOf("reason" to reason))
        auditLog.log(id, "RECOVERY_CANCELLED", "at_phase=${session.state}, reason=$reason, pre_snapshot=${session.preRecoverySnapshotId}")
        eventBus.emitStateChange(updated)

        ctx.status(200).contentType("application/json").result(gson.toJson(mapOf(
            "state" to "CANCELLED",
            "at_phase" to session.state.name,
            "preRecoverySnapshotId" to session.preRecoverySnapshotId,
        )))
    }

    fun getAudit(ctx: Context) {
        val id = ctx.pathParam("id")
        val session = store.byId(id)
        if (session == null) {
            ctx.status(404).contentType("application/json").result(gson.toJson(mapOf("code" to "NOT_FOUND")))
            return
        }
        val userId = ctx.attribute<String>("userId")!!
        val isAdmin = ctx.attribute<Boolean>("isSystemAdmin") == true
        if (session.userId != userId && !isAdmin) {
            ctx.status(403).contentType("application/json").result(gson.toJson(mapOf("code" to "FORBIDDEN")))
            return
        }
        val limit = ctx.queryParam("limit")?.toIntOrNull() ?: 200
        val entries = auditLog.listForSession(id, limit)
        ctx.status(200).contentType("application/json").result(gson.toJson(mapOf("entries" to entries)))
    }

    fun getDryRun(ctx: Context) {
        val id = ctx.pathParam("id")
        val session = store.byId(id)
        if (session == null) {
            ctx.status(404).contentType("application/json").result(gson.toJson(mapOf("code" to "NOT_FOUND")))
            return
        }
        val userId = ctx.attribute<String>("userId")!!
        val isAdmin = ctx.attribute<Boolean>("isSystemAdmin") == true
        if (session.userId != userId && !isAdmin) {
            ctx.status(403).contentType("application/json").result(gson.toJson(mapOf("code" to "FORBIDDEN")))
            return
        }
        val detail = store.getDryRunDetail(id)
        if (detail == null) {
            ctx.status(404).contentType("application/json").result(gson.toJson(mapOf("code" to "DRY_RUN_NOT_FOUND")))
            return
        }
        ctx.status(200).contentType("application/json").result(gson.toJson(detail))
    }

    fun history(ctx: Context) {
        val serverId = ctx.queryParam("server_id")?.trim()
        if (serverId.isNullOrBlank()) {
            ctx.status(400).contentType("application/json")
                .result(gson.toJson(mapOf("code" to "MISSING_SERVER_ID")))
            return
        }
        val limit = ctx.queryParam("limit")?.toIntOrNull() ?: 100
        val sessions = store.historyForServer(serverId, limit)
        ctx.status(200).contentType("application/json")
            .result(gson.toJson(mapOf("sessions" to sessions.map { sessionToMap(it) })))
    }

    // Agent endpoints

    fun agentPoll(ctx: Context) {
        val serverId = ctx.header("X-Server-Id")?.trim()
        if (serverId.isNullOrBlank()) {
            ctx.status(400).contentType("application/json")
                .result(gson.toJson(mapOf("code" to "MISSING_SERVER_ID")))
            return
        }
        val session = store.activeForServer(serverId)
        if (session == null) {
            ctx.status(200).contentType("application/json")
                .result(gson.toJson(mapOf("commands" to emptyList<Any>())))
            return
        }
        val commands = mutableListOf<Map<String, Any?>>()
        when (session.state) {
            RecoveryState.REQUESTED -> commands.add(mapOf(
                "command" to "dry_run",
                "sessionId" to session.id,
                "targetSnapshotId" to session.targetSnapshotId,
            ))
            RecoveryState.READY -> {
                val snapshotId = store.createPreRecoverySnapshot(session.serverId, session.id)
                auditLog.log(session.id, "RECOVERY_PRE_SNAPSHOT_CREATED", "snapshotId=$snapshotId")
                val updated = store.transition(session.id, RecoveryState.READY, RecoveryState.AGENT_RESTORING)
                auditLog.log(session.id, "RECOVERY_AGENT_STARTED", "trigger=agent_poll")
                eventBus.emitStateChange(updated)
                commands.add(mapOf(
                    "command" to "execute",
                    "sessionId" to session.id,
                    "targetSnapshotId" to session.targetSnapshotId,
                    "preRecoverySnapshotId" to snapshotId,
                ))
            }
            else -> {}
        }
        ctx.status(200).contentType("application/json").result(gson.toJson(mapOf("commands" to commands)))
    }

    fun agentDryRunResult(ctx: Context) {
        val id = ctx.pathParam("id")
        val body = gson.fromJson(ctx.body(), Map::class.java)
        dryRun.receiveDryRunResult(
            sessionId = id,
            filesToRestore = (body["files_to_restore"] as? Number)?.toInt() ?: 0,
            filesToDelete = (body["files_to_delete"] as? Number)?.toInt() ?: 0,
            filesUnchanged = (body["files_unchanged"] as? Number)?.toInt() ?: 0,
            totalBytes = (body["total_bytes"] as? Number)?.toLong() ?: 0L,
            sampleRestore = (body["sample_restore"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            sampleDelete = (body["sample_delete"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            criticalProtected = (body["critical_protected"] as? Number)?.toInt() ?: 0,
            estimatedDurationSec = (body["estimated_duration_seconds"] as? Number)?.toInt(),
        )
        ctx.status(200).contentType("application/json").result(gson.toJson(mapOf("ok" to true)))
    }

    fun agentStarted(ctx: Context) {
        val id = ctx.pathParam("id")
        val session = store.byId(id)
        if (session == null) {
            ctx.status(404).contentType("application/json").result(gson.toJson(mapOf("code" to "NOT_FOUND")))
            return
        }
        when (session.state) {
            RecoveryState.READY -> {
                val snapshotId = store.createPreRecoverySnapshot(session.serverId, session.id)
                auditLog.log(session.id, "RECOVERY_PRE_SNAPSHOT_CREATED", "snapshotId=$snapshotId")
                val updated = store.transition(session.id, RecoveryState.READY, RecoveryState.AGENT_RESTORING)
                auditLog.log(session.id, "RECOVERY_AGENT_STARTED", "trigger=agent_started_endpoint")
                eventBus.emitStateChange(updated)
                ctx.status(200).contentType("application/json").result(gson.toJson(mapOf(
                    "ok" to true, "state" to "AGENT_RESTORING", "preRecoverySnapshotId" to snapshotId,
                )))
            }
            RecoveryState.AGENT_RESTORING -> {
                ctx.status(200).contentType("application/json").result(gson.toJson(mapOf(
                    "ok" to true, "state" to "AGENT_RESTORING", "preRecoverySnapshotId" to session.preRecoverySnapshotId,
                )))
            }
            else -> {
                ctx.status(400).contentType("application/json")
                    .result(gson.toJson(mapOf("code" to "INVALID_STATE", "currentState" to session.state.name)))
            }
        }
    }

    fun agentProgress(ctx: Context) {
        val id = ctx.pathParam("id")
        val body = gson.fromJson(ctx.body(), Map::class.java)
        val filesRestoredDelta = (body["files_restored_delta"] as? Number)?.toInt() ?: 0
        val filesDeletedDelta = (body["files_deleted_delta"] as? Number)?.toInt() ?: 0
        val bytesDelta = (body["bytes_delta"] as? Number)?.toLong() ?: 0L
        store.incrementProgress(id, filesRestoredDelta, filesDeletedDelta, bytesDelta)
        val session = store.byId(id)
        if (session != null) eventBus.emitProgress(session)
        ctx.status(200).contentType("application/json").result(gson.toJson(mapOf("ok" to true)))
    }

    /**
     * HR-6: Per-file audit logging. Agent reports restored/deleted files in batches (max 100 per call).
     * Each file entry gets a RECOVERY_FILE_RESTORED or RECOVERY_FILE_DELETED audit record.
     */
    fun agentFilesReport(ctx: Context) {
        val id = ctx.pathParam("id")
        val session = store.byId(id)
        if (session == null) {
            ctx.status(404).contentType("application/json").result(gson.toJson(mapOf("code" to "NOT_FOUND")))
            return
        }
        if (session.state != RecoveryState.AGENT_RESTORING) {
            ctx.status(400).contentType("application/json")
                .result(gson.toJson(mapOf("code" to "INVALID_STATE", "currentState" to session.state.name)))
            return
        }

        val body = gson.fromJson(ctx.body(), Map::class.java)
        @Suppress("UNCHECKED_CAST")
        val files = (body["files"] as? List<Map<String, Any?>>) ?: emptyList()

        if (files.size > MAX_FILES_PER_BATCH) {
            ctx.status(400).contentType("application/json")
                .result(gson.toJson(mapOf("code" to "BATCH_TOO_LARGE", "max" to MAX_FILES_PER_BATCH)))
            return
        }

        var restoredCount = 0
        var deletedCount = 0
        var totalBytes = 0L

        for (file in files) {
            val path = file["path"] as? String ?: continue
            val size = (file["size"] as? Number)?.toLong() ?: 0L
            val action = (file["action"] as? String) ?: "restored"

            when (action) {
                "restored" -> {
                    auditLog.log(id, "RECOVERY_FILE_RESTORED", "path=$path, size=$size")
                    restoredCount++
                    totalBytes += size
                }
                "deleted" -> {
                    auditLog.log(id, "RECOVERY_FILE_DELETED", "path=$path")
                    deletedCount++
                }
            }
        }

        store.incrementProgress(id, restoredCount, deletedCount, totalBytes)
        val updated = store.byId(id)
        if (updated != null) eventBus.emitProgress(updated)

        ctx.status(200).contentType("application/json").result(gson.toJson(mapOf(
            "ok" to true,
            "processed" to files.size,
            "restored" to restoredCount,
            "deleted" to deletedCount,
        )))
    }

    fun agentComplete(ctx: Context) {
        val id = ctx.pathParam("id")
        val body = gson.fromJson(ctx.body(), Map::class.java)
        val success = body["success"] as? Boolean ?: false
        val session = store.byId(id)
        if (session == null) {
            ctx.status(404).contentType("application/json").result(gson.toJson(mapOf("code" to "NOT_FOUND")))
            return
        }

        if (success) {
            val afterVerify = store.transition(id, session.state, RecoveryState.VERIFYING)
            auditLog.log(id, "RECOVERY_AGENT_COMPLETED")
            eventBus.emitStateChange(afterVerify)

            val verification = body["verification"] as? String
            if (verification == "OK") {
                val done = store.transition(id, RecoveryState.VERIFYING, RecoveryState.DONE)
                auditLog.log(id, "RECOVERY_COMPLETED",
                    "files_restored=${done.filesRestored}, files_deleted=${done.filesDeleted}, bytes=${done.bytesTransferred}")
                eventBus.emitStateChange(done)
            }
        } else {
            val reason = (body["error"] as? String) ?: "Agent reported failure"
            store.markFailed(id, reason, session.state.name)
            val failed = store.byId(id)!!
            auditLog.log(id, "RECOVERY_FAILED", "phase=${session.state}, reason=$reason")
            eventBus.emitStateChange(failed)
        }

        ctx.status(200).contentType("application/json").result(gson.toJson(mapOf("ok" to true)))
    }

    private fun sessionToMap(s: RecoverySession): Map<String, Any?> = mapOf(
        "id" to s.id,
        "serverId" to s.serverId,
        "userId" to s.userId,
        "targetSnapshotId" to s.targetSnapshotId,
        "state" to s.state.name,
        "createdAt" to s.createdAt.toString(),
        "confirmedAt" to s.confirmedAt?.toString(),
        "thawStartedAt" to s.thawStartedAt?.toString(),
        "thawCompletedAt" to s.thawCompletedAt?.toString(),
        "agentStartedAt" to s.agentStartedAt?.toString(),
        "agentCompletedAt" to s.agentCompletedAt?.toString(),
        "verifyingStartedAt" to s.verifyingStartedAt?.toString(),
        "completedAt" to s.completedAt?.toString(),
        "cancelledAt" to s.cancelledAt?.toString(),
        "failedAt" to s.failedAt?.toString(),
        "preRecoverySnapshotId" to s.preRecoverySnapshotId,
        "dryRunFilesToRestore" to s.dryRunFilesToRestore,
        "dryRunFilesToDelete" to s.dryRunFilesToDelete,
        "dryRunFilesUnchanged" to s.dryRunFilesUnchanged,
        "dryRunTotalBytes" to s.dryRunTotalBytes,
        "filesRestored" to s.filesRestored,
        "filesDeleted" to s.filesDeleted,
        "bytesTransferred" to s.bytesTransferred,
        "failureReason" to s.failureReason,
        "cancellationReason" to s.cancellationReason,
    )
}
