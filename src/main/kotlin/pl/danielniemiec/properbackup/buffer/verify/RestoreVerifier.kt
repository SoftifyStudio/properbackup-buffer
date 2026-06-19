package pl.danielniemiec.properbackup.buffer.verify

import pl.danielniemiec.properbackup.buffer.db.Database
import pl.danielniemiec.properbackup.buffer.store.ObjectReadFacade
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Server-side backup verification: after a successful seal, check the stored
 * object on a background thread.
 *
 * The buffer is zero-knowledge — it never holds the encryption password — so it
 * cannot decrypt and therefore cannot perform a true restore. That check runs on
 * the agent (properbackup-shared RestoreVerification), which has the password.
 * Here we validate the ProperCrypto container format and frame framing via
 * [ContainerInspector], which reliably detects truncated or garbled uploads.
 */
class RestoreVerifier(
    private val db: Database,
    private val objects: ObjectReadFacade,
) {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "restore-verifier").apply { isDaemon = true }
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

        // Buffer is zero-knowledge (no password), so it cannot decrypt to do a
        // true restore — that runs on the agent (see properbackup-shared
        // RestoreVerification). Here we validate the container format and frame
        // framing, which detects truncated/garbled uploads.
        val inspection = ContainerInspector.inspect(data)
        if (inspection.ok) {
            storeResult(serverId, objectName, pathId, "passed", inspection.detail)
            println("[verify] PASSED for $objectName (${inspection.detail})")
        } else {
            storeResult(serverId, objectName, pathId, "failed", inspection.detail)
            println("[verify] FAILED for $objectName: ${inspection.detail}")
        }
    }

    private fun storeResult(serverId: String, objectName: String, pathId: String, status: String, detail: String?) {
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
                ps.setTimestamp(6, Timestamp.from(Instant.now()))
                ps.executeUpdate()
            }
        }
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

    fun shutdown() {
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }
}
