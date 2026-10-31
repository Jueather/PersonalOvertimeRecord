package com.example.personalovertimerecord.repository

import com.example.personalovertimerecord.OvertimeApplication
import com.example.personalovertimerecord.R
import com.example.personalovertimerecord.data.LeaveType
import com.example.personalovertimerecord.data.OvertimeRecord
import com.example.personalovertimerecord.data.ShiftType
import com.example.personalovertimerecord.data.db.AppDatabase
import com.example.personalovertimerecord.data.db.AttendanceEntity
import com.example.personalovertimerecord.data.db.ChangeLogEntity
import com.example.personalovertimerecord.utils.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room数据库实现的考勤记录仓储
 * 使用Room数据库和Flow进行响应式数据访问
 *
 * 修改日志埋点：本类覆盖全部「用户手动写库」路径（界面/小部件/快捷通知），
 * 因此增/改/删/清空在此统一记录；同步回写（WebDAV/团队/日历）直走 DAO，
 * 不属于本机用户修改，不记入日志。
 */
class RoomAttendanceRepository(
    private val database: AppDatabase
) : AttendanceRepository {

    private val dao = database.attendanceDao()
    private val changeLogDao = database.changeLogDao()

    /**
     * 获取所有记录（Flow版本）
     * 支持响应式更新，当数据库变化时会自动通知观察者
     */
    override fun getAllAttendanceFlow(): Flow<List<OvertimeRecord>> {
        return dao.getAllRecords().map { entities ->
            entities.map { it.toRecord() }
        }
    }

    /**
     * 获取所有记录（同步版本）
     */
    override suspend fun getAllAttendance(): List<OvertimeRecord> {
        return dao.getAllRecordsSync().map { it.toRecord() }
    }

    /**
     * 根据日期获取记录
     */
    override suspend fun getAttendanceByDate(date: String): OvertimeRecord? {
        return dao.getRecordByDate(date)?.toRecord()
    }

    /**
     * 根据ID获取记录
     */
    override suspend fun getAttendanceById(id: Long): OvertimeRecord? {
        return dao.getRecordById(id)?.toRecord()
    }

    /**
     * 插入或更新记录
     * 如果记录存在则更新，不存在则插入
     */
    override suspend fun saveAttendance(attendance: OvertimeRecord): Long {
        val entity = AttendanceEntity.fromRecord(attendance)
        val log = buildUpsertLog(entity)
        val id = dao.insertOrUpdate(entity)
        log?.let { insertLog(it) }
        return id
    }

    /**
     * 插入新记录
     * 使用ID=0来确保插入新记录
     */
    override suspend fun insertAttendance(attendance: OvertimeRecord): Long {
        val newAttendance = if (attendance.id == 0L) {
            attendance
        } else {
            attendance.copy(id = 0L) // 强制ID为0，确保插入新记录
        }
        val entity = AttendanceEntity.fromRecord(newAttendance)
        val log = buildUpsertLog(entity)
        val id = dao.insertOrUpdate(entity)
        log?.let { insertLog(it) }
        return id
    }

    /**
     * 更新记录
     */
    override suspend fun updateAttendance(attendance: OvertimeRecord) {
        val entity = AttendanceEntity.fromRecord(attendance)
        val log = buildUpsertLog(entity)
        dao.insertOrUpdate(entity)
        log?.let { insertLog(it) }
    }

    /**
     * 删除记录（软删除，标记 isDeleted=1 便于跨设备同步删除操作）
     */
    override suspend fun deleteAttendance(id: Long) {
        val before = dao.getRecordById(id)
        dao.markDeleted(id, System.currentTimeMillis())
        if (before != null) {
            insertLog(ChangeLogEntity(
                recordDate = before.date,
                changeType = ChangeLogEntity.TYPE_DELETE,
                summary = describeEntity(before)
            ))
        }
    }

    /**
     * 删除所有记录
     */
    override suspend fun deleteAllAttendance() {
        val count = dao.getCount()
        dao.deleteAll()
        insertLog(ChangeLogEntity(
            recordDate = "",
            changeType = ChangeLogEntity.TYPE_CLEAR,
            summary = str(R.string.changelog_repo_clear_all, count)
        ))
    }

    /**
     * 获取记录数量
     */
    override suspend fun getCount(): Int {
        return dao.getCount()
    }

    // ================== 修改日志 ==================

    /**
     * 为保存/插入/更新构建日志：按日期取改前记录（含墓碑）判断 INSERT 或 UPDATE；
     * UPDATE 且字段无实际变化时返回 null（不记噪音日志）。
     * 须在写库之前调用（依赖改前快照）。
     */
    private suspend fun buildUpsertLog(entity: AttendanceEntity): ChangeLogEntity? {
        val before = dao.getAnyRecordByDate(entity.date)
        return if (before == null || before.isDeleted) {
            ChangeLogEntity(
                recordDate = entity.date,
                changeType = ChangeLogEntity.TYPE_INSERT,
                summary = describeEntity(entity)
            )
        } else {
            val diffs = diffEntities(before, entity)
            if (diffs.isEmpty()) return null
            ChangeLogEntity(
                recordDate = entity.date,
                changeType = ChangeLogEntity.TYPE_UPDATE,
                summary = diffs.joinToString(str(R.string.changelog_repo_diff_sep))
            )
        }
    }

    /** 写日志失败不影响主流程（日志是辅助功能，不能反过来阻断用户保存） */
    private suspend fun insertLog(log: ChangeLogEntity) {
        try {
            changeLogDao.insert(log)
            changeLogDao.trimToLatest1000()
        } catch (e: Exception) {
            AppLogger.e("修改日志写入失败", e)
        }
    }

    /** 记录内容的人类可读快照（新增/删除时展示） */
    private fun describeEntity(e: AttendanceEntity): String {
        val parts = mutableListOf<String>()
        if (e.isLeave) {
            parts.add(str(R.string.changelog_repo_leave_prefix, leaveDesc(e)))
        } else {
            if (e.manualOvertimeHours >= 0) parts.add(str(R.string.changelog_repo_overtime_hours, formatNumber(e.manualOvertimeHours)))
            if (e.manualExtraHours >= 0) parts.add(str(R.string.changelog_repo_extra_hours, formatNumber(e.manualExtraHours)))
        }
        if (!e.checkInTime.isNullOrBlank()) parts.add(str(R.string.changelog_repo_check_in, e.checkInTime))
        if (!e.checkOutTime.isNullOrBlank()) parts.add(str(R.string.changelog_repo_check_out, e.checkOutTime))
        if (e.customRate >= 0) parts.add(str(R.string.changelog_repo_rate, formatNumber(e.customRate)))
        if (e.shiftType == ShiftType.NIGHT.name) parts.add(str(R.string.changelog_repo_night_shift))
        if (!e.note.isNullOrBlank()) parts.add(str(R.string.changelog_repo_note, e.note))
        return if (parts.isEmpty()) str(R.string.changelog_repo_empty) else parts.joinToString(str(R.string.changelog_repo_join_sep))
    }

    /** 逐字段 diff：输出「字段：旧值 → 新值」，仅列有变化的字段 */
    private fun diffEntities(b: AttendanceEntity, a: AttendanceEntity): List<String> {
        val diffs = mutableListOf<String>()
        fun diff(label: String, old: String, new: String) {
            if (old != new) diffs.add(str(R.string.changelog_repo_diff, label, old, new))
        }
        diff(str(R.string.changelog_repo_field_leave), if (b.isLeave) leaveDesc(b) else str(R.string.changelog_repo_none_leave), if (a.isLeave) leaveDesc(a) else str(R.string.changelog_repo_none_leave))
        diff(str(R.string.changelog_repo_field_overtime), hoursOrNone(b.manualOvertimeHours), hoursOrNone(a.manualOvertimeHours))
        diff(str(R.string.changelog_repo_field_extra), hoursOrNone(b.manualExtraHours), hoursOrNone(a.manualExtraHours))
        diff(str(R.string.changelog_repo_field_check_in), b.checkInTime ?: str(R.string.changelog_repo_unfilled), a.checkInTime ?: str(R.string.changelog_repo_unfilled))
        diff(str(R.string.changelog_repo_field_check_out), b.checkOutTime ?: str(R.string.changelog_repo_unfilled), a.checkOutTime ?: str(R.string.changelog_repo_unfilled))
        diff(str(R.string.changelog_repo_field_rate), rateOrDefault(b.customRate), rateOrDefault(a.customRate))
        diff(str(R.string.changelog_repo_field_shift), shiftDesc(b.shiftType), shiftDesc(a.shiftType))
        val none = str(R.string.changelog_repo_none)
        fun noteValue(v: String?): String = v?.takeIf { it.isNotBlank() }?.let { str(R.string.changelog_repo_note_value, it) } ?: none
        diff(str(R.string.changelog_repo_field_note), noteValue(b.note), noteValue(a.note))
        return diffs
    }

    private fun leaveDesc(e: AttendanceEntity): String {
        val typeName = LeaveType.fromString(e.leaveType)?.displayName ?: e.leaveType ?: str(R.string.changelog_repo_leave_fallback)
        return str(R.string.changelog_repo_leave_value, typeName, formatNumber(e.leaveHours))
    }

    private fun shiftDesc(value: String): String =
        ShiftType.fromStringOrDefault(value).displayName

    private fun hoursOrNone(v: Double): String =
        if (v < 0) str(R.string.changelog_repo_unfilled) else str(R.string.changelog_repo_hours_value, formatNumber(v))

    private fun rateOrDefault(v: Double): String =
        if (v < 0) str(R.string.changelog_repo_default) else str(R.string.changelog_repo_rate_value, formatNumber(v))

    private fun str(id: Int, vararg args: Any): String =
        OvertimeApplication.getInstance().getString(id, *args)

    /** 数字格式化：整数值去掉小数点（2.0 → "2"，2.5 → "2.5"） */
    private fun formatNumber(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}
