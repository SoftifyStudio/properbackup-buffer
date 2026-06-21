package pl.danielniemiec.properbackup.buffer.recovery

import com.google.gson.Gson
import io.javalin.Javalin
import io.javalin.testtools.JavalinTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.danielniemiec.properbackup.buffer.db.Database
import pl.danielniemiec.properbackup.buffer.sse.SseEventBus

class RecoveryHandlerTest {

    companion object {
        private lateinit var db: Database
        private lateinit var store: RecoverySessionStore
        private lateinit var guard: RecoveryGuard
        private lateinit var auditLog: RecoveryAuditLog
        private lateinit var eventBus: RecoveryEventBus
        private lateinit var dryRun: DryRunComputer
        private lateinit var preSnapshot: PreRecoverySnapshotCreator
        private lateinit var handler: RecoveryHandler
        private val gson = Gson()
        private val JSON = "application/json".toMediaType()
        private var snapshotId: Long = 0

        @JvmStatic
        @BeforeAll
        fun setup() {
            db = TestDatabaseHelper.createDatabase()
            store = RecoverySessionStore(db)
            guard = RecoveryGuard(store)
            auditLog = RecoveryAuditLog(db)
            val sseBus = SseEventBus()
            eventBus = RecoveryEventBus(sseBus)
            dryRun = DryRunComputer(store, eventBus, auditLog)
            preSnapshot = PreRecoverySnapshotCreator(store, eventBus, auditLog)
            handler = RecoveryHandler(store, guard, dryRun, preSnapshot, auditLog, eventBus)
            TestDatabaseHelper.seedUserAndServer(db)
            snapshotId = TestDatabaseHelper.seedSnapshot(db, "server-1")
        }
    }

    @BeforeEach
    fun cleanup() {
        TestDatabaseHelper.cleanRecoveryTables(db)
    }

    private fun createApp(): Javalin = Javalin.create().apply {
        before { ctx ->
            ctx.attribute("userId", "user-1")
            ctx.attribute("isSystemAdmin", false)
        }
        post("/recovery/start", handler::startRecovery)
        get("/recovery/history", handler::history)
        get("/recovery/{id}", handler::getSession)
        post("/recovery/{id}/confirm", handler::confirmRecovery)
        post("/recovery/{id}/cancel", handler::cancelRecovery)
        get("/recovery/{id}/audit", handler::getAudit)
        get("/recovery/{id}/dry_run", handler::getDryRun)
        get("/agent/recovery/poll", handler::agentPoll)
        post("/agent/recovery/{id}/dry_run_result", handler::agentDryRunResult)
        post("/agent/recovery/{id}/progress", handler::agentProgress)
        post("/agent/recovery/{id}/files_report", handler::agentFilesReport)
        post("/agent/recovery/{id}/complete", handler::agentComplete)
    }

    private fun jsonBody(data: Any): okhttp3.RequestBody =
        gson.toJson(data).toRequestBody(JSON)

    @Test
    fun `POST recovery start returns 201 with session_id`() {
        JavalinTest.test(createApp()) { _, client ->
            val resp = client.request("/recovery/start") {
                it.post(jsonBody(mapOf("server_id" to "server-1", "target_snapshot_id" to snapshotId)))
            }
            assertEquals(201, resp.code)
            val body = gson.fromJson(resp.body?.string(), Map::class.java)
            assertNotNull(body["session_id"])
        }
    }

    @Test
    fun `POST recovery start returns 409 when active recovery exists`() {
        JavalinTest.test(createApp()) { _, client ->
            client.request("/recovery/start") {
                it.post(jsonBody(mapOf("server_id" to "server-1", "target_snapshot_id" to snapshotId)))
            }
            val resp2 = client.request("/recovery/start") {
                it.post(jsonBody(mapOf("server_id" to "server-1", "target_snapshot_id" to snapshotId)))
            }
            assertEquals(409, resp2.code)
        }
    }

    @Test
    fun `POST recovery start returns 400 for missing fields`() {
        JavalinTest.test(createApp()) { _, client ->
            val resp = client.request("/recovery/start") {
                it.post(jsonBody(mapOf("server_id" to "server-1")))
            }
            assertEquals(400, resp.code)
        }
    }

