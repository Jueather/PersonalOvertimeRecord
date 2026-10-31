package com.example.personalovertimerecord.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 修改日志实体：记录本机用户对考勤记录的手动操作历史。
 *
 * 仅记录用户手动路径（Repository 层增/改/删/清空）；
 * WebDAV/团队/日历同步的回写不记入——那是云端状态同步，不是本机用户的修改。
 *
 * summary 为写入时生成的人类可读摘要（UPDATE 含前后值 diff），UI 直接展示。
 */
@Entity(tableName = "change_logs")
data class ChangeLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /** 操作发生时间（epoch millis） */
    val timestamp: Long = System.currentTimeMillis(),
    /** 被操作记录的日期；CLEAR 类型为空串 */
    val recordDate: String,
    /** INSERT / UPDATE / DELETE / CLEAR */
    val changeType: String,
    /** 摘要：INSERT/DELETE 为字段快照，UPDATE 为「字段：旧值 → 新值」列表 */
    val summary: String
) {
    companion object {
        const val TYPE_INSERT = "INSERT"
        const val TYPE_UPDATE = "UPDATE"
        const val TYPE_DELETE = "DELETE"
        const val TYPE_CLEAR = "CLEAR"
    }
}
