package pl.danielniemiec.properbackup.buffer.agent

import com.google.gson.Gson
import io.javalin.http.Context
import pl.danielniemiec.properbackup.buffer.auth.JwtService
import pl.danielniemiec.properbackup.buffer.auth.UserStore
import pl.danielniemiec.properbackup.buffer.db.Database
import pl.danielniemiec.properbackup.buffer.server.ServerStore
import pl.danielniemiec.properbackup.buffer.subscription.SubscriptionGuard

/**
 * Agent session management per master-tdd-plan.md §9.1 [TDD-G1].
 *
 * POST /agent/session/start — agent presents serverId + uploadToken,
 * gets a 5min JWT with subscription_status claim.
 *
 * POST /agent/session/refresh — agent presents current JWT,
 * gets a fresh 5min JWT (re-checks subscription status from DB).
 */
class AgentSessionHandler(
    private val db: Database,
    private val jwtService: JwtService,
    private val serverStore: ServerStore,
    private val userStore: UserStore,
    private val uploadToken: String,
) {
    private val gson = Gson()

    fun startSession(ctx: Context) {
        val auth = ctx.header("Authorization")
        if (auth == null || auth != "Bearer $uploadToken") {
            ctx.status(401).result("Unauthorized")
            return
        }

        val serverId = ctx.header("X-Server-Id")?.trim()
        if (serverId.isNullOrBlank()) {
            ctx.status(400).result("Missing X-Server-Id")
            return
        }

        val server = serverStore.findById(serverId).orElse(null)
        if (server == null) {
            ctx.status(404).result("Server not found")
            return
        }

        val userId = server.userId
        val subscriptionStatus = resolveSubscriptionStatus(userId)

        val agentJwt = jwtService.generateAgentToken(serverId, userId, subscriptionStatus)

        ctx.contentType("application/json").result(gson.toJson(mapOf(
            "token" to agentJwt,
            "expiresInMs" to 5 * 60 * 1000,
            "subscriptionStatus" to subscriptionStatus,
            "serverId" to serverId,
        )))
    }

    fun refreshSession(ctx: Context) {
        val claims = ctx.attribute<io.jsonwebtoken.Claims>("claims")
        if (claims == null) {
            ctx.status(401).result("Invalid or expired token")
            return
        }

        val tokenType = claims["type"] as? String
        if (tokenType != "agent") {
            ctx.status(403).result("Not an agent token")
            return
        }

        val serverId = claims.subject
        val userId = claims["userId"] as? String ?: run {
            ctx.status(400).result("Missing userId in token")
            return
        }

        val subscriptionStatus = resolveSubscriptionStatus(userId)
        val agentJwt = jwtService.generateAgentToken(serverId, userId, subscriptionStatus)

        ctx.contentType("application/json").result(gson.toJson(mapOf(
            "token" to agentJwt,
            "expiresInMs" to 5 * 60 * 1000,
            "subscriptionStatus" to subscriptionStatus,
            "serverId" to serverId,
        )))
    }

    private fun resolveSubscriptionStatus(userId: String): String {
        val user = userStore.findById(userId).orElse(null) ?: return "none"
        return SubscriptionGuard.resolveStatus(user)
    }
}
