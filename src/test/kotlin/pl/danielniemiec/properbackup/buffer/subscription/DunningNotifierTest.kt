package pl.danielniemiec.properbackup.buffer.subscription

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import pl.danielniemiec.properbackup.buffer.auth.UserStore
import pl.danielniemiec.properbackup.buffer.db.Database
import pl.danielniemiec.properbackup.buffer.payment.SubscriptionAuditLog
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/**
 * Integration tests for DunningNotifier.
 * Per master-tdd-plan.md §9.4 [TDD-H1].
 * Tests the email escalation sequence: T+0, T+3, T+14, final_cancellation.
 */
@Testcontainers
class DunningNotifierTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine").apply {
            withDatabaseName("properbackup_test")
            withUsername("test")
            withPassword("test")
        }
    }

    private lateinit var db: Database
    private lateinit var userStore: UserStore
    private lateinit var auditLog: SubscriptionAuditLog
    private lateinit var mailer: DunningNotifier.LoggingMailer
    private lateinit var notifier: DunningNotifier

    @BeforeEach
    fun setUp() {
        db = Database(postgres.jdbcUrl, postgres.username, postgres.password)
        db.getConnection().use { conn ->
            val schema = this::class.java.classLoader.getResource("schema.sql")!!.readText()
            conn.createStatement().execute(schema)
            conn.createStatement().execute("DELETE FROM subscription_audit_log")
            conn.createStatement().execute("DELETE FROM users")
        }
        userStore = UserStore(db)
        auditLog = SubscriptionAuditLog(db)
        mailer = DunningNotifier.LoggingMailer()
        notifier = DunningNotifier(db, userStore, auditLog, mailer)
    }

    private fun createPastDueUser(daysAgo: Long): String {
        val user = userStore.register("dunning-test@properbackup.dev", "TestPass123!")
        val pastDueStart = Instant.now().minus(Duration.ofDays(daysAgo))
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """UPDATE users SET 
                   subscription_plan = 'monthly',
                   subscription_expires_at = NOW() + INTERVAL '30 days',
                   subscription_payment_status = 'past_due',
                   past_due_started_at = ?,
                   ever_subscribed = TRUE
                   WHERE id = ?"""
            ).use { ps ->
                ps.setTimestamp(1, Timestamp.from(pastDueStart))
                ps.setString(2, user.id)
                ps.executeUpdate()
            }
        }
        return user.id
    }

    @Test
    fun `T0 email sent immediately on payment failure`() {
        val user = userStore.register("t0-test@properbackup.dev", "TestPass123!")
        notifier.sendImmediateT0(user.id, user.email)

        assertEquals(1, mailer.sentEmails.size)
        assertEquals("t0-test@properbackup.dev", mailer.sentEmails[0].first)
        assertTrue(mailer.sentEmails[0].second.contains("Problem z platnoscia"))

        // Verify idempotency marker set
        val updated = userStore.findById(user.id).get()
        assertEquals("past_due_t0", updated.lastPastDueNotification)
    }

    @Test
    fun `T3 email sent after 3 days past due`() {
        val userId = createPastDueUser(daysAgo = 4)

        notifier.runDailyCheck()

        assertEquals(1, mailer.sentEmails.size)
        assertTrue(mailer.sentEmails[0].second.contains("Ponawiamy probe"))

        val updated = userStore.findById(userId).get()
        assertEquals("past_due_t3", updated.lastPastDueNotification)
    }

    @Test
    fun `T14 email sent after 14 days past due`() {
        val userId = createPastDueUser(daysAgo = 15)

        notifier.runDailyCheck()

        assertEquals(1, mailer.sentEmails.size)
        assertTrue(mailer.sentEmails[0].second.contains("Ostatnie wezwanie"))

        val updated = userStore.findById(userId).get()
        assertEquals("past_due_t14", updated.lastPastDueNotification)
    }

    @Test
    fun `idempotency - same marker not sent twice`() {
        val userId = createPastDueUser(daysAgo = 4)

        // First run — sends T3
        notifier.runDailyCheck()
        assertEquals(1, mailer.sentEmails.size)

        // Second run — should NOT send again (same marker)
        notifier.runDailyCheck()
        assertEquals(1, mailer.sentEmails.size, "Should not send duplicate email")
    }

    @Test
    fun `escalation from T0 to T3 when time progresses`() {
        val userId = createPastDueUser(daysAgo = 1)

        // Mark as T0 already sent
        userStore.updateLastPastDueNotification(userId, "past_due_t0")

        // At day 1, shouldn't escalate yet
        notifier.runDailyCheck(Instant.now())
        assertEquals(0, mailer.sentEmails.size, "Should not escalate at T+1")

        // Move the past_due_started_at back to 4 days ago to simulate time passing
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE users SET past_due_started_at = NOW() - INTERVAL '4 days' WHERE id = ?"
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeUpdate()
            }
        }
        notifier.runDailyCheck()
        assertEquals(1, mailer.sentEmails.size)
        assertTrue(mailer.sentEmails[0].second.contains("Ponawiamy probe"))
    }

    @Test
    fun `final cancellation email sent`() {
        val user = userStore.register("final-test@properbackup.dev", "TestPass123!")
        notifier.sendFinalCancellation(user.id, user.email)

        assertEquals(1, mailer.sentEmails.size)
        assertTrue(mailer.sentEmails[0].second.contains("zamknieta"))

        val updated = userStore.findById(user.id).get()
        assertEquals("final_cancellation", updated.lastPastDueNotification)
    }

    @Test
    fun `audit log entries created for each dunning email`() {
        val user = userStore.register("audit-test@properbackup.dev", "TestPass123!")
        notifier.sendImmediateT0(user.id, user.email)

        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT action, detail FROM subscription_audit_log WHERE user_id = ?"
            ).use { ps ->
                ps.setString(1, user.id)
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals("dunning_email_sent", rs.getString("action"))
                    assertTrue(rs.getString("detail").contains("past_due_t0"))
                }
            }
        }
    }

    @Test
    fun `only past_due users are selected for notification`() {
        // Create a healthy user (should not be notified)
        val healthy = userStore.register("healthy@properbackup.dev", "TestPass123!")
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE users SET subscription_plan = 'monthly', subscription_expires_at = NOW() + INTERVAL '30 days' WHERE id = ?"
            ).use { ps ->
                ps.setString(1, healthy.id)
                ps.executeUpdate()
            }
        }

        // Create a past_due user
        createPastDueUser(daysAgo = 4)

        notifier.runDailyCheck()

        // Only 1 email sent (to past_due user)
        assertEquals(1, mailer.sentEmails.size)
        assertEquals("dunning-test@properbackup.dev", mailer.sentEmails[0].first)
    }
}
