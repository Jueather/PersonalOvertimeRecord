package com.example.personalovertimerecord.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 日历映射表数据访问对象。
 * 映射仅本机使用，记录被同步/恢复覆盖时不影响日历归属判断（按日期重新匹配）。
 */
@Dao
interface CalendarLinkDao {

    @Query("SELECT * FROM calendar_links")
    suspend fun getAll(): List<CalendarLinkEntity>

    @Query("SELECT * FROM calendar_links WHERE eventId = :eventId LIMIT 1")
    suspend fun findByEventId(eventId: Long): CalendarLinkEntity?

    @Query("SELECT * FROM calendar_links WHERE recordDate = :date LIMIT 1")
    suspend fun findByDate(date: String): CalendarLinkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(link: CalendarLinkEntity)

    @Query("DELETE FROM calendar_links WHERE eventId = :eventId")
    suspend fun deleteByEventId(eventId: Long)

    @Query("DELETE FROM calendar_links WHERE recordDate = :date")
    suspend fun deleteByDate(date: String)

    @Query("DELETE FROM calendar_links")
    suspend fun deleteAll()
}
