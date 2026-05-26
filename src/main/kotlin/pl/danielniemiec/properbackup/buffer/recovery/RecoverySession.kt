package pl.danielniemiec.properbackup.buffer.recovery

import java.time.Instant

data class RecoverySession(
    val id: String,
    val serverId: String,
    val userId: String,
    val targetSnapshotId: Long,
    val state: RecoveryState,
    val createdAt: Instant,
    val confirmedAt: Instant? = null,
    val thawStartedAt: Instant? = null,
    val thawCompletedAt: Instant? = null,
    val agentStartedAt: Instant? = null,
    val agentCompletedAt: Instant? = null,
    val verifyingStartedAt: Instant? = null,
    val completedAt: Instant? = null,
    val cancelledAt: Instant? = null,
    val failedAt: Instant? = null,
    val preRecoverySnapshotId: Long? = null,
    val dryRunFilesToRestore: Int? = null,
    val dryRunFilesToDelete: Int? = null,
    val dryRunFilesUnchanged: Int? = null,
    val dryRunTotalBytes: Long? = null,
    val filesRestored: Int = 0,
    val filesDeleted: Int = 0,
    val bytesTransferred: Long = 0,
    val failureReason: String? = null,
    val cancellationReason: String? = null,
    val userConfirmedViaIp: String? = null,
    val userConfirmedViaUserAgent: String? = null,
)
