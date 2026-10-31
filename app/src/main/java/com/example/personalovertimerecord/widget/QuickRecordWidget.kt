package com.example.personalovertimerecord.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.personalovertimerecord.MainActivity
import com.example.personalovertimerecord.R
import com.example.personalovertimerecord.utils.AppLogger
import com.example.personalovertimerecord.utils.Formatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 桌面小部件：展示当月加班时长与实发工资，并提供「加班+1h / 加点+1h / 登记」快捷入口。
 */
class QuickRecordWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_ADD_OVERTIME = "com.example.personalovertimerecord.action.QUICK_ADD_OVERTIME"
        const val ACTION_ADD_EXTRA = "com.example.personalovertimerecord.action.QUICK_ADD_EXTRA"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** 应用内数据变化后刷新所有小部件实例 */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(
                ComponentName(context.applicationContext, QuickRecordWidget::class.java)
            )
            if (ids.isEmpty()) return
            scope.launch {
                try {
                    val views = buildRemoteViews(context.applicationContext)
                    ids.forEach { manager.updateAppWidget(it, views) }
                } catch (e: Exception) {
                    AppLogger.e("QuickRecordWidget", "刷新小部件失败", e)
                }
            }
        }

        private suspend fun buildRemoteViews(context: Context): RemoteViews {
            val summary = QuickEntrySummaryLoader.load(context)
            val views = RemoteViews(context.packageName, R.layout.widget_quick_record)

            views.setTextViewText(R.id.tvWidgetMonth, context.getString(R.string.widget_month_fmt, summary.month))
            views.setTextViewText(R.id.tvWidgetHours, Formatter.formatHoursShort(summary.totalHours))
            views.setTextViewText(
                R.id.tvWidgetPay,
                context.getString(R.string.widget_net_salary_fmt, Formatter.formatMoney(summary.netSalary))
            )

            views.setOnClickPendingIntent(
                R.id.btnWidgetOvertime,
                broadcastPendingIntent(context, ACTION_ADD_OVERTIME, 1)
            )
            views.setOnClickPendingIntent(
                R.id.btnWidgetExtra,
                broadcastPendingIntent(context, ACTION_ADD_EXTRA, 2)
            )
            views.setOnClickPendingIntent(R.id.btnWidgetOpen, openEntryPendingIntent(context))
            views.setOnClickPendingIntent(R.id.widgetRoot, openEntryPendingIntent(context))

            return views
        }

        private fun broadcastPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
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
                3,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pendingResult = goAsync()
        scope.launch {
            try {
                val views = buildRemoteViews(context.applicationContext)
                appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
            } catch (e: Exception) {
                AppLogger.e("QuickRecordWidget", "更新小部件失败", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
