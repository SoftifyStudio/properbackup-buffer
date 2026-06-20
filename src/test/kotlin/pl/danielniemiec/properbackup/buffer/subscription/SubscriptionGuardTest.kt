package pl.danielniemiec.properbackup.buffer.subscription

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import pl.danielniemiec.properbackup.buffer.auth.UserStore
import pl.danielniemiec.properbackup.buffer.subscription.SubscriptionGuard.AccessState
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Integration tests for SubscriptionGuard state machine.
 * Per master-tdd-plan.md §5.2/§5.3 [TDD-H1].
 * Tests cover all access states in the canonical FSM.
 */
class SubscriptionGuardTest {

    private fun buildUser(
        subscriptionPlan: String? = null,
        subscriptionExpiresAt: String? = null,
        trialExpiresAt: String? = null,
        subscriptionPaymentStatus: String? = null,
        cancelAtPeriodEnd: Boolean = false,
        everSubscribed: Boolean = false,
    ): UserStore.User = UserStore.User(
        id = "test-user-001",
        email = "test@properbackup.dev",
        bcryptHash = "hash",
        createdAt = Instant.now().toString(),
        isSystemAdmin = false,
        trialExpiresAt = trialExpiresAt,
        subscriptionPlan = subscriptionPlan,
        subscriptionExpiresAt = subscriptionExpiresAt,
        storageLimitBytes = 2199023255552L,
        lastTrialNotification = null,
        subscriptionPaymentStatus = subscriptionPaymentStatus,
        cancelAtPeriodEnd = cancelAtPeriodEnd,
        everSubscribed = everSubscribed,
    )

    @Test
    fun `NONE state - no trial, no subscription`() {
        val user = buildUser()
        val result = SubscriptionGuard.computeAccess(user)
        assertEquals(AccessState.NONE, result.state)
        assertFalse(result.canUpload)
        assertTrue(result.canRestore, "Anti-hostage: canRestore must always be true")
    }

    @Test
    fun `TRIALING state - trial active`() {
        val user = buildUser(trialExpiresAt = Instant.now().plus(15, ChronoUnit.DAYS).toString())
        val result = SubscriptionGuard.computeAccess(user)
        assertEquals(AccessState.TRIALING, result.state)
        assertTrue(result.canUpload)
        assertTrue(result.canRestore)
    }

    @Test
    fun `ACTIVE state - paid subscription`() {
        val user = buildUser(
            subscriptionPlan = "monthly",
            subscriptionExpiresAt = Instant.now().plus(30, ChronoUnit.DAYS).toString(),
        )
        val result = SubscriptionGuard.computeAccess(user)
        assertEquals(AccessState.ACTIVE, result.state)
        assertTrue(result.canUpload)
        assertTrue(result.canRestore)
    }

    @Test
    fun `ACTIVE_CANCELLED state - active but will not renew`() {
        val user = buildUser(
            subscriptionPlan = "monthly",
            subscriptionExpiresAt = Instant.now().plus(10, ChronoUnit.DAYS).toString(),
            cancelAtPeriodEnd = true,
        )
        val result = SubscriptionGuard.computeAccess(user)
        assertEquals(AccessState.ACTIVE_CANCELLED, result.state)
        assertTrue(result.canUpload)
        assertTrue(result.canRestore)
    }

    @Test
    fun `PAST_DUE_GRACE state - payment failed, Stripe retrying`() {
        val user = buildUser(
            subscriptionPlan = "monthly",
            subscriptionExpiresAt = Instant.now().plus(30, ChronoUnit.DAYS).toString(),
            subscriptionPaymentStatus = "past_due",
        )
        val result = SubscriptionGuard.computeAccess(user)
        assertEquals(AccessState.PAST_DUE_GRACE, result.state)
        assertTrue(result.canUpload, "Grace period: upload still allowed")
        assertTrue(result.canRestore)
    }

