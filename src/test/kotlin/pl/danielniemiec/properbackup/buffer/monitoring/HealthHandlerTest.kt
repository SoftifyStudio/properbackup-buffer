package pl.danielniemiec.properbackup.buffer.monitoring

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import pl.danielniemiec.properbackup.buffer.db.Database
import io.javalin.Javalin
import io.javalin.testtools.JavalinTest
import java.nio.file.Path

@Testcontainers
class HealthHandlerTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine").apply {
            withDatabaseName("properbackup_test")
            withUsername("test")
            withPassword("test")
        }
    }

    @Test
    fun `health returns 200 healthy when all checks pass`(@TempDir tempDir: Path) {
        val db = Database(postgres.jdbcUrl, postgres.username, postgres.password)
        val handler = HealthHandler(db, tempDir.toString())
        handler.markReady()

        val app = Javalin.create()
        app.get("/health", handler::health)

        JavalinTest.test(app) { _, client ->
            val response = client.get("/health")
            assertEquals(200, response.code)
            val body = response.body!!.string()
            assertTrue(body.contains("healthy"))
        }

        db.close()
    }

    @Test
    fun `live always returns 200`(@TempDir tempDir: Path) {
        val db = Database(postgres.jdbcUrl, postgres.username, postgres.password)
        val handler = HealthHandler(db, tempDir.toString())

        val app = Javalin.create()
        app.get("/live", handler::live)

        JavalinTest.test(app) { _, client ->
            val response = client.get("/live")
            assertEquals(200, response.code)
            assertEquals("OK", response.body!!.string())
        }

        db.close()
    }

    @Test
    fun `ready returns 503 before markReady`(@TempDir tempDir: Path) {
        val db = Database(postgres.jdbcUrl, postgres.username, postgres.password)
        val handler = HealthHandler(db, tempDir.toString())
        // NOT calling markReady()

        val app = Javalin.create()
        app.get("/ready", handler::ready)

        JavalinTest.test(app) { _, client ->
            val response = client.get("/ready")
            assertEquals(503, response.code)
        }

        db.close()
    }

    @Test
    fun `ready returns 200 after markReady`(@TempDir tempDir: Path) {
        val db = Database(postgres.jdbcUrl, postgres.username, postgres.password)
        val handler = HealthHandler(db, tempDir.toString())
        handler.markReady()

        val app = Javalin.create()
        app.get("/ready", handler::ready)

        JavalinTest.test(app) { _, client ->
            val response = client.get("/ready")
            assertEquals(200, response.code)
            assertEquals("OK", response.body!!.string())
        }

        db.close()
    }

    @Test
    fun `health detailed includes check details`(@TempDir tempDir: Path) {
        val db = Database(postgres.jdbcUrl, postgres.username, postgres.password)
        val handler = HealthHandler(db, tempDir.toString())
        handler.markReady()

        val app = Javalin.create()
        app.get("/health/detailed", handler::healthDetailed)

        JavalinTest.test(app) { _, client ->
            val response = client.get("/health/detailed")
            assertEquals(200, response.code)
            val body = response.body!!.string()
            assertTrue(body.contains("postgres"))
            assertTrue(body.contains("disk"))
            assertTrue(body.contains("uptime_seconds"))
        }

        db.close()
    }
}
