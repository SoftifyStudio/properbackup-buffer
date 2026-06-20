package pl.danielniemiec.properbackup.buffer.monitoring

import com.google.gson.Gson
import io.javalin.http.Context
import pl.danielniemiec.properbackup.buffer.db.Database
import java.io.File

/**
 * Health, readiness, and liveness endpoints.
 * Per [OBS-A1, OBS-A2, OBS-A3, OBS-A4] from observability-and-dr-spec.md.
 *
 * - /health: public, checks all critical dependencies
 * - /health/detailed: admin-only, full status with latency/connections
 * - /live: always 200 if process is alive
 * - /ready: 200 only when fully initialized (DB pool warm)
 */
class HealthHandler(
    private val db: Database,
    private val storageDir: String,
    private val startTimeMs: Long = System.currentTimeMillis(),
) {
    private val gson = Gson()
    @Volatile
    private var ready = false

    fun markReady() {
        ready = true
    }

    /**
     * GET /health — public health check.
     * Returns 200 {"status":"healthy"} or 503 {"status":"degraded","reason":"..."}.
     * Never exposes secrets or connection strings.
     */
    fun health(ctx: Context) {
        val checks = runChecks()
        val allOk = checks.all { it.ok }

        if (allOk) {
            ctx.status(200).contentType("application/json").result(gson.toJson(mapOf("status" to "healthy")))
        } else {
            val reasons = checks.filter { !it.ok }.joinToString(", ") { it.name }
            ctx.status(503).contentType("application/json").result(gson.toJson(mapOf("status" to "degraded", "reason" to reasons)))
        }
    }

    /**
     * GET /health/detailed — admin-only full diagnostics.
     * Requires Authorization header (validated by caller/before-filter).
     */
    fun healthDetailed(ctx: Context) {
        val checks = runChecks()
        val allOk = checks.all { it.ok }

        val checksMap = checks.associate { check ->
            check.name to buildMap {
                put("ok", check.ok)
                put("latency_ms", check.latencyMs)
                check.details.forEach { (k, v) -> put(k, v) }
            }
        }

        ctx.status(if (allOk) 200 else 503).contentType("application/json").result(gson.toJson(mapOf(
            "status" to if (allOk) "healthy" else "degraded",
            "checks" to checksMap,
            "uptime_seconds" to (System.currentTimeMillis() - startTimeMs) / 1000,
        )))
    }

    /**
     * GET /live — Kubernetes liveness probe.
     * Always 200 if process is alive.
     */
    fun live(ctx: Context) {
        ctx.status(200).result("OK")
    }

    /**
     * GET /ready — Kubernetes readiness probe.
     * 503 during warm-up (first ~5s), 200 once DB pool initialized.
     */
    fun ready(ctx: Context) {
        if (ready) {
            ctx.status(200).result("OK")
        } else {
            ctx.status(503).result("warming up")
        }
    }

    private fun runChecks(): List<CheckResult> {
        return listOf(
            checkPostgres(),
            checkDisk(),
        )
    }

    private fun checkPostgres(): CheckResult {
        val start = System.currentTimeMillis()
        return try {
            db.getConnection().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT 1").use { it.next() }
                }
            }
            val latency = System.currentTimeMillis() - start
            CheckResult("postgres", true, latency)
        } catch (e: Exception) {
            CheckResult("postgres", false, System.currentTimeMillis() - start,
                mapOf("error" to (e.message ?: "unknown")))
        }
    }

    private fun checkDisk(): CheckResult {
        val start = System.currentTimeMillis()
        return try {
            val dir = File(storageDir)
            if (!dir.exists()) dir.mkdirs()
            val total = dir.totalSpace
            val free = dir.usableSpace
            val usedPct = if (total > 0) ((total - free) * 100 / total).toInt() else 0
            val latency = System.currentTimeMillis() - start

            val ok = usedPct < 95 // degraded at 95%+
            CheckResult("disk", ok, latency, mapOf(
                "used_pct" to usedPct,
                "free_gb" to (free / (1024 * 1024 * 1024)),
            ))
        } catch (e: Exception) {
            CheckResult("disk", false, System.currentTimeMillis() - start,
                mapOf("error" to (e.message ?: "unknown")))
        }
    }

    data class CheckResult(
        val name: String,
        val ok: Boolean,
        val latencyMs: Long,
        val details: Map<String, Any> = emptyMap(),
    )
}
