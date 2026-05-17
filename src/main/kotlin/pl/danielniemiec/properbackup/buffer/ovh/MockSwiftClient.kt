package pl.danielniemiec.properbackup.buffer.ovh

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.streams.toList

class MockSwiftClient(private val mockDir: Path) : SwiftClient {

    private val log = LoggerFactory.getLogger(MockSwiftClient::class.java)
    private val existsCache = ConcurrentHashMap<String, Boolean>()

    init {
        Files.createDirectories(mockDir)
        log.info("Mock OVH at: {}", mockDir.toAbsolutePath())
    }

    override fun put(objectName: String, data: ByteArray) {
        val target = mockDir.resolve(objectName)
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.write(tmp, data)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        existsCache[objectName] = true
        log.info("PUT {} ({} bytes)", objectName, data.size)
    }

    override fun get(objectName: String): ByteArray = Files.readAllBytes(mockDir.resolve(objectName))

    override fun list(): List<String> {
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
        log.info("DELETE {}", objectName)
    }

    override fun exists(objectName: String): Boolean = Files.exists(mockDir.resolve(objectName))
}
