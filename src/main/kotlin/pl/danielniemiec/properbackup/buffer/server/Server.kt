package pl.danielniemiec.properbackup.buffer.server

data class Server(
    val id: String,
    val name: String,
    val userId: String,
    val ownerEmail: String?,
    val activatedAt: String,
    val lastSeen: String?,
)