    @Test
    fun `PAST_DUE_SUSPENDED state - Stripe gave up, unpaid`() {
        val user = buildUser(
            subscriptionPlan = "monthly",
            subscriptionExpiresAt = Instant.now().plus(30, ChronoUnit.DAYS).toString(),
            subscriptionPaymentStatus = "unpaid",
        )
        val result = SubscriptionGuard.computeAccess(user)
        assertEquals(AccessState.PAST_DUE_SUSPENDED, result.state)
        assertFalse(result.canUpload, "Suspended: upload blocked")
        assertTrue(result.canRestore, "Anti-hostage: canRestore must always be true")
    }

    @Test
    fun `EXPIRED state - subscription ended`() {
        val user = buildUser(
            subscriptionExpiresAt = Instant.now().minus(5, ChronoUnit.DAYS).toString(),
            everSubscribed = true,
        )
        val result = SubscriptionGuard.computeAccess(user)
        assertEquals(AccessState.EXPIRED, result.state)
        assertFalse(result.canUpload)
        assertTrue(result.canRestore, "Anti-hostage: canRestore must always be true")
    }

    @Test
    fun `EXPIRED state - trial expired with no subscription`() {
        val user = buildUser(
            trialExpiresAt = Instant.now().minus(5, ChronoUnit.DAYS).toString(),
            subscriptionExpiresAt = Instant.now().minus(1, ChronoUnit.DAYS).toString(),
            everSubscribed = true,
        )
        val result = SubscriptionGuard.computeAccess(user)
        assertEquals(AccessState.EXPIRED, result.state)
        assertFalse(result.canUpload)
        assertTrue(result.canRestore)
    }

    @Test
    fun `canRestore is ALWAYS true regardless of state (anti-hostage policy)`() {
        val states = listOf(
            buildUser(),  // NONE
            buildUser(trialExpiresAt = Instant.now().plus(15, ChronoUnit.DAYS).toString()),  // TRIALING
            buildUser(subscriptionPlan = "monthly", subscriptionExpiresAt = Instant.now().plus(30, ChronoUnit.DAYS).toString()),  // ACTIVE
            buildUser(subscriptionPlan = "monthly", subscriptionExpiresAt = Instant.now().plus(30, ChronoUnit.DAYS).toString(), subscriptionPaymentStatus = "unpaid"),  // PAST_DUE_SUSPENDED
            buildUser(subscriptionExpiresAt = Instant.now().minus(5, ChronoUnit.DAYS).toString(), everSubscribed = true),  // EXPIRED
        )
        for (user in states) {
            val result = SubscriptionGuard.computeAccess(user)
            assertTrue(result.canRestore, "Anti-hostage: canRestore must be true in state ${result.state}")
        }
    }

    @Test
    fun `past_due_grace allows upload, past_due_suspended blocks upload`() {
        val grace = buildUser(
            subscriptionPlan = "monthly",
            subscriptionExpiresAt = Instant.now().plus(30, ChronoUnit.DAYS).toString(),
            subscriptionPaymentStatus = "past_due",
        )
        val suspended = buildUser(
            subscriptionPlan = "monthly",
            subscriptionExpiresAt = Instant.now().plus(30, ChronoUnit.DAYS).toString(),
            subscriptionPaymentStatus = "unpaid",
        )
        assertTrue(SubscriptionGuard.computeAccess(grace).canUpload)
        assertFalse(SubscriptionGuard.computeAccess(suspended).canUpload)
    }

    @Test
    fun `recovery from past_due to active on invoice paid`() {
        val pastDue = buildUser(
            subscriptionPlan = "monthly",
            subscriptionExpiresAt = Instant.now().plus(30, ChronoUnit.DAYS).toString(),
            subscriptionPaymentStatus = "past_due",
        )
        assertEquals(AccessState.PAST_DUE_GRACE, SubscriptionGuard.computeAccess(pastDue).state)

        // After invoice.paid clears payment_status
        val recovered = pastDue.copy(subscriptionPaymentStatus = null)
        assertEquals(AccessState.ACTIVE, SubscriptionGuard.computeAccess(recovered).state)
        assertTrue(SubscriptionGuard.computeAccess(recovered).canUpload)
    }
}
