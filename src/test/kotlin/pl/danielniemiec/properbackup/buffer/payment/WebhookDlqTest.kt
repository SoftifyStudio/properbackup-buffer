package pl.danielniemiec.properbackup.buffer.payment

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import pl.danielniemiec.properbackup.buffer.db.Database

@Testcontainers
class WebhookDlqTest {

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
    private lateinit var dlq: WebhookDlq

    @BeforeEach
    fun setUp() {
        db = Database(postgres.jdbcUrl, postgres.username, postgres.password)
        db.getConnection().use { conn ->
            conn.createStatement().execute("DELETE FROM stripe_webhook_dlq")
        }
        dlq = WebhookDlq(db)
    }

    @Test
    fun `enqueue stores failed webhook event`() {
        dlq.enqueue(
            eventId = "evt_test_123",
            eventType = "checkout.session.completed",
            payload = """{"id":"evt_test_123"}""",
            signatureHeader = "t=123,v1=abc",
            error = RuntimeException("DB connection refused"),
        )

        val entries = dlq.listUnresolved()
        assertEquals(1, entries.size)
        assertEquals("evt_test_123", entries[0].stripeEventId)
        assertEquals("checkout.session.completed", entries[0].eventType)
        assertEquals(0, entries[0].retryCount)
        assertNotNull(entries[0].lastError)
        assertNull(entries[0].resolvedAt)
    }

    @Test
    fun `enqueue increments retry count on duplicate event`() {
        val eventId = "evt_test_retry"
        val payload = """{"id":"$eventId"}"""

        dlq.enqueue(eventId, "invoice.paid", payload, "sig1", RuntimeException("fail 1"))
        dlq.enqueue(eventId, "invoice.paid", payload, "sig1", RuntimeException("fail 2"))
        dlq.enqueue(eventId, "invoice.paid", payload, "sig1", RuntimeException("fail 3"))

        val entries = dlq.listUnresolved()
        assertEquals(1, entries.size)
        assertEquals(2, entries[0].retryCount) // 0 + 2 increments
        assertTrue(entries[0].lastError!!.contains("fail 3"))
    }

    @Test
    fun `markResolved removes from unresolved list`() {
        dlq.enqueue("evt_resolved", "test.event", "{}", "sig", RuntimeException("x"))

        val entries = dlq.listUnresolved()
        assertEquals(1, entries.size)

        dlq.markResolved(entries[0].id)

        val remaining = dlq.listUnresolved()
        assertEquals(0, remaining.size)

        // But still retrievable by ID
        val resolved = dlq.getById(entries[0].id)
        assertNotNull(resolved)
        assertNotNull(resolved!!.resolvedAt)
    }

    @Test
    fun `cleanupResolved deletes old resolved entries`() {
        dlq.enqueue("evt_old", "test", "{}", "sig", RuntimeException("x"))
        val entries = dlq.listUnresolved()
        dlq.markResolved(entries[0].id)

        // Manually set resolved_at to 31 days ago
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE stripe_webhook_dlq SET resolved_at = NOW() - INTERVAL '31 days' WHERE id = ?"
            ).use { stmt ->
                stmt.setLong(1, entries[0].id)
                stmt.executeUpdate()
            }
        }

        val deleted = dlq.cleanupResolved(30)
        assertEquals(1, deleted)
    }

    @Test
    fun `listUnresolved respects limit`() {
        repeat(5) { i ->
            dlq.enqueue("evt_limit_$i", "test", "{}", "sig", RuntimeException("x"))
        }

        val limited = dlq.listUnresolved(limit = 3)
        assertEquals(3, limited.size)
    }
}
