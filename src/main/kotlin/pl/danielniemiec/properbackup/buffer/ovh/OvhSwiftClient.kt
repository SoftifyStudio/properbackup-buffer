package pl.danielniemiec.properbackup.buffer.ovh

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Optional
import java.util.regex.Pattern

class OvhSwiftClient private constructor(
    private val authUrlBase: String,
    private val keystoneAuthJson: String,
    private val container: String,
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val initialRetryDelayMs: Long = DEFAULT_INITIAL_RETRY_DELAY_MS,
    private val maxRetryDelayMs: Long = DEFAULT_MAX_RETRY_DELAY_MS,
) : SwiftClient {

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    @Volatile
    private var token: String? = null

    @Volatile
    private var tokenExpiresAt: Long = 0

    @Volatile
    private var storageUrl: String? = null

    companion object {
        const val DEFAULT_MAX_RETRIES = 5
        const val DEFAULT_INITIAL_RETRY_DELAY_MS = 1_000L
        const val DEFAULT_MAX_RETRY_DELAY_MS = 60_000L

        @JvmStatic
        fun tryCreateFromEnv(): OvhSwiftClient? {
            val auth = firstNonBlankWithDefault(
                System.getenv("OS_AUTH_URL"),
                System.getenv("PROPERBACKUP_OVH_AUTH_URL"),
                "https://auth.cloud.ovh.net/v3",
            )
            val authBase = if (auth.endsWith("/")) auth.substring(0, auth.length - 1) else auth

            val password = firstNonBlank(
                System.getenv("OS_PASSWORD"),
                System.getenv("PROPERBACKUP_OVH_PASSWORD"),
            )
            val projectId = firstNonBlank(
                System.getenv("OS_PROJECT_ID"),
                System.getenv("OS_TENANT_ID"),
                System.getenv("PROPERBACKUP_OVH_PROJECT_ID"),
            )
            val container = envOrDefault(System.getenv("PROPERBACKUP_OVH_CONTAINER"), "properbackup")

            val userName = Optional.ofNullable(System.getenv("OS_USERNAME")).map { it.trim() }
                .filter { it.isNotEmpty() }.orElse(null)
            val userId = Optional.ofNullable(System.getenv("PROPERBACKUP_OVH_USER_ID")).map { it.trim() }
                .filter { it.isNotEmpty() }.orElse(null)

            if (password == null || projectId == null) return null

            val json = when {
                userName != null -> {
                    val userDomain = envOrDefault(System.getenv("OS_USER_DOMAIN_NAME"), "Default")
                    buildPasswordAuthByName(userName, password, userDomain, projectId)
                }
                userId != null -> buildPasswordAuthById(userId, password, projectId)
                else -> return null
            }
            return OvhSwiftClient(authBase, json, container)
        }

        private fun buildPasswordAuthByName(user: String, pass: String, userDomain: String, projectId: String): String {
            val root = JsonObject()
            val auth = JsonObject()
            val identity = JsonObject()
            identity.add("methods", JsonParser.parseString("[\"password\"]"))
            val passwordObj = JsonObject()
            val userObj = JsonObject()
            userObj.addProperty("name", user)
            userObj.addProperty("password", pass)
            val dom = JsonObject()
            dom.addProperty("name", userDomain)
            userObj.add("domain", dom)
            passwordObj.add("user", userObj)
            identity.add("password", passwordObj)
            auth.add("identity", identity)
            val scope = JsonObject()
            val project = JsonObject()
            project.addProperty("id", projectId)
            scope.add("project", project)
            auth.add("scope", scope)
            root.add("auth", auth)
            return root.toString()
        }

        private fun buildPasswordAuthById(userId: String, pass: String, projectId: String): String {
            val root = JsonObject()
            val auth = JsonObject()
            val identity = JsonObject()
            identity.add("methods", JsonParser.parseString("[\"password\"]"))
            val passwordObj = JsonObject()
            val userObj = JsonObject()
            userObj.addProperty("id", userId)
            userObj.addProperty("password", pass)
            passwordObj.add("user", userObj)
            identity.add("password", passwordObj)
            auth.add("identity", identity)
            val scope = JsonObject()
            val project = JsonObject()
            project.addProperty("id", projectId)
            scope.add("project", project)
            auth.add("scope", scope)
            root.add("auth", auth)
            return root.toString()
        }

        private fun truncate(s: String?, max: Int): String {
            if (s == null) return ""
            return if (s.length <= max) s else s.substring(0, max) + "..."
        }

        private fun stripTrailingSlash(u: String): String =
            if (u.endsWith("/")) u.substring(0, u.length - 1) else u

        private fun enc(s: String): String =
            URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")

        private fun encObjectName(objectName: String): String {
            if (!objectName.contains("/")) return enc(objectName)
            return objectName.split("/").joinToString("/") { enc(it) }
        }

        private fun firstNonBlank(a: String?, b: String?): String? {
            if (a != null && a.isNotBlank()) return a.trim()
            if (b != null && b.isNotBlank()) return b.trim()
            return null
        }

        private fun firstNonBlank(a: String?, b: String?, c: String?): String? {
            val x = firstNonBlank(a, b)
            if (x != null) return x
            if (c != null && c.isNotBlank()) return c.trim()
            return null
        }

        private fun firstNonBlankWithDefault(a: String?, b: String?, defaultValue: String): String {
            val x = firstNonBlank(a, b)
            return if (x.isNullOrBlank()) defaultValue else x
        }

        private fun envOrDefault(value: String?, defaultValue: String): String =
            if (value.isNullOrBlank()) defaultValue else value.trim()

        private fun extractStorageUrl(keystoneJson: String): String {
            try {
                val root = JsonParser.parseString(keystoneJson).asJsonObject
                val token = root.getAsJsonObject("token")
                val catalog = token.getAsJsonArray("catalog")
                var fallback: String? = null
                for (el in catalog) {
                    val svc = el.asJsonObject
                    if (!svc.has("type") || svc.get("type").asString != "object-store") continue
                    val endpoints = svc.getAsJsonArray("endpoints")
                    for (ep in endpoints) {
                        val e = ep.asJsonObject
                        if (!e.has("interface") || e.get("interface").asString != "public") continue
                        val u = e.get("url").asString
                        if (u.contains("swift") || u.contains("storage")) {
                            return stripTrailingSlash(u)
                        }
                        fallback = u
                    }
                }
                if (fallback != null) return stripTrailingSlash(fallback)
            } catch (ignored: Exception) {
            }
            val p = Pattern.compile("\"publicURL\"\\s*:\\s*\"(https://[^\"]+)\"")
            val m = p.matcher(keystoneJson)
            while (m.find()) {
                val url = m.group(1)
                if (url.contains("storage") || url.contains("swift")) return stripTrailingSlash(url)
            }
            throw IllegalStateException("Could not find Swift storage URL in Keystone response")
        }
    }

    private fun <T> send(req: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        return try {
            http.send(req, handler)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException(e)
        }
    }

    private fun <T> sendWithRetry(
        requestBuilder: () -> HttpRequest,
        handler: HttpResponse.BodyHandler<T>,
        operationName: String,
    ): HttpResponse<T> {
        var delay = initialRetryDelayMs
        var lastException: IOException? = null

        for (attempt in 1..maxRetries) {
            try {
                val resp = send(requestBuilder(), handler)
                val code = resp.statusCode()
                if (code in 500..599 && attempt < maxRetries) {
                    System.err.println("[OVH] $operationName attempt $attempt/$maxRetries got $code, retrying in ${delay}ms")
                    Thread.sleep(delay)
                    delay = minOf(delay * 2, maxRetryDelayMs)
                    continue
                }
                return resp
            } catch (e: IOException) {
                lastException = e
                if (attempt == maxRetries) break
                System.err.println("[OVH] $operationName attempt $attempt/$maxRetries failed: ${e.message}, retrying in ${delay}ms")
                try {
                    Thread.sleep(delay)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted during retry", ie)
                }
                delay = minOf(delay * 2, maxRetryDelayMs)
            }
        }
        throw lastException ?: IOException("$operationName failed after $maxRetries attempts")
    }

    @Synchronized
    @Throws(IOException::class)
    private fun ensureToken() {
        val t = token
        if (t != null && System.currentTimeMillis() < tokenExpiresAt - 60_000L) return

        val uri = URI.create("$authUrlBase/auth/tokens")
        val req = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(keystoneAuthJson, StandardCharsets.UTF_8))
            .build()

        val resp = send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        val code = resp.statusCode()
        if (code != 201) {
            throw IOException("Keystone auth failed: $code ${truncate(resp.body(), 300)}")
        }
        token = resp.headers().firstValue("X-Subject-Token")
            .orElseThrow { IOException("Keystone response missing X-Subject-Token") }
        storageUrl = extractStorageUrl(resp.body())
        tokenExpiresAt = System.currentTimeMillis() + 23L * 3600 * 1000
    }

    private fun objectUri(objectName: String): URI {
        return URI.create("${storageUrl!!}/${enc(container)}/${encObjectName(objectName)}")
    }

    override fun put(objectName: String, data: ByteArray) {
        ensureToken()
        val resp = sendWithRetry(
            requestBuilder = {
                HttpRequest.newBuilder(objectUri(objectName))
                    .timeout(Duration.ofMinutes(5))
                    .header("X-Auth-Token", token!!)
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(data))
                    .build()
            },
            handler = HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
            operationName = "PUT $objectName",
        )
        val code = resp.statusCode()
        if (code < 200 || code >= 300) {
            throw IOException("OVH PUT failed: $code ${truncate(resp.body(), 200)}")
        }
        verifyAfterPut(objectName, data.size.toLong())
    }

    private fun verifyAfterPut(objectName: String, expectedSize: Long) {
        val meta = headObject(objectName)
        if (meta == null) {
            System.err.println("[OVH] integrity warning: HEAD after PUT returned null for $objectName")
            return
        }
        if (meta.contentLength != expectedSize) {
            System.err.println(
                "[OVH] integrity MISMATCH for $objectName: expected=$expectedSize, got=${meta.contentLength}"
            )
            throw IOException(
                "OVH integrity verification failed: size mismatch for $objectName " +
                    "(expected=$expectedSize, actual=${meta.contentLength})"
            )
        }
    }

    override fun get(objectName: String): ByteArray {
        ensureToken()
        val resp = sendWithRetry(
            requestBuilder = {
                HttpRequest.newBuilder(objectUri(objectName))
                    .timeout(Duration.ofMinutes(2))
                    .header("X-Auth-Token", token!!)
                    .GET()
                    .build()
            },
            handler = HttpResponse.BodyHandlers.ofByteArray(),
            operationName = "GET $objectName",
        )
        val code = resp.statusCode()
        if (code == 404) throw IOException("Object not found: $objectName")
        if (code < 200 || code >= 300) throw IOException("OVH GET failed: $code")
        return resp.body()
    }

    override fun list(): List<String> {
        ensureToken()
        val uri = URI.create("${storageUrl!!}/${enc(container)}?format=plain")
        val resp = sendWithRetry(
            requestBuilder = {
                HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMinutes(2))
                    .header("X-Auth-Token", token!!)
                    .GET()
                    .build()
            },
            handler = HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
            operationName = "LIST",
        )
        val code = resp.statusCode()
        if (code == 204) return emptyList()
        if (code < 200 || code >= 300) throw IOException("OVH LIST failed: $code")
        return resp.body().split("\n").filter { it.isNotBlank() }.sorted()
    }

    override fun delete(objectName: String) {
        ensureToken()
        val uri = objectUri(objectName)
        val resp = sendWithRetry(
            requestBuilder = {
                HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMinutes(1))
                    .header("X-Auth-Token", token!!)
                    .DELETE()
                    .build()
            },
            handler = HttpResponse.BodyHandlers.discarding(),
            operationName = "DELETE $objectName",
        )
        val code = resp.statusCode()
        if (code != 204 && code != 404) throw IOException("OVH DELETE failed: $code")
    }

    override fun exists(objectName: String): Boolean {
        ensureToken()
        val uri = objectUri(objectName)
        val req = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("X-Auth-Token", token!!)
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .build()
        val resp = send(req, HttpResponse.BodyHandlers.discarding())
        return resp.statusCode() == 200
    }

    override fun headObject(objectName: String): ObjectMetadata? {
        ensureToken()
        val uri = objectUri(objectName)
        val req = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("X-Auth-Token", token!!)
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .build()
        val resp = send(req, HttpResponse.BodyHandlers.discarding())
        if (resp.statusCode() == 404) return null
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw IOException("OVH HEAD failed: ${resp.statusCode()}")
        }
        val headers = mutableMapOf<String, String>()
        resp.headers().map().forEach { (k, v) -> if (v.isNotEmpty()) headers[k.lowercase()] = v[0] }
        return ObjectMetadata(
            contentLength = headers["content-length"]?.toLongOrNull() ?: 0L,
            etag = headers["etag"]?.replace("\"", ""),
            contentType = headers["content-type"],
            headers = headers,
        )
    }

    override fun requestUnseal(objectName: String): UnsealStatus {
        ensureToken()
        val uri = objectUri(objectName)
        val req = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("X-Auth-Token", token!!)
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .build()
        val resp = send(req, HttpResponse.BodyHandlers.discarding())
        if (resp.statusCode() == 404) return UnsealStatus.NOT_FOUND

        val headers = mutableMapOf<String, String>()
        resp.headers().map().forEach { (k, v) -> if (v.isNotEmpty()) headers[k.lowercase()] = v[0] }

        val unsealState = headers["x-ovh-retrieval-state"]
        return when {
            unsealState == null -> UnsealStatus.UNSEALED
            unsealState.equals("unsealed", ignoreCase = true) -> UnsealStatus.UNSEALED
            unsealState.equals("unsealing", ignoreCase = true) -> UnsealStatus.UNSEALING
            unsealState.equals("sealed", ignoreCase = true) -> {
                val getReq = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("X-Auth-Token", token!!)
                    .GET()
                    .build()
                try {
                    send(getReq, HttpResponse.BodyHandlers.discarding())
                } catch (_: IOException) {
                    // expected to fail for sealed objects; triggers unseal
                }
                UnsealStatus.UNSEALING
            }
            else -> UnsealStatus.SEALED
        }
    }

    override fun checkUnsealStatus(objectName: String): UnsealStatus {
        ensureToken()
        val uri = objectUri(objectName)
        val req = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("X-Auth-Token", token!!)
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .build()
        val resp = send(req, HttpResponse.BodyHandlers.discarding())
        if (resp.statusCode() == 404) return UnsealStatus.NOT_FOUND

        val headers = mutableMapOf<String, String>()
        resp.headers().map().forEach { (k, v) -> if (v.isNotEmpty()) headers[k.lowercase()] = v[0] }

        val unsealState = headers["x-ovh-retrieval-state"]
        return when {
            unsealState == null -> UnsealStatus.UNSEALED
            unsealState.equals("unsealed", ignoreCase = true) -> UnsealStatus.UNSEALED
            unsealState.equals("unsealing", ignoreCase = true) -> UnsealStatus.UNSEALING
            else -> UnsealStatus.SEALED
        }
    }
}
