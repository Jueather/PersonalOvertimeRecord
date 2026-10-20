package com.example.overtimeserver.db

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * SQLite 单连接 + 互斥锁：个人/小团队量级下串行访问足够，
 * 且天然规避多连接并发写锁问题。所有读写都必须经 withConnection / transaction。
 */
class Database(dbPath: String) {

    private val lock = ReentrantLock()
    private val conn: Connection

    init {
        File(dbPath).absoluteFile.parentFile?.mkdirs()
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite:${File(dbPath).absolutePath}")
        conn.autoCommit = true
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute("PRAGMA busy_timeout=5000")
            st.execute("PRAGMA foreign_keys=ON")
        }
    }

    fun <T> withConnection(block: (Connection) -> T): T = lock.withLock {
        block(conn)
    }

    /**
     * 事务。xerial 驱动下 setAutoCommit(false) 与显式 BEGIN 并用会冲突，
     * 因此用语句级 BEGIN IMMEDIATE / COMMIT / ROLLBACK。
     */
    fun <T> transaction(block: (Connection) -> T): T = lock.withLock {
        conn.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        try {
            val result = block(conn)
            conn.createStatement().use { it.execute("COMMIT") }
            result
        } catch (t: Throwable) {
            try {
                conn.createStatement().use { it.execute("ROLLBACK") }
            } catch (rollbackError: Throwable) {
                t.addSuppressed(rollbackError)
            }
            throw t
        }
    }

    fun close() {
        lock.withLock { conn.close() }
    }
}
