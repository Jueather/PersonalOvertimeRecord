package com.example.personalovertimerecord.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.example.personalovertimerecord.MainActivity
import com.example.personalovertimerecord.OvertimeApplication
import com.example.personalovertimerecord.data.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 自动同步管理器（WorkManager 实现）
 *
 * 自动同步注册为一个周期任务（AutoSyncWorker），并带网络约束：
 * - 仅 WiFi 时使用 NetworkType.UNMETERED，否则 NetworkType.CONNECTED；
 * - 未连接符合条件网络时 WorkManager 自动等待，不再需要自维护"一次性闹钟链"；
 * - 周期任务持久化，设备重启/应用升级后自动恢复，无需 BOOT_COMPLETED 广播。
 *
 * 对外 API 与旧实现保持一致（isSyncEnabled/setSyncEnabled/setSyncInterval/
 * setWifiOnly/SYNC_INTERVALS/performSync 手动同步等），设置页无需改动。
 */
object AutoSyncManager {

    private const val CHANNEL_ID = "auto_sync_channel"
    private const val CHANNEL_NAME = "自动同步"
    private const val NOTIFICATION_ID = 1001

    private const val NOTIFICATION_ID_RECOVERY = 1002
    private const val PREFS_NAME = "auto_sync_prefs"
    private const val KEY_SYNC_ENABLED = "sync_enabled"
    private const val KEY_SYNC_INTERVAL = "sync_interval"
    private const val KEY_AUTO_SYNC_WIFI_ONLY = "wifi_only"

    private const val AUTO_SYNC_UNIQUE_NAME = "auto_sync_work"

    // 同步间隔选项（分钟）
    val SYNC_INTERVALS = mapOf(
        15 to "15分钟",
        30 to "30分钟",
        60 to "1小时",
        120 to "2小时",
        360 to "6小时",
        720 to "12小时",
        1440 to "24小时"
    )

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 初始化自动同步管理器（建议在 Application/SettingsActivity 中调用，Worker 内也会调用）
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (!::prefs.isInitialized) {
            prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        createNotificationChannel(appContext)
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "用于显示自动同步失败提醒"
                setShowBadge(false)
            }

            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 获取 SharedPreferences（未初始化时抛异常，提示先调用 init）
     */
    private fun requirePrefs(): android.content.SharedPreferences {
        if (!::prefs.isInitialized) {
            throw IllegalStateException("AutoSyncManager 未初始化，请先调用 init(context)")
        }
        return prefs
    }

    /**
     * 检查是否启用自动同步
     */
    fun isSyncEnabled(): Boolean {
        return requirePrefs().getBoolean(KEY_SYNC_ENABLED, false)
    }

    /**
     * 设置是否启用自动同步
     */
    fun setSyncEnabled(context: Context, enabled: Boolean) {
        init(context)
        requirePrefs().edit().putBoolean(KEY_SYNC_ENABLED, enabled).apply()

        if (enabled) {
            scheduleSync(context)
        } else {
            cancelSync(context)
        }
    }

    /**
     * 获取同步间隔（分钟）
     */
    fun getSyncInterval(): Int {
        return requirePrefs().getInt(KEY_SYNC_INTERVAL, 60)
    }

    /**
     * 设置同步间隔
     */
    fun setSyncInterval(context: Context, intervalMinutes: Int) {
        init(context)
        requirePrefs().edit().putInt(KEY_SYNC_INTERVAL, intervalMinutes).apply()

        if (isSyncEnabled()) {
            // 重新调度：更新周期任务的时间间隔
            scheduleSync(context)
        }
    }

    /**
     * 检查是否仅在WiFi下同步
     */
    fun isWifiOnly(): Boolean {
        return requirePrefs().getBoolean(KEY_AUTO_SYNC_WIFI_ONLY, true)
    }

    /**
     * 设置是否仅在WiFi下同步（改变网络约束，需重新入队周期任务）
     */
    fun setWifiOnly(wifiOnly: Boolean) {
        requirePrefs().edit().putBoolean(KEY_AUTO_SYNC_WIFI_ONLY, wifiOnly).apply()
        if (isSyncEnabled() && ::appContext.isInitialized) {
            scheduleSync(appContext)
        }
    }

    /**
     * 获取上次同步时间（统一从 SettingsManager 读取，避免双份时间戳）
     */
    fun getLastSyncTime(context: Context): Long {
        return SettingsManager(context).getLastSyncTime()
    }

