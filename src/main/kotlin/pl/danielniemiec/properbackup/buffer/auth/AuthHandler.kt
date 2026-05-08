package pl.danielniemiec.properbackup.buffer.auth

import com.google.gson.Gson
import io.javalin.http.Context
import io.javalin.http.ForbiddenResponse

class AuthHandler(
    private val userStore: UserStore,
    private val jwtService: JwtService,
    private val serviceAdminCodeStore: ServiceAdminCodeStore,
) {

    private val gson = Gson()

    private data class RegReq(val email: String?, val password: String?)
    private data class LoginReq(val email: String?, val password: String?)
    private data class ServiceReq(val serviceKey: String?)

    private fun openRegistration(): Boolean {
        val v = System.getenv("PROPERBACKUP_OPEN_REGISTRATION") ?: return true
        val t = v.trim()
        return !t.equals("false", ignoreCase = true) && t != "0"
    }

    fun register(ctx: Context) {
        val req = gson.fromJson(ctx.body(), RegReq::class.java)
        if (req == null || req.email == null || req.password == null) {
            ctx.status(400).result("email and password required")
            return
        }
        val pwErr = PasswordPolicy.validate(req.password)
        if (pwErr.isPresent) {
            ctx.status(400).result(pwErr.get())
            return
        }
        if (!openRegistration() && !userStore.isEmpty()) {
            val adminKey = ctx.header("X-Admin-Key")
            val envKey = System.getenv("PROPERBACKUP_ADMIN_KEY")
            if (envKey == null || envKey != adminKey) {
                throw ForbiddenResponse("Registration is closed. Provide X-Admin-Key header.")
            }
        }
        try {
            val user = userStore.register(req.email!!, req.password!!)
            val token = jwtService.generate(user.id, user.email, user.isSystemAdmin)
            ctx.contentType("application/json").result(gson.toJson(authResponse(token, user)))
        } catch (e: IllegalArgumentException) {
            ctx.status(409).result(e.message ?: "conflict")
        }
    }

    fun login(ctx: Context) {
        val req = gson.fromJson(ctx.body(), LoginReq::class.java)
        if (req == null || req.email == null || req.password == null) {
            ctx.status(400).result("email and password required")
            return
        }
        val userOpt = userStore.findByEmail(req.email!!)
        if (!userOpt.isPresent || !userStore.verifyPassword(userOpt.get(), req.password!!)) {
            ctx.status(401).result("Invalid email or password")
            return
        }
        val user = userOpt.get()
        val token = jwtService.generate(user.id, user.email, user.isSystemAdmin)
        ctx.contentType("application/json").result(gson.toJson(authResponse(token, user)))
    }

    private fun authResponse(token: String, user: UserStore.User): Map<String, Any?> = mapOf(
        "token" to token,
        "email" to user.email,
        "id" to user.id,
        "isSystemAdmin" to user.isSystemAdmin,
        "trialExpiresAt" to user.trialExpiresAt,
        "subscriptionPlan" to user.subscriptionPlan,
        "subscriptionExpiresAt" to user.subscriptionExpiresAt,
        "storageLimitBytes" to user.storageLimitBytes,
    )

    fun elevateServiceAdmin(ctx: Context) {
        val req = gson.fromJson(ctx.body(), ServiceReq::class.java)
        if (req?.serviceKey.isNullOrBlank()) {
            ctx.status(400).result("Wpisz klucz dostępu.")
            return
        }
        if (!serviceAdminCodeStore.verifyAndUse(req.serviceKey!!)) {
            ctx.status(403).result("Nieprawidłowy klucz.")
            return
        }
        val userId = ctx.attribute<String>("userId")!!
        val user = userStore.findById(userId).orElse(null)
        if (user == null) {
            ctx.status(401).result("Sesja wygasła — zaloguj się ponownie.")
            return
        }
        val token = jwtService.generate(user.id, user.email, true)
        ctx.contentType("application/json").result(
            gson.toJson(
                mapOf(
                    "token" to token,
                    "email" to user.email,
                    "id" to user.id,
                    "isSystemAdmin" to true,
                ),
            ),
        )
    }
}
