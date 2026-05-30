package pl.danielniemiec.properbackup.buffer.recovery

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.danielniemiec.properbackup.buffer.db.Database

class RecoverySessionStoreTest {

    companion object {
        private lateinit var db: Database
        private lateinit var store: RecoverySessionStore
        private var snapshotId: Long = 0

        @JvmStatic
        @BeforeAll
        fun setup() {
            db = TestDatabaseHelper.createDatabase()
            store = RecoverySessionStore(db)
            TestDatabaseHelper.seedUserAndServer(db)
            snapshotId = TestDatabaseHelper.seedSnapshot(db, "server-1")
        }
    }

    @BeforeEach
    fun cleanup() {
        TestDatabaseHelper.cleanRecoveryTables(db)
    }

    @Test
    fun `create recovery session`() {
        val session = store.create("server-1", "user-1", snapshotId)
        assertNotNull(session.id)
        assertEquals("server-1", session.serverId)
        assertEquals("user-1", session.userId)
        assertEquals(snapshotId, session.targetSnapshotId)
        assertEquals(RecoveryState.REQUESTED, session.state)
        assertNotNull(session.createdAt)
        assertEquals(0, session.filesRestored)
        assertEquals(0, session.filesDeleted)
        assertEquals(0L, session.bytesTransferred)
    }

    @Test
    fun `byId returns session`() {
        val created = store.create("server-1", "user-1", snapshotId)
        val found = store.byId(created.id)
        assertNotNull(found)
        assertEquals(created.id, found!!.id)
        assertEquals(RecoveryState.REQUESTED, found.state)
    }

    @Test
    fun `byId returns null for unknown id`() {
        assertNull(store.byId("nonexistent-id"))
    }

    @Test
    fun `activeForServer returns active session`() {
        store.create("server-1", "user-1", snapshotId)
        val active = store.activeForServer("server-1")
        assertNotNull(active)
        assertEquals(RecoveryState.REQUESTED, active!!.state)
    }

    @Test
    fun `activeForServer returns null when no active session`() {
        assertNull(store.activeForServer("server-1"))
    }

    @Test
    fun `activeForServer returns null after session is done`() {
        val s = store.create("server-1", "user-1", snapshotId)
        store.transition(s.id, RecoveryState.REQUESTED, RecoveryState.PLANNING)
        store.transition(s.id, RecoveryState.PLANNING, RecoveryState.FAILED, mapOf("reason" to "test"))
        assertNull(store.activeForServer("server-1"))
    }

    @Test
    fun `activeForUser returns list of active sessions`() {
        store.create("server-1", "user-1", snapshotId)
        val active = store.activeForUser("user-1")
        assertEquals(1, active.size)
    }

    @Test
    fun `transition updates state`() {
        val s = store.create("server-1", "user-1", snapshotId)
        val updated = store.transition(s.id, RecoveryState.REQUESTED, RecoveryState.PLANNING)
        assertEquals(RecoveryState.PLANNING, updated.state)
    }

    @Test
    fun `transition fails if current state does not match expected`() {
        val s = store.create("server-1", "user-1", snapshotId)
        assertThrows<IllegalStateException> {
            store.transition(s.id, RecoveryState.PLANNING, RecoveryState.AWAITING_USER_CONFIRM)
        }
    }

    @Test
    fun `transition fails if illegal state machine transition`() {
        val s = store.create("server-1", "user-1", snapshotId)
        assertThrows<IllegalStateException> {
            store.transition(s.id, RecoveryState.REQUESTED, RecoveryState.DONE)
        }
    }

    @Test
    fun `transition to FAILED sets failed_at and failure_reason`() {
        val s = store.create("server-1", "user-1", snapshotId)
        val failed = store.transition(s.id, RecoveryState.REQUESTED, RecoveryState.FAILED, mapOf("reason" to "test error"))
        assertEquals(RecoveryState.FAILED, failed.state)
        assertNotNull(failed.failedAt)
        assertEquals("test error", failed.failureReason)
    }

    @Test
    fun `transition to CANCELLED sets cancelled_at and cancellation_reason`() {
        val s = store.create("server-1", "user-1", snapshotId)
        val cancelled = store.transition(s.id, RecoveryState.REQUESTED, RecoveryState.CANCELLED, mapOf("reason" to "user cancel"))
        assertEquals(RecoveryState.CANCELLED, cancelled.state)
        assertNotNull(cancelled.cancelledAt)
        assertEquals("user cancel", cancelled.cancellationReason)
    }

    @Test
    fun `transition to THAWING sets thaw_started_at`() {
        val s = store.create("server-1", "user-1", snapshotId)
        store.transition(s.id, RecoveryState.REQUESTED, RecoveryState.PLANNING)
        store.transition(s.id, RecoveryState.PLANNING, RecoveryState.AWAITING_USER_CONFIRM)
        val thawing = store.transition(s.id, RecoveryState.AWAITING_USER_CONFIRM, RecoveryState.THAWING)
        assertNotNull(thawing.thawStartedAt)
    }

