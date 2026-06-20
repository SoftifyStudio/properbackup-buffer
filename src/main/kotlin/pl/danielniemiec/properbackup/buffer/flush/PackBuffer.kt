package pl.danielniemiec.properbackup.buffer.flush

import pl.danielniemiec.properbackup.buffer.db.Database
import pl.danielniemiec.properbackup.buffer.ovh.SwiftClient
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.DataInputStream
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.GZIPOutputStream
import java.util.zip.GZIPInputStream

/**
 * PackBuffer collects sealed chunks into packs (up to PACK_MAX_BYTES).
 * Packs live in a local buffer directory and can be read back before flushing.
 * When a pack is full it gets flushed to remote storage (OVH/Swift) with a
 * DB-friendly name that encodes server, timestamp, and sequence for easy diffing.
 *
 * Pack naming: pack_{serverId}_{yyyyMMddHHmmss}_{seq}.pack
 * Pack format (binary):
 *   [4 bytes: magic 0x50424B31 "PBK1"]
 *   [2 bytes: version = 1]
 *   repeated entries:
 *     [4 bytes: objectName length]
 *     [N bytes: objectName (UTF-8)]
 *     [4 bytes: data length]
 *     [N bytes: data]
 *   [4 bytes: 0x00000000 — end marker]
 */
