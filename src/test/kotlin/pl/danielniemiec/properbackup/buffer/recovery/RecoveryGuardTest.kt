package pl.danielniemiec.properbackup.buffer.recovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import pl.danielniemiec.properbackup.buffer.db.Database

class RecoveryGuardTest {

    companion object {
        private lateinit var db: Database
        private lateinit var store: RecoverySessionStore
        private lateinit var guard: RecoveryGuard
        private var snapshotId: Long = 0

        @JvmStatic
        @BeforeAll
        fun setup() {
            db = TestDatabaseHelper.createDatabase()
            store = RecoverySessionStore(db)
            guard = RecoveryGuard(store)
            TestDatabaseHelper.seedUserAndServer(db)
            snapshotId = TestDatabaseHelper.seedSnapshot(db, "server-1")
        }
    }

    @BeforeEach
    fun cleanup() {
        TestDatabaseHelper.cleanRecoveryTables(db)
    }

    @Test
    fun `no active recovery does not throw`() {
        assertDoesNotThrow {
            guard.ensureNoActiveRecovery("server-1", "start_backup")
        }
    }

    @Test
    fun `active recovery throws RecoveryActiveException`() {
        store.create("server-1", "user-1", snapshotId)
        val ex = assertThrows<RecoveryActiveException> {
            guard.ensureNoActiveRecovery("server-1", "start_backup")
        }
        assertEquals("server-1", ex.serverId)
        assertEquals("start_backup", ex.blockedAction)
    }

    @Test
    fun `guard does not block after session is done`() {
        val s = store.create("server-1", "user-1", snapshotId)
        store.markFailed(s.id, "test", "REQUESTED")
        assertDoesNotThrow {
            guard.ensureNoActiveRecovery("server-1", "start_backup")
        }
    }

    @Test
    fun `guard blocks start_backup action`() {
        store.create("server-1", "user-1", snapshotId)
        val ex = assertThrows<RecoveryActiveException> {
            guard.ensureNoActiveRecovery("server-1", "start_backup")
        }
        assertEquals("start_backup", ex.blockedAction)
    }

    @Test
    fun `guard blocks delete_snapshot action`() {
        store.create("server-1", "user-1", snapshotId)
        val ex = assertThrows<RecoveryActiveException> {
            guard.ensureNoActiveRecovery("server-1", "delete_snapshot")
        }
        assertEquals("delete_snapshot", ex.blockedAction)
    }

    @Test
    fun `guard blocks single_file_restore action`() {
        store.create("server-1", "user-1", snapshotId)
        val ex = assertThrows<RecoveryActiveException> {
            guard.ensureNoActiveRecovery("server-1", "single_file_restore")
        }
        assertEquals("single_file_restore", ex.blockedAction)
    }

    @Test
    fun `guard blocks update_config action`() {
        store.create("server-1", "user-1", snapshotId)
        val ex = assertThrows<RecoveryActiveException> {
            guard.ensureNoActiveRecovery("server-1", "update_config")
        }
        assertEquals("update_config", ex.blockedAction)
    }

    @Test
    fun `different server is not blocked`() {
        TestDatabaseHelper.seedUserAndServer(db, "user-1", "server-2")
        TestDatabaseHelper.seedSnapshot(db, "server-2", "efgh5678", "test-obj-2")
        store.create("server-1", "user-1", snapshotId)
        assertDoesNotThrow {
            guard.ensureNoActiveRecovery("server-2", "start_backup")
        }
    }

    @Test
    fun `guard blocks in all active states`() {
        val s = store.create("server-1", "user-1", snapshotId)
        assertThrows<RecoveryActiveException> { guard.ensureNoActiveRecovery("server-1", "test") }

        store.transition(s.id, RecoveryState.REQUESTED, RecoveryState.PLANNING)
        assertThrows<RecoveryActiveException> { guard.ensureNoActiveRecovery("server-1", "test") }

        store.transition(s.id, RecoveryState.PLANNING, RecoveryState.AWAITING_USER_CONFIRM)
        assertThrows<RecoveryActiveException> { guard.ensureNoActiveRecovery("server-1", "test") }
    }
}
