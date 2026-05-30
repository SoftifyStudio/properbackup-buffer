package pl.danielniemiec.properbackup.buffer.recovery

enum class RecoveryState {
    REQUESTED,
    PLANNING,
    AWAITING_USER_CONFIRM,
    THAWING,
    READY,
    AGENT_RESTORING,
    VERIFYING,
    DONE,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == DONE || this == FAILED || this == CANCELLED

    val isActive: Boolean
        get() = !isTerminal

    companion object {
        val TERMINAL_STATES = setOf(DONE, FAILED, CANCELLED)
        val ACTIVE_STATES = entries.toSet() - TERMINAL_STATES
    }
}
