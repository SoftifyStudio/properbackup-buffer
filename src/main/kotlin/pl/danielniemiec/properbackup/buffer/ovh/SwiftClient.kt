package pl.danielniemiec.properbackup.buffer.ovh

import java.io.IOException

data class ObjectMetadata(
    val contentLength: Long,
    val etag: String?,
    val contentType: String?,
    val headers: Map<String, String> = emptyMap(),
)

enum class UnsealStatus { SEALED, UNSEALING, UNSEALED, NOT_FOUND }

interface SwiftClient : CloudStorageClient {

    @Throws(IOException::class)
    fun headObject(objectName: String): ObjectMetadata?

    @Throws(IOException::class)
    fun requestUnseal(objectName: String): UnsealStatus

    @Throws(IOException::class)
    fun checkUnsealStatus(objectName: String): UnsealStatus
}
