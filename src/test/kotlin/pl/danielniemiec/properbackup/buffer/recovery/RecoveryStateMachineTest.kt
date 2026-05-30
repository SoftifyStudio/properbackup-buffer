package pl.danielniemiec.properbackup.buffer.recovery

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import pl.danielniemiec.properbackup.buffer.recovery.RecoveryState.*

class RecoveryStateMachineTest {

    // --- Legal transitions (28 total per spec) ---

    @Test fun `REQUESTED to PLANNING is legal`() = assertLegal(REQUESTED, PLANNING)
    @Test fun `REQUESTED to CANCELLED is legal`() = assertLegal(REQUESTED, CANCELLED)
    @Test fun `REQUESTED to FAILED is legal`() = assertLegal(REQUESTED, FAILED)

    @Test fun `PLANNING to AWAITING_USER_CONFIRM is legal`() = assertLegal(PLANNING, AWAITING_USER_CONFIRM)
    @Test fun `PLANNING to FAILED is legal`() = assertLegal(PLANNING, FAILED)
    @Test fun `PLANNING to CANCELLED is legal`() = assertLegal(PLANNING, CANCELLED)

    @Test fun `AWAITING_USER_CONFIRM to THAWING is legal`() = assertLegal(AWAITING_USER_CONFIRM, THAWING)
    @Test fun `AWAITING_USER_CONFIRM to READY is legal`() = assertLegal(AWAITING_USER_CONFIRM, READY)
    @Test fun `AWAITING_USER_CONFIRM to CANCELLED is legal`() = assertLegal(AWAITING_USER_CONFIRM, CANCELLED)
    @Test fun `AWAITING_USER_CONFIRM to FAILED is legal`() = assertLegal(AWAITING_USER_CONFIRM, FAILED)

    @Test fun `THAWING to READY is legal`() = assertLegal(THAWING, READY)
    @Test fun `THAWING to CANCELLED is legal`() = assertLegal(THAWING, CANCELLED)
    @Test fun `THAWING to FAILED is legal`() = assertLegal(THAWING, FAILED)

    @Test fun `READY to AGENT_RESTORING is legal`() = assertLegal(READY, AGENT_RESTORING)
    @Test fun `READY to CANCELLED is legal`() = assertLegal(READY, CANCELLED)
    @Test fun `READY to FAILED is legal`() = assertLegal(READY, FAILED)

    @Test fun `AGENT_RESTORING to VERIFYING is legal`() = assertLegal(AGENT_RESTORING, VERIFYING)
    @Test fun `AGENT_RESTORING to FAILED is legal`() = assertLegal(AGENT_RESTORING, FAILED)
    @Test fun `AGENT_RESTORING to CANCELLED is legal`() = assertLegal(AGENT_RESTORING, CANCELLED)

    @Test fun `VERIFYING to DONE is legal`() = assertLegal(VERIFYING, DONE)
    @Test fun `VERIFYING to FAILED is legal`() = assertLegal(VERIFYING, FAILED)

    // --- Illegal transitions ---

    @Test fun `DONE has no outgoing transitions`() {
        RecoveryState.entries.forEach { target ->
            assertFalse(RecoveryStateMachine.isLegal(DONE, target), "DONE → $target should be illegal")
        }
    }

    @Test fun `FAILED has no outgoing transitions`() {
        RecoveryState.entries.forEach { target ->
            assertFalse(RecoveryStateMachine.isLegal(FAILED, target), "FAILED → $target should be illegal")
        }
    }

    @Test fun `CANCELLED has no outgoing transitions`() {
        RecoveryState.entries.forEach { target ->
            assertFalse(RecoveryStateMachine.isLegal(CANCELLED, target), "CANCELLED → $target should be illegal")
        }
    }

