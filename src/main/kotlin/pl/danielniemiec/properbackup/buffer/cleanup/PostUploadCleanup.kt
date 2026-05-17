package pl.danielniemiec.properbackup.buffer.cleanup

import org.slf4j.LoggerFactory
import pl.danielniemiec.properbackup.buffer.ovh.SwiftClient
import pl.danielniemiec.properbackup.buffer.pathsidx.PathsIdxStore
import pl.danielniemiec.properbackup.buffer.store.ObjectStore

class PostUploadCleanup(
    private val localStore: ObjectStore,
    private val idxStore: PathsIdxStore?,
    private val swiftClient: SwiftClient,
) {
    private val log = LoggerFactory.getLogger(PostUploadCleanup::class.java)

    fun afterUpload(objectName: String, pathId: String, originalPath: String?) {
        try {
            if (idxStore != null && !originalPath.isNullOrBlank()) {
                idxStore.register(pathId, originalPath)
            }
        } catch (e: Exception) {
            log.error("paths.idx registration failed for {} — local copy kept: {}", objectName, e.message)
            return
        }
        try {
            val objects = localStore.list()
            if (objects.contains(objectName)) {
                localStore.delete(objectName)
                log.info("Deleted local: {}", objectName)
            }
        } catch (e: java.io.IOException) {
            log.error("Could not delete local copy {}: {}", objectName, e.message)
        }
    }
}
