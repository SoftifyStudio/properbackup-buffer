package pl.danielniemiec.properbackup.buffer.storage

import java.nio.file.Files
import java.nio.file.Path

/**
 * HR-10: Disk-Full Soft Block.
 * When storage volume is >80% filled and current pack < 900MB -> return 503 + Retry-After.
 * Prevents partial packs from being created on a nearly-full disk.
 */
class DiskFullGuard(
    private val storageDir: Path,
    private val thresholdPercent: Double = 80.0,
) {
    data class DiskStatus(
        val totalBytes: Long,
        val usableBytes: Long,
        val usedPercent: Double,
        val blocked: Boolean,
    )

    fun checkDisk(): DiskStatus {
        val store = Files.getFileStore(storageDir)
        val total = store.totalSpace
        val usable = store.usableSpace
        val usedPercent = if (total > 0) ((total - usable).toDouble() / total * 100.0) else 100.0
        val blocked = usedPercent >= thresholdPercent
        return DiskStatus(total, usable, usedPercent, blocked)
    }

    fun canAcceptUpload(uploadSizeBytes: Long = 0): Boolean {
        val status = checkDisk()
        if (status.blocked) {
            System.err.println(
                "[DiskFullGuard] Disk usage ${String.format("%.1f", status.usedPercent)}% >= ${thresholdPercent}% — " +
                    "soft block active (${status.usableBytes / (1024 * 1024)}MB remaining)"
            )
            return false
        }
        if (uploadSizeBytes > 0 && uploadSizeBytes > status.usableBytes) {
            System.err.println("[DiskFullGuard] Not enough space: need ${uploadSizeBytes / (1024 * 1024)}MB, have ${status.usableBytes / (1024 * 1024)}MB")
            return false
        }
        return true
    }
}
