package pl.danielniemiec.properbackup.buffer.notifications

import com.google.gson.Gson
import io.javalin.http.Context
import pl.danielniemiec.properbackup.buffer.db.Database

/**
 * HTTP handler for notification settings (per-user).
 * GET  /notifications/settings  → current settings
 * POST /notifications/settings  → update settings
 */
class NotificationHandler(private val db: Database) {

    private val gson = Gson()

    fun getSettings(ctx: Context) {
        val userId = ctx.attribute<String>("userId")!!
        val settings = loadSettings(userId)
        ctx.contentType("application/json").result(gson.toJson(settings))
    }

    fun updateSettings(ctx: Context) {
        val userId = ctx.attribute<String>("userId")!!
        val body = gson.fromJson(ctx.body(), Map::class.java)

        val emailEnabled = body["emailEnabled"] as? Boolean ?: false
        val emailAddress = (body["emailAddress"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val smsEnabled = body["smsEnabled"] as? Boolean ?: false
        val smsNumber = (body["smsNumber"] as? String)?.trim()?.takeIf { it.isNotBlank() }

        db.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO notification_settings (user_id, email_enabled, email_address, sms_enabled, sms_number, updated_at)
                   VALUES (?, ?, ?, ?, ?, NOW())
                   ON CONFLICT (user_id) DO UPDATE SET
                     email_enabled = EXCLUDED.email_enabled,
                     email_address = EXCLUDED.email_address,
                     sms_enabled = EXCLUDED.sms_enabled,
                     sms_number = EXCLUDED.sms_number,
                     updated_at = NOW()""",
            ).use { ps ->
                ps.setString(1, userId)
                ps.setBoolean(2, emailEnabled)
                if (emailAddress == null) ps.setNull(3, java.sql.Types.VARCHAR) else ps.setString(3, emailAddress)
                ps.setBoolean(4, smsEnabled)
                if (smsNumber == null) ps.setNull(5, java.sql.Types.VARCHAR) else ps.setString(5, smsNumber)
                ps.executeUpdate()
            }
        }

        val updated = loadSettings(userId)
        ctx.contentType("application/json").result(gson.toJson(updated))
    }

    private fun loadSettings(userId: String): Map<String, Any?> {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT email_enabled, email_address, sms_enabled, sms_number, updated_at FROM notification_settings WHERE user_id = ?",
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        return linkedMapOf(
                            "emailEnabled" to rs.getBoolean("email_enabled"),
                            "emailAddress" to rs.getString("email_address"),
                            "smsEnabled" to rs.getBoolean("sms_enabled"),
                            "smsNumber" to rs.getString("sms_number"),
                            "updatedAt" to rs.getTimestamp("updated_at")?.toInstant()?.toString(),
                        )
                    }
                    return linkedMapOf(
                        "emailEnabled" to false,
                        "emailAddress" to null,
                        "smsEnabled" to false,
                        "smsNumber" to null,
                        "updatedAt" to null,
                    )
                }
            }
        }
    }
}