class PackBuffer(
    private val bufferDir: Path,
    private val db: Database,
    private val swiftRemote: SwiftClient? = null,
    private val maxPackBytes: Long = PACK_MAX_BYTES,
    private val forceFlushAgeMs: Long = System.getenv("PROPERBACKUP_FORCE_FLUSH_AGE_MS")?.toLongOrNull()
        ?: DEFAULT_FORCE_FLUSH_AGE_MS,
) {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val tsFmt = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)

    init {
        Files.createDirectories(bufferDir)
    }

    fun start() {
        recoverFromCrash()
        scheduler.scheduleAtFixedRate({ flushFullPacks(); forceFlushStalePacks() }, 30, 30, TimeUnit.SECONDS)
        println("[pack-buffer] started (check every 30s, max pack size=${maxPackBytes / 1024 / 1024}MB, force-flush after ${forceFlushAgeMs / 3600_000}h)")
    }

    fun stop() {
        scheduler.shutdown()
    }

    /**
     * Add a sealed object to the current open pack for this server.
     * If adding it would exceed maxPackBytes, flush the current pack first.
     */
    fun addToPack(serverId: String, objectName: String, data: ByteArray) {
        val lock = locks.computeIfAbsent(serverId) { ReentrantLock() }
        lock.lock()
        try {
            val serverDir = bufferDir.resolve(serverId)
            Files.createDirectories(serverDir)

            val currentPack = findOpenPack(serverDir)
            val currentSize = if (currentPack != null) Files.size(currentPack) else 0L
            val entrySize = 4L + objectName.toByteArray().size + 4L + data.size

            if (currentPack != null && (currentSize + entrySize) > maxPackBytes) {
                flushPack(serverId, currentPack)
            }

            val packFile = findOpenPack(serverDir) ?: createNewPack(serverDir, serverId)
            appendEntry(packFile, objectName, data)

            println("[pack-buffer] added $objectName (${data.size} bytes) to pack ${packFile.fileName}")

            val newSize = Files.size(packFile)
            if (newSize >= maxPackBytes) {
                flushPack(serverId, packFile)
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * Read all entries from a pack file (local buffer).
     */
    fun readPack(packPath: Path): List<PackEntry> {
        val data = Files.readAllBytes(packPath)
        return parsePackData(data)
    }

    /**
     * Read a specific object from the local buffer by scanning all packs for the server.
     */
    fun readObject(serverId: String, objectName: String): ByteArray? {
        val serverDir = bufferDir.resolve(serverId)
        if (!Files.exists(serverDir)) return null

        Files.list(serverDir).use { stream ->
            for (packFile in stream.filter { it.toString().endsWith(".pack") }.sorted().toList()) {
                val entries = readPack(packFile)
                val match = entries.find { it.objectName == objectName }
                if (match != null) return match.data
            }
        }
        return null
    }

    /**
     * Read a specific object by scanning packs across ALL servers in the buffer.
     * Used by ObjectReadFacade when the caller doesn't know which server owns the object.
     */
    fun readObjectFromAnyServer(objectName: String): ByteArray? {
        if (!Files.exists(bufferDir)) return null
        Files.list(bufferDir).use { serverDirs ->
            for (serverDir in serverDirs.filter { Files.isDirectory(it) }.toList()) {
                val serverId = serverDir.fileName.toString()
                val result = readObject(serverId, objectName)
                if (result != null) return result
            }
        }
        return null
    }

    /**
     * List all objects currently in the buffer for a server.
     */
    fun listBufferedObjects(serverId: String): List<String> {
        val serverDir = bufferDir.resolve(serverId)
        if (!Files.exists(serverDir)) return emptyList()

        val result = mutableListOf<String>()
        Files.list(serverDir).use { stream ->
            for (packFile in stream.filter { it.toString().endsWith(".pack") }.sorted().toList()) {
                val entries = readPack(packFile)
                result.addAll(entries.map { it.objectName })
            }
        }
        return result
    }

    /**
     * Flush all full packs to remote storage.
     */
    fun flushFullPacks() {
        try {
            if (!Files.exists(bufferDir)) return
            Files.list(bufferDir).use { serverDirs ->
                for (serverDir in serverDirs.filter { Files.isDirectory(it) }.toList()) {
                    val serverId = serverDir.fileName.toString()
                    val lock = locks.computeIfAbsent(serverId) { ReentrantLock() }
                    if (!lock.tryLock()) continue
                    try {
                        Files.list(serverDir).use { packs ->
                            val packFiles = packs
                                .filter { it.toString().endsWith(".pack") && !it.toString().endsWith(".open.pack") }
                                .sorted()
                                .toList()
                            for (packFile in packFiles) {
                                flushPack(serverId, packFile)
                            }
                        }
                    } finally {
                        lock.unlock()
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[pack-buffer] flushFullPacks error: ${e.message}")
        }
    }

    /**
     * Force flush all packs (including open ones) to remote storage.
     */
    fun flushAll() {
        try {
            if (!Files.exists(bufferDir)) return
            Files.list(bufferDir).use { serverDirs ->
                for (serverDir in serverDirs.filter { Files.isDirectory(it) }.toList()) {
                    val serverId = serverDir.fileName.toString()
                    val lock = locks.computeIfAbsent(serverId) { ReentrantLock() }
                    lock.lock()
                    try {
                        Files.list(serverDir).use { packs ->
                            val packFiles = packs
                                .filter { it.toString().endsWith(".pack") }
                                .sorted()
                                .toList()
                            for (packFile in packFiles) {
                                val actualPath = if (packFile.fileName.toString().endsWith(".open.pack")) {
                                    sealOpenPack(packFile)
                                    val sealedName = packFile.fileName.toString().replace(".open.pack", ".pack")
                                    packFile.parent.resolve(sealedName)
                                } else {
                                    packFile
                                }
                                flushPack(serverId, actualPath)
                            }
                        }
                    } finally {
                        lock.unlock()
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[pack-buffer] flushAll error: ${e.message}")
        }
    }

    private fun findOpenPack(serverDir: Path): Path? {
        if (!Files.exists(serverDir)) return null
        return Files.list(serverDir).use { stream ->
            stream.filter { it.toString().endsWith(".open.pack") }
                .sorted()
                .findFirst()
                .orElse(null)
        }
    }

    private fun createNewPack(serverDir: Path, serverId: String): Path {
        val timestamp = tsFmt.format(Instant.now())
        val seq = System.nanoTime() % 100000
        val packName = "pack_${serverId}_${timestamp}_${String.format("%05d", seq)}.open.pack"
        val packFile = serverDir.resolve(packName)

        val header = ByteArrayOutputStream()
        val dos = DataOutputStream(header)
        dos.writeInt(MAGIC)
        dos.writeShort(VERSION)
        dos.flush()

        Files.write(packFile, header.toByteArray())
        println("[pack-buffer] created new pack: $packName")
        return packFile
    }

    private fun appendEntry(packFile: Path, objectName: String, data: ByteArray) {
        val entry = ByteArrayOutputStream()
        val dos = DataOutputStream(entry)
        val nameBytes = objectName.toByteArray(Charsets.UTF_8)
        dos.writeInt(nameBytes.size)
        dos.write(nameBytes)
        dos.writeInt(data.size)
        dos.write(data)
        dos.flush()

        Files.newOutputStream(packFile, java.nio.file.StandardOpenOption.APPEND).use { out ->
            out.write(entry.toByteArray())
        }
    }

    private fun sealOpenPack(packFile: Path) {
        val name = packFile.fileName.toString()
        if (!name.endsWith(".open.pack")) return
        val sealedName = name.replace(".open.pack", ".pack")
        val sealedPath = packFile.parent.resolve(sealedName)
        Files.move(packFile, sealedPath, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun flushPack(serverId: String, packFile: Path) {
        if (!Files.exists(packFile)) return
        val remote = swiftRemote ?: run {
            println("[pack-buffer] no remote configured, keeping pack locally: ${packFile.fileName}")
            return
        }

        try {
            var actualPath = packFile
            if (packFile.fileName.toString().endsWith(".open.pack")) {
                sealOpenPack(packFile)
                val sealedName = packFile.fileName.toString().replace(".open.pack", ".pack")
                actualPath = packFile.parent.resolve(sealedName)
            }

            val packData = Files.readAllBytes(actualPath)
            val compressed = compressPack(packData)
            val remotePackName = actualPath.fileName.toString()

            remote.put(remotePackName, compressed)
            println("[pack-buffer] flushed pack to remote: $remotePackName (raw=${packData.size}, gzip=${compressed.size} bytes, ratio=${String.format("%.1f", compressed.size * 100.0 / packData.size)}%)")

            recordPackInDb(serverId, remotePackName, compressed.size.toLong())

            Files.deleteIfExists(actualPath)
            println("[pack-buffer] deleted local pack: $remotePackName")
        } catch (e: Exception) {
            System.err.println("[pack-buffer] flush failed for ${packFile.fileName}: ${e.message}")
        }
    }

    private fun recordPackInDb(serverId: String, packName: String, sizeBytes: Long) {
        try {
            db.getConnection().use { conn ->
                conn.prepareStatement(
                    "INSERT INTO buffer_pack(server_id, pack_name, size_bytes, flushed_at) " +
                        "VALUES (?, ?, ?, NOW()) " +
                        "ON CONFLICT (server_id, pack_name) DO NOTHING"
                ).use { ps ->
                    ps.setString(1, serverId)
                    ps.setString(2, packName)
                    ps.setLong(3, sizeBytes)
                    ps.executeUpdate()
                }
            }
        } catch (e: Exception) {
            System.err.println("[pack-buffer] DB record failed for $packName: ${e.message}")
        }
    }

    data class PackEntry(val objectName: String, val data: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PackEntry) return false
            return objectName == other.objectName && data.contentEquals(other.data)
        }
        override fun hashCode(): Int = 31 * objectName.hashCode() + data.contentHashCode()
    }

    /**
     * Force-flush open packs older than forceFlushAgeMs (HR-7: default 24h).
     */
    fun forceFlushStalePacks() {
        try {
            if (!Files.exists(bufferDir)) return
            val now = System.currentTimeMillis()
            Files.list(bufferDir).use { serverDirs ->
                for (serverDir in serverDirs.filter { Files.isDirectory(it) }.toList()) {
                    val serverId = serverDir.fileName.toString()
                    val lock = locks.computeIfAbsent(serverId) { ReentrantLock() }
                    if (!lock.tryLock()) continue
                    try {
                        val openPack = findOpenPack(serverDir)
                        if (openPack != null) {
                            val lastModified = Files.getLastModifiedTime(openPack).toMillis()
                            if ((now - lastModified) >= forceFlushAgeMs) {
                                println("[pack-buffer] force-flush stale open pack: ${openPack.fileName} (age=${(now - lastModified) / 1000}s)")
                                sealOpenPack(openPack)
                                val sealedName = openPack.fileName.toString().replace(".open.pack", ".pack")
                                val sealedPath = openPack.parent.resolve(sealedName)
                                flushPack(serverId, sealedPath)
                            }
                        }
                    } finally {
                        lock.unlock()
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[pack-buffer] forceFlushStalePacks error: ${e.message}")
        }
    }

    /**
     * Recover from a crash: clean up .tmp files, validate open packs.
     */
    private fun recoverFromCrash() {
        try {
            if (!Files.exists(bufferDir)) return
            Files.list(bufferDir).use { serverDirs ->
                for (serverDir in serverDirs.filter { Files.isDirectory(it) }.toList()) {
                    Files.list(serverDir).use { files ->
                        for (file in files.toList()) {
                            val name = file.fileName.toString()
                            if (name.endsWith(".tmp")) {
                                println("[pack-buffer] crash recovery: removing orphan tmp file: $name")
                                Files.deleteIfExists(file)
                                continue
                            }
                            if (name.endsWith(".pack") || name.endsWith(".open.pack")) {
                                if (Files.size(file) < 6) {
                                    println("[pack-buffer] crash recovery: removing corrupt pack (too small): $name")
                                    Files.deleteIfExists(file)
                                    continue
                                }
                                try {
                                    val data = Files.readAllBytes(file)
                                    parsePackData(data)
                                    println("[pack-buffer] crash recovery: pack OK: $name (${data.size} bytes)")
                                } catch (e: Exception) {
                                    println("[pack-buffer] crash recovery: corrupt pack removed: $name (${e.message})")
                                    Files.deleteIfExists(file)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[pack-buffer] crash recovery error: ${e.message}")
        }
    }

    companion object {
        const val MAGIC = 0x50424B31 // "PBK1"
        const val VERSION = 1
        const val PACK_MAX_BYTES = 900L * 1024 * 1024 // 900 MB
        const val DEFAULT_FORCE_FLUSH_AGE_MS = 24L * 60 * 60 * 1000
        private val GZIP_MAGIC = byteArrayOf(0x1f.toByte(), 0x8b.toByte())

        fun compressPack(raw: ByteArray): ByteArray {
            val baos = ByteArrayOutputStream(raw.size / 2)
            GZIPOutputStream(baos).use { gzip -> gzip.write(raw) }
            return baos.toByteArray()
        }

        fun decompressPack(data: ByteArray): ByteArray {
            if (data.size < 2) return data
            if (data[0] != GZIP_MAGIC[0] || data[1] != GZIP_MAGIC[1]) return data
            return GZIPInputStream(ByteArrayInputStream(data)).use { it.readAllBytes() }
        }

        fun parsePackData(raw: ByteArray): List<PackEntry> {
            val decompressed = decompressPack(raw)
            val entries = mutableListOf<PackEntry>()
            val dis = DataInputStream(ByteArrayInputStream(decompressed))

            val magic = dis.readInt()
            if (magic != MAGIC) throw IllegalArgumentException("Invalid pack magic: ${Integer.toHexString(magic)}")
            val version = dis.readUnsignedShort()
            if (version != VERSION) throw IllegalArgumentException("Unsupported pack version: $version")

            while (dis.available() > 0) {
                val nameLen = dis.readInt()
                if (nameLen == 0) break // end marker
                val nameBytes = ByteArray(nameLen)
                dis.readFully(nameBytes)
                val objectName = String(nameBytes, Charsets.UTF_8)

                val dataLen = dis.readInt()
                val data = ByteArray(dataLen)
                dis.readFully(data)

                entries.add(PackEntry(objectName, data))
            }
            return entries
        }
    }
}
