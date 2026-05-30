package pl.danielniemiec.properbackup.buffer.recovery

class RecoveryActiveException(
    val serverId: String,
    val sessionId: String,
    val blockedAction: String,
) : RuntimeException("Recovery active on server $serverId (session $sessionId), blocked action: $blockedAction")
