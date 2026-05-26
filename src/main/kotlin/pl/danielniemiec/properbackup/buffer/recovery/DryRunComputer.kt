package pl.danielniemiec.properbackup.buffer.recovery

class DryRunComputer(
    private val store: RecoverySessionStore,
    private val eventBus: RecoveryEventBus,
    private val auditLog: RecoveryAuditLog,
) {

    fun requestDryRun(session: RecoverySession) {
        eventBus.emitRecoveryCommand(
            serverId = session.serverId,
            command = "dry_run",
            sessionId = session.id,
            payload = mapOf("targetSnapshotId" to session.targetSnapshotId),
        )
        val updated = store.transition(session.id, RecoveryState.REQUESTED, RecoveryState.PLANNING)
        auditLog.log(session.id, "RECOVERY_DRY_RUN_REQUESTED")
        eventBus.emitStateChange(updated)
    }

    fun receiveDryRunResult(
        sessionId: String,
        filesToRestore: Int,
        filesToDelete: Int,
        filesUnchanged: Int,
        totalBytes: Long,
        sampleRestore: List<String>,
        sampleDelete: List<String>,
        criticalProtected: Int,
        estimatedDurationSec: Int?,
    ) {
        store.setDryRun(
            id = sessionId,
            filesToRestore = filesToRestore,
            filesToDelete = filesToDelete,
            filesUnchanged = filesUnchanged,
            totalBytes = totalBytes,
            sampleRestore = sampleRestore,
            sampleDelete = sampleDelete,
            criticalProtected = criticalProtected,
            estimatedDurationSec = estimatedDurationSec,
        )
        val updated = store.transition(sessionId, RecoveryState.PLANNING, RecoveryState.AWAITING_USER_CONFIRM)
        auditLog.log(
            sessionId,
            "RECOVERY_DRY_RUN_COMPUTED",
            "files_to_restore=$filesToRestore, files_to_delete=$filesToDelete, total_bytes=$totalBytes",
        )
        eventBus.emitStateChange(updated)
    }
}
