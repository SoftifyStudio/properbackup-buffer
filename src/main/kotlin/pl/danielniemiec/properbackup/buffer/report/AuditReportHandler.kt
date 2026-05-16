package pl.danielniemiec.properbackup.buffer.report

import io.javalin.http.Context
import io.javalin.http.NotFoundResponse
import pl.danielniemiec.properbackup.buffer.auth.UserStore
import pl.danielniemiec.properbackup.buffer.flush.StorageQuotaGuard
import pl.danielniemiec.properbackup.buffer.logs.FileStateStore
import pl.danielniemiec.properbackup.buffer.pathsidx.ArchiveSnapshotStore
import pl.danielniemiec.properbackup.buffer.pathsidx.BackupRootStore
import pl.danielniemiec.properbackup.buffer.server.ServerStore
import pl.danielniemiec.properbackup.buffer.verify.RestoreVerifier
import java.time.Instant

class AuditReportHandler(
    private val serverStore: ServerStore,
    private val userStore: UserStore,
    private val backupRootStore: BackupRootStore,
    private val archiveSnapshotStore: ArchiveSnapshotStore,
    private val fileStateStore: FileStateStore,
    private val storageQuotaGuard: StorageQuotaGuard?,
    private val restoreVerifier: RestoreVerifier? = null,
) {
    private val generator = AuditReportGenerator()

    fun downloadReport(ctx: Context) {
        val serverId = ctx.pathParam("id")
        val userId = ctx.attribute<String>("userId")!!

        val server = serverStore.findById(serverId).orElseThrow { NotFoundResponse() }
        if (server.userId != userId && java.lang.Boolean.TRUE != ctx.attribute<Boolean>("isSystemAdmin")) {
            throw NotFoundResponse("Server not found")
        }

        val user = userStore.findById(server.userId).orElseThrow { NotFoundResponse() }
        val roots = backupRootStore.listForMachine(serverId)
        val snapCounts = archiveSnapshotStore.countsByPathId(serverId)
        val totalSnapshots = snapCounts.values.sum()
        val totalFiles = fileStateStore.countForMachine(serverId)
        val quotaStatus = storageQuotaGuard?.checkQuota(server.userId)

        val lastBackupAt = archiveSnapshotStore.latestSealedAt(serverId)

        val rootEntries = roots.map { r ->
            AuditReportGenerator.RootEntry(
                rootPath = r.rootPath,
                snapshotCount = snapCounts[r.pathId] ?: 0L,
                lastSnapshot = archiveSnapshotStore.latestSealedAtForPath(serverId, r.pathId),
            )
        }

        val storageLimitLabel = formatBytes(user.storageLimitBytes)
        val storageUsedLabel = formatBytes(quotaStatus?.usedBytes ?: 0L)

        val verificationResults = restoreVerifier?.listResults(serverId, 50) ?: emptyList()
        val passedCount = verificationResults.count { it["status"] == "passed" }
        val failedCount = verificationResults.count { it["status"] == "failed" }
        val verificationStatus = when {
            verificationResults.isEmpty() && totalSnapshots > 0 -> "Backups present (not yet verified)"
            failedCount > 0 -> "$failedCount/${ verificationResults.size} FAILED"
            passedCount > 0 -> "All $passedCount verified — integrity confirmed"
            else -> "No backups yet"
        }

        val verificationEntries = verificationResults.map { r ->
            AuditReportGenerator.VerificationEntry(
                objectName = r["objectName"] as? String ?: "",
                pathId = r["pathId"] as? String ?: "",
                status = r["status"] as? String ?: "unknown",
                detail = r["detail"] as? String,
                verifiedAt = r["verifiedAt"] as? String,
            )
        }

        val reportData = AuditReportGenerator.ReportData(
            serverName = server.name,
            serverId = serverId,
            ownerEmail = user.email,
            generatedAt = Instant.now(),
            backupRoots = rootEntries,
            totalFiles = totalFiles,
            totalSnapshots = totalSnapshots,
            lastBackupAt = lastBackupAt,
            verificationStatus = verificationStatus,
            storageLimitLabel = storageLimitLabel,
            storageUsedLabel = storageUsedLabel,
            verificationResults = verificationEntries,
        )

        val pdf = generator.generate(reportData)
        ctx.contentType("application/pdf")
            .header("Content-Disposition", "attachment; filename=\"ProperBackup-Audit-Report-$serverId.pdf\"")
            .result(pdf)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        val gb = mb / 1024.0
        if (gb < 1024) return "%.1f GB".format(gb)
        val tb = gb / 1024.0
        return "%.2f TB".format(tb)
    }
}
