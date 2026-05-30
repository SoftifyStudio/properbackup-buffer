package pl.danielniemiec.properbackup.buffer.recovery

class RecoveryGuard(private val store: RecoverySessionStore) {

    fun ensureNoActiveRecovery(serverId: String, action: String) {
        val session = store.activeForServer(serverId)
        if (session != null) {
            throw RecoveryActiveException(
                serverId = serverId,
                sessionId = session.id,
                blockedAction = action,
            )
        }
    }
}
