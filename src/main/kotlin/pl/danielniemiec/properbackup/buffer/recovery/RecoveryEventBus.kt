package pl.danielniemiec.properbackup.buffer.recovery

import com.google.gson.Gson
import io.javalin.http.sse.SseClient
import pl.danielniemiec.properbackup.buffer.sse.SseEventBus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class RecoveryEventBus(private val sseBus: SseEventBus) {

    private val gson = Gson()
    private val sessionClients = ConcurrentHashMap<String, CopyOnWriteArrayList<SseClient>>()

    fun subscribeSession(sessionId: String, client: SseClient) {
        val list = sessionClients.computeIfAbsent(sessionId) { CopyOnWriteArrayList() }
        list.add(client)
        client.keepAlive()
        client.onClose { list.remove(client) }
    }

    private fun publishToSession(sessionId: String, eventType: String, data: Any?) {
        val list = sessionClients[sessionId] ?: return
        if (list.isEmpty()) return
        val json = gson.toJson(data)
        val dead = mutableListOf<SseClient>()
        for (c in list) {
            try { c.sendEvent(eventType, json) } catch (_: Exception) { dead.add(c) }
        }
        if (dead.isNotEmpty()) list.removeAll(dead.toSet())
    }

    fun emitStateChange(session: RecoverySession) {
        val payload = mapOf(
            "sessionId" to session.id,
            "state" to session.state.name,
            "serverId" to session.serverId,
            "filesRestored" to session.filesRestored,
            "filesDeleted" to session.filesDeleted,
            "bytesTransferred" to session.bytesTransferred,
            "dryRunFilesToRestore" to session.dryRunFilesToRestore,
            "dryRunFilesToDelete" to session.dryRunFilesToDelete,
            "dryRunFilesUnchanged" to session.dryRunFilesUnchanged,
            "dryRunTotalBytes" to session.dryRunTotalBytes,
            "failureReason" to session.failureReason,
            "cancellationReason" to session.cancellationReason,
        )
        sseBus.publish(session.serverId, "recovery_state", payload)
        publishToSession(session.id, "recovery_state", payload)
    }

    fun emitProgress(session: RecoverySession) {
        val payload = mapOf(
            "sessionId" to session.id,
            "filesRestored" to session.filesRestored,
            "filesDeleted" to session.filesDeleted,
            "bytesTransferred" to session.bytesTransferred,
        )
        sseBus.publish(session.serverId, "recovery_progress", payload)
        publishToSession(session.id, "recovery_progress", payload)
    }

    fun emitRecoveryCommand(serverId: String, command: String, sessionId: String, payload: Map<String, Any?> = emptyMap()) {
        sseBus.publish(serverId, "recovery_command", mapOf(
            "command" to command,
            "sessionId" to sessionId,
        ) + payload)
    }
}
