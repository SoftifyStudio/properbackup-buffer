package pl.danielniemiec.properbackup.buffer.flush

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import pl.danielniemiec.properbackup.buffer.db.Database

@Testcontainers
class BudgetGuardIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("properbackup_test")
            .withUsername("test")
            .withPassword("test")

        private lateinit var db: Database

        @BeforeAll
        @JvmStatic
        fun setupDb() {
            db = Database(postgres.jdbcUrl, postgres.username, postgres.password)
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            db.close()
        }
    }

    @BeforeEach
    fun cleanBudgetTable() {
        db.getConnection().use { conn ->
            conn.createStatement().execute("DELETE FROM flush_budget")
        }
    }

    @Test
    fun `tryConsume returns true when budget available`() {
        val guard = BudgetGuard(db, maxFlushesPerDay = 3)
        assertTrue(guard.tryConsume("U1", "P1"))
    }

    @Test
    fun `tryConsume blocks after exceeding daily limit`() {
        val guard = BudgetGuard(db, maxFlushesPerDay = 2)
        assertTrue(guard.tryConsume("U1", "P1"))
        assertTrue(guard.tryConsume("U1", "P1"))
        assertFalse(guard.tryConsume("U1", "P1"))
    }

    @Test
    fun `remaining returns correct count`() {
        val guard = BudgetGuard(db, maxFlushesPerDay = 3)
        assertEquals(3, guard.remaining("U1", "P1"))
        guard.tryConsume("U1", "P1")
        assertEquals(2, guard.remaining("U1", "P1"))
        guard.tryConsume("U1", "P1")
        assertEquals(1, guard.remaining("U1", "P1"))
        guard.tryConsume("U1", "P1")
        assertEquals(0, guard.remaining("U1", "P1"))
    }

    @Test
    fun `different users have independent budgets`() {
        val guard = BudgetGuard(db, maxFlushesPerDay = 1)
        assertTrue(guard.tryConsume("U1", "P1"))
        assertFalse(guard.tryConsume("U1", "P1"))
        assertTrue(guard.tryConsume("U2", "P1"))
    }

    @Test
    fun `different paths have independent budgets`() {
        val guard = BudgetGuard(db, maxFlushesPerDay = 1)
        assertTrue(guard.tryConsume("U1", "P1"))
        assertTrue(guard.tryConsume("U1", "P2"))
        assertFalse(guard.tryConsume("U1", "P1"))
    }

    @Test
    fun `remaining returns zero when budget exhausted`() {
        val guard = BudgetGuard(db, maxFlushesPerDay = 1)
        guard.tryConsume("U1", "P1")
        assertEquals(0, guard.remaining("U1", "P1"))
    }

    @Test
    fun `remaining never goes negative`() {
        val guard = BudgetGuard(db, maxFlushesPerDay = 1)
        guard.tryConsume("U1", "P1")
        guard.tryConsume("U1", "P1") // rejected, count stays at 1
        assertEquals(0, guard.remaining("U1", "P1"))
    }
}
