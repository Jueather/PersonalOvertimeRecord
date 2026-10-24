package com.example.personalovertimerecord.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.personalovertimerecord.OvertimeApplication
import com.example.personalovertimerecord.data.OvertimeRecord
import com.example.personalovertimerecord.utils.AppLogger
import com.example.personalovertimerecord.utils.DateUtils
import com.example.personalovertimerecord.utils.QuickEntryNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.min

/**
 * 小部件/通知快捷操作：直接在当前日期记录上累加 1 小时加班或加点。
 */
class QuickEntryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != QuickRecordWidget.ACTION_ADD_OVERTIME &&
            action != QuickRecordWidget.ACTION_ADD_EXTRA
        ) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val addOvertime = action == QuickRecordWidget.ACTION_ADD_OVERTIME
                QuickEntryStore.addOneHour(context.applicationContext, addOvertime)
                QuickRecordWidget.refresh(context.applicationContext)
                QuickEntryNotification.sync(context.applicationContext)
            } catch (e: Exception) {
                AppLogger.e("QuickEntryReceiver", "快捷登记失败", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

internal object QuickEntryStore {

    private const val MAX_DAILY_HOURS = 11.0

    /**
     * 在当前日期追加 1 小时加班/加点：
     * - 当天无记录 → 新建记录；
     * - 当天为请假 → 不改动（避免覆盖请假登记）；
     * - 已有记录 → 在原有小时数上累加，单日上限 11 小时。
     */
    suspend fun addOneHour(context: Context, addOvertime: Boolean) {
        val repository = OvertimeApplication.getAttendanceRepository()
        val now = Calendar.getInstance()
        val date = DateUtils.formatDate(
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH),
            now.get(Calendar.DAY_OF_MONTH)
        )
        val existing = repository.getAttendanceByDate(date)
        if (existing != null && existing.isLeave) return

        if (existing == null) {
            val record = OvertimeRecord(
                date = date,
                overtimeHours = if (addOvertime) 1.0 else -1.0,
                extraHours = if (addOvertime) -1.0 else 1.0,
                note = "快捷登记"
            )
            repository.insertAttendance(record)
            return
        }

        if (addOvertime) {
            val current = if (existing.overtimeHours >= 0) existing.overtimeHours else 0.0
            if (current >= MAX_DAILY_HOURS) return
            repository.updateAttendance(
                existing.copy(overtimeHours = min(MAX_DAILY_HOURS, current + 1.0))
            )
        } else {
            val current = if (existing.extraHours >= 0) existing.extraHours else 0.0
            if (current >= MAX_DAILY_HOURS) return
            repository.updateAttendance(
                existing.copy(extraHours = min(MAX_DAILY_HOURS, current + 1.0))
            )
        }
    }
}
