package com.example.personalovertimerecord.utils

import com.example.personalovertimerecord.data.DayType
import com.example.personalovertimerecord.data.OvertimeRecord
import com.example.personalovertimerecord.data.OvertimeResult
import com.example.personalovertimerecord.data.OvertimeSettings

object OvertimeCalculator {

    /**
     * 根据登记内容确定"有效日期类型"（登记即分类）：
     * - 登记了加班（overtimeHours > 0）→ 视为周末/节假日：
     *   日历上是法定节假日按节假日（3倍），否则按周末（2倍）
     * - 只登记了加点（extraHours > 0）→ 视为正常工作日（1.5倍）
     * - 两者都未登记 → 按日历（HolidayManager）判定
     */
    fun effectiveDayType(date: String, overtimeHours: Double, extraHours: Double): DayType {
        val calendarDayType = HolidayManager.getDayType(date)
        return when {
            overtimeHours > 0 -> when (calendarDayType) {
                DayType.HOLIDAY -> DayType.HOLIDAY
                else -> DayType.WEEKEND
            }
            extraHours > 0 -> DayType.WORKDAY
            else -> calendarDayType
        }
    }

    /**
     * 计算小时工资（统一口径，供 OvertimeCalculator / SalaryCalculator 共用，避免口径漂移）：
     * 时薪 = (基本工资 + 绩效奖金 + 固定奖金) / (月工作天数 × 每日工时)；分母为 0 时返回 0。
     */
    fun hourlyWage(settings: OvertimeSettings): Double {
        val performanceBonus = settings.baseSalary * (settings.performancePercent / 100.0)
        val totalMonthlySalary = settings.baseSalary + performanceBonus + settings.fixedBonus
        val monthlyTotalHours = settings.monthlyWorkDays * settings.dailyWorkHours
        return if (monthlyTotalHours > 0) {
            totalMonthlySalary / monthlyTotalHours
        } else {
            0.0
        }
    }

    fun calculateOvertime(
        record: OvertimeRecord,
        settings: OvertimeSettings
    ): OvertimeResult {
        // 请假记录不产生任何加班/加点与工资（兼容历史数据中残留时长的记录）
        if (record.isLeave) return OvertimeResult()

        // 归一化 -1 哨兵值（未手工设置）为 0
        val overtimeHours = if (record.overtimeHours >= 0) record.overtimeHours else 0.0
        val extraHours = if (record.extraHours >= 0) record.extraHours else 0.0

        return calculateInternal(
            date = record.date,
            overtimeHours = overtimeHours,
            extraHours = extraHours,
            settings = settings,
            customRate = record.customRate
        )
    }

    private fun calculateInternal(
        date: String,
        overtimeHours: Double,
        extraHours: Double,
        settings: OvertimeSettings,
        customRate: Double = -1.0
    ): OvertimeResult {
        // 以登记内容为准分类：登记"加点"按工作日、登记"加班"按周末/节假日
        val dayType = effectiveDayType(date, overtimeHours, extraHours)
        
        var normalOvertime = 0.0
        var weekendOvertime = 0.0
        var holidayOvertime = 0.0
        
        when (dayType) {
            DayType.HOLIDAY -> {
                holidayOvertime = overtimeHours
            }
            DayType.WEEKEND -> {
                weekendOvertime = overtimeHours
            }
            DayType.WORKDAY -> {
                normalOvertime = overtimeHours
            }
        }
        
        val estimatedPay = calculateOvertimePay(
            normalOvertime, weekendOvertime, holidayOvertime, extraHours, settings, customRate
        )
        
        return OvertimeResult(
            workHours = 0.0,
            overtimeHours = overtimeHours,
            normalOvertime = normalOvertime,
            weekendOvertime = weekendOvertime,
            holidayOvertime = holidayOvertime,
            estimatedPay = estimatedPay,
            extraHours = extraHours
        )
    }
    
    private fun calculateOvertimePay(
        normalOvertime: Double,
        weekendOvertime: Double,
        holidayOvertime: Double,
        extraHours: Double,
        settings: OvertimeSettings,
        customRate: Double = -1.0
    ): Double {
        // 统一口径：见 hourlyWage()
        val hourlyWage = OvertimeCalculator.hourlyWage(settings)

        // 记录自定义倍率：该条记录整体（含加班与加点）按 customRate 计算，覆盖按日期类型的默认倍率
        if (customRate >= 0) {
            val totalHours = normalOvertime + weekendOvertime + holidayOvertime + extraHours
            return totalHours * hourlyWage * customRate
        }

        val normalPay = normalOvertime * hourlyWage * settings.overtimeRateNormal
        val weekendPay = weekendOvertime * hourlyWage * settings.overtimeRateWeekend
        val holidayPay = holidayOvertime * hourlyWage * settings.overtimeRateHoliday
        val extraPay = extraHours * hourlyWage * settings.overtimeRateNormal

        return normalPay + weekendPay + holidayPay + extraPay
    }
}