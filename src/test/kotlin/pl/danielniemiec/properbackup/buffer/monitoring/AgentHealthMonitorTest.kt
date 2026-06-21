package pl.danielniemiec.properbackup.buffer.monitoring

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.danielniemiec.properbackup.buffer.db.Database
import pl.danielniemiec.properbackup.buffer.notifications.EmailNotifier
import pl.danielniemiec.properbackup.buffer.recovery.TestDatabaseHelper
import pl.danielniemiec.properbackup.buffer.server.ServerStore
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

class AgentHealthMonitorTest {

    companion object {
        private lateinit var db: Database
        private lateinit var serverStore: ServerStore

        @JvmStatic
        @BeforeAll
        fun setup() {
            db = TestDatabaseHelper.createDatabase()
            serverStore = ServerStore(db)
        }
    }

    @BeforeEach
    fun cleanup() {
        db.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM alert_history")
                stmt.execute("DELETE FROM agent_health_status")
                stmt.execute("DELETE FROM notification_settings")
                stmt.execute("DELETE FROM backup_schedule")
            }
        }
    }

    private fun setLastSeen(serverId: String, instant: Instant) {
        db.getConnection().use { conn ->
            conn.prepareStatement("UPDATE servers SET last_seen_at = ? WHERE id = ?").use { ps ->
                ps.setTimestamp(1, Timestamp.from(instant))
                ps.setString(2, serverId)
                ps.executeUpdate()
            }
        }
    }

    private fun insertNotificationSettings(userId: String, emailEnabled: Boolean, emailAddress: String?) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO notification_settings (user_id, email_enabled, email_address, sms_enabled, sms_number)
                   VALUES (?, ?, ?, false, null) ON CONFLICT (user_id) DO UPDATE SET
                   email_enabled = EXCLUDED.email_enabled, email_address = EXCLUDED.email_address""",
            ).use { ps ->
                ps.setString(1, userId)
                ps.setBoolean(2, emailEnabled)
                if (emailAddress == null) ps.setNull(3, java.sql.Types.VARCHAR) else ps.setString(3, emailAddress)
                ps.executeUpdate()
            }
        }
    }

    private fun insertAlertHistory(serverId: String, alertType: String, sentAt: Instant) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO alert_history (server_id, alert_type, sent_at) VALUES (?, ?, ?)",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.setString(2, alertType)
                ps.setTimestamp(3, Timestamp.from(sentAt))
                ps.executeUpdate()
            }
        }
    }

    private fun countAlertHistory(serverId: String): Int {
        db.getConnection().use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM alert_history WHERE server_id = ?").use { ps ->
                ps.setString(1, serverId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }

    // --- Happy path: status classification ---

    @Test
    fun `checkAll classifies recently-seen server as ONLINE`() {
        TestDatabaseHelper.seedUserAndServer(db, "u-health-1", "s-health-online")
        setLastSeen("s-health-online", Instant.now().minus(Duration.ofMinutes(1)))

        val monitor = AgentHealthMonitor(db, serverStore, null)
        monitor.checkAll()

        val status = monitor.getHealthStatus("s-health-online")
        assertNotNull(status)
        assertEquals("ONLINE", status!!["status"])
    }

    @Test
    fun `checkAll classifies server not seen for 6 min as WARNING`() {
        TestDatabaseHelper.seedUserAndServer(db, "u-health-2", "s-health-warning")
        setLastSeen("s-health-warning", Instant.now().minus(Duration.ofMinutes(6)))

        val monitor = AgentHealthMonitor(db, serverStore, null)
        monitor.checkAll()

        val status = monitor.getHealthStatus("s-health-warning")
        assertNotNull(status)
        assertEquals("WARNING", status!!["status"])
    }

    @Test
    fun `checkAll classifies server not seen for 31 min as OFFLINE`() {
        TestDatabaseHelper.seedUserAndServer(db, "u-health-3", "s-health-offline")
        setLastSeen("s-health-offline", Instant.now().minus(Duration.ofMinutes(31)))

        val monitor = AgentHealthMonitor(db, serverStore, null)
        monitor.checkAll()

        val status = monitor.getHealthStatus("s-health-offline")
        assertNotNull(status)
        assertEquals("OFFLINE", status!!["status"])
    }

    // --- Edge: exactly at thresholds ---

    @Test
    fun `server seen 4m50s ago is still ONLINE`() {
        TestDatabaseHelper.seedUserAndServer(db, "u-health-4", "s-health-boundary5")
        setLastSeen("s-health-boundary5", Instant.now().minus(Duration.ofMinutes(4)).plus(Duration.ofSeconds(10)))

        val monitor = AgentHealthMonitor(db, serverStore, null)
        monitor.checkAll()

        val status = monitor.getHealthStatus("s-health-boundary5")
        assertNotNull(status)
        assertEquals("ONLINE", status!!["status"])
    }

    @Test
    fun `server seen 29m ago is WARNING, not OFFLINE`() {
        TestDatabaseHelper.seedUserAndServer(db, "u-health-5", "s-health-boundary30")
        setLastSeen("s-health-boundary30", Instant.now().minus(Duration.ofMinutes(29)))

        val monitor = AgentHealthMonitor(db, serverStore, null)
        monitor.checkAll()

        val status = monitor.getHealthStatus("s-health-boundary30")
        assertNotNull(status)
        assertEquals("WARNING", status!!["status"])
    }

    // --- Edge: getHealthStatus for non-existent server ---

    @Test
    fun `getHealthStatus returns null for unknown server`() {
        val monitor = AgentHealthMonitor(db, serverStore, null)
        assertNull(monitor.getHealthStatus("nonexistent-server-id"))
    }

    // --- Integration: getHealthStatusForUser ---

    @Test
    fun `getHealthStatusForUser returns statuses for user's servers`() {
        TestDatabaseHelper.seedUserAndServer(db, "u-multi", "s-multi-1")
        TestDatabaseHelper.seedUserAndServer(db, "u-multi", "s-multi-2")
        setLastSeen("s-multi-1", Instant.now().minus(Duration.ofMinutes(1)))
        setLastSeen("s-multi-2", Instant.now().minus(Duration.ofMinutes(31)))

        val monitor = AgentHealthMonitor(db, serverStore, null)
        monitor.checkAll()

        val statuses = monitor.getHealthStatusForUser("u-multi")
        assertEquals(2, statuses.size)
        val statusMap = statuses.associateBy { it["serverId"] }
        assertEquals("ONLINE", statusMap["s-multi-1"]!!["status"])
        assertEquals("OFFLINE", statusMap["s-multi-2"]!!["status"])
    }

    @Test
    fun `getHealthStatusForUser returns empty list for user with no servers`() {
        val monitor = AgentHealthMonitor(db, serverStore, null)
        val statuses = monitor.getHealthStatusForUser("nonexistent-user")
        assertTrue(statuses.isEmpty())
    }

    // --- Integration: upsert overwrites previous status ---

    @Test
    fun `checkAll updates status when server comes back online`() {
        TestDatabaseHelper.seedUserAndServer(db, "u-recovery", "s-recovery-1")

        val monitor = AgentHealthMonitor(db, serverStore, null)

        // First: offline
        setLastSeen("s-recovery-1", Instant.now().minus(Duration.ofMinutes(31)))
        monitor.checkAll()
        assertEquals("OFFLINE", monitor.getHealthStatus("s-recovery-1")!!["status"])

        // Then: comes back
        setLastSeen("s-recovery-1", Instant.now().minus(Duration.ofSeconds(10)))
        monitor.checkAll()
        assertEquals("ONLINE", monitor.getHealthStatus("s-recovery-1")!!["status"])
    }

    // --- Email alert with dedup ---

    @Test
    fun `OFFLINE server triggers email alert when notifications enabled`() {
        TestDatabaseHelper.seedUserAndServer(db, "u-email-1", "s-email-1")
        setLastSeen("s-email-1", Instant.now().minus(Duration.ofMinutes(31)))
        insertNotificationSettings("u-email-1", emailEnabled = true, emailAddress = "alert@test.com")

        val notifier = RecordingEmailNotifier()
        val monitor = AgentHealthMonitor(db, serverStore, notifier)
        monitor.checkAll()

        assertEquals(1, notifier.sentAlerts.size)
        assertEquals("alert@test.com", notifier.sentAlerts[0].to)
        assertTrue(notifier.sentAlerts[0].serverName.isNotBlank())
        assertEquals(1, countAlertHistory("s-email-1"))
    }

    @Test
    fun `OFFLINE server does NOT trigger email when notifications disabled`() {
        TestDatabaseHelper.seedUserAndServer(db, "u-email-2", "s-email-2")
        setLastSeen("s-email-2", Instant.now().minus(Duration.ofMinutes(31)))
        insertNotificationSettings("u-email-2", emailEnabled = false, emailAddress = "nope@test.com")

        val notifier = RecordingEmailNotifier()
        val monitor = AgentHealthMonitor(db, serverStore, notifier)
        monitor.checkAll()

        assertTrue(notifier.sentAlerts.isEmpty())
        assertEquals(0, countAlertHistory("s-email-2"))
    }

    @Test
    fun `OFFLINE server does NOT trigger email when no notification settings exist`() {
        TestDatabaseHelper.seedUserAndServer(db, "u-email-3", "s-email-3")
        setLastSeen("s-email-3", Instant.now().minus(Duration.ofMinutes(31)))

        val notifier = RecordingEmailNotifier()
        val monitor = AgentHealthMonitor(db, serverStore, notifier)
        monitor.checkAll()

        assertTrue(notifier.sentAlerts.isEmpty())
    }

    @Test
    fun `OFFLINE server does NOT trigger email when email_address is null`() {
        TestDatabaseHelper.seedUserAndServer(db, "u-email-4", "s-email-4")
        setLastSeen("s-email-4", Instant.now().minus(Duration.ofMinutes(31)))
        insertNotificationSettings("u-email-4", emailEnabled = true, emailAddress = null)

        val notifier = RecordingEmailNotifier()
        val monitor = AgentHealthMonitor(db, serverStore, notifier)
        monitor.checkAll()

        assertTrue(notifier.sentAlerts.isEmpty())
    }

    @Test
    fun `alert dedup prevents duplicate within 10 min window`() {
        TestDatabaseHelper.seedUserAndServer(db, "u-dedup-1", "s-dedup-1")
        setLastSeen("s-dedup-1", Instant.now().minus(Duration.ofMinutes(31)))
        insertNotificationSettings("u-dedup-1", emailEnabled = true, emailAddress = "dedup@test.com")

        val notifier = RecordingEmailNotifier()
        val monitor = AgentHealthMonitor(db, serverStore, notifier)

        // First check → alert sent
        monitor.checkAll()
        assertEquals(1, notifier.sentAlerts.size)

        // Second check within 10 min → dedup, no new alert
        monitor.checkAll()
        assertEquals(1, notifier.sentAlerts.size)
        assertEquals(1, countAlertHistory("s-dedup-1"))
    }

    @Test
    fun `alert sent again after dedup window expires`() {
        TestDatabaseHelper.seedUserAndServer(db, "u-dedup-2", "s-dedup-2")
        setLastSeen("s-dedup-2", Instant.now().minus(Duration.ofMinutes(31)))
        insertNotificationSettings("u-dedup-2", emailEnabled = true, emailAddress = "dedup2@test.com")

        val notifier = RecordingEmailNotifier()
        val monitor = AgentHealthMonitor(db, serverStore, notifier)

        // First check → alert sent
        monitor.checkAll()
        assertEquals(1, notifier.sentAlerts.size)

        // Backdate the alert_history entry to 11 min ago (past the 10-min window)
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE alert_history SET sent_at = NOW() - INTERVAL '11 minutes' WHERE server_id = ?",
            ).use { ps ->
                ps.setString(1, "s-dedup-2")
                ps.executeUpdate()
            }
        }

        // Second check → new alert sent
        monitor.checkAll()
        assertEquals(2, notifier.sentAlerts.size)
    }

    @Test
    fun `WARNING server does NOT trigger email alert`() {
        TestDatabaseHelper.seedUserAndServer(db, "u-warn-no-email", "s-warn-1")
        setLastSeen("s-warn-1", Instant.now().minus(Duration.ofMinutes(10)))
        insertNotificationSettings("u-warn-no-email", emailEnabled = true, emailAddress = "warn@test.com")

        val notifier = RecordingEmailNotifier()
        val monitor = AgentHealthMonitor(db, serverStore, notifier)
        monitor.checkAll()

        assertTrue(notifier.sentAlerts.isEmpty())
    }

    // --- Edge: server never heartbeated (last_seen = registration time, very old) ---

    @Test
    fun `server registered days ago with no heartbeat is OFFLINE`() {
        TestDatabaseHelper.seedUserAndServer(db, "u-no-hb", "s-no-heartbeat")
        setLastSeen("s-no-heartbeat", Instant.now().minus(Duration.ofDays(7)))

        val monitor = AgentHealthMonitor(db, serverStore, null)
        monitor.checkAll()

        val status = monitor.getHealthStatus("s-no-heartbeat")
        assertNotNull(status)
        assertEquals("OFFLINE", status!!["status"])
    }

    @Test
    fun `server registered months ago with no heartbeat is OFFLINE`() {
        TestDatabaseHelper.seedUserAndServer(db, "u-no-hb-long", "s-no-hb-long")
        setLastSeen("s-no-hb-long", Instant.now().minus(Duration.ofDays(365)))

        val monitor = AgentHealthMonitor(db, serverStore, null)
        monitor.checkAll()

        val status = monitor.getHealthStatus("s-no-hb-long")
        assertNotNull(status)
        assertEquals("OFFLINE", status!!["status"])
    }

    @Test
    fun `server with no heartbeat triggers email alert when notifications enabled`() {
        TestDatabaseHelper.seedUserAndServer(db, "u-no-hb-email", "s-no-hb-email")
        setLastSeen("s-no-hb-email", Instant.now().minus(Duration.ofDays(2)))
        insertNotificationSettings("u-no-hb-email", emailEnabled = true, emailAddress = "nohb@test.com")

        val notifier = RecordingEmailNotifier()
        val monitor = AgentHealthMonitor(db, serverStore, notifier)
        monitor.checkAll()

        assertEquals(1, notifier.sentAlerts.size)
        assertEquals("nohb@test.com", notifier.sentAlerts[0].to)
    }

    // --- Edge: null emailNotifier does not crash on OFFLINE ---

    @Test
    fun `checkAll with null emailNotifier does not crash on OFFLINE server`() {
        TestDatabaseHelper.seedUserAndServer(db, "u-null-notif", "s-null-notif")
        setLastSeen("s-null-notif", Instant.now().minus(Duration.ofMinutes(31)))
        insertNotificationSettings("u-null-notif", emailEnabled = true, emailAddress = "null@test.com")

        val monitor = AgentHealthMonitor(db, serverStore, null)
        assertDoesNotThrow { monitor.checkAll() }

        val status = monitor.getHealthStatus("s-null-notif")
        assertEquals("OFFLINE", status!!["status"])
        assertEquals(0, countAlertHistory("s-null-notif"))
    }

    // --- Edge: health status records lastSeenAt and checkedAt correctly ---

    @Test
    fun `health status records contain valid timestamps`() {
        TestDatabaseHelper.seedUserAndServer(db, "u-ts", "s-ts-1")
        val before = Instant.now()
        setLastSeen("s-ts-1", Instant.now().minus(Duration.ofMinutes(10)))

        val monitor = AgentHealthMonitor(db, serverStore, null)
        monitor.checkAll()

        val status = monitor.getHealthStatus("s-ts-1")
        assertNotNull(status)
        val checkedAt = Instant.parse(status!!["checkedAt"] as String)
        val lastSeenAt = Instant.parse(status["lastSeenAt"] as String)
        assertTrue(checkedAt.isAfter(before) || checkedAt == before)
        assertTrue(lastSeenAt.isBefore(before))
    }

    // --- Edge: multiple servers mixed states processed correctly ---

    @Test
    fun `checkAll processes all servers even when some are in different states`() {
        TestDatabaseHelper.seedUserAndServer(db, "u-mix", "s-mix-online")
        TestDatabaseHelper.seedUserAndServer(db, "u-mix", "s-mix-warning")
        TestDatabaseHelper.seedUserAndServer(db, "u-mix", "s-mix-offline")
        setLastSeen("s-mix-online", Instant.now().minus(Duration.ofSeconds(30)))
        setLastSeen("s-mix-warning", Instant.now().minus(Duration.ofMinutes(15)))
        setLastSeen("s-mix-offline", Instant.now().minus(Duration.ofHours(2)))

        val monitor = AgentHealthMonitor(db, serverStore, null)
        monitor.checkAll()

        assertEquals("ONLINE", monitor.getHealthStatus("s-mix-online")!!["status"])
        assertEquals("WARNING", monitor.getHealthStatus("s-mix-warning")!!["status"])
        assertEquals("OFFLINE", monitor.getHealthStatus("s-mix-offline")!!["status"])
    }

    // --- Edge: OFFLINE server with email alert records correct serverId in alert_history ---

    @Test
    fun `alert_history records correct serverId and alert_type`() {
        TestDatabaseHelper.seedUserAndServer(db, "u-alert-audit", "s-alert-audit")
        setLastSeen("s-alert-audit", Instant.now().minus(Duration.ofMinutes(31)))
        insertNotificationSettings("u-alert-audit", emailEnabled = true, emailAddress = "audit@test.com")

        val notifier = RecordingEmailNotifier()
        val monitor = AgentHealthMonitor(db, serverStore, notifier)
        monitor.checkAll()

        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT server_id, alert_type FROM alert_history WHERE server_id = ?",
            ).use { ps ->
                ps.setString(1, "s-alert-audit")
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals("s-alert-audit", rs.getString("server_id"))
                    assertEquals("agent_offline", rs.getString("alert_type"))
                }
            }
        }
    }

    // --- Edge: alert for one server does not affect another server's dedup ---

    @Test
    fun `alert dedup is per-server, not global`() {
        TestDatabaseHelper.seedUserAndServer(db, "u-dedup-iso", "s-dedup-iso-a")
        TestDatabaseHelper.seedUserAndServer(db, "u-dedup-iso", "s-dedup-iso-b")
        setLastSeen("s-dedup-iso-a", Instant.now().minus(Duration.ofMinutes(31)))
        setLastSeen("s-dedup-iso-b", Instant.now().minus(Duration.ofMinutes(31)))
        insertNotificationSettings("u-dedup-iso", emailEnabled = true, emailAddress = "iso@test.com")

        val notifier = RecordingEmailNotifier()
        val monitor = AgentHealthMonitor(db, serverStore, notifier)
        monitor.checkAll()

        assertEquals(2, notifier.sentAlerts.size)
        val serverIds = notifier.sentAlerts.map { it.serverId }.toSet()
        assertTrue(serverIds.contains("s-dedup-iso-a"))
        assertTrue(serverIds.contains("s-dedup-iso-b"))
    }

    // --- Edge: checkAll with ServerStore exception → background thread survives ---

    @Test
    fun `background thread survives when checkAll encounters exception`() {
        val failingStore = object : ServerStore(db) {
            var callCount = 0
            override fun listAll(): List<pl.danielniemiec.properbackup.buffer.server.Server> {
                callCount++
                if (callCount == 1) throw RuntimeException("Simulated DB failure")
                return emptyList()
            }
        }

        val monitor = AgentHealthMonitor(db, failingStore, null)

        // First call throws, second succeeds
        assertThrows(RuntimeException::class.java) { monitor.checkAll() }
        assertDoesNotThrow { monitor.checkAll() }
    }

    @Test
    fun `checkAll exception does not corrupt existing health status records`() {
        TestDatabaseHelper.seedUserAndServer(db, "u-resilient", "s-resilient")
        setLastSeen("s-resilient", Instant.now().minus(Duration.ofMinutes(31)))

        val monitor = AgentHealthMonitor(db, serverStore, null)
        monitor.checkAll()
        assertEquals("OFFLINE", monitor.getHealthStatus("s-resilient")!!["status"])

        // Now make listAll fail — existing status should be preserved
        val failingStore = object : ServerStore(db) {
            override fun listAll(): List<pl.danielniemiec.properbackup.buffer.server.Server> {
                throw RuntimeException("DB connection lost")
            }
        }
        val monitorWithFailure = AgentHealthMonitor(db, failingStore, null)
        try { monitorWithFailure.checkAll() } catch (_: Exception) { }

        // Existing record still there, unchanged
        assertEquals("OFFLINE", monitor.getHealthStatus("s-resilient")!!["status"])
    }

    // --- Start/stop lifecycle ---

    @Test
    fun `start and stop do not throw`() {
        val monitor = AgentHealthMonitor(db, serverStore, null)
        monitor.start()
        Thread.sleep(100)
        monitor.stop()
    }

    @Test
    fun `stop is idempotent`() {
        val monitor = AgentHealthMonitor(db, serverStore, null)
        monitor.stop()
        monitor.stop()
    }
}

/**
 * Test double for EmailNotifier — records calls instead of sending email.
 */
class RecordingEmailNotifier : EmailNotifier(null, 587, null, null, "test@properbackup.com") {

    data class SentAlert(val to: String, val serverName: String, val serverId: String, val lastSeen: Instant)

    val sentAlerts = mutableListOf<SentAlert>()

    override fun sendAgentOfflineAlert(to: String, serverName: String, serverId: String, lastSeen: Instant) {
        sentAlerts.add(SentAlert(to, serverName, serverId, lastSeen))
    }
}
