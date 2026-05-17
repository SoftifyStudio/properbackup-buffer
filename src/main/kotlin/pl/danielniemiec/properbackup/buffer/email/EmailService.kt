package pl.danielniemiec.properbackup.buffer.email

import com.google.gson.Gson
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Sends transactional email via Resend HTTP API.
 * Falls back to a no-op logger when RESEND_API_KEY is not configured.
 */
class EmailService {

    private val log = LoggerFactory.getLogger(EmailService::class.java)
    private val gson = Gson()
    private val apiKey = System.getenv("RESEND_API_KEY") ?: ""
    private val fromAddress = System.getenv("EMAIL_FROM") ?: "ProperBackup <noreply@properbackup.com>"
    private val enabled = apiKey.isNotBlank()
    private val client: HttpClient = HttpClient.newBuilder().build()

    fun isEnabled(): Boolean = enabled

    fun send(to: String, subject: String, html: String) {
        if (!enabled) {
            log.info("SKIP (no RESEND_API_KEY): to={} subject={}", to, subject)
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
                log.info("Sent to={} subject={}", to, subject)
            } else {
                log.error("Send error {} for to={}: {}", response.statusCode(), to, response.body())
            }
        } catch (e: Exception) {
            log.error("Send failed to={}: {}", to, e.message)
        }
    }
}
