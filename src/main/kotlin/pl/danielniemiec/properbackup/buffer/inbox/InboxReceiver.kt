package pl.danielniemiec.properbackup.buffer.inbox

import io.javalin.http.Context
import org.slf4j.LoggerFactory
import pl.danielniemiec.properbackup.buffer.server.ServerStore

class InboxReceiver(
    private val storage: ChunkStorage,
    private val diskGuard: DiskGuard,
    private val expectedToken: String,
    private val serverStore: ServerStore? = null,
) {
    private val log = LoggerFactory.getLogger(InboxReceiver::class.java)

    fun handle(ctx: Context) {
        val auth = ctx.header("Authorization")
        if (auth == null || auth != "Bearer $expectedToken") {
            ctx.status(401).result("Unauthorized")
            return
        }
        if (!diskGuard.hasSpace()) {
            ctx.status(503).header("Retry-After", "3600").result("Buffer full")
            return
        }
        val body = ctx.bodyAsBytes()
        if (body.size > MAX_PAYLOAD) {
            ctx.status(413).result("Payload too large")
            return
        }
        val pathId = ctx.header("X-Path-Id")
        val origPath = ctx.header("X-Original-Path")
        val flag = ctx.header("X-Flag")
        val serverId = ctx.header("X-Server-Id")
        val userId = if (!serverId.isNullOrBlank() && serverStore != null) {
            serverStore.findById(serverId).map { it.userId }.orElse("U1")
        } else {
            "U1"
        }

        if (pathId == null || flag == null) {
            ctx.status(400).result("Missing X-Path-Id or X-Flag")
            return
        }

        storage.store(userId, pathId, body, flag ?: "A")
        ctx.status(200).result("OK")
        log.info("Stored {} bytes for {} ({}) flag={}", body.size, pathId, origPath, flag)
    }

    companion object {
        private const val MAX_PAYLOAD = 100 * 1024 * 1024
    }
}