    @Test
    fun `transition to READY sets thaw_completed_at`() {
        val s = store.create("server-1", "user-1", snapshotId)
        store.transition(s.id, RecoveryState.REQUESTED, RecoveryState.PLANNING)
        store.transition(s.id, RecoveryState.PLANNING, RecoveryState.AWAITING_USER_CONFIRM)
        val ready = store.transition(s.id, RecoveryState.AWAITING_USER_CONFIRM, RecoveryState.READY)
        assertNotNull(ready.thawCompletedAt)
    }

    @Test
    fun `full happy path REQUESTED to DONE`() {
        val s = store.create("server-1", "user-1", snapshotId)
        store.transition(s.id, RecoveryState.REQUESTED, RecoveryState.PLANNING)
        store.transition(s.id, RecoveryState.PLANNING, RecoveryState.AWAITING_USER_CONFIRM)
        store.transition(s.id, RecoveryState.AWAITING_USER_CONFIRM, RecoveryState.READY)
        store.transition(s.id, RecoveryState.READY, RecoveryState.AGENT_RESTORING)
        store.transition(s.id, RecoveryState.AGENT_RESTORING, RecoveryState.VERIFYING)
        val done = store.transition(s.id, RecoveryState.VERIFYING, RecoveryState.DONE)
        assertEquals(RecoveryState.DONE, done.state)
        assertNotNull(done.completedAt)
    }

    @Test
    fun `setDryRun stores dry run data`() {
        val s = store.create("server-1", "user-1", snapshotId)
        store.setDryRun(
            id = s.id,
            filesToRestore = 100,
            filesToDelete = 10,
            filesUnchanged = 500,
            totalBytes = 1024000L,
            sampleRestore = listOf("/file1.txt", "/file2.txt"),
            sampleDelete = listOf("/old.txt"),
            criticalProtected = 3,
            estimatedDurationSec = 120,
        )
        val updated = store.byId(s.id)!!
        assertEquals(100, updated.dryRunFilesToRestore)
        assertEquals(10, updated.dryRunFilesToDelete)
        assertEquals(500, updated.dryRunFilesUnchanged)
        assertEquals(1024000L, updated.dryRunTotalBytes)
    }

    @Test
    fun `setPreRecoverySnapshot stores snapshot id`() {
        val s = store.create("server-1", "user-1", snapshotId)
        store.setPreRecoverySnapshot(s.id, snapshotId)
        val updated = store.byId(s.id)!!
        assertEquals(snapshotId, updated.preRecoverySnapshotId)
    }

    @Test
    fun `incrementProgress increments counters`() {
        val s = store.create("server-1", "user-1", snapshotId)
        store.incrementProgress(s.id, 5, 2, 1024L)
        store.incrementProgress(s.id, 3, 1, 512L)
        val updated = store.byId(s.id)!!
        assertEquals(8, updated.filesRestored)
        assertEquals(3, updated.filesDeleted)
        assertEquals(1536L, updated.bytesTransferred)
    }

    @Test
    fun `markFailed transitions to FAILED`() {
        val s = store.create("server-1", "user-1", snapshotId)
        store.markFailed(s.id, "disk error", "REQUESTED")
        val updated = store.byId(s.id)!!
        assertEquals(RecoveryState.FAILED, updated.state)
        assertTrue(updated.failureReason!!.contains("disk error"))
    }

    @Test
    fun `markCancelled transitions to CANCELLED`() {
        val s = store.create("server-1", "user-1", snapshotId)
        store.markCancelled(s.id, "user wants to stop", "REQUESTED")
        val updated = store.byId(s.id)!!
        assertEquals(RecoveryState.CANCELLED, updated.state)
        assertTrue(updated.cancellationReason!!.contains("user wants to stop"))
    }

    @Test
    fun `historyForServer returns sessions ordered by created_at desc`() {
        val s1 = store.create("server-1", "user-1", snapshotId)
        store.transition(s1.id, RecoveryState.REQUESTED, RecoveryState.FAILED, mapOf("reason" to "test"))
        TestDatabaseHelper.cleanRecoveryTables(db)

        val s2 = store.create("server-1", "user-1", snapshotId)
        store.transition(s2.id, RecoveryState.REQUESTED, RecoveryState.FAILED, mapOf("reason" to "test"))

        val s3 = store.create("server-1", "user-1", snapshotId)
        val history = store.historyForServer("server-1")
        assertTrue(history.size >= 1)
        assertEquals(s3.id, history.first().id)
    }

    @Test
    fun `setConfirmed stores confirmation metadata`() {
        val s = store.create("server-1", "user-1", snapshotId)
        store.setConfirmed(s.id, "192.168.1.1", "Mozilla/5.0")
        val updated = store.byId(s.id)!!
        assertNotNull(updated.confirmedAt)
        assertEquals("192.168.1.1", updated.userConfirmedViaIp)
        assertEquals("Mozilla/5.0", updated.userConfirmedViaUserAgent)
    }

    @Test
    fun `unique constraint prevents two active sessions on same server`() {
        store.create("server-1", "user-1", snapshotId)
        assertThrows<Exception> {
            store.create("server-1", "user-1", snapshotId)
        }
    }
}
