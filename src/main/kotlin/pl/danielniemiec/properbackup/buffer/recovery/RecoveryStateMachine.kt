package pl.danielniemiec.properbackup.buffer.recovery

import pl.danielniemiec.properbackup.buffer.recovery.RecoveryState.*

object RecoveryStateMachine {

    private val legalTransitions: Map<RecoveryState, Set<RecoveryState>> = mapOf(
        REQUESTED to setOf(PLANNING, CANCELLED, FAILED),
        PLANNING to setOf(AWAITING_USER_CONFIRM, FAILED, CANCELLED),
        AWAITING_USER_CONFIRM to setOf(THAWING, READY, CANCELLED, FAILED),
        THAWING to setOf(READY, CANCELLED, FAILED),
        READY to setOf(AGENT_RESTORING, CANCELLED, FAILED),
        AGENT_RESTORING to setOf(VERIFYING, FAILED, CANCELLED),
        VERIFYING to setOf(DONE, FAILED),
        DONE to emptySet(),
        FAILED to emptySet(),
        CANCELLED to emptySet(),
    )

    fun isLegal(from: RecoveryState, to: RecoveryState): Boolean =
        legalTransitions[from]?.contains(to) == true

    fun ensureLegal(from: RecoveryState, to: RecoveryState) {
        if (!isLegal(from, to)) {
            throw IllegalStateException("Illegal recovery state transition: $from → $to")
        }
    }

    fun allowedFrom(state: RecoveryState): Set<RecoveryState> =
        legalTransitions[state] ?: emptySet()
}
