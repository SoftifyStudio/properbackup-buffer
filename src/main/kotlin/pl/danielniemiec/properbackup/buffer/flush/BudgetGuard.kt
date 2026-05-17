package pl.danielniemiec.properbackup.buffer.flush

import org.slf4j.LoggerFactory
import pl.danielniemiec.properbackup.buffer.db.Database
import java.sql.SQLException

class BudgetGuard(private val db: Database, private val maxFlushesPerDay: Int = 3) {

    private val log = LoggerFactory.getLogger(BudgetGuard::class.java)

    @Synchronized
    fun tryConsume(userId: String, pathId: String): Boolean {
        try {
            db.getConnection().use { conn ->
                conn.autoCommit = false
                try {
                    var current = -1
                    conn.prepareStatement(
                        "SELECT used_count FROM flush_budget " +
                            "WHERE user_id = ? AND path_id = ? AND day = CURRENT_DATE FOR UPDATE",
                    ).use { sel ->
                        sel.setString(1, userId)
                        sel.setString(2, pathId)
                        sel.executeQuery().use { rs ->
                            if (rs.next()) current = rs.getInt(1)
                        }
                    }
                    if (current >= maxFlushesPerDay) {
                        conn.rollback()
                        log.warn("Flush blocked for {}/{} — {}/{} used today", userId, pathId, current, maxFlushesPerDay)
                        return false
                    }
                    if (current < 0) {
                        conn.prepareStatement(
                            "INSERT INTO flush_budget (user_id, path_id, day, used_count) VALUES (?, ?, CURRENT_DATE, 1)",
                        ).use { ins ->
                            ins.setString(1, userId)
                            ins.setString(2, pathId)
                            ins.executeUpdate()
                        }
                    } else {
                        conn.prepareStatement(
                            "UPDATE flush_budget SET used_count = used_count + 1 " +
                                "WHERE user_id = ? AND path_id = ? AND day = CURRENT_DATE",
                        ).use { up ->
                            up.setString(1, userId)
                            up.setString(2, pathId)
                            up.executeUpdate()
                        }
                    }
                    conn.commit()
                    return true
                } catch (e: SQLException) {
                    conn.rollback()
                    log.error("DB error during tryConsume for {}/{}: {}", userId, pathId, e.message)
                    return false
                }
            }
        } catch (e: SQLException) {
            log.error("Connection error during tryConsume for {}/{}: {}", userId, pathId, e.message)
            return false
        }
    }

    fun remaining(userId: String, pathId: String): Int {
        return try {
            db.getConnection().use { conn ->
                conn.prepareStatement(
                    "SELECT used_count FROM flush_budget " +
                        "WHERE user_id = ? AND path_id = ? AND day = CURRENT_DATE",
                ).use { ps ->
                    ps.setString(1, userId)
                    ps.setString(2, pathId)
                    ps.executeQuery().use { rs ->
                        val used = if (rs.next()) rs.getInt(1) else 0
                        maxOf(0, maxFlushesPerDay - used)
                    }
                }
            }
        } catch (e: SQLException) {
            log.error("DB error in remaining() for {}/{}: {}", userId, pathId, e.message)
            0
        }
    }
}
