package pl.danielniemiec.properbackup.buffer.storage

import java.io.IOException
import java.nio.file.FileStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Writes sealed packs to local filesystem (dedicated OVH server, RAID5 ~11 TB at /mnt/storage).
 *
 * Guarantees:
 * - Atomic write: .tmp -> fsync -> rename (crash-safe per HR-5)
 * - SHA-256 sidecar: {packName}.sha256 written alongside each pack
 * - Integrity verification: compare stored sha256 against recomputed hash
 * - Instant restore: packs read directly from disk (no unsealing/thawing)
 */
class LocalFsStorageClient(
    private val storageDir: Path,
) : StorageClient {

    init {
        Files.createDirectories(storageDir)
    }

    override fun putPack(packName: String, data: ByteArray): StorageClient.PackWriteResult {
        val target = storageDir.resolve(packName)
        Files.createDirectories(target.parent)

        val sha256 = sha256Hex(data)

        val tmp = target.resolveSibling("${target.fileName}.tmp")
        try {
            Files.newOutputStream(tmp).use { out ->
                out.write(data)
                out.flush()
            }
            // fsync the tmp file for durability
            java.io.FileOutputStream(tmp.toFile(), true).use { fos ->
                fos.fd.sync()
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.deleteIfExists(tmp)
            throw IOException("Failed to write pack $packName: ${e.message}", e)
        }

        // Write sha256 sidecar
        val sidecar = storageDir.resolve("$packName.sha256")
        Files.createDirectories(sidecar.parent)
        val sidecarTmp = sidecar.resolveSibling("${sidecar.fileName}.tmp")
        try {
            Files.writeString(sidecarTmp, "$sha256  $packName\n")
            Files.move(sidecarTmp, sidecar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.deleteIfExists(sidecarTmp)
            System.err.println("[LocalFsStorage] WARNING: sha256 sidecar write failed for $packName: ${e.message}")
        }

        println("[LocalFsStorage] PUT $packName (${data.size} bytes, sha256=$sha256)")
        return StorageClient.PackWriteResult(packName, data.size.toLong(), sha256)
    }

    override fun getPack(packName: String): ByteArray {
        val path = storageDir.resolve(packName)
        if (!Files.exists(path)) throw IOException("Pack not found: $packName")
        return Files.readAllBytes(path)
    }

    override fun exists(packName: String): Boolean {
        return Files.exists(storageDir.resolve(packName))
    }

    override fun listPacks(): List<String> {
        if (!Files.exists(storageDir)) return emptyList()
        return Files.walk(storageDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .map { storageDir.relativize(it).toString() }
                .filter { it.endsWith(".pack") }
                .sorted()
                .toList()
        }
    }

    override fun deletePack(packName: String) {
        Files.deleteIfExists(storageDir.resolve(packName))
        Files.deleteIfExists(storageDir.resolve("$packName.sha256"))
        println("[LocalFsStorage] DELETE $packName")
    }

    override fun verifyIntegrity(packName: String): Boolean {
        val path = storageDir.resolve(packName)
        if (!Files.exists(path)) return false

        val sidecar = storageDir.resolve("$packName.sha256")
        if (!Files.exists(sidecar)) {
            System.err.println("[LocalFsStorage] No sidecar for $packName — cannot verify")
            return false
        }

        val storedLine = Files.readString(sidecar).trim()
        val storedHash = storedLine.split("\\s+".toRegex()).firstOrNull() ?: return false

        val actualHash = sha256Hex(Files.readAllBytes(path))
        val match = storedHash.equals(actualHash, ignoreCase = true)
        if (!match) {
            System.err.println("[LocalFsStorage] INTEGRITY MISMATCH for $packName: expected=$storedHash actual=$actualHash")
        }
        return match
    }

    override fun availableBytes(): Long {
        val store: FileStore = Files.getFileStore(storageDir)
        return store.usableSpace
    }

    override fun usedBytes(): Long {
        if (!Files.exists(storageDir)) return 0L
        return Files.walk(storageDir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".pack") }
                .mapToLong { Files.size(it) }
                .sum()
        }
    }

    companion object {
        fun sha256Hex(data: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(data)
            return hash.joinToString("") { "%02x".format(it) }
        }
    }
}
