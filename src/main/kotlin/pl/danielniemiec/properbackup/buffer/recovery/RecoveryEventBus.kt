package pl.danielniemiec.properbackup.buffer.recovery

import pl.danielniemiec.properbackup.buffer.sse.SseEventBus

class RecoveryEventBus(private val sseBus: SseEventBus) {

    fun emitStateChange(session: RecoverySession) {
        sseBus.publish(session.serverId, "recovery_state", mapOf(
            "sessionId" to session.id,
            "state" to session.state.name,
            "serverId" to session.serverId,
            "filesRestored" to session.filesRestored,
            "filesDeleted" to session.filesDeleted,
            "bytesTransferred" to session.bytesTransferred,
            "dryRunFilesToRestore" to session.dryRunFilesToRestore,
            "dryRunFilesToDelete" to session.dryRunFilesToDelete,
            "dryRunFilesUnchanged" to session.dryRunFilesUnchanged,
            "dryRunTotalBytes" to session.dryRunTotalBytes,
            "failureReason" to session.failureReason,
            "cancellationReason" to session.cancellationReason,
        ))
    }

    fun emitProgress(session: RecoverySession) {
        sseBus.publish(session.serverId, "recovery_progress", mapOf(
            "sessionId" to session.id,
            "filesRestored" to session.filesRestored,
            "filesDeleted" to session.filesDeleted,
            "bytesTransferred" to session.bytesTransferred,
        ))
    }

    fun emitRecoveryCommand(serverId: String, command: String, sessionId: String, payload: Map<String, Any?> = emptyMap()) {
        sseBus.publish(serverId, "recovery_command", mapOf(
            "command" to command,
            "sessionId" to sessionId,
        ) + payload)
    }
}
