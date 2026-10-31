package com.example.personalovertimerecord.utils

import com.example.personalovertimerecord.OvertimeApplication
import com.example.personalovertimerecord.R

/**
 * 同步结果枚举
 */
enum class SyncResult {
    SUCCESS,
    NO_CONFIG,
    NO_NETWORK,
    CONNECTION_FAILED,
    UPLOAD_FAILED,
    DOWNLOAD_FAILED,
    RESTORE_FAILED,
    NO_CHANGES,
    CONFLICT,
    /** 云端数据已加密，但本机同步加密密码错误或未配置 */
    ENCRYPTION_MISMATCH
}

/**
 * 同步报告：在 [SyncResult] 之外携带本次同步的统计细节，便于日志与 UI 展示。
 *
 * 计数字段语义：
 * - localRecordCount：同步前本地记录数（含软删除墓碑）；
 * - cloudRecordCount：云端备份中的记录数；
 * - uploadedCount：本次上传到云端的记录数（全量上传时等于本地记录数）；
 * - downloadedCount：本次从云端下载并恢复的记录数；
 * - mergedCount：双向合并后的总记录数。
 */
data class SyncReport(
    val result: SyncResult,
    val direction: SyncDirection? = null,
    val localRecordCount: Int = 0,
    val cloudRecordCount: Int = 0,
    val uploadedCount: Int = 0,
    val downloadedCount: Int = 0,
    val mergedCount: Int = 0,
    /** 下载恢复时因字段级校验不通过被跳过的记录数（详见 validateBackupRecord） */
    val invalidCount: Int = 0,
    val syncTime: Long = System.currentTimeMillis(),
    /**
     * 本次上传是否新生成了恢复码（旧格式数据首次迁移为信封加密时为 true）。
     * UI 应提醒用户立即到设置页查看并离线保存恢复码；恢复码本身不进日志/报告。
     */
    val recoveryCodeGenerated: Boolean = false
) {
    /** 是否同步成功（含"无变更"） */
    val isSuccess: Boolean get() = result == SyncResult.SUCCESS || result == SyncResult.NO_CHANGES

    /** 人类可读的摘要信息，用于日志/通知 */
    fun toSummaryString(): String {
        fun str(id: Int, vararg args: Any): String =
            OvertimeApplication.getInstance().getString(id, *args)
        val parts = mutableListOf<String>()
        parts.add(str(R.string.sync_summary_result, result))
        if (direction != null) parts.add(str(R.string.sync_summary_direction, direction))
        if (localRecordCount > 0) parts.add(str(R.string.sync_summary_local, localRecordCount))
        if (cloudRecordCount > 0) parts.add(str(R.string.sync_summary_cloud, cloudRecordCount))
        if (uploadedCount > 0) parts.add(str(R.string.sync_summary_uploaded, uploadedCount))
        if (downloadedCount > 0) parts.add(str(R.string.sync_summary_downloaded, downloadedCount))
        if (mergedCount > 0) parts.add(str(R.string.sync_summary_merged, mergedCount))
        if (invalidCount > 0) parts.add(str(R.string.sync_summary_invalid_count, invalidCount))
        return parts.joinToString(str(R.string.sync_summary_sep))
    }
}
