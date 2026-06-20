package pl.danielniemiec.properbackup.buffer.ovh

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import pl.danielniemiec.properbackup.buffer.db.Database
import java.math.BigDecimal
import java.time.LocalDate

@Testcontainers
class OvhCostTrackerTest {

    @Container
    private val postgres = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("properbackup_test")
        .withUsername("test")
        .withPassword("test")

    private lateinit var db: Database
    private lateinit var tracker: OvhCostTracker

    private val userId = "cost-test-user"
    private val serverId = "cost-test-server"

    @BeforeEach
    fun setUp() {
        db = Database(postgres.jdbcUrl, postgres.username, postgres.password)
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO users (id, email, bcrypt_hash) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"
            ).use { ps ->
                ps.setString(1, userId)
                ps.setString(2, "cost-test@example.com")
                ps.setString(3, "\$2a\$10\$fakehashjustfortesting000000000000000000000000000000")
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO servers (id, name, user_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"
            ).use { ps ->
                ps.setString(1, serverId)
                ps.setString(2, "Cost Test Server")
                ps.setString(3, userId)
                ps.executeUpdate()
            }
        }
        tracker = OvhCostTracker(db)
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    @Test
    fun `computeStorageCost for 1 GiB`() {
        val oneGib = 1024L * 1024 * 1024
        val cost = tracker.computeStorageCost(oneGib)
        assertEquals(0, cost.compareTo(OvhCostTracker.STORAGE_PRICE_PLN_PER_GIB_MONTH))
    }

    @Test
    fun `computeUploadCost for 1 GiB`() {
        val oneGib = 1024L * 1024 * 1024
        val cost = tracker.computeUploadCost(oneGib)
        assertEquals(0, cost.compareTo(OvhCostTracker.UPLOAD_PRICE_PLN_PER_GIB))
    }

    @Test
    fun `computeStorageCost for 0 bytes is zero`() {
        val cost = tracker.computeStorageCost(0)
        assertEquals(0, cost.compareTo(BigDecimal.ZERO))
    }

    @Test
    fun `queryStoredBytes with no packs returns 0`() {
        val stored = tracker.queryStoredBytes(userId)
        assertEquals(0L, stored)
    }

    @Test
    fun `queryStoredBytes counts buffer_pack sizes for user`() {
        insertBufferPack(serverId, "pack-a.pack", 1000)
        insertBufferPack(serverId, "pack-b.pack", 2000)

        val stored = tracker.queryStoredBytes(userId)
        assertEquals(3000L, stored)
    }

    @Test
    fun `queryUploadedBytesToday counts only today packs`() {
        insertBufferPack(serverId, "pack-today.pack", 5000)

        val today = LocalDate.now()
        val uploaded = tracker.queryUploadedBytesToday(userId, today)
        assertEquals(5000L, uploaded)
    }

    @Test
    fun `upsertDaily inserts and getDailyCosts retrieves`() {
        val today = LocalDate.now()
        tracker.upsertDaily(
            userId, today,
            storedBytes = 10_000,
            uploadedBytes = 5_000,
            storageCost = BigDecimal("0.001"),
            uploadCost = BigDecimal("0.002"),
            totalCost = BigDecimal("0.003"),
        )

        val rows = tracker.getDailyCosts(userId, 7)
        assertEquals(1, rows.size)
        assertEquals(today, rows[0].day)
        assertEquals(10_000L, rows[0].storedBytes)
        assertEquals(5_000L, rows[0].uploadedBytes)
    }

    @Test
    fun `upsertDaily updates existing row on same day`() {
        val today = LocalDate.now()
        tracker.upsertDaily(userId, today, 100, 50, BigDecimal("0.01"), BigDecimal("0.02"), BigDecimal("0.03"))
        tracker.upsertDaily(userId, today, 200, 100, BigDecimal("0.04"), BigDecimal("0.05"), BigDecimal("0.09"))

        val rows = tracker.getDailyCosts(userId, 7)
        assertEquals(1, rows.size)
        assertEquals(200L, rows[0].storedBytes)
    }

    @Test
    fun `runDailyAggregation completes without error`() {
        insertBufferPack(serverId, "pack-agg.pack", 1024 * 1024)
        assertDoesNotThrow { tracker.runDailyAggregation() }

        val rows = tracker.getDailyCosts(userId, 7)
        assertTrue(rows.isNotEmpty())
        assertTrue(rows[0].storedBytes > 0)
    }

    @Test
    fun `getDailyCosts returns empty for user with no data`() {
        val rows = tracker.getDailyCosts("nonexistent-user", 30)
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `spike alert threshold is 50 GB`() {
        assertEquals(50L * 1024 * 1024 * 1024, OvhCostTracker.SPIKE_ALERT_THRESHOLD_BYTES)
    }

    private fun insertBufferPack(serverId: String, packName: String, sizeBytes: Long) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO buffer_pack (server_id, pack_name, size_bytes) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"
            ).use { ps ->
                ps.setString(1, serverId)
                ps.setString(2, packName)
                ps.setLong(3, sizeBytes)
                ps.executeUpdate()
            }
        }
    }
}
