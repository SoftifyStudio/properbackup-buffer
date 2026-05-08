package pl.danielniemiec.properbackup.buffer.flush

import pl.danielniemiec.properbackup.buffer.inbox.ChunkStorage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FlushTrigger(
    private val storage: ChunkStorage,
    private val sealer: ChunkSealer,
    private val budgetGuard: BudgetGuard? = null,
    private val flushAgeMs: Long = System.getenv("PROPERBACKUP_FLUSH_AGE_SECONDS")?.toLongOrNull()?.times(1000L)
        ?: DEFAULT_FLUSH_AGE_MS,
) {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val pathRegistry = ConcurrentHashMap<String, Array<String>>()

    fun registerPath(pathId: String, originalPath: String?, serverId: String?) {
        pathRegistry[pathId] = arrayOf(originalPath ?: "", serverId ?: "")
    }

    fun start() {
        scheduler.scheduleAtFixedRate({ checkAndFlush() }, 5, CHECK_INTERVAL_MS / 1000, TimeUnit.SECONDS)
        println("[flush] FlushTrigger started (check every 60s, age threshold=${flushAgeMs / 1000}s)")
    }

    fun stop() {
        scheduler.shutdown()
    }

    fun checkAndFlush() {
        try {
            for (userId in storage.listUserIds()) {
                val userDirs = storage.getPathDirs(userId)
                for (pathDir in userDirs) {
                    val pathId = pathDir.fileName.toString()
                    val totalSize = storage.totalSize(userId, pathId)
                    val sizeTriggered = totalSize >= FLUSH_SIZE_BYTES

                    val parts = storage.getParts(userId, pathId)
                    var ageTriggered = false
                    if (parts.isNotEmpty()) {
                        val firstName = parts[0].fileName.toString()
                        try {
                            val ts = firstName.split("-")[0].toLong()
                            ageTriggered = (System.currentTimeMillis() - ts) >= flushAgeMs
                        } catch (ignored: NumberFormatException) {
                        }
                    }

                    if (sizeTriggered || ageTriggered) {
                        if (budgetGuard != null && budgetGuard.remaining(userId, pathId) <= 0) {
                            println("[flush] budget exhausted — skip seal for $userId/$pathId")
                            continue
                        }
                        val reason = if (sizeTriggered) "size" else "age"
                        println("[flush] sealing $userId/$pathId (reason=$reason, size=$totalSize)")
                        val reg = pathRegistry[pathId] ?: arrayOf("", "")
                        try {
                            sealer.seal(userId, pathId, reg[0].ifEmpty { null }, reg[1].ifEmpty { null })
                            budgetGuard?.tryConsume(userId, pathId)
                        } catch (e: Exception) {
                            System.err.println("[flush] seal failed for $userId/$pathId: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[flush] checkAndFlush error: ${e.message}")
        }
    }

    fun flushAll() {
        try {
            for (userId in storage.listUserIds()) {
                val userDirs = storage.getPathDirs(userId)
                for (pathDir in userDirs) {
                    val pathId = pathDir.fileName.toString()
                    val parts = storage.getParts(userId, pathId)
                    if (parts.isNotEmpty()) {
                        if (budgetGuard != null && budgetGuard.remaining(userId, pathId) <= 0) {
                            println("[flush] budget exhausted — skip flushAll for $userId/$pathId")
                            continue
                        }
                        val reg = pathRegistry[pathId] ?: arrayOf("", "")
                        try {
                            sealer.seal(userId, pathId, reg[0].ifEmpty { null }, reg[1].ifEmpty { null })
                            budgetGuard?.tryConsume(userId, pathId)
                        } catch (e: Exception) {
                            System.err.println("[flush] seal failed for $userId/$pathId: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[flush] flushAll error: ${e.message}")
        }
    }

    companion object {
        private const val FLUSH_SIZE_BYTES = 900L * 1024 * 1024
        private const val DEFAULT_FLUSH_AGE_MS = 24 * 60 * 60 * 1000L
        private const val CHECK_INTERVAL_MS = 60_000L
    }
}
