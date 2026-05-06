package pl.danielniemiec.properbackup.buffer.flush

import pl.danielniemiec.properbackup.buffer.inbox.ChunkStorage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FlushTrigger(
    private val storage: ChunkStorage,
    private val sealer: ChunkSealer,
    private val budgetGuard: BudgetGuard? = null,
) {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val pathRegistry = ConcurrentHashMap<String, Array<String>>()

    fun registerPath(pathId: String, originalPath: String?, serverId: String?) {
        pathRegistry[pathId] = arrayOf(originalPath ?: "", serverId ?: "")
    }

    fun start() {
        scheduler.scheduleAtFixedRate({ checkAndFlush() }, 5, CHECK_INTERVAL_MS / 1000, TimeUnit.SECONDS)
        println("[flush] FlushTrigger started (check every 60s)")
    }

    fun stop() {
        scheduler.shutdown()
    }

    fun checkAndFlush() {
        try {
            val userDirs = storage.getPathDirs("U1")
            for (pathDir in userDirs) {
                val pathId = pathDir.fileName.toString()
                val totalSize = storage.totalSize("U1", pathId)
                val sizeTriggered = totalSize >= FLUSH_SIZE_BYTES

                val parts = storage.getParts("U1", pathId)
                var ageTriggered = false
                if (parts.isNotEmpty()) {
                    val firstName = parts[0].fileName.toString()
                    try {
                        val ts = firstName.split("-")[0].toLong()
                        ageTriggered = (System.currentTimeMillis() - ts) >= FLUSH_AGE_MS
                    } catch (ignored: NumberFormatException) {
                    }
                }

                if (sizeTriggered || ageTriggered) {
                    if (budgetGuard != null && budgetGuard.remaining("U1", pathId) <= 0) {
                        println("[flush] budget exhausted — skip seal for $pathId")
                        continue
                    }
                    val reason = if (sizeTriggered) "size" else "age"
                    println("[flush] sealing $pathId (reason=$reason, size=$totalSize)")
                    val reg = pathRegistry[pathId] ?: arrayOf("", "")
                    try {
                        sealer.seal("U1", pathId, reg[0].ifEmpty { null }, reg[1].ifEmpty { null })
                        budgetGuard?.tryConsume("U1", pathId)
                    } catch (e: Exception) {
                        System.err.println("[flush] seal failed for $pathId: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[flush] checkAndFlush error: ${e.message}")
        }
    }

    fun flushAll() {
        try {
            val userDirs = storage.getPathDirs("U1")
            for (pathDir in userDirs) {
                val pathId = pathDir.fileName.toString()
                val parts = storage.getParts("U1", pathId)
                if (parts.isNotEmpty()) {
                    if (budgetGuard != null && budgetGuard.remaining("U1", pathId) <= 0) {
                        println("[flush] budget exhausted — skip flushAll for $pathId")
                        continue
                    }
                    val reg = pathRegistry[pathId] ?: arrayOf("", "")
                    try {
                        sealer.seal("U1", pathId, reg[0].ifEmpty { null }, reg[1].ifEmpty { null })
                        budgetGuard?.tryConsume("U1", pathId)
                    } catch (e: Exception) {
                        System.err.println("[flush] seal failed for $pathId: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[flush] flushAll error: ${e.message}")
        }
    }

    companion object {
        private const val FLUSH_SIZE_BYTES = 900L * 1024 * 1024
        private const val FLUSH_AGE_MS = 24 * 60 * 60 * 1000L
        private const val CHECK_INTERVAL_MS = 60_000L
    }
}