    @Test
    fun `GET recovery session returns 200`() {
        JavalinTest.test(createApp()) { _, client ->
            val createResp = client.request("/recovery/start") {
                it.post(jsonBody(mapOf("server_id" to "server-1", "target_snapshot_id" to snapshotId)))
            }
            val createBody = gson.fromJson(createResp.body?.string(), Map::class.java)
            val sessionId = createBody["session_id"] as String

            val resp = client.get("/recovery/$sessionId")
            assertEquals(200, resp.code)
            val body = gson.fromJson(resp.body?.string(), Map::class.java)
            assertEquals("PLANNING", body["state"])
        }
    }

    @Test
    fun `GET recovery session returns 404 for unknown id`() {
        JavalinTest.test(createApp()) { _, client ->
            val resp = client.get("/recovery/nonexistent-id")
            assertEquals(404, resp.code)
        }
    }

    @Test
    fun `POST recovery cancel returns 200`() {
        JavalinTest.test(createApp()) { _, client ->
            val createResp = client.request("/recovery/start") {
                it.post(jsonBody(mapOf("server_id" to "server-1", "target_snapshot_id" to snapshotId)))
            }
            val sessionId = gson.fromJson(createResp.body?.string(), Map::class.java)["session_id"] as String

            val resp = client.request("/recovery/$sessionId/cancel") {
                it.post(jsonBody(mapOf("reason" to "test cancel")))
            }
            assertEquals(200, resp.code)

            val getResp = client.get("/recovery/$sessionId")
            val body = gson.fromJson(getResp.body?.string(), Map::class.java)
            assertEquals("CANCELLED", body["state"])
        }
    }

    @Test
    fun `GET recovery audit returns entries`() {
        JavalinTest.test(createApp()) { _, client ->
            val createResp = client.request("/recovery/start") {
                it.post(jsonBody(mapOf("server_id" to "server-1", "target_snapshot_id" to snapshotId)))
            }
            val sessionId = gson.fromJson(createResp.body?.string(), Map::class.java)["session_id"] as String

            val resp = client.get("/recovery/$sessionId/audit")
            assertEquals(200, resp.code)
            val body = gson.fromJson(resp.body?.string(), Map::class.java)
            val entries = body["entries"] as List<*>
            assertTrue(entries.isNotEmpty())
        }
    }

    @Test
    fun `GET recovery history returns sessions`() {
        JavalinTest.test(createApp()) { _, client ->
            client.request("/recovery/start") {
                it.post(jsonBody(mapOf("server_id" to "server-1", "target_snapshot_id" to snapshotId)))
            }
            val resp = client.get("/recovery/history?server_id=server-1")
            assertEquals(200, resp.code)
        }
    }

    @Test
    fun `GET recovery history returns 400 without server_id`() {
        JavalinTest.test(createApp()) { _, client ->
            val resp = client.get("/recovery/history")
            assertEquals(400, resp.code)
        }
    }

    @Test
    fun `POST agent dry_run_result transitions to AWAITING_USER_CONFIRM`() {
        JavalinTest.test(createApp()) { _, client ->
            val createResp = client.request("/recovery/start") {
                it.post(jsonBody(mapOf("server_id" to "server-1", "target_snapshot_id" to snapshotId)))
            }
            val sessionId = gson.fromJson(createResp.body?.string(), Map::class.java)["session_id"] as String

            val resp = client.request("/agent/recovery/$sessionId/dry_run_result") {
                it.post(jsonBody(mapOf(
                    "files_to_restore" to 100,
                    "files_to_delete" to 10,
                    "files_unchanged" to 500,
                    "total_bytes" to 1024000,
                    "sample_restore" to listOf("/a.txt", "/b.txt"),
                    "sample_delete" to listOf("/old.txt"),
                    "critical_protected" to 3,
                    "estimated_duration_seconds" to 120,
                )))
            }
            assertEquals(200, resp.code)

            val getResp = client.get("/recovery/$sessionId")
            val body = gson.fromJson(getResp.body?.string(), Map::class.java)
            assertEquals("AWAITING_USER_CONFIRM", body["state"])
            assertEquals(100.0, body["dryRunFilesToRestore"])
            assertEquals(10.0, body["dryRunFilesToDelete"])
        }
    }

