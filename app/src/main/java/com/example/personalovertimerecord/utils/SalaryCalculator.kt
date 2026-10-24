package com.example.personalovertimerecord.utils

import com.example.personalovertimerecord.data.DayType
import com.example.personalovertimerecord.data.OvertimeRecord
import com.example.personalovertimerecord.data.OvertimeSettings
import java.util.Calendar
import java.util.Locale

object SalaryCalculator {
    
    data class SalaryReport(
        val year: Int,
        val month: Int,
        val baseSalary: Double,
        val performanceBonus: Double,
        val normalOvertimePay: Double,
        val weekendOvertimePay: Double,
        val holidayOvertimePay: Double,
        val extraPay: Double,
        val totalOvertimePay: Double,
        val totalSalary: Double,
        // 分项加班时长（小时）
        val normalOvertime: Double,
        val weekendOvertime: Double,
        val holidayOvertime: Double,
        val totalOvertimeHours: Double,
        val totalExtraHours: Double,
        val totalHours: Double,
        val overtimeDays: Int,
        val normalDays: Int,
        val weekendDays: Int,
        val holidayDays: Int,
        // 报表展示用的倍率（取用户配置值，避免硬编码）
        val overtimeRateNormal: Double,
        val overtimeRateWeekend: Double,
        val overtimeRateHoliday: Double
    )
    
    data class DailySalary(
        val date: String,
        val dayType: DayType,
        val overtimeHours: Double,
        val extraHours: Double,
        val pay: Double
    )
    
    fun calculateMonthlySalary(
        attendanceList: List<OvertimeRecord>,
        settings: OvertimeSettings,
        year: Int,
        month: Int
    ): SalaryReport {
        val monthPrefix = String.format(Locale.getDefault(), "%04d-%02d", year, month)
        
        val monthRecords = attendanceList.filter { it.date.startsWith(monthPrefix) }
        
        var normalOvertime = 0.0
        var weekendOvertime = 0.0
        var holidayOvertime = 0.0
        var extraHours = 0.0
        var normalDays = 0
        var weekendDays = 0
        var holidayDays = 0
        
        monthRecords.forEach { record ->
            // 请假记录不参与加班/加点统计（兼容历史数据中残留时长的记录）
            if (record.isLeave) return@forEach

            val overtime = if (record.overtimeHours >= 0) record.overtimeHours else 0.0
            val extra = if (record.extraHours >= 0) record.extraHours else 0.0

            // 与 OvertimeCalculator 保持一致：以登记内容分类（加点→工作日，加班→周末/节假日）
            val dayType = OvertimeCalculator.effectiveDayType(record.date, overtime, extra)

            when (dayType) {
                DayType.WORKDAY -> {
                    normalOvertime += overtime
                    if (overtime > 0) normalDays++
                }
                DayType.WEEKEND -> {
                    weekendOvertime += overtime
                    if (overtime > 0) weekendDays++
                }
                DayType.HOLIDAY -> {
                    holidayOvertime += overtime
                    if (overtime > 0) holidayDays++
                }
            }
            extraHours += extra
        }
        
        val performanceBonus = settings.baseSalary * (settings.performancePercent / 100.0)
        val totalMonthlySalary = settings.baseSalary + performanceBonus
        // 统一口径：时薪计算见 OvertimeCalculator.hourlyWage()，避免三处实现漂移
        val hourlyWage = OvertimeCalculator.hourlyWage(settings)
        
        val normalPay = normalOvertime * hourlyWage * settings.overtimeRateNormal
        val weekendPay = weekendOvertime * hourlyWage * settings.overtimeRateWeekend
        val holidayPay = holidayOvertime * hourlyWage * settings.overtimeRateHoliday
        val extraPayAmount = extraHours * hourlyWage * settings.overtimeRateNormal
        
        val totalOvertimePay = normalPay + weekendPay + holidayPay + extraPayAmount
        val totalSalary = totalMonthlySalary + totalOvertimePay
        
        return SalaryReport(
            year = year,
            month = month,
            baseSalary = settings.baseSalary,
            performanceBonus = performanceBonus,
            normalOvertimePay = normalPay,
            weekendOvertimePay = weekendPay,
            holidayOvertimePay = holidayPay,
            extraPay = extraPayAmount,
            totalOvertimePay = totalOvertimePay,
            totalSalary = totalSalary,
            totalOvertimeHours = normalOvertime + weekendOvertime + holidayOvertime,
            totalExtraHours = extraHours,
            totalHours = normalOvertime + weekendOvertime + holidayOvertime + extraHours,
            overtimeDays = normalDays + weekendDays + holidayDays,
            normalOvertime = normalOvertime,
            weekendOvertime = weekendOvertime,
            holidayOvertime = holidayOvertime,
            normalDays = normalDays,
            weekendDays = weekendDays,
            holidayDays = holidayDays,
            overtimeRateNormal = settings.overtimeRateNormal,
            overtimeRateWeekend = settings.overtimeRateWeekend,
            overtimeRateHoliday = settings.overtimeRateHoliday
        )
    }
    
