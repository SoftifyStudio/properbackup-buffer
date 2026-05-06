package pl.danielniemiec.properbackup.buffer.server

import pl.danielniemiec.properbackup.buffer.db.Database
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.Optional
import java.util.UUID

class ServerStore(private val db: Database) {

    fun listForUser(userId: String): List<Server> {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT s.id, s.name, s.user_id, u.email AS owner_email, s.activated_at, s.last_seen_at " +
                    "FROM servers s JOIN users u ON u.id = s.user_id WHERE s.user_id = ? ORDER BY s.activated_at",
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    val result = ArrayList<Server>()
                    while (rs.next()) result.add(map(rs))
                    return result
                }
            }
        }
    }

    fun listAll(): List<Server> {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT s.id, s.name, s.user_id, u.email AS owner_email, s.activated_at, s.last_seen_at " +
                    "FROM servers s JOIN users u ON u.id = s.user_id ORDER BY s.activated_at",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val result = ArrayList<Server>()
                    while (rs.next()) result.add(map(rs))
                    return result
                }
            }
        }
    }

    fun findById(id: String): Optional<Server> {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT s.id, s.name, s.user_id, u.email AS owner_email, s.activated_at, s.last_seen_at " +
                    "FROM servers s JOIN users u ON u.id = s.user_id WHERE s.id = ?",
            ).use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) Optional.of(map(rs)) else Optional.empty()
                }
            }
        }
    }

    fun findByIdAndUser(serverId: String, userId: String): Optional<Server> {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT s.id, s.name, s.user_id, u.email AS owner_email, s.activated_at, s.last_seen_at " +
                    "FROM servers s JOIN users u ON u.id = s.user_id WHERE s.id = ? AND s.user_id = ?",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.setString(2, userId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) Optional.of(map(rs)) else Optional.empty()
                }
            }
        }
    }

    fun register(conn: Connection, userId: String, name: String): Server {
        val id = UUID.randomUUID().toString()
        val now = Instant.now()
        conn.prepareStatement(
            "INSERT INTO servers (id, name, user_id, activated_at, last_seen_at) VALUES (?, ?, ?, ?, ?)",
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, name)
            ps.setString(3, userId)
            ps.setTimestamp(4, Timestamp.from(now))
            ps.setTimestamp(5, Timestamp.from(now))
            ps.executeUpdate()
        }
        return Server(id, name, userId, null, now.toString(), now.toString())
    }

    fun registerWithId(conn: Connection, serverId: String, userId: String, name: String): Server {
        val now = Instant.now()
        conn.prepareStatement(
            "INSERT INTO servers (id, name, user_id, activated_at, last_seen_at) VALUES (?, ?, ?, ?, ?)",
        ).use { ps ->
            ps.setString(1, serverId)
            ps.setString(2, name)
            ps.setString(3, userId)
            ps.setTimestamp(4, Timestamp.from(now))
            ps.setTimestamp(5, Timestamp.from(now))
            ps.executeUpdate()
        }
        return Server(serverId, name, userId, null, now.toString(), now.toString())
    }

    fun updateLastSeen(serverId: String) {
        db.getConnection().use { conn ->
            conn.prepareStatement("UPDATE servers SET last_seen_at = ? WHERE id = ?").use { ps ->
                ps.setTimestamp(1, Timestamp.from(Instant.now()))
                ps.setString(2, serverId)
                ps.executeUpdate()
            }
        }
    }

    private fun map(rs: java.sql.ResultSet): Server = Server(
        rs.getString("id"),
        rs.getString("name"),
        rs.getString("user_id"),
        rs.getString("owner_email"),
        rs.getTimestamp("activated_at").toInstant().toString(),
        rs.getTimestamp("last_seen_at").toInstant().toString(),
    )
}
