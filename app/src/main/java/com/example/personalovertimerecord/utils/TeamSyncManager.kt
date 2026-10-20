package com.example.personalovertimerecord.utils

import android.content.Context
import com.example.personalovertimerecord.data.OvertimeSettings
import com.example.personalovertimerecord.data.SettingsManager
import com.example.personalovertimerecord.data.db.AppDatabase
import com.example.personalovertimerecord.data.db.AttendanceDao
import com.example.personalovertimerecord.data.db.AttendanceEntity
import com.example.personalovertimerecord.data.model.TeamApiException
import com.example.personalovertimerecord.data.model.TeamRecordDto
import com.example.personalovertimerecord.data.model.TeamSyncRequest
import com.example.personalovertimerecord.data.model.TeamSyncResponse
import com.example.personalovertimerecord.data.OvertimeRecord
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 团队同步管理器（方案 B：多用户小团队工具）。
 *
 * 流程：本地含墓碑全量读取 → OvertimeCalculator 补算 overtimeHours（仅上传不落库）
 * → POST /api/sync → 响应的服务端全量与本地 mergeAttendanceBackups(NEWER_WINS)
 * → 差异逐条 insertOrUpdate 落库（保留本地自增 id）。
 *
 * 与 WebDAV 同步（SyncManager）通过 [SyncGate] 互斥：二者写同一个 Room 库。
 */
