package com.example.overtimeserver.db

import java.sql.Connection

data class UserRow(
    val id: Long,
    val username: String,
    val passwordHash: String,
    val displayName: String,
    val role: String,
    val isActive: Boolean,
    val createdAt: Long,
)

class UserDao(private val db: Database) {

    fun count(): Int = db.withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM users").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    fun findById(id: Long): UserRow? = db.withConnection { findById(it, id) }

    fun findByUsername(username: String): UserRow? = db.withConnection { findByUsername(it, username) }

    /** 用户名唯一（NOCASE）：已存在返回 null */
    fun create(username: String, passwordHash: String, displayName: String, role: String): UserRow? =
        db.withConnection { conn ->
            if (findByUsername(conn, username) != null) return@withConnection null
            conn.prepareStatement(
                "INSERT INTO users(username, password_hash, display_name, role, created_at, is_active) VALUES(?,?,?,?,?,1)"
            ).use { ps ->
                ps.setString(1, username.trim())
                ps.setString(2, passwordHash)
                ps.setString(3, displayName.trim())
                ps.setString(4, role)
                ps.setLong(5, System.currentTimeMillis())
                ps.executeUpdate()
            }
            findByUsername(conn, username)
        }

    fun listAll(): List<UserRow> = db.withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT id, username, password_hash, display_name, role, is_active, created_at FROM users ORDER BY id"
            ).use { rs ->
                buildList { while (rs.next()) add(mapUser(rs)) }
            }
        }
    }

    fun updatePassword(id: Long, passwordHash: String): Boolean = db.withConnection { conn ->
        conn.prepareStatement("UPDATE users SET password_hash = ? WHERE id = ?").use { ps ->
            ps.setString(1, passwordHash)
            ps.setLong(2, id)
            ps.executeUpdate() > 0
        }
    }

    fun setActive(id: Long, active: Boolean): Boolean = db.withConnection { conn ->
        conn.prepareStatement("UPDATE users SET is_active = ? WHERE id = ?").use { ps ->
            ps.setInt(1, if (active) 1 else 0)
            ps.setLong(2, id)
            ps.executeUpdate() > 0
        }
    }

    private fun findById(conn: Connection, id: Long): UserRow? {
        conn.prepareStatement(
            "SELECT id, username, password_hash, display_name, role, is_active, created_at FROM users WHERE id = ?"
        ).use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs ->
                return if (rs.next()) mapUser(rs) else null
            }
        }
    }

    private fun findByUsername(conn: Connection, username: String): UserRow? {
        conn.prepareStatement(
            "SELECT id, username, password_hash, display_name, role, is_active, created_at FROM users WHERE username = ?"
        ).use { ps ->
            ps.setString(1, username.trim())
            ps.executeQuery().use { rs ->
                return if (rs.next()) mapUser(rs) else null
            }
        }
    }

    private fun mapUser(rs: java.sql.ResultSet): UserRow = UserRow(
        id = rs.getLong("id"),
        username = rs.getString("username"),
        passwordHash = rs.getString("password_hash"),
        displayName = rs.getString("display_name"),
        role = rs.getString("role"),
        isActive = rs.getInt("is_active") == 1,
        createdAt = rs.getLong("created_at"),
    )
}
