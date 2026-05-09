package pl.danielniemiec.properbackup.buffer

import com.google.gson.Gson
import io.javalin.Javalin
import io.javalin.http.ForbiddenResponse
import pl.danielniemiec.properbackup.buffer.auth.AuthHandler
import pl.danielniemiec.properbackup.buffer.auth.JwtFilter
import pl.danielniemiec.properbackup.buffer.auth.JwtService
import pl.danielniemiec.properbackup.buffer.auth.ServiceAdminCodeStore
import pl.danielniemiec.properbackup.buffer.auth.UserStore
import pl.danielniemiec.properbackup.buffer.db.Database
import pl.danielniemiec.properbackup.buffer.flush.BudgetGuard
import pl.danielniemiec.properbackup.buffer.flush.ChunkSealer
import pl.danielniemiec.properbackup.buffer.flush.FlushTrigger
import pl.danielniemiec.properbackup.buffer.flush.PackBuffer
import pl.danielniemiec.properbackup.buffer.flush.StorageQuotaGuard
import pl.danielniemiec.properbackup.buffer.inbox.ChunkStorage
import pl.danielniemiec.properbackup.buffer.inbox.DiskGuard
import pl.danielniemiec.properbackup.buffer.inbox.InboxReceiver
import pl.danielniemiec.properbackup.buffer.inbox.PayloadGuard
import pl.danielniemiec.properbackup.buffer.logs.AgentStatusStore
import pl.danielniemiec.properbackup.buffer.logs.FileStateStore
import pl.danielniemiec.properbackup.buffer.logs.LogApiHandler
import pl.danielniemiec.properbackup.buffer.logs.MachineFileEventStore
import pl.danielniemiec.properbackup.buffer.logs.LogIngestReq
import pl.danielniemiec.properbackup.buffer.logs.StackLogStore
import pl.danielniemiec.properbackup.buffer.ovh.DevSafetyGuard
import pl.danielniemiec.properbackup.buffer.ovh.MockSwiftClient
import pl.danielniemiec.properbackup.buffer.ovh.OvhSwiftClient
import pl.danielniemiec.properbackup.buffer.ovh.SwiftClient
import pl.danielniemiec.properbackup.buffer.payment.LemonSqueezyHandler
import pl.danielniemiec.properbackup.buffer.report.AuditReportHandler
import pl.danielniemiec.properbackup.buffer.verify.RestoreVerifier
import pl.danielniemiec.properbackup.buffer.subscription.SubscriptionGuard
import pl.danielniemiec.properbackup.buffer.subscription.SubscriptionHandler
import pl.danielniemiec.properbackup.buffer.subscription.TrialNotifier
import pl.danielniemiec.properbackup.buffer.pathsidx.ArchiveSnapshotStore
import pl.danielniemiec.properbackup.buffer.pathsidx.BackupConfigStore
import pl.danielniemiec.properbackup.buffer.pathsidx.BackupRootStore
import pl.danielniemiec.properbackup.buffer.pathsidx.PathsIdxStore
import pl.danielniemiec.properbackup.buffer.server.ActivationTokenStore
import pl.danielniemiec.properbackup.buffer.server.ServerHandler
import pl.danielniemiec.properbackup.buffer.server.ServerStore
import pl.danielniemiec.properbackup.buffer.sse.SseEventBus
import pl.danielniemiec.properbackup.buffer.store.LocalObjectStore
import pl.danielniemiec.properbackup.buffer.monitoring.AgentMetricsStore
import pl.danielniemiec.properbackup.buffer.store.ObjectReadFacade
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.concurrent.thread

