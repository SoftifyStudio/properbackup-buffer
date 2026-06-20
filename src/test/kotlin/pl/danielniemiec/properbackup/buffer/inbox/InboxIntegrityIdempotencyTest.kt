package pl.danielniemiec.properbackup.buffer.inbox

import io.javalin.Javalin
import io.javalin.testtools.JavalinTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.streams.toList

/**
 * [BUF-A1] Integrity + idempotency on the ingest path.
 *
 * Protects against the silent-corruption bug: when the agent retries an upload
 * after a lost response, the buffer must NOT create a second `.part` file for
 * the same content (which `ChunkSealer.seal()` would later concatenate into a
 * corrupted object). Also: a body whose declared SHA-256 does not match the
 * received bytes must be rejected (HR-6 integrity), storing nothing.
 */
class InboxIntegrityIdempotencyTest {

    private val token = "test-upload-token"
    private val octet = "application/octet-stream".toMediaType()

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }

    private fun createApp(inboxDir: Path): Javalin {
        val storage = ChunkStorage(inboxDir)
        val diskGuard = DiskGuard(inboxDir, minFreeBytes = 0L)
        val inbox = InboxReceiver(storage, diskGuard, token, null)
        return Javalin.create().apply {
            before("/upload", PayloadGuard())
            post("/upload") { ctx -> inbox.handle(ctx) }
        }
    }

    private fun partFiles(inboxDir: Path, userId: String, pathId: String): List<Path> {
        val dir = inboxDir.resolve(userId).resolve(pathId)
        if (!Files.exists(dir)) return emptyList()
        return Files.list(dir).use { s -> s.filter { it.toString().endsWith(".part") }.toList() }
    }

    @Test
    fun `retried identical upload does not create a duplicate part`(@TempDir tmp: Path) {
        val inboxDir = tmp.resolve("inbox")
        val body = "hello-backup-payload".toByteArray()
        val sha = sha256Hex(body)
        val pathId = "p-abc123"

        JavalinTest.test(createApp(inboxDir)) { _, client ->
            repeat(2) {
                val resp = client.request("/upload") {
                    it.post(body.toRequestBody(octet))
                        .header("Authorization", "Bearer $token")
                        .header("X-Path-Id", pathId)
                        .header("X-Flag", "A")
                        .header("X-Content-Sha256", sha)
                }
                assertEquals(200, resp.code, "upload should succeed")
            }
        }

        val parts = partFiles(inboxDir, "U1", pathId)
        assertEquals(1, parts.size, "retry of identical content must not duplicate the part")
    }

    @Test
    fun `upload with mismatched content sha is rejected and stores nothing`(@TempDir tmp: Path) {
        val inboxDir = tmp.resolve("inbox")
        val body = "real-bytes".toByteArray()
        val wrongSha = sha256Hex("different-bytes".toByteArray())
        val pathId = "p-mismatch"

        JavalinTest.test(createApp(inboxDir)) { _, client ->
            val resp = client.request("/upload") {
                it.post(body.toRequestBody(octet))
                    .header("Authorization", "Bearer $token")
                    .header("X-Path-Id", pathId)
                    .header("X-Flag", "A")
                    .header("X-Content-Sha256", wrongSha)
            }
            assertEquals(400, resp.code, "integrity check must reject mismatched sha256")
        }

        assertEquals(0, partFiles(inboxDir, "U1", pathId).size, "rejected upload must store nothing")
    }

    @Test
    fun `valid upload stores exactly one part`(@TempDir tmp: Path) {
        val inboxDir = tmp.resolve("inbox")
        val body = "valid-payload-content".toByteArray()
        val sha = sha256Hex(body)
        val pathId = "p-ok"

        JavalinTest.test(createApp(inboxDir)) { _, client ->
            val resp = client.request("/upload") {
                it.post(body.toRequestBody(octet))
                    .header("Authorization", "Bearer $token")
                    .header("X-Path-Id", pathId)
                    .header("X-Flag", "A")
                    .header("X-Content-Sha256", sha)
            }
            assertEquals(200, resp.code)
        }

        assertEquals(1, partFiles(inboxDir, "U1", pathId).size)
    }
}
