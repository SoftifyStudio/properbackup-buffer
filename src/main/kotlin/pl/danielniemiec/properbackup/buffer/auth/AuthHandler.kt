package pl.danielniemiec.properbackup.buffer.auth

import com.google.gson.Gson
import io.javalin.http.Context
import io.javalin.http.ForbiddenResponse
import org.slf4j.LoggerFactory
import pl.danielniemiec.properbackup.buffer.email.EmailService
import pl.danielniemiec.properbackup.buffer.email.EmailTemplates

class AuthHandler(
    private val userStore: UserStore,
    private val jwtService: JwtService,
    private val serviceAdminCodeStore: ServiceAdminCodeStore,
    private val emailService: EmailService = EmailService(),
) {
    private val log = LoggerFactory.getLogger(AuthHandler::class.java)

    private val gson = Gson()

    private data class RegReq(val email: String?, val password: String?)
    private data class LoginReq(val email: String?, val password: String?)
    private data class ServiceReq(val serviceKey: String?)

    private fun openRegistration(): Boolean {
        val v = System.getenv("PROPERBACKUP_OPEN_REGISTRATION") ?: return true
        val t = v.trim()
        return !t.equals("false", ignoreCase = true) && t != "0"
    }

    private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$")

    fun register(ctx: Context) {
        val req = gson.fromJson(ctx.body(), RegReq::class.java)
        if (req == null || req.email == null || req.password == null) {
            ctx.status(400).result("email and password required")
            return
        }
        val email = req.email.trim()
        if (email.length > 64) {
            ctx.status(400).result("EMAIL_TOO_LONG")
            return
        }
        if (!EMAIL_REGEX.matches(email)) {
            ctx.status(400).result("INVALID_EMAIL_FORMAT")
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
            val verifyToken = userStore.getVerifyToken(user.id)
            if (verifyToken != null) {
                val panelUrl = System.getenv("PROPERBACKUP_PANEL_URL") ?: "https://panel.properbackup.com"
                val verifyUrl = "$panelUrl/verify-email?token=$verifyToken"
                emailService.send(
                    user.email,
                    "Confirm your ProperBackup account",
                    EmailTemplates.emailVerification(user.email, verifyUrl),
                )
                log.info("Verification email sent to {}", user.email)
            }
            val token = jwtService.generate(user.id, user.email, user.isSystemAdmin)
            ctx.contentType("application/json").result(gson.toJson(authResponse(token, user)))
        } catch (e: IllegalArgumentException) {
            ctx.status(409).result(e.message ?: "conflict")
        }
    }

    fun verifyEmail(ctx: Context) {
        val token = ctx.queryParam("token")
        if (token.isNullOrBlank()) {
            ctx.status(400).result("Missing verification token")
            return
        }
        if (userStore.verifyEmail(token)) {
            ctx.contentType("application/json").result(gson.toJson(mapOf("verified" to true)))
        } else {
            ctx.status(400).result("Invalid or expired verification token")
        }
    }

    fun resendVerification(ctx: Context) {
        val userId = ctx.attribute<String>("userId")
        if (userId == null) {
            ctx.status(401).result("Unauthorized")
            return
        }
        val user = userStore.findById(userId).orElse(null)
        if (user == null) {
            ctx.status(404).result("User not found")
            return
        }
        if (user.emailVerified) {
            ctx.contentType("application/json").result(gson.toJson(mapOf("alreadyVerified" to true)))
            return
        }
        val newToken = userStore.regenerateVerifyToken(userId)
        val panelUrl = System.getenv("PROPERBACKUP_PANEL_URL") ?: "https://panel.properbackup.com"
        val verifyUrl = "$panelUrl/verify-email?token=$newToken"
        emailService.send(
            user.email,
            "Confirm your ProperBackup account",
            EmailTemplates.emailVerification(user.email, verifyUrl),
        )
        log.info("Resent verification email to {}", user.email)
        ctx.contentType("application/json").result(gson.toJson(mapOf("sent" to true)))
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
        "emailVerified" to user.emailVerified,
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
