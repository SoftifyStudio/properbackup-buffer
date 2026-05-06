package pl.danielniemiec.properbackup.buffer.flush

import pl.danielniemiec.properbackup.buffer.cleanup.PostUploadCleanup
import pl.danielniemiec.properbackup.buffer.db.Database
import pl.danielniemiec.properbackup.buffer.inbox.ChunkStorage
import pl.danielniemiec.properbackup.buffer.ovh.SwiftClient
import pl.danielniemiec.properbackup.buffer.pathsidx.ArchiveSnapshotStore
import pl.danielniemiec.properbackup.buffer.pathsidx.PathsIdxStore
import pl.danielniemiec.properbackup.buffer.store.ObjectStore
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class ChunkSealer(
    private val storage: ChunkStorage,
    private val objectStore: ObjectStore,
    private val db: Database,
    private val swiftRemote: SwiftClient? = null,
    private val archiveSnapshotStore: ArchiveSnapshotStore? = null,
    private val storageQuotaGuard: StorageQuotaGuard? = null,
) {

    private val tsFmt = DateTimeFormatter.ofPattern("yyMMddHHmm").withZone(ZoneOffset.UTC)

    fun seal(userId: String, pathId: String, originalPath: String?, serverId: String?) {
        val parts = storage.getParts(userId, pathId)
        if (parts.isEmpty()) return

        var flag = "A"
        for (p in parts) {
            val name = p.fileName.toString()
            if (name.contains("-D.part")) flag = "D"
            if (name.contains("-A.part")) flag = "A"
        }

        val combined = ByteArrayOutputStream()
        for (part in parts) {
            combined.write(Files.readAllBytes(part))
        }
        val data = combined.toByteArray()

        if (storageQuotaGuard != null && !storageQuotaGuard.canUpload(userId, data.size.toLong())) {
            val q = storageQuotaGuard.checkQuota(userId)
            println("[seal] BLOCKED by StorageQuotaGuard for $userId/$pathId — used ${q.usedBytes}/${q.limitBytes} bytes (${String.format("%.1f", q.usedPercent)}%)")
            for (part in parts) Files.deleteIfExists(part)
            return
        }

        val timestamp = tsFmt.format(ZonedDateTime.now(ZoneOffset.UTC))
        val shortHash = Integer.toHexString(java.util.Arrays.hashCode(data) and 0xFFFF)
        val objectName = "$userId-$timestamp-$pathId-$flag-$shortHash.enc"

        objectStore.put(objectName, data)
        println("[seal] $pathId → $objectName (${data.size} bytes)")

        try {
            if (swiftRemote != null) {
                swiftRemote.put(objectName, data)
                if (!originalPath.isNullOrBlank() && !serverId.isNullOrBlank()) {
                    PostUploadCleanup(objectStore, PathsIdxStore(db, serverId), swiftRemote)
                        .afterUpload(objectName, pathId, originalPath)
                } else {
                    objectStore.delete(objectName)
                }
            } else if (!originalPath.isNullOrBlank() && !serverId.isNullOrBlank()) {
                PathsIdxStore(db, serverId).register(pathId, originalPath)
            }
        } catch (e: Exception) {
            if (swiftRemote != null) {
                try {
                    objectStore.delete(objectName)
                } catch (ignored: java.io.IOException) {
                }
            }
            throw e
        }

        val store = archiveSnapshotStore
        val sid = serverId
        if (store != null &&
            !sid.isNullOrBlank() &&
            !(swiftRemote != null && originalPath.isNullOrBlank())
        ) {
            store.insert(
                sid,
                pathId,
                objectName,
                flag,
                data.size.toLong(),
                Instant.now(),
            )
        }

        for (part in parts) {
            Files.deleteIfExists(part)
        }
    }
}
