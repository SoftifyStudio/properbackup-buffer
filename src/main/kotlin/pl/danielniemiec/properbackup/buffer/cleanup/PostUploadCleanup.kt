package pl.danielniemiec.properbackup.buffer.cleanup

import pl.danielniemiec.properbackup.buffer.ovh.SwiftClient
import pl.danielniemiec.properbackup.buffer.pathsidx.PathsIdxStore
import pl.danielniemiec.properbackup.buffer.store.ObjectStore

class PostUploadCleanup(
    private val localStore: ObjectStore,
    private val idxStore: PathsIdxStore?,
    private val swiftClient: SwiftClient,
) {

    fun afterUpload(objectName: String, pathId: String, originalPath: String?) {
        try {
            if (idxStore != null && !originalPath.isNullOrBlank()) {
                idxStore.register(pathId, originalPath)
            }
        } catch (e: Exception) {
            System.err.printf(
                "[PostUploadCleanup] paths.idx registration failed for %s: %s — local copy kept%n",
                objectName,
                e.message,
            )
            return
        }
        try {
            val objects = localStore.list()
            if (objects.contains(objectName)) {
                localStore.delete(objectName)
                println("[PostUploadCleanup] Deleted local: $objectName")
            }
        } catch (e: java.io.IOException) {
            System.err.printf("[PostUploadCleanup] Could not delete local copy %s: %s%n", objectName, e.message)
        }
    }
}
