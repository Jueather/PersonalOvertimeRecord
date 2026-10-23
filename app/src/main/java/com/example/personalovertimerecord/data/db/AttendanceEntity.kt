package com.example.personalovertimerecord.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.personalovertimerecord.data.OvertimeRecord

/**
 * Room数据库实体 - 加班记录表
 * date 加唯一索引：按日期查询/排序（列表、日历、按月统计）更高效，
 * 同时在数据库层面保证同一天只有一条记录，防止重复打卡/重复登记
 */
@Entity(
    tableName = "attendance_records",
    indices = [Index(value = ["date"], unique = true)]
)
data class AttendanceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val date: String,
    val checkInTime: String? = null,
    val checkOutTime: String? = null,
    val checkInTimestamp: Long? = null,
    val checkOutTimestamp: Long? = null,
    val note: String? = null,
    val manualOvertimeHours: Double = -1.0,
    val manualExtraHours: Double = -1.0,
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long? = null,  // 用于支持增量同步
    // 请假相关字段
    val isLeave: Boolean = false,
    val leaveType: String? = null,
    val leaveHours: Double = 0.0,
    // 自定义工资倍率：< 0 表示按设置默认倍率计算（与 manualOvertimeHours 的 -1 哨兵风格一致）
    val customRate: Double = -1.0,
    // 班次标记："DAY"=白班（默认）/ "NIGHT"=夜班；仅作记录与统计展示，不影响工资计算
    val shiftType: String = "DAY",
    // 软删除标记：用于支持跨设备删除同步（tombstone）
    // true 表示该记录已被删除，正常查询会过滤掉，仅用于同步删除操作
    val isDeleted: Boolean = false
) {
    /**
     * 转换为记录模型（OvertimeRecord）
     */
    fun toRecord(): OvertimeRecord {
        return OvertimeRecord(
            id = id,
            date = date,
            checkInTime = checkInTime,
            checkOutTime = checkOutTime,
            checkInTimestamp = checkInTimestamp,
            checkOutTimestamp = checkOutTimestamp,
            note = note,
            overtimeHours = manualOvertimeHours,
            extraHours = manualExtraHours,
            createdAt = createdAt,
            isLeave = isLeave,
            leaveType = leaveType,
            leaveHours = leaveHours,
            customRate = customRate,
            shiftType = shiftType
        )
    }

    companion object {
        /**
         * 从记录模型（OvertimeRecord）创建实体
         */
        fun fromRecord(record: OvertimeRecord): AttendanceEntity {
            val now = System.currentTimeMillis()
            return AttendanceEntity(
                id = record.id,
                date = record.date,
                checkInTime = record.checkInTime,
                checkOutTime = record.checkOutTime,
                checkInTimestamp = record.checkInTimestamp,
                checkOutTimestamp = record.checkOutTimestamp,
                note = record.note,
                manualOvertimeHours = record.overtimeHours,
                manualExtraHours = record.extraHours,
                modifiedAt = now,
                isLeave = record.isLeave,
                leaveType = record.leaveType,
                leaveHours = record.leaveHours,
                customRate = record.customRate,
                shiftType = record.shiftType
            )
        }
    }
}
