package pl.danielniemiec.properbackup.buffer.inbox

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.streams.toList

class ChunkStorage(private val inboxDir: Path) {

    init {
        Files.createDirectories(inboxDir)
    }

    @Throws(IOException::class)
    fun store(userId: String, pathId: String, data: ByteArray, flag: String) {
        val dir = inboxDir.resolve(userId).resolve(pathId)
        Files.createDirectories(dir)
        val name = System.currentTimeMillis().toString() + "-" + flag + ".part"
        val tmp = dir.resolve(name + ".tmp")
        Files.write(tmp, data)
        Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE)
    }

    @Throws(IOException::class)
    fun listUserIds(): List<String> {
        if (!Files.exists(inboxDir)) return emptyList()
        return Files.list(inboxDir).use { dirs ->
            dirs.filter { Files.isDirectory(it) }.map { it.fileName.toString() }.toList()
        }
    }

    @Throws(IOException::class)
    fun getPathDirs(userId: String): List<Path> {
        val userDir = inboxDir.resolve(userId)
        if (!Files.exists(userDir)) return emptyList()
        return Files.list(userDir).use { dirs ->
            dirs.filter { Files.isDirectory(it) }.toList()
        }
    }

    @Throws(IOException::class)
    fun getParts(userId: String, pathId: String): List<Path> {
        val dir = inboxDir.resolve(userId).resolve(pathId)
        if (!Files.exists(dir)) return emptyList()
        return Files.list(dir).use { files ->
            files.filter { it.toString().endsWith(".part") }.sorted().toList()
        }
    }

    @Throws(IOException::class)
    fun totalSize(userId: String, pathId: String): Long =
        getParts(userId, pathId).sumOf { p ->
            try {
                Files.size(p)
            } catch (e: IOException) {
                0L
            }
        }
}
