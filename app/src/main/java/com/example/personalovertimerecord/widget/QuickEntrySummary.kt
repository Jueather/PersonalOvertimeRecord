package com.example.personalovertimerecord.widget

import android.content.Context
import com.example.personalovertimerecord.OvertimeApplication
import com.example.personalovertimerecord.data.SettingsManager
import com.example.personalovertimerecord.utils.SalaryCalculator
import java.util.Calendar

/**
 * 小部件/通知共用的当月摘要（加班时长 + 实发工资）。
 */
data class QuickEntrySummary(
    val month: Int,
    val totalHours: Double,
    val netSalary: Double
)

object QuickEntrySummaryLoader {

    suspend fun load(context: Context): QuickEntrySummary {
        val settings = SettingsManager(context).getSettings()
        val records = OvertimeApplication.getAttendanceRepository().getAllAttendance()
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val report = SalaryCalculator.calculateMonthlySalary(records, settings, year, month)
        return QuickEntrySummary(
            month = month,
            totalHours = report.totalHours,
            netSalary = report.netSalary
        )
    }
}