    @Test
    fun `POST agent progress increments counters`() {
        JavalinTest.test(createApp()) { _, client ->
            val createResp = client.request("/recovery/start") {
                it.post(jsonBody(mapOf("server_id" to "server-1", "target_snapshot_id" to snapshotId)))
            }
            val sessionId = gson.fromJson(createResp.body?.string(), Map::class.java)["session_id"] as String

            val resp = client.request("/agent/recovery/$sessionId/progress") {
                it.post(jsonBody(mapOf(
                    "files_restored_delta" to 5,
                    "files_deleted_delta" to 2,
                    "bytes_delta" to 1024,
                )))
            }
            assertEquals(200, resp.code)

            val getResp = client.get("/recovery/$sessionId")
            val body = gson.fromJson(getResp.body?.string(), Map::class.java)
            assertEquals(5.0, body["filesRestored"])
            assertEquals(2.0, body["filesDeleted"])
        }
    }

    @Test
    fun `GET agent recovery poll returns commands`() {
        JavalinTest.test(createApp()) { _, client ->
            client.request("/recovery/start") {
                it.post(jsonBody(mapOf("server_id" to "server-1", "target_snapshot_id" to snapshotId)))
            }

            val resp = client.request("/agent/recovery/poll") {
                it.header("X-Server-Id", "server-1").get()
            }
            assertEquals(200, resp.code)
        }
    }

    @Test
    fun `POST recovery confirm with cold tier transitions to THAWING`() {
        JavalinTest.test(createApp()) { _, client ->
            val createResp = client.request("/recovery/start") {
                it.post(jsonBody(mapOf("server_id" to "server-1", "target_snapshot_id" to snapshotId)))
            }
            val sessionId = gson.fromJson(createResp.body?.string(), Map::class.java)["session_id"] as String

            client.request("/agent/recovery/$sessionId/dry_run_result") {
                it.post(jsonBody(mapOf(
                    "files_to_restore" to 10, "files_to_delete" to 1, "files_unchanged" to 50,
                    "total_bytes" to 1024,
                    "sample_restore" to emptyList<String>(), "sample_delete" to emptyList<String>(),
                    "critical_protected" to 0,
                )))
            }

            val confirmResp = client.request("/recovery/$sessionId/confirm") {
                it.post(jsonBody(mapOf("user_confirmation" to true, "cold_tier" to true)))
            }
            assertEquals(200, confirmResp.code)
            val body = gson.fromJson(confirmResp.body?.string(), Map::class.java)
            assertEquals("THAWING", body["state"])
        }
    }

    @Test
    fun `POST recovery confirm with hot tier transitions to READY`() {
        JavalinTest.test(createApp()) { _, client ->
            val createResp = client.request("/recovery/start") {
                it.post(jsonBody(mapOf("server_id" to "server-1", "target_snapshot_id" to snapshotId)))
            }
            val sessionId = gson.fromJson(createResp.body?.string(), Map::class.java)["session_id"] as String

            client.request("/agent/recovery/$sessionId/dry_run_result") {
                it.post(jsonBody(mapOf(
                    "files_to_restore" to 10, "files_to_delete" to 1, "files_unchanged" to 50,
                    "total_bytes" to 1024,
                    "sample_restore" to emptyList<String>(), "sample_delete" to emptyList<String>(),
                    "critical_protected" to 0,
                )))
            }

            val confirmResp = client.request("/recovery/$sessionId/confirm") {
                it.post(jsonBody(mapOf("user_confirmation" to true, "cold_tier" to false)))
            }
            assertEquals(200, confirmResp.code)
            val body = gson.fromJson(confirmResp.body?.string(), Map::class.java)
            assertEquals("READY", body["state"])
        }
    }

    @Test
    fun `GET agent recovery poll transitions READY to AGENT_RESTORING`() {
        JavalinTest.test(createApp()) { _, client ->
            // Create session → dry_run → confirm (hot) → READY
            val createResp = client.request("/recovery/start") {
                it.post(jsonBody(mapOf("server_id" to "server-1", "target_snapshot_id" to snapshotId)))
            }
            val sessionId = gson.fromJson(createResp.body?.string(), Map::class.java)["session_id"] as String

            client.request("/agent/recovery/$sessionId/dry_run_result") {
                it.post(jsonBody(mapOf(
                    "files_to_restore" to 10, "files_to_delete" to 1, "files_unchanged" to 50,
                    "total_bytes" to 1024,
                    "sample_restore" to emptyList<String>(), "sample_delete" to emptyList<String>(),
                    "critical_protected" to 0,
                )))
            }

            client.request("/recovery/$sessionId/confirm") {
                it.post(jsonBody(mapOf("user_confirmation" to true, "cold_tier" to false)))
            }

            // Verify state is READY before poll
            val beforePoll = client.get("/recovery/$sessionId")
            val beforeBody = gson.fromJson(beforePoll.body?.string(), Map::class.java)
            assertEquals("READY", beforeBody["state"])

            // Agent polls — should get "execute" command and transition to AGENT_RESTORING
            val pollResp = client.request("/agent/recovery/poll") {
                it.header("X-Server-Id", "server-1").get()
            }
            assertEquals(200, pollResp.code)
            val pollBody = gson.fromJson(pollResp.body?.string(), Map::class.java)
            val commands = pollBody["commands"] as List<*>
            assertEquals(1, commands.size)
            val cmd = commands[0] as Map<*, *>
            assertEquals("execute", cmd["command"])
            assertEquals(sessionId, cmd["sessionId"])

            // Verify state transitioned to AGENT_RESTORING
            val afterPoll = client.get("/recovery/$sessionId")
            val afterBody = gson.fromJson(afterPoll.body?.string(), Map::class.java)
            assertEquals("AGENT_RESTORING", afterBody["state"])
        }
    }

