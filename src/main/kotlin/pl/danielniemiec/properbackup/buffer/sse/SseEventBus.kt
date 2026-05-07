package pl.danielniemiec.properbackup.buffer.sse

import com.google.gson.Gson
import io.javalin.http.sse.SseClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory pub/sub for Server-Sent Events, keyed by serverId (machineId).
 * Agent ingest endpoints call [publish] after persisting; connected panels
 * receive the event via their SSE stream without polling.
 */
class SseEventBus {

    private val gson = Gson()
    private val clients = ConcurrentHashMap<String, CopyOnWriteArrayList<SseClient>>()

    fun subscribe(serverId: String, client: SseClient) {
        val list = clients.computeIfAbsent(serverId) { CopyOnWriteArrayList() }
        list.add(client)
        client.onClose { list.remove(client) }
        client.sendEvent("connected", gson.toJson(mapOf("serverId" to serverId, "ts" to System.currentTimeMillis())))
    }

    fun publish(serverId: String, eventType: String, data: Any?) {
        val list = clients[serverId] ?: return
        if (list.isEmpty()) return
        val json = gson.toJson(data)
        val dead = mutableListOf<SseClient>()
        for (c in list) {
            try {
                c.sendEvent(eventType, json)
            } catch (_: Exception) {
                dead.add(c)
            }
        }
        if (dead.isNotEmpty()) list.removeAll(dead.toSet())
    }

    fun connectionCount(serverId: String): Int = clients[serverId]?.size ?: 0
}
