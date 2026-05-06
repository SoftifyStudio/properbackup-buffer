package pl.danielniemiec.properbackup.buffer.logs

data class LogIngestReq(
    val machineId: String? = null,
    val component: String? = null,
    val eventCode: Int? = null,
    val level: String? = null,
    val message: String? = null,
    val meta: Map<String, Any?>? = null,
)

data class FileStateReq(
    val machineId: String? = null,
    val fullPath: String? = null,
    val status: String? = null,
)

data class FileStateBatchReq(
    val machineId: String? = null,
    val files: List<FileStateBatchEntry>? = null,
)

data class FileStateBatchEntry(
    val fullPath: String? = null,
    val status: String? = null,
)

data class BackupRootsReq(
    val machineId: String? = null,
    val roots: List<BackupRootEntry>? = null,
)

data class BackupRootEntry(
    val pathId: String? = null,
    val rootPath: String? = null,
)

data class RegisterPathsReq(
    val serverId: String? = null,
    val paths: List<PathRegisterEntry>? = null,
)

data class PathRegisterEntry(
    val pathId: String? = null,
    val originalPath: String? = null,
)

data class AgentStatusReq(
    val machineId: String? = null,
    val phase: String? = null,
    val detail: String? = null,
    val totalFiles: Int? = null,
    val scannedFiles: Int? = null,
    val currentFile: String? = null,
)
