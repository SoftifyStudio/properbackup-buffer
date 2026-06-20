package pl.danielniemiec.properbackup.buffer.auth

import io.javalin.http.Context
import io.javalin.http.Handler
import io.javalin.http.UnauthorizedResponse
import io.jsonwebtoken.JwtException

class JwtFilter(private val jwtService: JwtService) : Handler {
    override fun handle(ctx: Context) {
        if (ctx.path() == "/servers/activate") return
        if (ctx.path().endsWith("/events")) return  // SSE does its own token auth via query string

        val auth = ctx.header("Authorization")
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw UnauthorizedResponse("Missing Authorization header")
        }
        val token = auth.substring(7)
        try {
            val claims = jwtService.verify(token)
            ctx.attribute("claims", claims)
            ctx.attribute("userId", claims.subject)
            ctx.attribute("userEmail", claims["email"] as? String)
            ctx.attribute("isSystemAdmin", java.lang.Boolean.TRUE == claims["sa"])
        } catch (e: JwtException) {
            throw UnauthorizedResponse("Invalid or expired token")
        }
    }
}
