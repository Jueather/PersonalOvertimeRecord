package com.example.personalovertimerecord.utils

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.personalovertimerecord.MainActivity
import com.example.personalovertimerecord.R
import com.example.personalovertimerecord.data.SettingsManager
import com.example.personalovertimerecord.widget.QuickEntrySummaryLoader
import com.example.personalovertimerecord.widget.QuickRecordWidget
import com.example.personalovertimerecord.widget.QuickEntryReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 常驻快捷登记通知：展示当月加班与实发工资，并提供「加班+1h / 加点+1h / 打开登记」操作。
 * 是否显示由设置页开关控制（设备本地偏好）。
 */
object QuickEntryNotification {

    private const val TAG = "QuickEntryNotification"
    private const val CHANNEL_ID = "quick_entry"
    private const val NOTIFICATION_ID = 1001
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 按设置开关同步通知：关闭时立即移除，开启时刷新内容 */
    fun sync(context: Context) {
        val appContext = context.applicationContext
        if (!SettingsManager(appContext).isQuickEntryNotificationEnabled()) {
            cancel(appContext)
            return
        }
        post(appContext)
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun post(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            AppLogger.d(TAG, "未授予通知权限，跳过快捷登记通知")
            return
        }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        createChannel(context)
        scope.launch {
            try {
                val summary = QuickEntrySummaryLoader.load(context)
                val contentText = "${summary.month}月加班 ${Formatter.formatHoursShort(summary.totalHours)}" +
                    " · 实发 ${Formatter.formatMoney(summary.netSalary)}"
                manager.notify(NOTIFICATION_ID, buildNotification(context, contentText))
            } catch (e: Exception) {
                AppLogger.e(TAG, "刷新快捷登记通知失败", e)
            }
        }
    }

    private fun buildNotification(context: Context, contentText: String) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_check_in)
            .setContentTitle("快捷登记")
            .setContentText(contentText)
            .setContentIntent(openEntryPendingIntent(context))
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(0, "加班+1h", actionPendingIntent(context, QuickRecordWidget.ACTION_ADD_OVERTIME, 11))
            .addAction(0, "加点+1h", actionPendingIntent(context, QuickRecordWidget.ACTION_ADD_EXTRA, 12))
            .addAction(0, "打开登记", openEntryPendingIntent(context))
            .build()

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "快捷登记",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "通知栏常驻加班/加点快捷登记入口"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun actionPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, QuickEntryReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openEntryPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_QUICK_ADD, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            13,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