    @Test
    fun `GET agent recovery poll with READY does not issue duplicate commands on second poll`() {
        JavalinTest.test(createApp()) { _, client ->
            val createResp = client.request("/recovery/start") {
                it.post(jsonBody(mapOf("server_id" to "server-1", "target_snapshot_id" to snapshotId)))
            }
            val sessionId = gson.fromJson(createResp.body?.string(), Map::class.java)["session_id"] as String

            client.request("/agent/recovery/$sessionId/dry_run_result") {
                it.post(jsonBody(mapOf(
                    "files_to_restore" to 10, "files_to_delete" to 1, "files_unchanged" to 50,
                    "total_bytes" to 1024,
                    "sample_restore" to emptyList<String>(), "sample_delete" to emptyList<String>(),
                    "critical_protected" to 0,
                )))
            }

            client.request("/recovery/$sessionId/confirm") {
                it.post(jsonBody(mapOf("user_confirmation" to true, "cold_tier" to false)))
            }

            // First poll transitions READY → AGENT_RESTORING
            client.request("/agent/recovery/poll") {
                it.header("X-Server-Id", "server-1").get()
            }

            // Second poll should NOT issue execute again (state is now AGENT_RESTORING)
            val poll2 = client.request("/agent/recovery/poll") {
                it.header("X-Server-Id", "server-1").get()
            }
            val poll2Body = gson.fromJson(poll2.body?.string(), Map::class.java)
            val commands = poll2Body["commands"] as List<*>
            assertEquals(0, commands.size)
        }
    }

    @Test
    fun `POST recovery confirm without confirmation flag returns 400`() {
        JavalinTest.test(createApp()) { _, client ->
            val createResp = client.request("/recovery/start") {
                it.post(jsonBody(mapOf("server_id" to "server-1", "target_snapshot_id" to snapshotId)))
            }
            val sessionId = gson.fromJson(createResp.body?.string(), Map::class.java)["session_id"] as String

            client.request("/agent/recovery/$sessionId/dry_run_result") {
                it.post(jsonBody(mapOf(
                    "files_to_restore" to 10, "files_to_delete" to 1, "files_unchanged" to 50,
                    "total_bytes" to 1024,
                    "sample_restore" to emptyList<String>(), "sample_delete" to emptyList<String>(),
                    "critical_protected" to 0,
                )))
            }

            val confirmResp = client.request("/recovery/$sessionId/confirm") {
                it.post(jsonBody(mapOf("user_confirmation" to false)))
            }
            assertEquals(400, confirmResp.code)
        }
    }

