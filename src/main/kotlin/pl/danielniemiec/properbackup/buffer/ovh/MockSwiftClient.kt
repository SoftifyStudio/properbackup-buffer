package pl.danielniemiec.properbackup.buffer.ovh

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.streams.toList

class MockSwiftClient(
    private val mockDir: Path,
    private val simulateLatencyMs: Long = 0L,
    private val errorRatePercent: Int = 0,
) : SwiftClient {

    private val existsCache = ConcurrentHashMap<String, Boolean>()
    private val sealedObjects = ConcurrentHashMap<String, Long>()
    private val unsealStartTimes = ConcurrentHashMap<String, Long>()
    private val unsealDurationMs: Long = 5_000L

    init {
        Files.createDirectories(mockDir)
        println("[MockSwiftClient] Mock OVH at: ${mockDir.toAbsolutePath()}")
    }

    private fun maybeFail(operation: String) {
        if (errorRatePercent > 0 && (Math.random() * 100) < errorRatePercent) {
            throw IOException("MockSwiftClient simulated 503 on $operation")
        }
    }

    private fun maybeDelay() {
        if (simulateLatencyMs > 0) {
            try {
                Thread.sleep(simulateLatencyMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    override fun put(objectName: String, data: ByteArray) {
        maybeDelay()
        maybeFail("PUT $objectName")
        val target = mockDir.resolve(objectName)
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.write(tmp, data)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        existsCache[objectName] = true
        println("[MockSwiftClient] PUT $objectName (${data.size} bytes)")
    }

    override fun get(objectName: String): ByteArray {
        maybeDelay()
        maybeFail("GET $objectName")
        val sealTime = sealedObjects[objectName]
        if (sealTime != null) {
            val unsealStart = unsealStartTimes[objectName]
            if (unsealStart == null || (System.currentTimeMillis() - unsealStart) < unsealDurationMs) {
                throw IOException("Object is sealed (Cloud Archive): $objectName")
            }
        }
        return Files.readAllBytes(mockDir.resolve(objectName))
    }

    override fun list(): List<String> {
        maybeDelay()
        maybeFail("LIST")
        return Files.walk(mockDir).use { files ->
            files.filter { Files.isRegularFile(it) }
                .map { mockDir.relativize(it).toString() }
                .filter { !it.endsWith(".tmp") }
                .sorted()
                .toList()
        }
    }

    override fun delete(objectName: String) {
        Files.deleteIfExists(mockDir.resolve(objectName))
        existsCache.remove(objectName)
        sealedObjects.remove(objectName)
        unsealStartTimes.remove(objectName)
        println("[MockSwiftClient] DELETE $objectName")
    }

    override fun exists(objectName: String): Boolean = Files.exists(mockDir.resolve(objectName))

    override fun headObject(objectName: String): ObjectMetadata? {
        maybeDelay()
        val path = mockDir.resolve(objectName)
        if (!Files.exists(path)) return null
        val size = Files.size(path)
        val data = Files.readAllBytes(path)
        val md5 = MessageDigest.getInstance("MD5").digest(data)
        val etag = md5.joinToString("") { "%02x".format(it) }
        val headers = mutableMapOf<String, String>()
        val sealTime = sealedObjects[objectName]
        if (sealTime != null) {
            val unsealStart = unsealStartTimes[objectName]
            if (unsealStart == null) {
                headers["x-ovh-retrieval-state"] = "sealed"
            } else if ((System.currentTimeMillis() - unsealStart) < unsealDurationMs) {
                headers["x-ovh-retrieval-state"] = "unsealing"
            } else {
                headers["x-ovh-retrieval-state"] = "unsealed"
            }
        }
        return ObjectMetadata(
            contentLength = size,
            etag = etag,
            contentType = "application/octet-stream",
            headers = headers,
        )
    }

    override fun requestUnseal(objectName: String): UnsealStatus {
        maybeDelay()
        if (!Files.exists(mockDir.resolve(objectName))) return UnsealStatus.NOT_FOUND
        sealedObjects[objectName] ?: return UnsealStatus.UNSEALED

        val unsealStart = unsealStartTimes[objectName]
        if (unsealStart == null) {
            unsealStartTimes[objectName] = System.currentTimeMillis()
            return UnsealStatus.UNSEALING
        }
        return if ((System.currentTimeMillis() - unsealStart) >= unsealDurationMs) {
            sealedObjects.remove(objectName)
            unsealStartTimes.remove(objectName)
            UnsealStatus.UNSEALED
        } else {
            UnsealStatus.UNSEALING
        }
    }

    override fun checkUnsealStatus(objectName: String): UnsealStatus {
        if (!Files.exists(mockDir.resolve(objectName))) return UnsealStatus.NOT_FOUND
        sealedObjects[objectName] ?: return UnsealStatus.UNSEALED

        val unsealStart = unsealStartTimes[objectName]
        if (unsealStart == null) return UnsealStatus.SEALED
        return if ((System.currentTimeMillis() - unsealStart) >= unsealDurationMs) {
            sealedObjects.remove(objectName)
            unsealStartTimes.remove(objectName)
            UnsealStatus.UNSEALED
        } else {
            UnsealStatus.UNSEALING
        }
    }

    fun simulateSeal(objectName: String) {
        sealedObjects[objectName] = System.currentTimeMillis()
        unsealStartTimes.remove(objectName)
    }


}