    fun calculateDailySalaries(
        attendanceList: List<OvertimeRecord>,
        settings: OvertimeSettings
    ): List<DailySalary> {
        return attendanceList.map { record ->
            val overtime = if (record.overtimeHours >= 0) record.overtimeHours else 0.0
            val extra = if (record.extraHours >= 0) record.extraHours else 0.0

            // 与 OvertimeCalculator 保持一致：以登记内容分类（加点→工作日，加班→周末/节假日）
            val dayType = OvertimeCalculator.effectiveDayType(record.date, overtime, extra)
            
            // 统一口径：时薪计算见 OvertimeCalculator.hourlyWage()
            val hourlyWage = OvertimeCalculator.hourlyWage(settings)
            
            val rate = when (dayType) {
                DayType.WORKDAY -> settings.overtimeRateNormal
                DayType.WEEKEND -> settings.overtimeRateWeekend
                DayType.HOLIDAY -> settings.overtimeRateHoliday
            }
            
            val overtimePay = overtime * hourlyWage * rate
            val extraPay = extra * hourlyWage * settings.overtimeRateNormal
            val totalPay = overtimePay + extraPay
            
            DailySalary(
                date = record.date,
                dayType = dayType,
                overtimeHours = overtime,
                extraHours = extra,
                pay = totalPay
            )
        }
    }
    
    fun calculateYearlySalary(
        attendanceList: List<OvertimeRecord>,
        settings: OvertimeSettings,
        year: Int
    ): List<SalaryReport> {
        val reports = mutableListOf<SalaryReport>()
        
        for (month in 1..12) {
            val report = calculateMonthlySalary(attendanceList, settings, year, month)
            if (report.totalOvertimeHours > 0 || report.totalExtraHours > 0) {
                reports.add(report)
            }
        }
        
        return reports
    }
    
    fun formatSalaryReport(report: SalaryReport): String {
        return buildString {
            appendLine("=" .repeat(40))
            appendLine("📊 ${report.year}年${report.month}月工资报表")
            appendLine("=".repeat(40))
            appendLine()
            appendLine("💰 基础工资: ${Formatter.formatMoney(report.baseSalary)}")
            appendLine("🎯 绩效奖金: ${Formatter.formatMoney(report.performanceBonus)}")
            appendLine()
            appendLine("📈 加班工资明细:")
            appendLine("  工作日加班: ${String.format(Locale.getDefault(), "%.1f", report.normalOvertime)}h × ${report.overtimeRateNormal} = ${Formatter.formatMoney(report.normalOvertimePay)}")
            appendLine("  周末加班: ${String.format(Locale.getDefault(), "%.1f", report.weekendOvertime)}h × ${report.overtimeRateWeekend} = ${Formatter.formatMoney(report.weekendOvertimePay)}")
            appendLine("  法定假日: ${String.format(Locale.getDefault(), "%.1f", report.holidayOvertime)}h × ${report.overtimeRateHoliday} = ${Formatter.formatMoney(report.holidayOvertimePay)}")
            appendLine("  加点工资: ${String.format(Locale.getDefault(), "%.1f", report.totalExtraHours)}h × ${report.overtimeRateNormal} = ${Formatter.formatMoney(report.extraPay)}")
            appendLine()
            appendLine("⏱️ 加班统计:")
            appendLine("  工作日加班: ${report.normalDays}天 (${String.format(Locale.getDefault(), "%.1f", report.normalOvertime)}h)")
            appendLine("  周末加班: ${report.weekendDays}天 (${String.format(Locale.getDefault(), "%.1f", report.weekendOvertime)}h)")
            appendLine("  法定假日: ${report.holidayDays}天 (${String.format(Locale.getDefault(), "%.1f", report.holidayOvertime)}h)")
            appendLine()
            appendLine("📋 总计:")
            appendLine("  总加班时长: ${Formatter.formatHours(report.totalOvertimeHours)}")
            appendLine("  总加点时长: ${Formatter.formatHours(report.totalExtraHours)}")
            appendLine("  加班总工资: ${Formatter.formatMoney(report.totalOvertimePay)}")
            appendLine()
            appendLine("💵 本月总工资: ${Formatter.formatMoney(report.totalSalary)}")
            appendLine("=".repeat(40))
        }
    }
}