    @Test
    fun `POST files_report logs per-file RECOVERY_FILE_RESTORED audit entries (HR-6)`() {
        JavalinTest.test(createApp()) { _, client ->
            val sessionId = createReadySession(client)

            // Poll → AGENT_RESTORING
            client.request("/agent/recovery/poll") {
                it.header("X-Server-Id", "server-1").get()
            }

            // Report 5 files restored
            val files = (1..5).map { i ->
                mapOf("path" to "/home/user/file$i.txt", "size" to (1024L * i), "action" to "restored")
            }
            val reportResp = client.request("/agent/recovery/$sessionId/files_report") {
                it.post(jsonBody(mapOf("files" to files)))
            }
            assertEquals(200, reportResp.code)
            val reportBody = gson.fromJson(reportResp.body?.string(), Map::class.java)
            assertEquals(5.0, reportBody["restored"])
            assertEquals(5.0, reportBody["processed"])

            // Verify audit log has per-file entries
            val auditResp = client.request("/recovery/$sessionId/audit") { it.get() }
            val auditBody = gson.fromJson(auditResp.body?.string(), Map::class.java)
            @Suppress("UNCHECKED_CAST")
            val entries = auditBody["entries"] as List<Map<String, Any?>>
            val fileEntries = entries.filter { it["action"] == "RECOVERY_FILE_RESTORED" }
            assertEquals(5, fileEntries.size)
            assertTrue(fileEntries[0]["detail"].toString().contains("/home/user/file1.txt"))
            assertTrue(fileEntries[0]["detail"].toString().contains("size=1024"))

            // Verify progress was incremented
            val sessionResp = client.request("/recovery/$sessionId") { it.get() }
            val session = gson.fromJson(sessionResp.body?.string(), Map::class.java)
            assertEquals(5.0, session["filesRestored"])
            assertEquals(15360.0, session["bytesTransferred"]) // 1024+2048+3072+4096+5120
        }
    }

    @Test
    fun `POST files_report rejects batch larger than 100`() {
        JavalinTest.test(createApp()) { _, client ->
            val sessionId = createReadySession(client)

            client.request("/agent/recovery/poll") {
                it.header("X-Server-Id", "server-1").get()
            }

            val files = (1..101).map { i ->
                mapOf("path" to "/file$i.txt", "size" to 100, "action" to "restored")
            }
            val resp = client.request("/agent/recovery/$sessionId/files_report") {
                it.post(jsonBody(mapOf("files" to files)))
            }
            assertEquals(400, resp.code)
            val body = gson.fromJson(resp.body?.string(), Map::class.java)
            assertEquals("BATCH_TOO_LARGE", body["code"])
        }
    }

    @Test
    fun `Multi-file recovery reaches DONE with correct files_restored count`() {
        JavalinTest.test(createApp()) { _, client ->
            val sessionId = createReadySession(client)

            // Poll → AGENT_RESTORING
            client.request("/agent/recovery/poll") {
                it.header("X-Server-Id", "server-1").get()
            }

            // Report files in 2 batches (simulating batch processing)
            val batch1 = (1..3).map { i ->
                mapOf("path" to "/data/batch1/file$i.dat", "size" to 50000L, "action" to "restored")
            }
            client.request("/agent/recovery/$sessionId/files_report") {
                it.post(jsonBody(mapOf("files" to batch1)))
            }

            val batch2 = (1..2).map { i ->
                mapOf("path" to "/data/batch2/file$i.dat", "size" to 75000L, "action" to "restored")
            } + listOf(mapOf("path" to "/data/old/removed.log", "size" to 0, "action" to "deleted"))
            client.request("/agent/recovery/$sessionId/files_report") {
                it.post(jsonBody(mapOf("files" to batch2)))
            }

            // Agent completes
            val completeResp = client.request("/agent/recovery/$sessionId/complete") {
                it.post(jsonBody(mapOf("success" to true, "verification" to "OK")))
            }
            assertEquals(200, completeResp.code)

            // Verify final state
            val sessionResp = client.request("/recovery/$sessionId") { it.get() }
            val session = gson.fromJson(sessionResp.body?.string(), Map::class.java)
            assertEquals("DONE", session["state"])
            assertEquals(5.0, session["filesRestored"])
            assertEquals(1.0, session["filesDeleted"])
            assertEquals(300000.0, session["bytesTransferred"]) // 3*50000 + 2*75000

            // Verify all timestamps filled
            assertNotNull(session["agentStartedAt"])
            assertNotNull(session["agentCompletedAt"])
            assertNotNull(session["verifyingStartedAt"])
            assertNotNull(session["completedAt"])

            // Verify per-file audit entries
            val auditResp = client.request("/recovery/$sessionId/audit") { it.get() }
            val auditBody = gson.fromJson(auditResp.body?.string(), Map::class.java)
            @Suppress("UNCHECKED_CAST")
            val entries = auditBody["entries"] as List<Map<String, Any?>>
            val restoredEntries = entries.filter { it["action"] == "RECOVERY_FILE_RESTORED" }
            val deletedEntries = entries.filter { it["action"] == "RECOVERY_FILE_DELETED" }
            assertEquals(5, restoredEntries.size)
            assertEquals(1, deletedEntries.size)
            assertTrue(entries.any { it["action"] == "RECOVERY_COMPLETED" })
        }
    }

