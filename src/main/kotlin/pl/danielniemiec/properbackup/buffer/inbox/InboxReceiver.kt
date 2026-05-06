package pl.danielniemiec.properbackup.buffer.inbox

import io.javalin.http.Context

class InboxReceiver(
    private val storage: ChunkStorage,
    private val diskGuard: DiskGuard,
    private val expectedToken: String,
) {

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
        val userId = "U1"

        if (pathId == null || flag == null) {
            ctx.status(400).result("Missing X-Path-Id or X-Flag")
            return
        }

        storage.store(userId, pathId, body, flag ?: "A")
        ctx.status(200).result("OK")
        println("[inbox] stored ${body.size} bytes for $pathId ($origPath) flag=$flag")
    }

    companion object {
        private const val MAX_PAYLOAD = 100 * 1024 * 1024
    }
}