    /**
     * 获取上次同步时间的可读字符串
     */
    fun getLastSyncTimeString(context: Context): String {
        val lastTime = getLastSyncTime(context)
        if (lastTime == 0L) return "从未同步"

        val diff = System.currentTimeMillis() - lastTime
        val minutes = diff / (1000 * 60)
        val hours = minutes / 60
        val days = hours / 24

        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "${minutes}分钟前"
            hours < 24 -> "${hours}小时前"
            else -> "${days}天前"
        }
    }

    /**
     * 调度自动同步周期任务（带网络约束；WorkManager 持久化，重启后自动恢复）
     */
    fun scheduleSync(context: Context) {
        if (!isSyncEnabled()) return

        val intervalMinutes = getSyncInterval().coerceIn(15, 1440)
        val networkType = if (isWifiOnly()) {
            NetworkType.UNMETERED
        } else {
            NetworkType.CONNECTED
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()

        val request = PeriodicWorkRequest.Builder(
            AutoSyncWorker::class.java,
            intervalMinutes.toLong(),
            TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(
                AUTO_SYNC_UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                request
            )
    }

    /**
     * 取消自动同步周期任务
     */
    fun cancelSync(context: Context) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(AUTO_SYNC_UNIQUE_NAME)
    }

    /**
     * 手动立即同步一次（设置页"立即同步"按钮）。
     * 仅执行单次同步，不注册/不修改周期任务。
     */
    fun performSync(context: Context, onComplete: ((Boolean, String) -> Unit)? = null) {
        scope.launch {
            try {
                init(context)
                val settingsManager = SettingsManager(context)

                if (settingsManager.getWebDAVConfig() == null) {
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke(false, "未配置WebDAV")
                    }
                    return@launch
                }

                if (isWifiOnly() && !NetworkUtils.isWifiConnected(context)) {
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke(false, "仅在WiFi下同步")
                    }
                    return@launch
                }

                val database = OvertimeApplication.getDatabase()
                val syncManager = SyncManager(context, settingsManager, database)
                val report = syncManager.performSync()
                // NO_CHANGES 表示"本地没有需要同步的更改"，属于正常完成，不应提示失败
                val success = report.isSuccess

                // 失败时给出具体原因（含服务器响应码），避免只显示笼统的"同步失败"
                val baseMessage = when (report.result) {
                    SyncResult.SUCCESS -> "同步成功"
                    SyncResult.NO_CHANGES -> "没有需要同步的更改"
                    SyncResult.NO_CONFIG -> "未配置WebDAV"
                    SyncResult.NO_NETWORK -> "网络不可用，请检查网络连接"
                    SyncResult.CONNECTION_FAILED -> "连接失败（服务器响应码 ${WebDAVManager.lastResponseCode}），请检查网络和配置"
                    SyncResult.UPLOAD_FAILED -> "上传失败"
                    SyncResult.DOWNLOAD_FAILED -> "下载失败（服务器响应码 ${WebDAVManager.lastResponseCode}）"
                    SyncResult.RESTORE_FAILED -> "恢复数据失败"
                    SyncResult.CONFLICT -> "存在数据冲突，请手动处理"
                    SyncResult.ENCRYPTION_MISMATCH -> "云端数据已加密且密码不匹配，可在设置中检查密码或用恢复码找回"
                }
                // 成功时附带同步统计细节
                val message = if (success) "$baseMessage（${report.toSummaryString()}）" else baseMessage

                withContext(Dispatchers.Main) {
                    onComplete?.invoke(success, message)
                }
                if (!success) {
                    notifySyncFailure(context)
                } else if (report.recoveryCodeGenerated) {
                    // 后台同步完成了信封格式迁移：通知提醒用户尽快到设置页离线保存恢复码
                    notifyRecoveryCodeReady(context)
                }
            } catch (e: Exception) {
                AppLogger.e("AutoSync", "手动同步异常", e)
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(false, e.message ?: "同步异常")
                }
            }
        }
    }

    /**
     * 同步失败提醒（仅失败时调用；成功不打扰）
     */
    fun notifySyncFailure(context: Context) {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            0,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("数据同步")
            .setContentText("自动同步失败，请检查网络后重试")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 恢复码已生成提醒：后台同步把云端数据升级为信封加密后，
     * 提醒用户尽快到设置页查看并离线保存恢复码（忘记密码时的唯一找回方式）。
     */
    fun notifyRecoveryCodeReady(context: Context) {
        val intent = Intent(context, com.example.personalovertimerecord.SettingsActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            0,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("请保存同步恢复码")
            .setContentText("云端备份已升级为恢复码加密，忘记密码时需凭此恢复码找回，请立即离线保存")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID_RECOVERY, notification)
    }
}