    @Test
    fun `POST cancel during AGENT_RESTORING emits rollback with at_phase (HR-9)`() {
        JavalinTest.test(createApp()) { _, client ->
            val sessionId = createReadySession(client)

            // Poll → AGENT_RESTORING
            client.request("/agent/recovery/poll") {
                it.header("X-Server-Id", "server-1").get()
            }

            // Cancel
            val cancelResp = client.request("/recovery/$sessionId/cancel") {
                it.post(jsonBody(mapOf("reason" to "user_requested_rollback")))
            }
            assertEquals(200, cancelResp.code)
            val cancelBody = gson.fromJson(cancelResp.body?.string(), Map::class.java)
            assertEquals("CANCELLED", cancelBody["state"])
            assertEquals("AGENT_RESTORING", cancelBody["at_phase"])

            // Verify session state
            val sessionResp = client.request("/recovery/$sessionId") { it.get() }
            val session = gson.fromJson(sessionResp.body?.string(), Map::class.java)
            assertEquals("CANCELLED", session["state"])
            assertNotNull(session["cancelledAt"])

            // Verify audit includes at_phase
            val auditResp = client.request("/recovery/$sessionId/audit") { it.get() }
            val auditBody = gson.fromJson(auditResp.body?.string(), Map::class.java)
            @Suppress("UNCHECKED_CAST")
            val entries = auditBody["entries"] as List<Map<String, Any?>>
            val cancelEntry = entries.find { it["action"] == "RECOVERY_CANCELLED" }
            assertNotNull(cancelEntry)
            assertTrue(cancelEntry!!["detail"].toString().contains("at_phase=AGENT_RESTORING"))
            assertTrue(cancelEntry["detail"].toString().contains("reason=user_requested_rollback"))
        }
    }

    @Test
    fun `GET dry_run returns accurate file counts and sample files (HR-4)`() {
        JavalinTest.test(createApp()) { _, client ->
            val createResp = client.request("/recovery/start") {
                it.post(jsonBody(mapOf("server_id" to "server-1", "target_snapshot_id" to snapshotId)))
            }
            val sessionId = gson.fromJson(createResp.body?.string(), Map::class.java)["session_id"] as String

            val sampleRestore = listOf("/home/user/docs/report.pdf", "/home/user/photos/vacation.jpg", "/home/user/code/main.kt")
            val sampleDelete = listOf("/tmp/old-cache.dat")

            client.request("/agent/recovery/$sessionId/dry_run_result") {
                it.post(jsonBody(mapOf(
                    "files_to_restore" to 3, "files_to_delete" to 1, "files_unchanged" to 20,
                    "total_bytes" to 524288,
                    "sample_restore" to sampleRestore, "sample_delete" to sampleDelete,
                    "critical_protected" to 0, "estimated_duration_seconds" to 30,
                )))
            }

            // Verify via GET /recovery/{id}
            val sessionResp = client.request("/recovery/$sessionId") { it.get() }
            val session = gson.fromJson(sessionResp.body?.string(), Map::class.java)
            assertEquals(3.0, session["dryRunFilesToRestore"])
            assertEquals(1.0, session["dryRunFilesToDelete"])
            assertEquals(20.0, session["dryRunFilesUnchanged"])
            assertEquals(524288.0, session["dryRunTotalBytes"])

            // Verify via GET /recovery/{id}/dry_run (detailed)
            val dryRunResp = client.request("/recovery/$sessionId/dry_run") { it.get() }
            assertEquals(200, dryRunResp.code)
            val dryRun = gson.fromJson(dryRunResp.body?.string(), Map::class.java)
            assertEquals(3.0, dryRun["filesToRestore"])
            assertEquals(524288.0, dryRun["filesToRestoreBytes"])
            assertEquals(1.0, dryRun["filesToDelete"])
            assertEquals(20.0, dryRun["filesUnchanged"])
            assertEquals(30.0, dryRun["estimatedDurationSeconds"])
            @Suppress("UNCHECKED_CAST")
            val samples = dryRun["sampleFilesToRestore"] as List<String>
            assertEquals(3, samples.size)
            assertTrue(samples.contains("/home/user/docs/report.pdf"))
            assertTrue(samples.contains("/home/user/photos/vacation.jpg"))
            assertTrue(samples.contains("/home/user/code/main.kt"))
        }
    }

