package com.example.personalovertimerecord.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 记录与系统日历事件的映射表（仅本机使用，不参与备份与云端同步）。
 *
 * - eventId 为主键：日历事件的唯一标识
 * - recordDate 加唯一索引：一条考勤记录（按日期唯一）最多对应一个日历事件
 * - syncedAt 记录上次同步时间，用于判断本地记录/日历事件哪边更新更晚
 */
@Entity(
    tableName = "calendar_links",
    indices = [Index(value = ["recordDate"], unique = true)]
)
data class CalendarLinkEntity(
    @PrimaryKey val eventId: Long,
    val calendarId: Long,
    val recordDate: String,
    val syncedAt: Long
)
