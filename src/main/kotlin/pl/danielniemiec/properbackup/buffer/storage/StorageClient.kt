package pl.danielniemiec.properbackup.buffer.storage

import java.io.IOException

/**
 * Pluggable storage backend for flushed packs.
 * Primary implementation: [LocalFsStorageClient] (local RAID5 on /mnt/storage).
 * [pl.danielniemiec.properbackup.buffer.ovh.OvhSwiftClient] remains behind
 * the [pl.danielniemiec.properbackup.buffer.ovh.CloudStorageClient] interface
 * but is NOT used in the pipeline.
 */
interface StorageClient {

    /** Write a pack to storage with atomic semantics (.tmp -> rename) + sha256 sidecar. */
    @Throws(IOException::class)
    fun putPack(packName: String, data: ByteArray): PackWriteResult

    /** Read a pack from storage. */
    @Throws(IOException::class)
    fun getPack(packName: String): ByteArray

    /** Check if a pack exists in storage. */
    @Throws(IOException::class)
    fun exists(packName: String): Boolean

    /** List all packs in storage. */
    @Throws(IOException::class)
    fun listPacks(): List<String>

    /** Delete a pack from storage (admin only, not used in normal pipeline per HR-3). */
    @Throws(IOException::class)
    fun deletePack(packName: String)

    /** Verify pack integrity against its sha256 sidecar. Returns true if matches. */
    @Throws(IOException::class)
    fun verifyIntegrity(packName: String): Boolean

    /** Available bytes on the storage volume. */
    @Throws(IOException::class)
    fun availableBytes(): Long

    /** Total bytes used by all packs in storage. */
    @Throws(IOException::class)
    fun usedBytes(): Long

    data class PackWriteResult(
        val packName: String,
        val sizeBytes: Long,
        val sha256: String,
    )
}