    @Test
    fun `POST recovery start returns 409 CONFLICT when another session is AGENT_RESTORING (HR-2)`() {
        JavalinTest.test(createApp()) { _, client ->
            val sessionId = createReadySession(client)

            // Poll → transitions to AGENT_RESTORING
            client.request("/agent/recovery/poll") {
                it.header("X-Server-Id", "server-1").get()
            }

            // Try to start second recovery on same server → 409
            val resp = client.request("/recovery/start") {
                it.post(jsonBody(mapOf("server_id" to "server-1", "target_snapshot_id" to snapshotId)))
            }
            assertEquals(409, resp.code)
            val body = gson.fromJson(resp.body?.string(), Map::class.java)
            assertEquals("RECOVERY_ALREADY_ACTIVE", body["code"])
            assertEquals(sessionId, body["sessionId"])
            assertEquals("AGENT_RESTORING", body["state"])
        }
    }

    @Test
    fun `agentPoll creates PRE_RECOVERY snapshot visible after DONE (HR-5)`() {
        JavalinTest.test(createApp()) { _, client ->
            val sessionId = createReadySession(client)

            // Poll → AGENT_RESTORING (creates PRE_RECOVERY snapshot)
            val pollResp = client.request("/agent/recovery/poll") {
                it.header("X-Server-Id", "server-1").get()
            }
            val pollBody = gson.fromJson(pollResp.body?.string(), Map::class.java)
            @Suppress("UNCHECKED_CAST")
            val cmds = pollBody["commands"] as List<Map<String, Any?>>
            assertTrue(cmds.isNotEmpty())
            val preSnapshotId = cmds[0]["preRecoverySnapshotId"]
            assertNotNull(preSnapshotId, "preRecoverySnapshotId should be set in execute command")

            // Verify session has preRecoverySnapshotId
            val sessionResp = client.request("/recovery/$sessionId") {
                it.get()
            }
            val session = gson.fromJson(sessionResp.body?.string(), Map::class.java)
            assertNotNull(session["preRecoverySnapshotId"], "session should have preRecoverySnapshotId after AGENT_RESTORING")

            // Complete recovery
            client.request("/agent/recovery/$sessionId/complete") {
                it.post(jsonBody(mapOf("success" to true, "verification" to "OK")))
            }

            // Verify session is DONE and still has preRecoverySnapshotId
            val doneResp = client.request("/recovery/$sessionId") {
                it.get()
            }
            val doneSession = gson.fromJson(doneResp.body?.string(), Map::class.java)
            assertEquals("DONE", doneSession["state"])
            assertNotNull(doneSession["preRecoverySnapshotId"], "preRecoverySnapshotId should persist after DONE")

            // Verify audit has RECOVERY_PRE_SNAPSHOT_CREATED
            val auditResp = client.request("/recovery/$sessionId/audit") {
                it.get()
            }
            val audit = gson.fromJson(auditResp.body?.string(), Map::class.java)
            @Suppress("UNCHECKED_CAST")
            val entries = audit["entries"] as List<Map<String, Any?>>
            val preSnapshotEntry = entries.find { it["action"] == "RECOVERY_PRE_SNAPSHOT_CREATED" }
            assertNotNull(preSnapshotEntry, "audit should contain RECOVERY_PRE_SNAPSHOT_CREATED")
            assertTrue(preSnapshotEntry!!["detail"].toString().contains("snapshotId="))
        }
    }

    private fun createReadySession(client: io.javalin.testtools.HttpClient): String {
        val createResp = client.request("/recovery/start") {
            it.post(jsonBody(mapOf("server_id" to "server-1", "target_snapshot_id" to snapshotId)))
        }
        val sessionId = gson.fromJson(createResp.body?.string(), Map::class.java)["session_id"] as String

        client.request("/agent/recovery/$sessionId/dry_run_result") {
            it.post(jsonBody(mapOf(
                "files_to_restore" to 5, "files_to_delete" to 1, "files_unchanged" to 10,
                "total_bytes" to 300000,
                "sample_restore" to listOf("/data/file1.dat", "/data/file2.dat"),
                "sample_delete" to listOf("/data/old.log"),
                "critical_protected" to 0,
            )))
        }

        client.request("/recovery/$sessionId/confirm") {
            it.post(jsonBody(mapOf("user_confirmation" to true, "cold_tier" to false)))
        }

        return sessionId
    }
}