    @Test fun `VERIFYING to CANCELLED is illegal`() = assertIllegal(VERIFYING, CANCELLED)
    @Test fun `REQUESTED to DONE is illegal`() = assertIllegal(REQUESTED, DONE)
    @Test fun `REQUESTED to READY is illegal`() = assertIllegal(REQUESTED, READY)
    @Test fun `REQUESTED to AGENT_RESTORING is illegal`() = assertIllegal(REQUESTED, AGENT_RESTORING)
    @Test fun `REQUESTED to VERIFYING is illegal`() = assertIllegal(REQUESTED, VERIFYING)
    @Test fun `REQUESTED to THAWING is illegal`() = assertIllegal(REQUESTED, THAWING)
    @Test fun `REQUESTED to AWAITING_USER_CONFIRM is illegal`() = assertIllegal(REQUESTED, AWAITING_USER_CONFIRM)
    @Test fun `PLANNING to READY is illegal`() = assertIllegal(PLANNING, READY)
    @Test fun `PLANNING to THAWING is illegal`() = assertIllegal(PLANNING, THAWING)
    @Test fun `PLANNING to DONE is illegal`() = assertIllegal(PLANNING, DONE)
    @Test fun `PLANNING to AGENT_RESTORING is illegal`() = assertIllegal(PLANNING, AGENT_RESTORING)
    @Test fun `AWAITING_USER_CONFIRM to DONE is illegal`() = assertIllegal(AWAITING_USER_CONFIRM, DONE)
    @Test fun `AWAITING_USER_CONFIRM to AGENT_RESTORING is illegal`() = assertIllegal(AWAITING_USER_CONFIRM, AGENT_RESTORING)
    @Test fun `THAWING to DONE is illegal`() = assertIllegal(THAWING, DONE)
    @Test fun `THAWING to AGENT_RESTORING is illegal`() = assertIllegal(THAWING, AGENT_RESTORING)
    @Test fun `READY to DONE is illegal`() = assertIllegal(READY, DONE)
    @Test fun `READY to VERIFYING is illegal`() = assertIllegal(READY, VERIFYING)
    @Test fun `AGENT_RESTORING to DONE is illegal`() = assertIllegal(AGENT_RESTORING, DONE)
    @Test fun `AGENT_RESTORING to READY is illegal`() = assertIllegal(AGENT_RESTORING, READY)

    // Self-transitions are illegal
    @Test fun `self transition REQUESTED is illegal`() = assertIllegal(REQUESTED, REQUESTED)
    @Test fun `self transition PLANNING is illegal`() = assertIllegal(PLANNING, PLANNING)
    @Test fun `self transition VERIFYING is illegal`() = assertIllegal(VERIFYING, VERIFYING)
    @Test fun `self transition AGENT_RESTORING is illegal`() = assertIllegal(AGENT_RESTORING, AGENT_RESTORING)

    @Test fun `ensureLegal throws on illegal transition`() {
        assertThrows<IllegalStateException> {
            RecoveryStateMachine.ensureLegal(DONE, REQUESTED)
        }
    }

    @Test fun `ensureLegal does not throw on legal transition`() {
        assertDoesNotThrow {
            RecoveryStateMachine.ensureLegal(REQUESTED, PLANNING)
        }
    }

    @Test fun `allowedFrom returns correct states for REQUESTED`() {
        val allowed = RecoveryStateMachine.allowedFrom(REQUESTED)
        assertTrue(allowed.containsAll(setOf(PLANNING, CANCELLED, FAILED)))
        assertTrue(allowed.size == 3)
    }

    @Test fun `allowedFrom returns empty for terminal states`() {
        assertTrue(RecoveryStateMachine.allowedFrom(DONE).isEmpty())
        assertTrue(RecoveryStateMachine.allowedFrom(FAILED).isEmpty())
        assertTrue(RecoveryStateMachine.allowedFrom(CANCELLED).isEmpty())
    }

    // --- State properties ---

    @Test fun `terminal states are correctly identified`() {
        assertTrue(DONE.isTerminal)
        assertTrue(FAILED.isTerminal)
        assertTrue(CANCELLED.isTerminal)

        assertFalse(REQUESTED.isTerminal)
        assertFalse(PLANNING.isTerminal)
        assertFalse(AWAITING_USER_CONFIRM.isTerminal)
        assertFalse(THAWING.isTerminal)
        assertFalse(READY.isTerminal)
        assertFalse(AGENT_RESTORING.isTerminal)
        assertFalse(VERIFYING.isTerminal)
    }

    @Test fun `active states are correctly identified`() {
        assertTrue(REQUESTED.isActive)
        assertTrue(PLANNING.isActive)
        assertTrue(AGENT_RESTORING.isActive)

        assertFalse(DONE.isActive)
        assertFalse(FAILED.isActive)
        assertFalse(CANCELLED.isActive)
    }

    @Test fun `exhaustive illegal transition check - all non-legal pairs rejected`() {
        var illegalCount = 0
        for (from in RecoveryState.entries) {
            for (to in RecoveryState.entries) {
                if (!RecoveryStateMachine.isLegal(from, to)) {
                    illegalCount++
                }
            }
        }
        assertTrue(illegalCount >= 60, "Expected at least 60 illegal transitions, got $illegalCount")
    }

    private fun assertLegal(from: RecoveryState, to: RecoveryState) {
        assertTrue(RecoveryStateMachine.isLegal(from, to), "$from → $to should be legal")
    }

    private fun assertIllegal(from: RecoveryState, to: RecoveryState) {
        assertFalse(RecoveryStateMachine.isLegal(from, to), "$from → $to should be illegal")
    }
}
