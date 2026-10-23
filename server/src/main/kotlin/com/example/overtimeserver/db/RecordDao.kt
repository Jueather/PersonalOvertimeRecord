package com.example.overtimeserver.db

import com.example.overtimeserver.api.RecordDto
import com.example.overtimeserver.api.SummaryEntry
import com.example.overtimeserver.sync.MergeService
import java.sql.Connection
import java.sql.ResultSet

class RecordDao(private val db: Database) {

    /** 该用户服务端全量（含墓碑），date 升序 */
    fun listByUser(userId: Long): List<RecordDto> = db.withConnection { conn ->
        listByUser(conn, userId)
    }

    fun countByUser(userId: Long): Int = db.withConnection { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM records WHERE user_id = ?").use { ps ->
            ps.setLong(1, userId)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    /**
     * 单事务内逐条 NEWER_WINS 合并落库，返回该用户合并后的服务端全量。
     * 调用方需先持有 per-user 锁，避免并发同步交错。
     */
    fun upsertBatch(userId: Long, incoming: List<RecordDto>): List<RecordDto> =
        db.transaction { conn ->
            val now = System.currentTimeMillis()
            for (record in incoming) {
                if (record.date.isBlank()) continue
                val existing = findByUserAndDate(conn, userId, record.date)
                val merged = MergeService.mergeRecord(existing, record)
                upsertRecord(conn, userId, merged, now)
            }
            listByUser(conn, userId)
        }

    /** 按人按月聚合（仅活跃记录），month 为 yyyyMM；date 兼容 yyyy-MM-dd / yyyyMMdd */
    fun monthlySummary(): List<SummaryEntry> = db.withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT u.id AS user_id, u.username, u.display_name,
                       substr(replace(r.date, '-', ''), 1, 6) AS ym,
                       COALESCE(SUM(CASE WHEN r.is_leave = 0 THEN COALESCE(r.overtime_hours, 0.0) ELSE 0.0 END), 0.0) AS overtime_hours,
                       COALESCE(SUM(CASE WHEN r.is_leave = 1 THEN r.leave_hours ELSE 0.0 END), 0.0) AS leave_hours,
                       COUNT(*) AS record_count,
                       COALESCE(SUM(CASE WHEN r.is_leave = 1 THEN 1 ELSE 0 END), 0) AS leave_count
                FROM records r JOIN users u ON u.id = r.user_id
                WHERE r.is_deleted = 0
                GROUP BY u.id, ym
                ORDER BY u.username, ym
                """.trimIndent()
            ).use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            SummaryEntry(
                                userId = rs.getLong("user_id"),
                                username = rs.getString("username"),
                                displayName = rs.getString("display_name"),
                                month = rs.getString("ym"),
                                overtimeHours = rs.getDouble("overtime_hours"),
                                leaveHours = rs.getDouble("leave_hours"),
                                recordCount = rs.getInt("record_count"),
                                leaveCount = rs.getInt("leave_count"),
                            )
                        )
                    }
                }
            }
        }
    }

    private fun listByUser(conn: Connection, userId: Long): List<RecordDto> =
        conn.prepareStatement(
            """
            SELECT date, check_in_time, check_out_time, check_in_timestamp, check_out_timestamp,
                   note, manual_overtime_hours, manual_extra_hours, overtime_hours,
                   created_at, modified_at, is_leave, leave_type, leave_hours, custom_rate,
                   shift_type, is_deleted, last_modified_by
            FROM records WHERE user_id = ? ORDER BY date
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, userId)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(mapRecord(rs)) }
            }
        }

    private fun findByUserAndDate(conn: Connection, userId: Long, date: String): RecordDto? {
        conn.prepareStatement(
            """
            SELECT date, check_in_time, check_out_time, check_in_timestamp, check_out_timestamp,
                   note, manual_overtime_hours, manual_extra_hours, overtime_hours,
                   created_at, modified_at, is_leave, leave_type, leave_hours, custom_rate,
                   shift_type, is_deleted, last_modified_by
            FROM records WHERE user_id = ? AND date = ?
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, userId)
            ps.setString(2, date)
            ps.executeQuery().use { rs ->
                return if (rs.next()) mapRecord(rs) else null
            }
        }
    }

    private fun upsertRecord(conn: Connection, userId: Long, r: RecordDto, syncedAt: Long) {
        conn.prepareStatement(
            """
            INSERT INTO records(
                user_id, date, check_in_time, check_out_time, check_in_timestamp, check_out_timestamp,
                note, manual_overtime_hours, manual_extra_hours, overtime_hours,
                created_at, modified_at, is_leave, leave_type, leave_hours, custom_rate,
                shift_type, is_deleted, last_modified_by, synced_at
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(user_id, date) DO UPDATE SET
                check_in_time=excluded.check_in_time,
                check_out_time=excluded.check_out_time,
                check_in_timestamp=excluded.check_in_timestamp,
                check_out_timestamp=excluded.check_out_timestamp,
                note=excluded.note,
                manual_overtime_hours=excluded.manual_overtime_hours,
                manual_extra_hours=excluded.manual_extra_hours,
                overtime_hours=excluded.overtime_hours,
                created_at=excluded.created_at,
                modified_at=excluded.modified_at,
                is_leave=excluded.is_leave,
                leave_type=excluded.leave_type,
                leave_hours=excluded.leave_hours,
                custom_rate=excluded.custom_rate,
                shift_type=excluded.shift_type,
                is_deleted=excluded.is_deleted,
                last_modified_by=excluded.last_modified_by,
                synced_at=excluded.synced_at
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, userId)
            ps.setString(2, r.date)
            ps.setString(3, r.checkInTime)
            ps.setString(4, r.checkOutTime)
            setNullableLong(ps, 5, r.checkInTimestamp)
            setNullableLong(ps, 6, r.checkOutTimestamp)
            ps.setString(7, r.note)
            ps.setDouble(8, r.manualOvertimeHours)
            ps.setDouble(9, r.manualExtraHours)
            setNullableDouble(ps, 10, r.overtimeHours)
            ps.setLong(11, r.createdAt)
            ps.setLong(12, r.modifiedAt)
            ps.setInt(13, if (r.isLeave) 1 else 0)
            ps.setString(14, r.leaveType)
            ps.setDouble(15, r.leaveHours)
            setNullableDouble(ps, 16, r.customRate)
            ps.setString(17, r.shiftType)
            ps.setInt(18, if (r.isDeleted) 1 else 0)
            ps.setString(19, r.lastModifiedBy)
            ps.setLong(20, syncedAt)
            ps.executeUpdate()
        }
    }

    private fun mapRecord(rs: ResultSet): RecordDto = RecordDto(
        date = rs.getString("date"),
        checkInTime = rs.optString("check_in_time"),
        checkOutTime = rs.optString("check_out_time"),
        checkInTimestamp = rs.optLong("check_in_timestamp"),
        checkOutTimestamp = rs.optLong("check_out_timestamp"),
        note = rs.optString("note"),
        manualOvertimeHours = rs.getDouble("manual_overtime_hours"),
        manualExtraHours = rs.getDouble("manual_extra_hours"),
        overtimeHours = rs.optDouble("overtime_hours"),
        createdAt = rs.getLong("created_at"),
        modifiedAt = rs.getLong("modified_at"),
        isLeave = rs.getInt("is_leave") == 1,
        leaveType = rs.optString("leave_type"),
        leaveHours = rs.getDouble("leave_hours"),
        customRate = rs.optDouble("custom_rate"),
        shiftType = rs.optString("shift_type"),
        isDeleted = rs.getInt("is_deleted") == 1,
        lastModifiedBy = rs.getString("last_modified_by") ?: "",
    )

    private fun setNullableLong(ps: java.sql.PreparedStatement, index: Int, value: Long?) {
        if (value == null) ps.setNull(index, java.sql.Types.INTEGER) else ps.setLong(index, value)
    }

    private fun setNullableDouble(ps: java.sql.PreparedStatement, index: Int, value: Double?) {
        if (value == null) ps.setNull(index, java.sql.Types.REAL) else ps.setDouble(index, value)
    }

    private fun ResultSet.optLong(col: String): Long? {
        val v = getLong(col)
        return if (wasNull()) null else v
    }

    private fun ResultSet.optDouble(col: String): Double? {
        val v = getDouble(col)
        return if (wasNull()) null else v
    }

    private fun ResultSet.optString(col: String): String? {
        val v = getString(col)
        return if (wasNull()) null else v
    }
}
