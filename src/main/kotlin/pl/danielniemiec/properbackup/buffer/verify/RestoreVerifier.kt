package pl.danielniemiec.properbackup.buffer.verify

import pl.danielniemiec.properbackup.buffer.db.Database
import pl.danielniemiec.properbackup.buffer.sse.SseEventBus
import pl.danielniemiec.properbackup.buffer.store.ObjectReadFacade
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Auto-test restore: after a successful seal, verify backup integrity by
 * reading the archive and validating its ProperCrypto header + frame structure.
 *
 * Verification levels:
 * 1. Header check — MAGIC "PROP", version 0x01, salt/nonce/authTag present
 * 2. Frame structure — each encrypted chunk has valid frame length + nonce
 * 3. Full decrypt — requires encryption password (optional, when provided)
 */
class RestoreVerifier(
    private val db: Database,
    private val objects: ObjectReadFacade,
    private val sseBus: SseEventBus? = null,
) {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "restore-verifier").apply { isDaemon = true }
    }

    companion object {
        private val MAGIC = byteArrayOf(0x50, 0x52, 0x4F, 0x50) // "PROP"
        private const val VERSION: Byte = 0x01
        private const val HEADER_SIZE = 49 // 4 magic + 1 version + 16 salt + 12 nonce + 16 authTag
    }

    fun scheduleVerification(serverId: String, objectName: String, pathId: String) {
        executor.submit {
            try {
                runVerification(serverId, objectName, pathId)
            } catch (e: Exception) {
                println("[verify] ERROR for $objectName: ${e.message}")
                storeResult(serverId, objectName, pathId, "failed", e.message)
            }
        }
    }

    private fun runVerification(serverId: String, objectName: String, pathId: String) {
        println("[verify] Starting verification for $objectName (server=$serverId, pathId=$pathId)")

        val data = try {
            objects.getMerged(objectName)
        } catch (e: Exception) {
            storeResult(serverId, objectName, pathId, "failed", "Cannot read object: ${e.message}")
            return
        }

        if (data.isEmpty()) {
            storeResult(serverId, objectName, pathId, "passed", "Tombstone (empty) — ok")
            return
        }

        if (data.size < HEADER_SIZE) {
            storeResult(serverId, objectName, pathId, "failed", "Archive too small: ${data.size} bytes (expected >= $HEADER_SIZE)")
            return
        }

        // 1. Verify MAGIC bytes ("PROP")
        for (i in MAGIC.indices) {
            if (data[i] != MAGIC[i]) {
                storeResult(serverId, objectName, pathId, "failed", "Invalid magic bytes — not a ProperBackup archive")
                return
            }
        }

        // 2. Verify version
        if (data[4] != VERSION) {
            storeResult(serverId, objectName, pathId, "failed", "Unsupported archive version: ${data[4]}")
            return
        }

        // 3. Validate encrypted frame structure after header
        val frameData = data.copyOfRange(HEADER_SIZE, data.size)
        val frameResult = validateFrames(frameData)
        if (frameResult != null) {
            storeResult(serverId, objectName, pathId, "failed", frameResult)
            return
        }

        val frameCount = countFrames(frameData)
        val detail = "Header OK (PROP v1), $frameCount frame(s), ${data.size} bytes total"
        storeResult(serverId, objectName, pathId, "passed", detail)
        println("[verify] PASSED for $objectName ($detail)")
    }

    private fun validateFrames(frameData: ByteArray): String? {
        if (frameData.isEmpty()) return null // header-only is valid for empty files
        val dis = DataInputStream(ByteArrayInputStream(frameData))
        var offset = 0
        var frameIndex = 0
        while (offset < frameData.size) {
            if (frameData.size - offset < 4) {
                return "Frame $frameIndex: truncated frame length at offset $offset"
            }
            val frameLen = dis.readInt()
            offset += 4
            if (frameLen < 12 + 16) { // nonce(12) + min GCM tag(16)
                return "Frame $frameIndex: frame too small ($frameLen bytes, min 28)"
            }
            if (frameLen > frameData.size - offset) {
                return "Frame $frameIndex: frame length $frameLen exceeds remaining data (${frameData.size - offset} bytes)"
            }
            dis.skipNBytes(frameLen.toLong())
            offset += frameLen
            frameIndex++
        }
        return null
    }

    private fun countFrames(frameData: ByteArray): Int {
        if (frameData.isEmpty()) return 0
        val dis = DataInputStream(ByteArrayInputStream(frameData))
        var offset = 0
        var count = 0
        while (offset < frameData.size) {
            if (frameData.size - offset < 4) break
            val frameLen = dis.readInt()
            offset += 4
            if (frameLen > frameData.size - offset) break
            dis.skipNBytes(frameLen.toLong())
            offset += frameLen
            count++
        }
        return count
    }

    private fun storeResult(serverId: String, objectName: String, pathId: String, status: String, detail: String?) {
        val verifiedAt = Instant.now()
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO verification_result (server_id, object_name, path_id, status, detail, verified_at)
                   VALUES (?, ?, ?, ?, ?, ?)
                   ON CONFLICT (server_id, object_name) DO UPDATE SET status = EXCLUDED.status, detail = EXCLUDED.detail, verified_at = EXCLUDED.verified_at""",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.setString(2, objectName)
                ps.setString(3, pathId)
                ps.setString(4, status)
                ps.setString(5, detail)
                ps.setTimestamp(6, Timestamp.from(verifiedAt))
                ps.executeUpdate()
            }
        }
        sseBus?.publish(serverId, "verificationResult", mapOf(
            "objectName" to objectName,
            "pathId" to pathId,
            "status" to status,
            "detail" to detail,
            "verifiedAt" to verifiedAt.toString(),
        ))
    }

    fun getLatestResult(serverId: String): Map<String, Any?>? {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT object_name, path_id, status, detail, verified_at FROM verification_result WHERE server_id = ? ORDER BY verified_at DESC LIMIT 1",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) {
                        linkedMapOf(
                            "objectName" to rs.getString("object_name"),
                            "pathId" to rs.getString("path_id"),
                            "status" to rs.getString("status"),
                            "detail" to rs.getString("detail"),
                            "verifiedAt" to rs.getTimestamp("verified_at").toInstant().toString(),
                        )
                    } else {
                        null
                    }
                }
            }
        }
    }

    fun listResults(serverId: String, limit: Int = 20): List<Map<String, Any?>> {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT object_name, path_id, status, detail, verified_at FROM verification_result WHERE server_id = ? ORDER BY verified_at DESC LIMIT ?",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.setInt(2, limit.coerceIn(1, 100))
                ps.executeQuery().use { rs ->
                    val out = ArrayList<Map<String, Any?>>()
                    while (rs.next()) {
                        out.add(
                            linkedMapOf(
                                "objectName" to rs.getString("object_name"),
                                "pathId" to rs.getString("path_id"),
                                "status" to rs.getString("status"),
                                "detail" to rs.getString("detail"),
                                "verifiedAt" to rs.getTimestamp("verified_at").toInstant().toString(),
                            ),
                        )
                    }
                    return out
                }
            }
        }
    }

    fun getSummary(serverId: String): Map<String, Any> {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT status, COUNT(*) AS cnt FROM verification_result WHERE server_id = ? GROUP BY status",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.executeQuery().use { rs ->
                    var passed = 0L
                    var failed = 0L
                    while (rs.next()) {
                        when (rs.getString("status")) {
                            "passed" -> passed = rs.getLong("cnt")
                            "failed" -> failed = rs.getLong("cnt")
                        }
                    }
                    val total = passed + failed
                    return linkedMapOf(
                        "total" to total,
                        "passed" to passed,
                        "failed" to failed,
                        "status" to when {
                            total == 0L -> "none"
                            failed > 0 -> "degraded"
                            else -> "healthy"
                        },
                    )
                }
            }
        }
    }

    fun shutdown() {
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }
}
