package com.example.personalovertimerecord.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO - 加班记录数据访问对象
 * 说明：isDeleted=1 的记录为软删除记录（仅用于跨设备同步删除操作），
 * 常规查询一律过滤，避免在 UI 中展示。
 */
@Dao
interface AttendanceDao {

    /**
     * 获取所有未删除记录，按日期降序
     */
    @Query("SELECT * FROM attendance_records WHERE isDeleted = 0 ORDER BY date DESC")
    fun getAllRecords(): Flow<List<AttendanceEntity>>

    /**
     * 获取所有未删除记录列表（非Flow）
     */
    @Query("SELECT * FROM attendance_records WHERE isDeleted = 0 ORDER BY date DESC")
    suspend fun getAllRecordsSync(): List<AttendanceEntity>

    /**
     * 获取全部记录（含软删除记录），用于同步全量上传
     */
    @Query("SELECT * FROM attendance_records ORDER BY date DESC")
    suspend fun getAllRecordsIncludingDeletedSync(): List<AttendanceEntity>

    /**
     * 获取所有软删除记录，用于同步删除操作
     */
    @Query("SELECT * FROM attendance_records WHERE isDeleted = 1 ORDER BY date DESC")
    suspend fun getAllDeletedRecordsSync(): List<AttendanceEntity>

    /**
     * 根据日期获取未删除记录
     */
    @Query("SELECT * FROM attendance_records WHERE date = :date AND isDeleted = 0 LIMIT 1")
    suspend fun getRecordByDate(date: String): AttendanceEntity?

    /**
     * 根据日期获取记录（含软删除记录）
     * 日历同步改期/导入时用于探测墓碑，避免 REPLACE 覆盖掉删除标记
     */
    @Query("SELECT * FROM attendance_records WHERE date = :date LIMIT 1")
    suspend fun getAnyRecordByDate(date: String): AttendanceEntity?

    /**
     * 根据ID获取未删除记录
     */
    @Query("SELECT * FROM attendance_records WHERE id = :id AND isDeleted = 0 LIMIT 1")
    suspend fun getRecordById(id: Long): AttendanceEntity?

    /**
     * 插入或更新记录
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(record: AttendanceEntity): Long

    /**
     * 批量插入记录
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<AttendanceEntity>)

    /**
     * 插入单条记录
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: AttendanceEntity): Long

    /**
     * 软删除：标记记录为已删除（tombstone），用于跨设备同步删除
     */
    @Query("UPDATE attendance_records SET isDeleted = 1, modifiedAt = :modifiedAt WHERE id = :id")
    suspend fun markDeleted(id: Long, modifiedAt: Long)

    /**
     * 物理删除记录
     */
    @Delete
    suspend fun delete(record: AttendanceEntity)

    /**
     * 根据ID物理删除记录
     */
    @Query("DELETE FROM attendance_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * 删除所有记录
     */
    @Query("DELETE FROM attendance_records")
    suspend fun deleteAll()

    /**
     * 获取未删除记录数量
     */
    @Query("SELECT COUNT(*) FROM attendance_records WHERE isDeleted = 0")
    suspend fun getCount(): Int
}
