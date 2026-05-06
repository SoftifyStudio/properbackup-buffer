package pl.danielniemiec.properbackup.buffer.logs

import com.google.gson.Gson
import io.javalin.http.Context
import io.javalin.http.ForbiddenResponse
import pl.danielniemiec.properbackup.buffer.db.Database
import pl.danielniemiec.properbackup.buffer.pathsidx.BackupRootStore
import pl.danielniemiec.properbackup.buffer.pathsidx.PathsIdxStore
import java.time.Instant

class LogApiHandler(
    private val logStore: StackLogStore,
    private val fileStateStore: FileStateStore,
    private val fileEventStore: MachineFileEventStore,
    private val backupRootStore: BackupRootStore,
    private val agentStatusStore: AgentStatusStore,
    private val db: Database,
    private val ingestToken: String,
    private val limiter: TokenBucketLimiter = TokenBucketLimiter(),
) {
    private val gson = Gson()

    fun ingestLog(ctx: Context) {
        if (!checkToken(ctx)) return
        val req = gson.fromJson(ctx.body(), LogIngestReq::class.java)
        if (req?.machineId.isNullOrBlank() || req?.message.isNullOrBlank()) {
            ctx.status(400).result("machineId and message required")
            return
        }
        val key = "${req.machineId}|${req.component ?: "Agent"}"
        if (!limiter.tryAcquire(key)) {
            ctx.status(202).result("dropped_by_rate_limit")
            return
        }
        val dropped = limiter.consumeDropped(key)
        logStore.ingest(req, dropped)
        ctx.status(202).result("ok")
    }

    fun ingestFileState(ctx: Context) {
        if (!checkToken(ctx)) return
        val req = gson.fromJson(ctx.body(), FileStateReq::class.java)
        if (req?.machineId.isNullOrBlank() || req?.fullPath.isNullOrBlank()) {
            ctx.status(400).result("machineId and fullPath required")
            return
        }
        val prev = fileStateStore.getCurrentStatus(req.machineId!!, req.fullPath!!)
        val next = req.status ?: "current"
        if (prev != next) {
            fileEventStore.insert(req.machineId!!, req.fullPath!!, next, prev)
        }
        fileStateStore.upsert(req)
        ctx.status(202).result("ok")
    }

    fun ingestBackupRoots(ctx: Context) {
        if (!checkToken(ctx)) return
        val req = gson.fromJson(ctx.body(), BackupRootsReq::class.java)
        val mid = req?.machineId?.trim()
        if (mid.isNullOrBlank() || req.roots.isNullOrEmpty()) {
            ctx.status(400).result("machineId and roots required")
            return
        }
        val pairs = req.roots.mapNotNull { r ->
            val pid = r.pathId?.trim()
            val rp = r.rootPath?.trim()
            if (pid.isNullOrBlank() || rp.isNullOrBlank()) null else pid to rp
        }
        if (pairs.isEmpty()) {
            ctx.status(400).result("valid roots required")
            return
        }
        backupRootStore.upsertBatch(mid, pairs)
        ctx.status(202).result("ok")
    }

    fun ingestRegisterPaths(ctx: Context) {
        if (!checkToken(ctx)) return
        val req = gson.fromJson(ctx.body(), RegisterPathsReq::class.java)
        val sid = req?.serverId?.trim()
        if (sid.isNullOrBlank() || req.paths.isNullOrEmpty()) {
            ctx.status(400).result("serverId and paths required")
            return
        }
        val idx = PathsIdxStore(db, sid)
        for (p in req.paths) {
            val pid = p.pathId?.trim()
            val op = p.originalPath?.trim()
            if (!pid.isNullOrBlank() && !op.isNullOrBlank()) {
                idx.register(pid, op)
            }
        }
        ctx.status(202).result("ok")
    }

    fun ingestAgentStatus(ctx: Context) {
        if (!checkToken(ctx)) return
        val req = gson.fromJson(ctx.body(), AgentStatusReq::class.java)
        val mid = req?.machineId?.trim()
        val phase = req?.phase?.trim()
        if (mid.isNullOrBlank() || phase.isNullOrBlank()) {
            ctx.status(400).result("machineId and phase required")
            return
        }
        agentStatusStore.upsert(
            mid, phase, req.detail?.trim()?.ifBlank { null },
            req.totalFiles, req.scannedFiles, req.currentFile?.trim()?.ifBlank { null },
        )
        ctx.status(202).result("ok")
    }

    fun listLogsAdmin(ctx: Context) {
        requireAdmin(ctx)
        val machineId = ctx.queryParam("machineId")
        val component = ctx.queryParam("component")
        val eventCode = ctx.queryParam("eventCode")?.toIntOrNull()
        val level = ctx.queryParam("level")
        val since = ctx.queryParam("since")?.let {
            runCatching { Instant.parse(it) }.getOrNull()
        }
        val limit = ctx.queryParam("limit")?.toIntOrNull() ?: 500
        val logs = logStore.list(machineId, component, eventCode, level, since, limit)
        ctx.contentType("application/json").result(gson.toJson(logs))
    }

    private fun checkToken(ctx: Context): Boolean {
        val auth = ctx.header("Authorization")
        if (auth == null || auth != "Bearer $ingestToken") {
            ctx.status(401).result("Unauthorized")
            return false
        }
        return true
    }

    private fun requireAdmin(ctx: Context) {
        if (java.lang.Boolean.TRUE != ctx.attribute<Boolean>("isSystemAdmin")) {
            throw ForbiddenResponse("Service admin only")
        }
    }
}