class TeamSyncManager(
    private val context: Context,
    private val settingsManager: SettingsManager,
    database: AppDatabase
) {

    companion object {
        private const val TAG = "TeamSyncManager"
    }

    private val attendanceDao: AttendanceDao = database.attendanceDao()
    private val gson = Gson()

    enum class TeamSyncResult { SUCCESS, NO_AUTH, NO_NETWORK, FAILED }

    data class TeamSyncReport(
        val result: TeamSyncResult,
        val localRecordCount: Int = 0,
        val cloudRecordCount: Int = 0,
        val changedCount: Int = 0,
        val message: String? = null
    )

    suspend fun performSync(): TeamSyncReport = withContext(Dispatchers.IO) {
        SyncGate.lock {
            val auth = settingsManager.getTeamAuth()
                ?: return@lock TeamSyncReport(TeamSyncResult.NO_AUTH)
            if (!NetworkUtils.isNetworkAvailable(context)) {
                return@lock TeamSyncReport(TeamSyncResult.NO_NETWORK)
            }

            try {
                val client = TeamApiClient(auth.serverUrl, auth.token)

                // 本地全量（含软删除墓碑，保证删除操作能同步）
                val localEntities = attendanceDao.getAllRecordsIncludingDeletedSync()
                val localBackups = localEntities.map { it.toBackup() }

                // 上传记录：镜像备份字段 + 补算 overtimeHours（服务端聚合用，本地不落库）
                val settings = settingsManager.getSettings()
                val uploads = localEntities.map { it.toTeamDto(settings) }

                AppLogger.d("团队同步开始：本地 ${uploads.size} 条，device=${BackupData.currentDeviceName()}")
                val response = client.sync(
                    TeamSyncRequest(records = uploads, deviceName = BackupData.currentDeviceName())
                )

                val changedCount = applyServerState(response)
                AppLogger.d("团队同步完成：云端 ${response.records.size} 条，本地落库变更 $changedCount 条")

                TeamSyncReport(
                    result = TeamSyncResult.SUCCESS,
                    localRecordCount = localEntities.size,
                    cloudRecordCount = response.records.size,
                    changedCount = changedCount
                )
            } catch (e: TeamApiException) {
                AppLogger.e("团队同步失败（${e.kind}）", e)
                TeamSyncReport(TeamSyncResult.FAILED, message = TeamErrors.message(e))
            } catch (e: Exception) {
                AppLogger.e("团队同步异常", e)
                TeamSyncReport(TeamSyncResult.FAILED, message = e.message ?: "未知错误")
            }
        }
    }

    /**
     * 服务端全量 → NEWER_WINS 合并 → 差异落库。
     * 返回落库变更条数。
     */
    private suspend fun applyServerState(response: TeamSyncResponse): Int {
        // 合并前必须重新读取本地全量，不能复用 performSync 上传时的快照：
        // 两次读取之间隔着一次网络请求，期间 UI（添加/编辑对话框）可并发写库（SyncGate 不约束 UI）。
        // 用旧快照合并会导致：a) 请求期间的新编辑因 modifiedAt 偏旧被云端旧版本覆盖（数据丢失）；
        // b) 请求期间新建的日期在 localByDate 查不到 id，走 toEntity(0L) 新插入造成同日期重复行。
        val localEntities = attendanceDao.getAllRecordsIncludingDeletedSync()
        val localBackups = localEntities.map { it.toBackup() }
        val cloudBackups = response.records.map { it.toBackup() }

        // 与 WebDAV 同步同一套纯合并算法（utils/RecordMerge.kt）：date 为键、NEWER_WINS、墓碑参与
        val merged = mergeAttendanceBackups(
            local = localBackups,
            cloud = cloudBackups,
            strategy = ConflictStrategy.NEWER_WINS
        )

        val localByDate = localEntities.associateBy { it.date }
        var changed = 0
        for (record in merged) {
            val candidate = record.toEntity(localByDate[record.date]?.id ?: 0L)
            if (!sameRecord(localByDate[record.date], candidate)) {
                attendanceDao.insertOrUpdate(candidate)
                changed++
            }
        }
        return changed
    }

    // ================== 模型转换 ==================

    /** 本地实体 → 备份模型（口径与 SyncManager.toBackup 一致：modifiedAt 缺失用 createdAt 兜底） */
    private fun AttendanceEntity.toBackup(): AttendanceEntityBackup {
        return AttendanceEntityBackup(
            date = this.date,
            checkInTime = this.checkInTime,
            checkOutTime = this.checkOutTime,
            checkInTimestamp = this.checkInTimestamp,
            checkOutTimestamp = this.checkOutTimestamp,
            note = this.note,
            manualOvertimeHours = this.manualOvertimeHours,
            manualExtraHours = this.manualExtraHours,
            createdAt = this.createdAt,
            modifiedAt = this.modifiedAt ?: this.createdAt,
            isLeave = this.isLeave,
            leaveType = this.leaveType,
            leaveHours = this.leaveHours,
            customRate = if (this.customRate >= 0) this.customRate else null,
            isDeleted = this.isDeleted
        )
    }

    /** 服务端 DTO → 备份模型（modifiedAt 缺失用 createdAt 兜底，供 NEWER_WINS 比较与落库） */
    private fun TeamRecordDto.toBackup(): AttendanceEntityBackup {
        return AttendanceEntityBackup(
            date = this.date,
            checkInTime = this.checkInTime,
            checkOutTime = this.checkOutTime,
            checkInTimestamp = this.checkInTimestamp,
            checkOutTimestamp = this.checkOutTimestamp,
            note = this.note,
            manualOvertimeHours = this.manualOvertimeHours,
            manualExtraHours = this.manualExtraHours,
            createdAt = this.createdAt,
            modifiedAt = if (this.modifiedAt > 0) this.modifiedAt else this.createdAt,
            isLeave = this.isLeave,
            leaveType = this.leaveType,
            leaveHours = this.leaveHours,
            customRate = this.customRate,
            isDeleted = this.isDeleted,
            lastModifiedBy = this.lastModifiedBy
        )
    }

    /** 本地实体 → 上传 DTO：备份镜像 + overtimeHours（算好仅上传）+ 设备名 */
    private fun AttendanceEntity.toTeamDto(settings: OvertimeSettings): TeamRecordDto {
        val backup = toBackup()
        return TeamRecordDto(
            date = backup.date,
            checkInTime = backup.checkInTime,
            checkOutTime = backup.checkOutTime,
            checkInTimestamp = backup.checkInTimestamp,
            checkOutTimestamp = backup.checkOutTimestamp,
            note = backup.note,
            manualOvertimeHours = backup.manualOvertimeHours,
            manualExtraHours = backup.manualExtraHours,
            overtimeHours = computeOvertimeHours(backup, settings),
            createdAt = backup.createdAt,
            modifiedAt = backup.modifiedAt,
            isLeave = backup.isLeave,
            leaveType = backup.leaveType,
            leaveHours = backup.leaveHours,
            customRate = backup.customRate,
            isDeleted = backup.isDeleted,
            lastModifiedBy = BackupData.currentDeviceName()
        )
    }

    /**
     * 备份模型 → 本地实体（[localId] 保留本地自增 id；0 表示新记录）。
     * overtimeHours 为服务端聚合字段，本地 Room 不存，直接丢弃；lastModifiedBy 实体不存。
     */
    private fun AttendanceEntityBackup.toEntity(localId: Long): AttendanceEntity {
        return AttendanceEntity(
            id = localId,
            date = this.date,
            checkInTime = this.checkInTime,
            checkOutTime = this.checkOutTime,
            checkInTimestamp = this.checkInTimestamp,
            checkOutTimestamp = this.checkOutTimestamp,
            note = this.note,
            manualOvertimeHours = this.manualOvertimeHours,
            manualExtraHours = this.manualExtraHours,
            createdAt = this.createdAt,
            modifiedAt = this.modifiedAt,
            isLeave = this.isLeave,
            leaveType = this.leaveType,
            leaveHours = this.leaveHours,
            customRate = this.customRate ?: -1.0,
            isDeleted = this.isDeleted
        )
    }

    /** 计算加班总工时（加班 + 加点，按倍率前的小时数口径）；请假/墓碑记录为 0 */
    private fun computeOvertimeHours(backup: AttendanceEntityBackup, settings: OvertimeSettings): Double {
        if (backup.isDeleted || backup.isLeave) return 0.0
        val result = OvertimeCalculator.calculateOvertime(
            OvertimeRecord(
                date = backup.date,
                overtimeHours = backup.manualOvertimeHours,
                extraHours = backup.manualExtraHours,
                customRate = backup.customRate ?: -1.0
            ),
            settings
        )
        return result.normalOvertime + result.weekendOvertime + result.holidayOvertime + result.extraHours
    }

    /** 语义字段比较（忽略 id/modifiedAt/lastModifiedBy），避免合并结果无变化时重复写库 */
    private fun sameRecord(a: AttendanceEntity?, b: AttendanceEntity): Boolean {
        if (a == null) return false
        return a.date == b.date &&
            a.checkInTime == b.checkInTime &&
            a.checkOutTime == b.checkOutTime &&
            a.checkInTimestamp == b.checkInTimestamp &&
            a.checkOutTimestamp == b.checkOutTimestamp &&
            a.note == b.note &&
            a.manualOvertimeHours == b.manualOvertimeHours &&
            a.manualExtraHours == b.manualExtraHours &&
            a.createdAt == b.createdAt &&
            a.isLeave == b.isLeave &&
            a.leaveType == b.leaveType &&
            a.leaveHours == b.leaveHours &&
            a.customRate == b.customRate &&
            a.isDeleted == b.isDeleted
    }
}