fun main(args: Array<String>) {
    var port = 7100
    var storagePath = "/tmp/properbackup/storage"
    var inboxPath = "/tmp/properbackup/inbox"
    var token = "dev-token-local"
    var dbUrl = "jdbc:postgresql://localhost:5432/properbackup"
    var dbUser = "properbackup"
    var dbPass = System.getenv().getOrDefault("PROPERBACKUP_DB_PASSWORD", "dev-pg-pass")

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> port = args[++i].toInt()
            "--storage" -> storagePath = args[++i]
            "--inbox" -> inboxPath = args[++i]
            "--token" -> token = args[++i]
            "--db-url" -> dbUrl = args[++i]
            "--db-user" -> dbUser = args[++i]
            "--db-pass" -> dbPass = args[++i]
        }
        i++
    }

    val uploadToken = System.getenv().getOrDefault("PROPERBACKUP_UPLOAD_TOKEN", token)
    val ingestToken = System.getenv().getOrDefault("PROPERBACKUP_LOG_INGEST_TOKEN", uploadToken)
    val retentionDays = System.getenv().getOrDefault("PROPERBACKUP_LOG_RETENTION_DAYS", "14").toIntOrNull() ?: 14

    val storageDir = Path.of(storagePath)
    val inboxDir = Path.of(inboxPath)

    val db = Database(dbUrl, dbUser, dbPass)
    val logStore = StackLogStore(db)
    val fileStateStore = FileStateStore(db)
    val fileEventStore = MachineFileEventStore(db)
    val backupRootStore = BackupRootStore(db)
    val archiveSnapshotStore = ArchiveSnapshotStore(db)
    val agentStatusStore = AgentStatusStore(db)
    val agentMetricsStore = AgentMetricsStore(db)
    val sseBus = SseEventBus()
    val logApi = LogApiHandler(logStore, fileStateStore, fileEventStore, backupRootStore, agentStatusStore, db, ingestToken, sseBus = sseBus)

    val objectStore = LocalObjectStore(storageDir)
    val chunkStorage = ChunkStorage(inboxDir)
    val diskGuard = DiskGuard(inboxDir)

    val userStore = UserStore(db)
    val serverStore = ServerStore(db)

    val maxFlushes = System.getenv("PROPERBACKUP_MAX_FLUSHES_PER_DAY")?.toIntOrNull() ?: 50
    val budgetGuard = BudgetGuard(db, maxFlushes)
    val swiftRemote = swiftClientFromEnv(storageDir)
    val storageQuotaGuard = StorageQuotaGuard(db, userStore)
    val packBufferDir = Path.of(System.getenv().getOrDefault("PROPERBACKUP_PACK_BUFFER_DIR", "/tmp/properbackup/pack-buffer"))
    val packBuffer = PackBuffer(packBufferDir, db, swiftRemote)

    val sealer = ChunkSealer(chunkStorage, objectStore, db, swiftRemote, archiveSnapshotStore, storageQuotaGuard)
    sealer.setPackBuffer(packBuffer)
    val flusher = FlushTrigger(chunkStorage, sealer, budgetGuard)
    val inbox = InboxReceiver(chunkStorage, diskGuard, uploadToken, serverStore)

    val jwtSecret = System.getenv().getOrDefault("PROPERBACKUP_JWT_SECRET", "dev-jwt-secret-change-in-prod")
    val serviceCodeStore = ServiceAdminCodeStore(db)
    serviceCodeStore.ensureSeedCode(System.getenv().getOrDefault("PROPERBACKUP_SERVICE_ADMIN_SEED_CODE", "dt5mkom8Serwer.13"))
    val jwtService = JwtService(jwtSecret)
    val authHandler = AuthHandler(userStore, jwtService, serviceCodeStore)
    val jwtFilter = JwtFilter(jwtService)

    val tokenStore = ActivationTokenStore(db)
    val objectRead = ObjectReadFacade(objectStore, swiftRemote)
    val restoreVerifier = RestoreVerifier(db, objectRead)
    sealer.setVerifier(restoreVerifier)
    val backupConfigStore = BackupConfigStore(db)
    val serverHandler = ServerHandler(
        serverStore,
        tokenStore,
        objectRead,
        db,
        userStore,
        jwtService,
        uploadToken,
        fileStateStore,
        fileEventStore,
        archiveSnapshotStore,
        backupRootStore,
        agentStatusStore,
        backupConfigStore,
        restoreVerifier,
    )

    val subscriptionHandler = SubscriptionHandler(userStore, storageQuotaGuard)
    val subscriptionGuard = SubscriptionGuard(userStore)
    val trialNotifier = TrialNotifier(userStore, logStore)

    val lemonWebhookSecret = System.getenv().getOrDefault("LEMONSQUEEZY_WEBHOOK_SECRET", "")
    val lemonHandler = LemonSqueezyHandler(userStore, lemonWebhookSecret)

    val auditReportHandler = AuditReportHandler(
        serverStore, userStore, backupRootStore, archiveSnapshotStore, fileStateStore, storageQuotaGuard,
    )

    flusher.start()
    packBuffer.start()
    trialNotifier.start()

    val app = Javalin.create { cfg ->
        cfg.http.maxRequestSize = 100L * 1024 * 1024
        cfg.bundledPlugins.enableCors { cors -> cors.addRule { it.anyHost() } }
    }

    app.get("/health") { ctx -> ctx.status(200).contentType("text/plain").result("ok") }

    app.post("/auth/login", authHandler::login)
    app.post("/auth/register", authHandler::register)
    app.before("/auth/service-admin", jwtFilter)
    app.post("/auth/service-admin", authHandler::elevateServiceAdmin)
    app.post("/agent/logs", logApi::ingestLog)
    app.post("/agent/file-state", logApi::ingestFileState)
    app.post("/agent/file-states", logApi::ingestFileStateBatch)
    app.post("/agent/backup-roots", logApi::ingestBackupRoots)
    app.post("/agent/register-paths", logApi::ingestRegisterPaths)
    app.post("/agent/status", logApi::ingestAgentStatus)

    app.post("/agent/scan-complete") { ctx ->
        val auth = ctx.header("Authorization")
        if (auth == null || auth != "Bearer $uploadToken") {
            ctx.status(401).result("Unauthorized")
            return@post
        }
        val serverId = ctx.header("X-Server-Id")?.trim()
        println("[agent] scan-complete received" + if (!serverId.isNullOrBlank()) " (server=$serverId)" else "")
        Thread { flusher.flushAll() }.start()
        ctx.status(200).result("flush triggered")
    }

    app.post("/agent/metrics") { ctx ->
        val auth = ctx.header("Authorization")
        if (auth == null || auth != "Bearer $uploadToken") {
            ctx.status(401).result("Unauthorized")
            return@post
        }
        val body = Gson().fromJson(ctx.body(), Map::class.java)
        val serverId = (body["serverId"] as? String)?.trim()
        if (serverId.isNullOrBlank()) {
            ctx.status(400).result("serverId required")
            return@post
        }
        val cpu = (body["cpuPercent"] as? Number)?.toFloat() ?: 0f
        val ramUsed = (body["ramUsedMb"] as? Number)?.toFloat() ?: 0f
        val ramTotal = (body["ramTotalMb"] as? Number)?.toFloat() ?: 0f
        agentMetricsStore.insert(serverId, cpu, ramUsed, ramTotal)
        ctx.status(200).result("ok")
    }

    app.get("/agent/paths.idx") { ctx ->
        val auth = ctx.header("Authorization")
        if (auth == null || auth != "Bearer $uploadToken") {
            ctx.status(401).result("Unauthorized")
            return@get
        }
        val serverId = ctx.header("X-Server-Id")
        if (serverId.isNullOrBlank()) {
            ctx.status(400).result("Missing X-Server-Id")
            return@get
        }
        ctx.contentType("application/json").result(PathsIdxStore(db, serverId).toJson())
    }

    app.before("/upload", PayloadGuard())
    app.post("/upload") { ctx ->
        val pathId = ctx.header("X-Path-Id")
        val origPath = ctx.header("X-Original-Path")
        val serverId = ctx.header("X-Server-Id")
        if (pathId != null && origPath != null) {
            flusher.registerPath(pathId, origPath, serverId)
        }
        if (serverId != null) {
            try {
                serverStore.updateLastSeen(serverId)
            } catch (ignored: Exception) {
            }
        }
        inbox.handle(ctx)
    }

    app.post("/servers/activate", serverHandler::activate)
    app.post("/agent/auto-register", serverHandler::autoRegister)

    app.before("/servers/*", jwtFilter)
    app.before("/servers", jwtFilter)
    app.before("/objects", jwtFilter)
    app.before("/objects/*", jwtFilter)
    app.before("/flush", jwtFilter)
    app.before("/admin/*", jwtFilter)

    app.get("/admin/users") { ctx ->
        if (java.lang.Boolean.TRUE != ctx.attribute<Boolean>("isSystemAdmin")) {
            throw ForbiddenResponse()
        }
        ctx.contentType("application/json").result(Gson().toJson(userStore.listSummaries()))
    }
    app.get("/admin/logs", logApi::listLogsAdmin)

    app.before("/account/*", jwtFilter)
    app.get("/account/subscription", subscriptionHandler::getSubscription)
    app.get("/account/checkout", lemonHandler::getCheckoutUrl)

    app.post("/webhooks/lemonsqueezy", lemonHandler::handleWebhook)
    app.get("/account/storage") { ctx ->
        val userId = ctx.attribute<String>("userId")!!
        val q = storageQuotaGuard.checkQuota(userId)
        ctx.contentType("application/json").result(
            Gson().toJson(
                mapOf(
                    "usedBytes" to q.usedBytes,
                    "limitBytes" to q.limitBytes,
                    "remainingBytes" to q.remainingBytes,
                    "usedPercent" to q.usedPercent,
                    "exceeded" to q.exceeded,
                ),
            ),
        )
    }

    app.get("/health/inventory-summary") { ctx ->
        val auth = ctx.header("Authorization")
        if (auth == null || !auth.startsWith("Bearer ")) {
            ctx.status(401).result("Unauthorized")
            return@get
        }
        val serverId = ctx.queryParam("serverId")?.trim()
        if (serverId.isNullOrBlank()) {
            ctx.status(400).result("serverId required")
            return@get
        }
        val agentStatus = agentStatusStore.get(serverId)
        val totalFiles = fileStateStore.countForMachine(serverId)
        val rootsCount = backupRootStore.listForMachine(serverId).size
        val latestVerification = restoreVerifier.getLatestResult(serverId)
        ctx.contentType("application/json").result(
            Gson().toJson(
                mapOf(
                    "agentStatus" to agentStatus,
                    "totalFiles" to totalFiles,
                    "rootsCount" to rootsCount,
                    "latestVerification" to latestVerification,
                ),
            ),
        )
    }

    app.get("/servers", serverHandler::listServers)
    app.get("/servers/activation-tokens", serverHandler::listActivationTokens)
    app.post("/servers/generate-token", serverHandler::generateToken)
    app.get("/servers/{id}/objects", serverHandler::listObjects)
    app.get("/servers/{id}/paths.idx", serverHandler::getPathsIdx)
    app.sse("/servers/{id}/events") { client ->
        val ctx = client.ctx()
        val serverId = ctx.pathParam("id")
        val qToken = ctx.queryParam("token")?.trim()
        if (!qToken.isNullOrBlank()) {
            try {
                val claims = jwtService.verify(qToken)
                val userId = claims.subject
                val isAdmin = claims["sa"] == true
                val server = serverStore.findById(serverId)
                if (server.isPresent && (server.get().userId == userId || isAdmin)) {
                    sseBus.subscribe(serverId, client)
                    // Push current agent status immediately so Activity Log is populated on connect
                    val currentStatus = agentStatusStore.get(serverId)
                    if (currentStatus != null) {
                        try {
                            client.sendEvent("agentStatus", Gson().toJson(currentStatus))
                        } catch (_: Exception) { /* ignore if client disconnected */ }
                    }
                } else {
                    client.sendEvent("error", "\"forbidden\"")
                }
            } catch (_: Exception) {
                client.sendEvent("error", "\"invalid_token\"")
            }
        } else {
            client.sendEvent("error", "\"token_required\"")
        }
    }
    app.get("/servers/{id}/activity") { ctx ->
        val serverId = ctx.pathParam("id")
        val userId = ctx.attribute<String>("userId")!!
        val isAdmin = java.lang.Boolean.TRUE == ctx.attribute<Boolean>("isSystemAdmin")
        val server = serverStore.findById(serverId)
        if (!server.isPresent || (!isAdmin && server.get().userId != userId)) {
            ctx.status(404).result("Not found")
            return@get
        }
        val limit = (ctx.queryParam("limit")?.toIntOrNull() ?: 100).coerceIn(1, 500)
        val agentStatus = agentStatusStore.get(serverId)
        val recentLogs = logStore.list(serverId, null, null, null, null, limit)
        val recentFileEvents = fileEventStore.listRecentForMachine(serverId, null, limit, 0)
        ctx.contentType("application/json").result(Gson().toJson(mapOf(
            "agentStatus" to agentStatus,
            "recentLogs" to recentLogs,
            "recentFileEvents" to recentFileEvents,
        )))
    }
    app.get("/servers/{id}/inventory", serverHandler::getInventory)
    app.get("/servers/{id}/file-events", serverHandler::getFileEvents)
    app.get("/servers/{id}/file-states-at", serverHandler::getFileStatesAt)
    app.get("/servers/{id}/archive-snapshots", serverHandler::listArchiveSnapshots)
    app.get("/servers/{id}/audit-report", auditReportHandler::downloadReport)
    app.get("/servers/{id}/verification") { ctx ->
        val serverId = ctx.pathParam("id")
        val limit = ctx.queryParam("limit")?.toIntOrNull() ?: 20
        val latest = restoreVerifier.getLatestResult(serverId)
        val results = restoreVerifier.listResults(serverId, limit)
        ctx.contentType("application/json").result(
            Gson().toJson(mapOf("latest" to latest, "results" to results)),
        )
    }
    app.get("/servers/{id}/metrics") { ctx ->
        val serverId = ctx.pathParam("id")
        val userId = ctx.attribute<String>("userId")!!
        val isAdmin = java.lang.Boolean.TRUE == ctx.attribute<Boolean>("isSystemAdmin")
        val server = serverStore.findById(serverId)
        if (!server.isPresent || (!isAdmin && server.get().userId != userId)) {
            ctx.status(404).result("Not found")
            return@get
        }
        val fromParam = ctx.queryParam("from")?.trim()
        val toParam = ctx.queryParam("to")?.trim()
        val from = if (!fromParam.isNullOrBlank()) {
            try { Instant.parse(fromParam) } catch (_: Exception) { Instant.now().minus(24, ChronoUnit.HOURS) }
        } else {
            Instant.now().minus(24, ChronoUnit.HOURS)
        }
        val to = if (!toParam.isNullOrBlank()) {
            try { Instant.parse(toParam) } catch (_: Exception) { Instant.now() }
        } else {
            Instant.now()
        }
        val limit = (ctx.queryParam("limit")?.toIntOrNull() ?: 1000).coerceIn(1, 5000)
        val rows = agentMetricsStore.query(serverId, from, to, limit)
        ctx.contentType("application/json").result(Gson().toJson(mapOf("metrics" to rows, "from" to from.toString(), "to" to to.toString())))
    }
    app.get("/servers/{id}/backup-config", serverHandler::getBackupConfig)
    app.post("/servers/{id}/backup-config", serverHandler::addBackupPath)
    app.delete("/servers/{id}/backup-config", serverHandler::removeBackupPath)

    app.get("/objects") { ctx ->
        val list = objectRead.listMerged()
        ctx.contentType("application/json").result(Gson().toJson(list))
    }

    app.get("/objects/{name}") { ctx ->
        val name = ctx.pathParam("name")
        try {
            val data = objectRead.getMerged(name)
            ctx.contentType("application/octet-stream")
                .header("Content-Disposition", "attachment; filename=\"$name\"")
                .result(data)
        } catch (e: Exception) {
            ctx.status(404).result("Not found: $name")
        }
    }

    app.post("/flush") { ctx ->
        flusher.flushAll()
        ctx.result("flushed")
    }

    app.post("/flush-packs") { ctx ->
        packBuffer.flushAll()
        ctx.result("packs flushed")
    }

    app.get("/pack-buffer/status") { ctx ->
        val serverIds = mutableListOf<Map<String, Any>>()
        val bufDir = packBufferDir.toFile()
        if (bufDir.exists()) {
            for (dir in bufDir.listFiles()?.filter { it.isDirectory } ?: emptyList()) {
                val packs = dir.listFiles()?.filter { it.name.endsWith(".pack") } ?: emptyList()
                val totalBytes = packs.sumOf { it.length() }
                serverIds.add(mapOf(
                    "serverId" to dir.name,
                    "packCount" to packs.size,
                    "totalBytes" to totalBytes,
                    "packs" to packs.map { mapOf("name" to it.name, "sizeBytes" to it.length()) },
                ))
            }
        }
        ctx.contentType("application/json").result(Gson().toJson(mapOf("servers" to serverIds)))
    }

    app.start(port)
    println("ProperBackup Buffer listening on port $port")
    println("  storage    : ${storageDir.toAbsolutePath()}")
    println("  inbox      : ${inboxDir.toAbsolutePath()}")
    println("  pack-buffer: ${packBufferDir.toAbsolutePath()}")
    println("  db         : $dbUrl")
    logStore.ingest(
        LogIngestReq(
            machineId = "buffer-local",
            component = "Buffer",
            eventCode = -201,
            level = "INFO",
            message = "Buffer started",
            meta = mapOf("port" to port),
        ),
    )
    val snapshotRetentionDays = System.getenv().getOrDefault("PROPERBACKUP_SNAPSHOT_RETENTION_DAYS", retentionDays.toString()).toIntOrNull() ?: retentionDays
    thread(name = "logs-retention-cleaner", isDaemon = true) {
        while (!Thread.currentThread().isInterrupted) {
            try {
                Thread.sleep(60_000)
                logStore.deleteOlderThan(retentionDays)
                fileStateStore.deleteOlderThan(retentionDays)
                fileEventStore.deleteOlderThan(retentionDays)
                archiveSnapshotStore.deleteOlderThan(snapshotRetentionDays)
                agentMetricsStore.deleteOlderThan(retentionDays)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (_: Exception) {
            }
        }
    }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            flusher.stop()
            packBuffer.stop()
            app.stop()
            db.close()
        },
    )
}

private fun swiftClientFromEnv(storageDir: Path): SwiftClient? {
    val mockEnv = System.getenv("PROPERBACKUP_OVH_MOCK")
    val mockDisabled = mockEnv != null &&
        ("false".equals(mockEnv.trim(), ignoreCase = true) || mockEnv.trim() == "0")

    val real = OvhSwiftClient.tryCreateFromEnv()
    if (real != null) {
        println("[buffer] Swift remote: Keystone v3 + java.net.http.HttpClient")
        return DevSafetyGuard(real)
    }
    if (mockDisabled) {
        println("[buffer] No Swift credentials and PROPERBACKUP_OVH_MOCK=false — archive layer off")
        return null
    }
    val mockDir = Path.of(
        System.getenv().getOrDefault(
            "PROPERBACKUP_OVH_MOCK_DIR",
            storageDir.resolve("swift-mock").toString(),
        ),
    )
    println("[buffer] Swift mock (no OS_* / OVH creds): ${mockDir.toAbsolutePath()}")
    return DevSafetyGuard(MockSwiftClient(mockDir))
}
