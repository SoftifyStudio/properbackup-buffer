package pl.danielniemiec.properbackup.buffer.server

import com.google.gson.Gson
import io.javalin.http.Context
import io.javalin.http.ForbiddenResponse
import io.javalin.http.NotFoundResponse
import pl.danielniemiec.properbackup.buffer.auth.JwtService
import pl.danielniemiec.properbackup.buffer.auth.UserStore
import pl.danielniemiec.properbackup.buffer.db.Database
import pl.danielniemiec.properbackup.buffer.logs.AgentStatusStore
import pl.danielniemiec.properbackup.buffer.logs.FileStateStore
import pl.danielniemiec.properbackup.buffer.logs.MachineFileEventStore
import pl.danielniemiec.properbackup.buffer.pathsidx.ArchiveSnapshotStore
import pl.danielniemiec.properbackup.buffer.pathsidx.BackupRootStore
import pl.danielniemiec.properbackup.buffer.pathsidx.PathsIdxStore
import pl.danielniemiec.properbackup.buffer.store.ObjectReadFacade

class ServerHandler(
    private val serverStore: ServerStore,
    private val tokenStore: ActivationTokenStore,
    private val objects: ObjectReadFacade,
    private val db: Database,
    private val userStore: UserStore,
    private val jwtService: JwtService,
    private val uploadToken: String,
    private val fileStateStore: FileStateStore,
    private val fileEventStore: MachineFileEventStore,
    private val archiveSnapshotStore: ArchiveSnapshotStore,
    private val backupRootStore: BackupRootStore,
    private val agentStatusStore: AgentStatusStore,
) {

    private val gson = Gson()

    private data class GenBody(val forUserId: String?)

    private data class ActivateReq(val token: String?, val name: String?, val hostname: String?, val includeAuth: Boolean?)
    private data class AutoRegisterReq(val machineId: String?, val ownerEmail: String?, val name: String?)

    fun listServers(ctx: Context) {
        val userId = ctx.attribute<String>("userId")!!
        val admin = java.lang.Boolean.TRUE == ctx.attribute<Boolean>("isSystemAdmin")
        val list = if (admin) serverStore.listAll() else serverStore.listForUser(userId)
        ctx.contentType("application/json").result(gson.toJson(list))
    }

    fun listObjects(ctx: Context) {
        val serverId = ctx.pathParam("id")
        requireServerAccess(ctx, serverId)
        ctx.contentType("application/json").result(gson.toJson(objects.listMerged()))
    }

    fun getPathsIdx(ctx: Context) {
        val serverId = ctx.pathParam("id")
        requireServerAccess(ctx, serverId)
        val idxStore = PathsIdxStore(db, serverId)
        ctx.contentType("application/json").result(idxStore.toJson())
    }

    /** Paginowany indeks plików + skonfigurowane rooty (machine_id == serverId). */
    fun getInventory(ctx: Context) {
        val serverId = ctx.pathParam("id")
        requireServerAccess(ctx, serverId)
        val page = ctx.queryParam("page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val pageSize = (ctx.queryParam("pageSize")?.toIntOrNull() ?: 1000).coerceIn(1, 1000)
        val offset = (page - 1) * pageSize

        val machineId = serverId
        val totalFiles = fileStateStore.countForMachine(machineId)
        val files = fileStateStore.listPaged(machineId, offset, pageSize)
        val snapCounts = archiveSnapshotStore.countsByPathId(serverId)
        val rootRows = backupRootStore.listForMachine(machineId)
        val rootsOut = rootRows.map { r ->
            val n = snapCounts[r.pathId] ?: 0L
            mapOf(
                "pathId" to r.pathId,
                "rootPath" to r.rootPath,
                "snapshotCount" to n,
                "updatedAt" to r.updatedAt,
                "hasArchiveHistory" to (n > 0L),
            )
        }
        val agentStatus = agentStatusStore.get(machineId)
        ctx.contentType("application/json").result(
            gson.toJson(
                mapOf(
                    "roots" to rootsOut,
                    "files" to files,
                    "page" to page,
                    "pageSize" to pageSize,
                    "totalFiles" to totalFiles,
                    "hasMore" to (offset + files.size < totalFiles),
                    "agentStatus" to agentStatus,
                ),
            ),
        )
    }

    /**
     * Historia zmian statusu: dokładna ścieżka (`path`), prefiks folderu (`prefix`),
     * lub ostatnie zdarzenia na maszynie (bez path/prefix, opcjonalnie `status`).
     */
    fun getFileEvents(ctx: Context) {
        val serverId = ctx.pathParam("id")
        requireServerAccess(ctx, serverId)
        val path = ctx.queryParam("path")?.trim()
        val prefix = ctx.queryParam("prefix")?.trim()
        val status = ctx.queryParam("status")?.trim()?.takeIf { it.isNotEmpty() }
        val limit = (ctx.queryParam("limit")?.toIntOrNull() ?: 200).coerceIn(1, 2000)
        val offset = (ctx.queryParam("offset")?.toIntOrNull() ?: 0).coerceAtLeast(0)

        val rows = when {
            !path.isNullOrEmpty() -> fileEventStore.listByExactPath(serverId, path, limit)
            !prefix.isNullOrEmpty() -> fileEventStore.listByPrefix(serverId, prefix, limit, offset)
            else -> fileEventStore.listRecentForMachine(serverId, status, limit, offset)
        }
        ctx.contentType("application/json").result(gson.toJson(rows))
    }

    /** Paczki archiwum po seal (nazwa obiektu w storage). */
    fun listArchiveSnapshots(ctx: Context) {
        val serverId = ctx.pathParam("id")
        requireServerAccess(ctx, serverId)
        val pathId = ctx.queryParam("pathId")?.trim()
        val limit = (ctx.queryParam("limit")?.toIntOrNull() ?: 100).coerceIn(1, 2000)
        val rows = archiveSnapshotStore.listRecent(serverId, pathId, limit)
        ctx.contentType("application/json").result(gson.toJson(rows))
    }

    fun listActivationTokens(ctx: Context) {
        val userId = ctx.attribute<String>("userId")!!
        val admin = java.lang.Boolean.TRUE == ctx.attribute<Boolean>("isSystemAdmin")
        val list = if (admin) tokenStore.listAllPending() else tokenStore.listPendingForUser(userId)
        ctx.contentType("application/json").result(gson.toJson(list))
    }

    fun generateToken(ctx: Context) {
        val admin = java.lang.Boolean.TRUE == ctx.attribute<Boolean>("isSystemAdmin")
        var jwtUserId = ctx.attribute<String>("userId")!!
        var targetUserId = jwtUserId
        val body = ctx.body()
        if (!body.isNullOrBlank()) {
            val parsed = gson.fromJson(body, GenBody::class.java)
            if (parsed?.forUserId != null && parsed.forUserId.isNotBlank()) {
                if (!admin) {
                    throw ForbiddenResponse("Only system administrators may specify forUserId")
                }
                targetUserId = parsed.forUserId.trim()
                if (!userStore.findById(targetUserId).isPresent) {
                    ctx.status(400).result("Unknown forUserId")
                    return
                }
            }
        }
        val token = tokenStore.generate(targetUserId)
        ctx.contentType("application/json").result(gson.toJson(mapOf("token" to token)))
    }

    fun activate(ctx: Context) {
        val req = gson.fromJson(ctx.body(), ActivateReq::class.java)
        if (req?.token == null) {
            ctx.status(400).result("token required")
            return
        }

        db.getConnection().use { conn ->
            conn.autoCommit = false
            try {
                val tkOpt = tokenStore.findValid(conn, req.token!!)
                if (!tkOpt.isPresent) {
                    conn.rollback()
                    ctx.status(401).result("Invalid or expired activation token")
                    return
                }
                val tk = tkOpt.get()
                val name = req.name ?: (req.hostname ?: "Server")
                val server = serverStore.register(conn, tk.userId, name)
                tokenStore.markUsed(conn, req.token!!)
                conn.commit()

                val body = linkedMapOf<String, Any?>(
                    "serverId" to server.id,
                    "name" to server.name,
                )
                if (java.lang.Boolean.TRUE == req.includeAuth) {
                    val u = userStore.findById(tk.userId).orElseThrow()
                    val jwt = jwtService.generate(u.id, u.email, u.isSystemAdmin)
                    body["token"] = jwt
                    body["email"] = u.email
                    body["id"] = u.id
                    body["isSystemAdmin"] = u.isSystemAdmin
                }
                ctx.contentType("application/json").result(gson.toJson(body))
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun autoRegister(ctx: Context) {
        val auth = ctx.header("Authorization")
        if (auth == null || auth != "Bearer $uploadToken") {
            ctx.status(401).result("Unauthorized")
            return
        }
        val req = gson.fromJson(ctx.body(), AutoRegisterReq::class.java)
        val machineId = req?.machineId?.trim()
        val ownerEmail = req?.ownerEmail?.trim()?.lowercase()
        if (machineId.isNullOrBlank() || ownerEmail.isNullOrBlank()) {
            ctx.status(400).result("machineId and ownerEmail required")
            return
        }
        val owner = userStore.findByEmail(ownerEmail).orElse(null)
        if (owner == null) {
            ctx.status(404).result("Owner not found")
            return
        }
        val existing = serverStore.findByIdAndUser(machineId, owner.id)
        if (existing.isPresent) {
            serverStore.updateLastSeen(machineId)
            ctx.contentType("application/json").result(gson.toJson(mapOf("serverId" to machineId, "created" to false)))
            return
        }
        db.getConnection().use { conn ->
            conn.autoCommit = false
            try {
                val nm = req?.name?.trim()?.ifBlank { null } ?: machineId
                serverStore.registerWithId(conn, machineId, owner.id, nm)
                conn.commit()
                ctx.contentType("application/json").result(gson.toJson(mapOf("serverId" to machineId, "created" to true)))
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    private fun requireServerAccess(ctx: Context, serverId: String) {
        val server = serverStore.findById(serverId).orElseThrow { NotFoundResponse() }
        if (java.lang.Boolean.TRUE == ctx.attribute<Boolean>("isSystemAdmin")) return
        val userId = ctx.attribute<String>("userId")!!
        if (server.userId != userId) throw NotFoundResponse("Server not found")
    }
}
