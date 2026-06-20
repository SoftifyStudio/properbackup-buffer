package pl.danielniemiec.properbackup.buffer.sse

import com.google.gson.Gson
import io.javalin.http.sse.SseClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * User-keyed SSE pub/sub for subscription lifecycle events.
 * Per master-tdd-plan.md §9.3 [TDD-F1].
 *
 * Supports topic-scoped subscriptions: a client subscribes to (userId, topic),
 * and receives only events published to that (userId, topic) pair.
 */
class UserSseEventBus {

    private val gson = Gson()

    private data class SubKey(val userId: String, val topic: String)

    private val clients = ConcurrentHashMap<SubKey, CopyOnWriteArrayList<SseClient>>()

    fun subscribe(userId: String, topic: String, client: SseClient) {
        val key = SubKey(userId, topic)
        val list = clients.computeIfAbsent(key) { CopyOnWriteArrayList() }
        list.add(client)
        client.keepAlive()
        client.onClose { list.remove(client) }
        client.sendEvent("connected", gson.toJson(mapOf("userId" to userId, "topic" to topic, "ts" to System.currentTimeMillis())))
    }

    fun publish(userId: String, topic: String, data: Map<String, Any?>) {
        val key = SubKey(userId, topic)
        val list = clients[key] ?: return
        if (list.isEmpty()) return
        val json = gson.toJson(data)
        val dead = mutableListOf<SseClient>()
        for (c in list) {
            try {
                c.sendEvent(topic, json)
            } catch (_: Exception) {
                dead.add(c)
            }
        }
        if (dead.isNotEmpty()) list.removeAll(dead.toSet())
    }

    fun connectionCount(userId: String, topic: String): Int =
        clients[SubKey(userId, topic)]?.size ?: 0
}
