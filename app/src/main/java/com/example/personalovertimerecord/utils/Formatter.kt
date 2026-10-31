package com.example.personalovertimerecord.utils

import androidx.annotation.StringRes
import com.example.personalovertimerecord.OvertimeApplication
import com.example.personalovertimerecord.R
import com.example.personalovertimerecord.data.DayType
import com.example.personalovertimerecord.data.LeaveType
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Formatter {

    /** 应用级 Context：展示文案随系统语言切换，无需每个调用点都传入 Context */
    private fun str(@StringRes id: Int): String = OvertimeApplication.getInstance().getString(id)

    private fun str(@StringRes id: Int, vararg args: Any): String =
        OvertimeApplication.getInstance().getString(id, *args)

    fun formatHours(hours: Double): String {
        val fullHours = hours.toInt()
        val minutes = ((hours - fullHours) * 60).toInt()
        return if (minutes > 0) {
            "${fullHours}h${minutes}m"
        } else {
            "${fullHours}h"
        }
    }

    fun formatMoney(amount: Double): String {
        return String.format(Locale.getDefault(), "¥%.2f", amount)
    }

    fun formatHoursShort(hours: Double): String {
        return String.format(Locale.getDefault(), "%.1fh", hours)
    }

    fun formatDate(dateStr: String): String {
        return try {
            val date = DateUtils.parseDate(dateStr) ?: return dateStr
            val weekday = getWeekdayName(date)
            val parts = dateStr.split("-")
            if (parts.size == 3) {
                str(R.string.common_date_format, parts[1], parts[2], weekday)
            } else {
                dateStr
            }
        } catch (e: Exception) {
            dateStr
        }
    }

    /** 首页顶部的完整日期：2026年10月31日 / 2026-10-31 */
    fun formatFullDate(date: Date): String {
        val calendar = Calendar.getInstance().apply { time = date }
        return str(
            R.string.common_full_date,
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    /** 本地化星期名称 */
    fun getWeekdayName(date: Date): String {
        val calendar = Calendar.getInstance().apply { time = date }
        val resId = when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> R.string.common_weekday_mon
            Calendar.TUESDAY -> R.string.common_weekday_tue
            Calendar.WEDNESDAY -> R.string.common_weekday_wed
            Calendar.THURSDAY -> R.string.common_weekday_thu
            Calendar.FRIDAY -> R.string.common_weekday_fri
            Calendar.SATURDAY -> R.string.common_weekday_sat
            else -> R.string.common_weekday_sun
        }
        return str(resId)
    }

    fun getDayTypeString(dateStr: String): String {
        return getDayTypeName(HolidayManager.getDayType(dateStr))
    }

    /**
     * 按"登记即分类"规则获取日期类型的名称：
     * 登记了加点→工作日；登记了加班→周末/法定假日；否则按日历
     */
    fun getEffectiveDayTypeString(dateStr: String, overtimeHours: Double, extraHours: Double): String {
        return getDayTypeName(OvertimeCalculator.effectiveDayType(dateStr, overtimeHours, extraHours))
    }

    private fun getDayTypeName(dayType: DayType): String {
        return str(
            when (dayType) {
                DayType.HOLIDAY -> R.string.common_day_holiday
                DayType.WEEKEND -> R.string.common_day_weekend
                DayType.WORKDAY -> R.string.common_day_workday
            }
        )
    }

    /**
     * 获取请假类型的显示名称
     */
    fun getLeaveTypeDisplayName(leaveType: String?): String {
        return LeaveType.fromString(leaveType)?.let { getLeaveTypeName(it) }
            ?: str(R.string.common_leave_default)
    }

    /**
     * 获取请假类型的本地化名称（下拉列表与详情展示共用）
     */
    fun getLeaveTypeName(leaveType: LeaveType): String {
        return str(
            when (leaveType) {
                LeaveType.ANNUAL_LEAVE -> R.string.leave_type_annual
                LeaveType.SICK_LEAVE -> R.string.leave_type_sick
                LeaveType.PERSONAL_LEAVE -> R.string.leave_type_personal
                LeaveType.MARRIAGE_LEAVE -> R.string.leave_type_marriage
                LeaveType.MATERNITY_LEAVE -> R.string.leave_type_maternity
                LeaveType.PATERNITY_LEAVE -> R.string.leave_type_paternity
                LeaveType.FUNERAL_LEAVE -> R.string.leave_type_funeral
                LeaveType.WORK_INJURY -> R.string.leave_type_work_injury
                LeaveType.UNPAID_LEAVE -> R.string.leave_type_unpaid
                LeaveType.OTHER_LEAVE -> R.string.leave_type_other
            }
        )
    }

    /**
     * 格式化请假信息
     */
    fun formatLeaveInfo(leaveType: String?, leaveHours: Double): String {
        if (leaveType == null || leaveHours <= 0) return ""
        val typeName = getLeaveTypeDisplayName(leaveType)
        return if (leaveHours >= 1.0) {
            str(R.string.common_leave_days, typeName, leaveHours.toInt())
        } else {
            str(R.string.common_leave_hours, typeName, (leaveHours * 8).toInt())
        }
    }
}
