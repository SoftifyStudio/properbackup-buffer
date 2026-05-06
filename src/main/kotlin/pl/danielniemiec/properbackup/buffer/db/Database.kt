package pl.danielniemiec.properbackup.buffer.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.Statement

class Database(jdbcUrl: String, user: String, password: String) {

    private val ds: HikariDataSource

    init {
        val cfg = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            username = user
            this.password = password
            maximumPoolSize = 10
            minimumIdle = 2
            connectionTimeout = 10_000
            initializationFailTimeout = 30_000
        }
        ds = HikariDataSource(cfg)
        runSchema()
    }

    fun getConnection(): Connection = ds.connection

    fun close() {
        ds.close()
    }

    private fun runSchema() {
        val sql = javaClass.getResourceAsStream("/schema.sql")?.use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
            ?: throw IllegalStateException("schema.sql not found in classpath")
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                for (statement in sql.split(";")) {
                    val trimmed = statement.trim()
                    if (trimmed.isNotEmpty()) {
                        stmt.execute(trimmed)
                    }
                }
            }
        }
        println("[db] Schema initialized")
    }
}
