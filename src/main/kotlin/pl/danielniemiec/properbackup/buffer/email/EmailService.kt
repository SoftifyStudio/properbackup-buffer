package pl.danielniemiec.properbackup.buffer.email

import com.google.gson.Gson
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Sends transactional email via Resend HTTP API.
 * Falls back to a no-op logger when RESEND_API_KEY is not configured.
 */
class EmailService {

    private val gson = Gson()
    private val apiKey = System.getenv("RESEND_API_KEY") ?: ""
    private val fromAddress = System.getenv("EMAIL_FROM") ?: "ProperBackup <noreply@properbackup.com>"
    private val enabled = apiKey.isNotBlank()
    private val client: HttpClient = HttpClient.newBuilder().build()

    fun isEnabled(): Boolean = enabled

    fun send(to: String, subject: String, html: String) {
        if (!enabled) {
            println("[email] SKIP (no RESEND_API_KEY): to=$to subject=$subject")
            return
        }

        val payload = gson.toJson(
            mapOf(
                "from" to fromAddress,
                "to" to listOf(to),
                "subject" to subject,
                "html" to html,
            ),
        )

        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.resend.com/emails"))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) {
                println("[email] Sent to=$to subject=$subject")
            } else {
                println("[email] ERROR ${response.statusCode()}: ${response.body()}")
            }
        } catch (e: Exception) {
            println("[email] FAILED to=$to: ${e.message}")
        }
    }
}
