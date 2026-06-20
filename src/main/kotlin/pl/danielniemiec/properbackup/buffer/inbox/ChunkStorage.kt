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

    /**
     * Persist a received fragment write-ahead (HR-1): `.tmp` -> atomic rename.
     *
     * The part filename is derived from the content SHA-256 so that a retried
     * upload of identical bytes resolves to the same file instead of creating a
     * duplicate that `ChunkSealer.seal()` would later concatenate into a
     * corrupted object. The rename uses REPLACE_EXISTING, making retries
     * idempotent at the filesystem level.
     */
    @Throws(IOException::class)
    fun store(userId: String, pathId: String, data: ByteArray, flag: String, contentSha: String) {
        val dir = inboxDir.resolve(userId).resolve(pathId)
        Files.createDirectories(dir)
        val name = "$contentSha-$flag.part"
        val target = dir.resolve(name)
        if (Files.exists(target)) return
        val tmp = dir.resolve("$name.${System.nanoTime()}.tmp")
        Files.write(tmp, data)
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
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
