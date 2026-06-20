package pl.danielniemiec.properbackup.buffer.ovh

import pl.danielniemiec.properbackup.buffer.db.Database
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OvhCostTracker(
    private val db: Database,
    private val storagePricePerGibMonth: BigDecimal = STORAGE_PRICE_PLN_PER_GIB_MONTH,
    private val uploadPricePerGib: BigDecimal = UPLOAD_PRICE_PLN_PER_GIB,
    private val spikeAlertThresholdBytes: Long = SPIKE_ALERT_THRESHOLD_BYTES,
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    fun start() {
        scheduler.scheduleAtFixedRate({ runDailyAggregation() }, 1, 60, TimeUnit.MINUTES)
        println("[cost-tracker] started (aggregation every 60min)")
    }

    fun stop() {
        scheduler.shutdown()
    }

    fun runDailyAggregation() {
        try {
            val today = LocalDate.now()
            db.getConnection().use { conn ->
                val userIds = mutableListOf<String>()
                conn.prepareStatement("SELECT id FROM users").use { ps ->
                    val rs = ps.executeQuery()
                    while (rs.next()) {
                        userIds.add(rs.getString("id"))
                    }
                }

                for (userId in userIds) {
                    val storedBytes = queryStoredBytes(userId)
                    val uploadedBytes = queryUploadedBytesToday(userId, today)
                    val storageCost = computeStorageCost(storedBytes)
                    val uploadCost = computeUploadCost(uploadedBytes)
                    val totalCost = storageCost.add(uploadCost)

                    upsertDaily(userId, today, storedBytes, uploadedBytes, storageCost, uploadCost, totalCost)

                    if (uploadedBytes > spikeAlertThresholdBytes) {
                        System.err.println(
                            "[cost-tracker] SPIKE ALERT: user=$userId uploaded ${uploadedBytes / (1024 * 1024)}MB today " +
                                "(threshold=${spikeAlertThresholdBytes / (1024 * 1024 * 1024)}GB)"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[cost-tracker] aggregation error: ${e.message}")
        }
    }

    fun queryStoredBytes(userId: String): Long {
        return db.getConnection().use { conn ->
            conn.prepareStatement(
                """SELECT COALESCE(SUM(bp.size_bytes), 0) AS total
                   FROM buffer_pack bp
                   JOIN servers s ON bp.server_id = s.id
                   WHERE s.user_id = ?"""
            ).use { ps ->
                ps.setString(1, userId)
                val rs = ps.executeQuery()
                if (rs.next()) rs.getLong("total") else 0L
            }
        }
    }

    fun queryUploadedBytesToday(userId: String, day: LocalDate): Long {
        return db.getConnection().use { conn ->
            conn.prepareStatement(
                """SELECT COALESCE(SUM(bp.size_bytes), 0) AS total
                   FROM buffer_pack bp
                   JOIN servers s ON bp.server_id = s.id
                   WHERE s.user_id = ? AND bp.flushed_at::date = ?"""
            ).use { ps ->
                ps.setString(1, userId)
                ps.setObject(2, day)
                val rs = ps.executeQuery()
                if (rs.next()) rs.getLong("total") else 0L
            }
        }
    }

    fun computeStorageCost(bytes: Long): BigDecimal {
        val gib = BigDecimal(bytes).divide(GIB_BYTES, 10, RoundingMode.HALF_UP)
        return gib.multiply(storagePricePerGibMonth).setScale(6, RoundingMode.HALF_UP)
    }

    fun computeUploadCost(bytes: Long): BigDecimal {
        val gib = BigDecimal(bytes).divide(GIB_BYTES, 10, RoundingMode.HALF_UP)
        return gib.multiply(uploadPricePerGib).setScale(6, RoundingMode.HALF_UP)
    }

    fun upsertDaily(
        userId: String,
        day: LocalDate,
        storedBytes: Long,
        uploadedBytes: Long,
        storageCost: BigDecimal,
        uploadCost: BigDecimal,
        totalCost: BigDecimal,
    ) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO user_storage_daily
                   (user_id, day, stored_bytes, uploaded_bytes, storage_cost_pln, upload_cost_pln, total_cost_pln, updated_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, NOW())
                   ON CONFLICT (user_id, day) DO UPDATE SET
                     stored_bytes = EXCLUDED.stored_bytes,
                     uploaded_bytes = EXCLUDED.uploaded_bytes,
                     storage_cost_pln = EXCLUDED.storage_cost_pln,
                     upload_cost_pln = EXCLUDED.upload_cost_pln,
                     total_cost_pln = EXCLUDED.total_cost_pln,
                     updated_at = NOW()"""
            ).use { ps ->
                ps.setString(1, userId)
                ps.setObject(2, day)
                ps.setLong(3, storedBytes)
                ps.setLong(4, uploadedBytes)
                ps.setBigDecimal(5, storageCost)
                ps.setBigDecimal(6, uploadCost)
                ps.setBigDecimal(7, totalCost)
                ps.executeUpdate()
            }
        }
    }

    fun getDailyCosts(userId: String, days: Int = 30): List<DailyCostRow> {
        return db.getConnection().use { conn ->
            conn.prepareStatement(
                """SELECT day, stored_bytes, uploaded_bytes, storage_cost_pln, upload_cost_pln, total_cost_pln
                   FROM user_storage_daily
                   WHERE user_id = ? AND day >= CURRENT_DATE - ?::int
                   ORDER BY day DESC"""
            ).use { ps ->
                ps.setString(1, userId)
                ps.setInt(2, days)
                val rs = ps.executeQuery()
                val rows = mutableListOf<DailyCostRow>()
                while (rs.next()) {
                    rows.add(
                        DailyCostRow(
                            day = rs.getDate("day").toLocalDate(),
                            storedBytes = rs.getLong("stored_bytes"),
                            uploadedBytes = rs.getLong("uploaded_bytes"),
                            storageCostPln = rs.getBigDecimal("storage_cost_pln"),
                            uploadCostPln = rs.getBigDecimal("upload_cost_pln"),
                            totalCostPln = rs.getBigDecimal("total_cost_pln"),
                        )
                    )
                }
                rows
            }
        }
    }

    data class DailyCostRow(
        val day: LocalDate,
        val storedBytes: Long,
        val uploadedBytes: Long,
        val storageCostPln: BigDecimal,
        val uploadCostPln: BigDecimal,
        val totalCostPln: BigDecimal,
    )

    companion object {
        private val GIB_BYTES = BigDecimal(1024L * 1024 * 1024)
        val STORAGE_PRICE_PLN_PER_GIB_MONTH: BigDecimal = BigDecimal("0.009636")
        val UPLOAD_PRICE_PLN_PER_GIB: BigDecimal = BigDecimal("0.04")
        const val SPIKE_ALERT_THRESHOLD_BYTES = 50L * 1024 * 1024 * 1024
    }
}
