package com.example.personalovertimerecord.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.example.personalovertimerecord.MainActivity
import com.example.personalovertimerecord.OvertimeApplication
import com.example.personalovertimerecord.R
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
    private const val NOTIFICATION_ID = 1001

    private const val NOTIFICATION_ID_RECOVERY = 1002
    private const val PREFS_NAME = "auto_sync_prefs"
    private const val KEY_SYNC_ENABLED = "sync_enabled"
    private const val KEY_SYNC_INTERVAL = "sync_interval"
    private const val KEY_AUTO_SYNC_WIFI_ONLY = "wifi_only"

    private const val AUTO_SYNC_UNIQUE_NAME = "auto_sync_work"

    private fun str(@StringRes id: Int, vararg args: Any): String =
        OvertimeApplication.getInstance().getString(id, *args)

    // 同步间隔选项（分钟）；展示文案随系统语言切换
    val SYNC_INTERVALS: Map<Int, String>
        get() = mapOf(
            15 to str(R.string.auto_sync_interval_15),
            30 to str(R.string.auto_sync_interval_30),
            60 to str(R.string.auto_sync_interval_60),
            120 to str(R.string.auto_sync_interval_120),
            360 to str(R.string.auto_sync_interval_360),
            720 to str(R.string.auto_sync_interval_720),
            1440 to str(R.string.auto_sync_interval_1440)
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
                str(R.string.auto_sync_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = str(R.string.auto_sync_channel_desc)
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
            throw IllegalStateException(str(R.string.auto_sync_not_initialized))
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
        if (lastTime == 0L) return str(R.string.auto_sync_never)

        val diff = System.currentTimeMillis() - lastTime
        val minutes = diff / (1000 * 60)
        val hours = minutes / 60
        val days = hours / 24

        return when {
            minutes < 1 -> str(R.string.auto_sync_just_now)
            minutes < 60 -> str(R.string.auto_sync_minutes_ago, minutes)
            hours < 24 -> str(R.string.auto_sync_hours_ago, hours)
            else -> str(R.string.auto_sync_days_ago, days)
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
                        onComplete?.invoke(false, str(R.string.auto_sync_no_config))
                    }
                    return@launch
                }

                if (isWifiOnly() && !NetworkUtils.isWifiConnected(context)) {
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke(false, str(R.string.auto_sync_wifi_only))
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
                    SyncResult.SUCCESS -> str(R.string.auto_sync_success)
                    SyncResult.NO_CHANGES -> str(R.string.auto_sync_no_changes)
                    SyncResult.NO_CONFIG -> str(R.string.auto_sync_no_config)
                    SyncResult.NO_NETWORK -> str(R.string.auto_sync_no_network)
                    SyncResult.CONNECTION_FAILED -> str(R.string.auto_sync_connection_failed, WebDAVManager.lastResponseCode)
                    SyncResult.UPLOAD_FAILED -> str(R.string.auto_sync_upload_failed)
                    SyncResult.DOWNLOAD_FAILED -> str(R.string.auto_sync_download_failed, WebDAVManager.lastResponseCode)
                    SyncResult.RESTORE_FAILED -> str(R.string.auto_sync_restore_failed)
                    SyncResult.CONFLICT -> str(R.string.auto_sync_conflict)
                    SyncResult.ENCRYPTION_MISMATCH -> str(R.string.auto_sync_encryption_mismatch)
                }
                // 成功时附带同步统计细节
                val message = if (success) str(R.string.auto_sync_summary_fmt, baseMessage, report.toSummaryString()) else baseMessage

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
                    onComplete?.invoke(false, e.message ?: str(R.string.auto_sync_exception))
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
            .setContentTitle(str(R.string.auto_sync_notification_title))
            .setContentText(str(R.string.auto_sync_notification_failed))
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
            .setContentTitle(str(R.string.auto_sync_recovery_title))
            .setContentText(str(R.string.auto_sync_recovery_message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID_RECOVERY, notification)
    }
}
