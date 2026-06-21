package pl.danielniemiec.properbackup.buffer.notifications

import com.google.gson.Gson
import io.javalin.Javalin
import io.javalin.testtools.JavalinTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.danielniemiec.properbackup.buffer.db.Database
import pl.danielniemiec.properbackup.buffer.recovery.TestDatabaseHelper

class NotificationHandlerTest {

    companion object {
        private lateinit var db: Database
        private lateinit var handler: NotificationHandler
        private val gson = Gson()
        private val JSON = "application/json".toMediaType()

        @JvmStatic
        @BeforeAll
        fun setup() {
            db = TestDatabaseHelper.createDatabase()
            handler = NotificationHandler(db)
            TestDatabaseHelper.seedUserAndServer(db, "user-notif-1", "server-notif-1")
            TestDatabaseHelper.seedUserAndServer(db, "user-notif-2", "server-notif-2")
        }
    }

    @BeforeEach
    fun cleanup() {
        db.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM notification_settings")
            }
        }
    }

    private fun createApp(userId: String = "user-notif-1"): Javalin = Javalin.create().apply {
        before { ctx ->
            ctx.attribute("userId", ctx.header("X-Test-UserId") ?: userId)
        }
        get("/notifications/settings", handler::getSettings)
        post("/notifications/settings", handler::updateSettings)
    }

    private fun jsonBody(data: Any): okhttp3.RequestBody =
        gson.toJson(data).toRequestBody(JSON)

    // --- GET: default settings ---

    @Test
    fun `GET returns defaults when no settings saved`() {
        JavalinTest.test(createApp()) { _, client ->
            val resp = client.get("/notifications/settings")
            assertEquals(200, resp.code)

            val body = gson.fromJson(resp.body?.string(), Map::class.java)
            assertEquals(false, body["emailEnabled"])
            assertNull(body["emailAddress"])
            assertEquals(false, body["smsEnabled"])
            assertNull(body["smsNumber"])
            assertNull(body["updatedAt"])
        }
    }

    // --- POST + GET: save and retrieve ---

    @Test
    fun `POST saves email settings and GET returns them`() {
        JavalinTest.test(createApp()) { _, client ->
            val postResp = client.request("/notifications/settings") {
                it.post(jsonBody(mapOf(
                    "emailEnabled" to true,
                    "emailAddress" to "alert@mycompany.com",
                    "smsEnabled" to false,
                    "smsNumber" to null,
                )))
            }
            assertEquals(200, postResp.code)

            val postBody = gson.fromJson(postResp.body?.string(), Map::class.java)
            assertEquals(true, postBody["emailEnabled"])
            assertEquals("alert@mycompany.com", postBody["emailAddress"])
            assertEquals(false, postBody["smsEnabled"])
            assertNotNull(postBody["updatedAt"])

            // GET to confirm persistence
            val getResp = client.get("/notifications/settings")
            assertEquals(200, getResp.code)
            val getBody = gson.fromJson(getResp.body?.string(), Map::class.java)
            assertEquals(true, getBody["emailEnabled"])
            assertEquals("alert@mycompany.com", getBody["emailAddress"])
        }
    }

    // --- POST: upsert overwrites existing ---

    @Test
    fun `POST updates existing settings (upsert)`() {
        JavalinTest.test(createApp()) { _, client ->
            // Save initial
            client.request("/notifications/settings") {
                it.post(jsonBody(mapOf(
                    "emailEnabled" to true,
                    "emailAddress" to "first@test.com",
                )))
            }

            // Overwrite
            val resp = client.request("/notifications/settings") {
                it.post(jsonBody(mapOf(
                    "emailEnabled" to false,
                    "emailAddress" to "second@test.com",
                )))
            }
            assertEquals(200, resp.code)

            val body = gson.fromJson(resp.body?.string(), Map::class.java)
            assertEquals(false, body["emailEnabled"])
            assertEquals("second@test.com", body["emailAddress"])
        }
    }

    // --- Edge: empty body → defaults ---

    @Test
    fun `POST with empty JSON uses defaults`() {
        JavalinTest.test(createApp()) { _, client ->
            val resp = client.request("/notifications/settings") {
                it.post(jsonBody(emptyMap<String, Any>()))
            }
            assertEquals(200, resp.code)

            val body = gson.fromJson(resp.body?.string(), Map::class.java)
            assertEquals(false, body["emailEnabled"])
            assertNull(body["emailAddress"])
            assertEquals(false, body["smsEnabled"])
            assertNull(body["smsNumber"])
        }
    }

    // --- Edge: blank email treated as null ---

    @Test
    fun `POST with blank email address saves null`() {
        JavalinTest.test(createApp()) { _, client ->
            val resp = client.request("/notifications/settings") {
                it.post(jsonBody(mapOf(
                    "emailEnabled" to true,
                    "emailAddress" to "   ",
                )))
            }
            assertEquals(200, resp.code)

            val body = gson.fromJson(resp.body?.string(), Map::class.java)
            assertEquals(true, body["emailEnabled"])
            assertNull(body["emailAddress"])
        }
    }

    // --- Edge: SMS fields saved ---

    @Test
    fun `POST saves SMS fields correctly`() {
        JavalinTest.test(createApp()) { _, client ->
            val resp = client.request("/notifications/settings") {
                it.post(jsonBody(mapOf(
                    "emailEnabled" to false,
                    "smsEnabled" to true,
                    "smsNumber" to "+48123456789",
                )))
            }
            assertEquals(200, resp.code)

            val body = gson.fromJson(resp.body?.string(), Map::class.java)
            assertEquals(true, body["smsEnabled"])
            assertEquals("+48123456789", body["smsNumber"])
        }
    }

    // --- Isolation: different users have independent settings ---

    @Test
    fun `settings are isolated per user`() {
        JavalinTest.test(createApp()) { _, client ->
            // Save user1 settings
            client.request("/notifications/settings") {
                it.header("X-Test-UserId", "user-notif-1")
                    .post(jsonBody(mapOf(
                        "emailEnabled" to true,
                        "emailAddress" to "user1@test.com",
                    )))
            }

            // Save user2 settings
            client.request("/notifications/settings") {
                it.header("X-Test-UserId", "user-notif-2")
                    .post(jsonBody(mapOf(
                        "emailEnabled" to false,
                        "emailAddress" to "user2@test.com",
                    )))
            }

            // Verify user1 still has their own settings
            val resp1 = client.request("/notifications/settings") {
                it.header("X-Test-UserId", "user-notif-1").get()
            }
            val body1 = gson.fromJson(resp1.body?.string(), Map::class.java)
            assertEquals(true, body1["emailEnabled"])
            assertEquals("user1@test.com", body1["emailAddress"])

            // Verify user2 has their own settings
            val resp2 = client.request("/notifications/settings") {
                it.header("X-Test-UserId", "user-notif-2").get()
            }
            val body2 = gson.fromJson(resp2.body?.string(), Map::class.java)
            assertEquals(false, body2["emailEnabled"])
            assertEquals("user2@test.com", body2["emailAddress"])
        }
    }

    // --- POST returns updated_at timestamp ---

    @Test
    fun `POST returns non-null updatedAt`() {
        JavalinTest.test(createApp()) { _, client ->
            val resp = client.request("/notifications/settings") {
                it.post(jsonBody(mapOf("emailEnabled" to true, "emailAddress" to "ts@test.com")))
            }
            assertEquals(200, resp.code)

            val body = gson.fromJson(resp.body?.string(), Map::class.java)
            assertNotNull(body["updatedAt"])
            assertTrue((body["updatedAt"] as String).isNotBlank())
        }
    }

    // --- All fields together ---

    @Test
    fun `POST saves all four fields at once`() {
        JavalinTest.test(createApp()) { _, client ->
            val resp = client.request("/notifications/settings") {
                it.post(jsonBody(mapOf(
                    "emailEnabled" to true,
                    "emailAddress" to "all@test.com",
                    "smsEnabled" to true,
                    "smsNumber" to "+48999888777",
                )))
            }
            assertEquals(200, resp.code)

            val body = gson.fromJson(resp.body?.string(), Map::class.java)
            assertEquals(true, body["emailEnabled"])
            assertEquals("all@test.com", body["emailAddress"])
            assertEquals(true, body["smsEnabled"])
            assertEquals("+48999888777", body["smsNumber"])
        }
    }
}
