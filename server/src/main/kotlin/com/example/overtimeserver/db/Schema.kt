package com.example.overtimeserver.db

import java.sql.Connection

object Schema {

    fun createTables(conn: Connection) {
        conn.createStatement().use { st ->
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS users (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  username TEXT NOT NULL UNIQUE COLLATE NOCASE,
                  password_hash TEXT NOT NULL,
                  display_name TEXT NOT NULL DEFAULT '',
                  role TEXT NOT NULL DEFAULT 'member' CHECK (role IN ('admin','member')),
                  created_at INTEGER NOT NULL,
                  is_active INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent()
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS records (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                  date TEXT NOT NULL,
                  check_in_time TEXT, check_out_time TEXT,
                  check_in_timestamp INTEGER, check_out_timestamp INTEGER,
                  note TEXT,
                  manual_overtime_hours REAL NOT NULL DEFAULT -1.0,
                  manual_extra_hours REAL NOT NULL DEFAULT -1.0,
                  overtime_hours REAL,
                  created_at INTEGER NOT NULL, modified_at INTEGER NOT NULL,
                  is_leave INTEGER NOT NULL DEFAULT 0,
                  leave_type TEXT, leave_hours REAL NOT NULL DEFAULT 0.0,
                  custom_rate REAL,
                  shift_type TEXT,
                  is_deleted INTEGER NOT NULL DEFAULT 0,
                  last_modified_by TEXT NOT NULL DEFAULT '',
                  synced_at INTEGER NOT NULL,
                  UNIQUE (user_id, date)
                )
                """.trimIndent()
            )
            st.execute("CREATE INDEX IF NOT EXISTS idx_records_user_deleted ON records(user_id, is_deleted)")
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS trial_codes (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  code TEXT NOT NULL UNIQUE,
                  trial_days INTEGER NOT NULL,
                  max_uses INTEGER NOT NULL DEFAULT 1,
                  used_count INTEGER NOT NULL DEFAULT 0,
                  bound_device_id TEXT,
                  activated_at INTEGER,
                  expires_at INTEGER,
                  created_at INTEGER NOT NULL,
                  created_by TEXT NOT NULL,
                  is_revoked INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            st.execute("CREATE INDEX IF NOT EXISTS idx_trial_codes_code ON trial_codes(code)")
            st.execute("CREATE INDEX IF NOT EXISTS idx_trial_codes_device ON trial_codes(bound_device_id)")
        }
        // 老库升级：CREATE TABLE IF NOT EXISTS 不会为已存在的表补列，
        // SQLite 的 ADD COLUMN 也没有 IF NOT EXISTS，需先用 PRAGMA table_info 判存在性
        addColumnIfMissing(conn, "records", "shift_type", "shift_type TEXT")
    }

    private fun addColumnIfMissing(conn: Connection, table: String, column: String, ddl: String) {
        val exists = conn.prepareStatement("PRAGMA table_info($table)").use { ps ->
            ps.executeQuery().use { rs ->
                generateSequence { if (rs.next()) rs.getString("name") else null }.any { it == column }
            }
        }
        if (!exists) {
            conn.createStatement().use { it.execute("ALTER TABLE $table ADD COLUMN $ddl") }
        }
    }
}
