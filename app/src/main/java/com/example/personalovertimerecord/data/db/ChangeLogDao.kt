package com.example.personalovertimerecord.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChangeLogDao {

    @Insert
    suspend fun insert(log: ChangeLogEntity): Long

    @Query("SELECT * FROM change_logs ORDER BY id DESC")
    fun getAllFlow(): Flow<List<ChangeLogEntity>>

    /** 只保留最近 1000 条，防止日志无限增长 */
    @Query("DELETE FROM change_logs WHERE id NOT IN (SELECT id FROM change_logs ORDER BY id DESC LIMIT 1000)")
    suspend fun trimToLatest1000()

    @Query("SELECT COUNT(*) FROM change_logs")
    suspend fun getCount(): Int
}
