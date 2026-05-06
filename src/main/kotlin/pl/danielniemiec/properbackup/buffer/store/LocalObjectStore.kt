package pl.danielniemiec.properbackup.buffer.store

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.streams.toList

class LocalObjectStore(private val storageDir: Path) : ObjectStore {

    init {
        Files.createDirectories(storageDir)
    }

    override fun put(objectName: String, data: ByteArray) {
        val target = storageDir.resolve(objectName)
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.write(tmp, data)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun get(objectName: String): ByteArray = Files.readAllBytes(storageDir.resolve(objectName))

    override fun list(): List<String> {
        return Files.walk(storageDir).use { files ->
            files.filter { Files.isRegularFile(it) }
                .map { storageDir.relativize(it).toString() }
                .filter { !it.endsWith(".tmp") }
                .sorted()
                .toList()
        }
    }

    override fun delete(objectName: String) {
        Files.deleteIfExists(storageDir.resolve(objectName))
    }
}
