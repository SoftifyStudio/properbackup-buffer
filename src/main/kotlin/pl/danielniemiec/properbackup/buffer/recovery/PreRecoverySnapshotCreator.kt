package pl.danielniemiec.properbackup.buffer.recovery

class PreRecoverySnapshotCreator(
    private val store: RecoverySessionStore,
    private val eventBus: RecoveryEventBus,
    private val auditLog: RecoveryAuditLog,
) {

    fun requestPreRecoverySnapshot(session: RecoverySession) {
        eventBus.emitRecoveryCommand(
            serverId = session.serverId,
            command = "execute",
            sessionId = session.id,
            payload = mapOf("targetSnapshotId" to session.targetSnapshotId),
        )
        auditLog.log(session.id, "RECOVERY_AGENT_EXECUTE_SENT")
    }

    fun receivePreRecoverySnapshotId(sessionId: String, snapshotId: Long) {
        store.setPreRecoverySnapshot(sessionId, snapshotId)
        auditLog.log(sessionId, "RECOVERY_PRE_SNAPSHOT_CREATED", "snapshotId=$snapshotId")
    }
}
