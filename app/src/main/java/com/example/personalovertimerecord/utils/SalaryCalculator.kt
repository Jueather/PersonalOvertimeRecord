package com.example.personalovertimerecord.utils

import com.example.personalovertimerecord.data.DayType
import com.example.personalovertimerecord.data.OvertimeRecord
import com.example.personalovertimerecord.data.OvertimeSettings
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.round

object SalaryCalculator {

    /** 个税起征点（每月减除费用） */
    const val TAX_FREE_THRESHOLD = 5000.0

    /**
     * 五险一金个人扣款明细。
     * 缴费基数 <= 0 时按底薪计算（见 [calculateInsurance]）。
     */
    data class InsuranceDeduction(
        val socialInsuranceBase: Double,
        val housingFundBase: Double,
        val pension: Double,
        val medical: Double,
        val unemployment: Double,
        val housingFund: Double,
        val total: Double
    )

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
        // 应发合计（底薪 + 绩效 + 加班工资）
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
        val overtimeRateHoliday: Double,
        // ---- 五险一金与个税 ----
        val insurance: InsuranceDeduction,
        /** 每月专项附加扣除合计 */
        val specialDeduction: Double,
        /** 本月应纳税所得额（应发 - 五险一金 - 起征点 - 专项附加扣除，负值按 0） */
        val monthlyTaxableIncome: Double,
        /** 本月预扣个税（累计预扣法） */
        val incomeTax: Double,
        /** 年初至本月的累计应纳税所得额 */
        val cumulativeTaxableIncome: Double,
        /** 年初至本月的累计预扣个税 */
        val cumulativeTax: Double,
        /** 实发工资 = 应发合计 - 五险一金 - 本月个税 */
        val netSalary: Double
    )

    data class DailySalary(
        val date: String,
        val dayType: DayType,
        val overtimeHours: Double,
        val extraHours: Double,
        val pay: Double
    )

    /**
     * 个人所得税累计预扣税率表（综合所得年度税率表，按累计应纳税所得额适用）。
     * [limit] 为累计应纳税所得额上限，[quickDeduction] 为速算扣除数。
     */
    private data class TaxBracket(val limit: Double, val rate: Double, val quickDeduction: Double)

    private val TAX_BRACKETS = listOf(
        TaxBracket(36_000.0, 0.03, 0.0),
        TaxBracket(144_000.0, 0.10, 2_520.0),
        TaxBracket(300_000.0, 0.20, 16_920.0),
        TaxBracket(420_000.0, 0.25, 31_920.0),
        TaxBracket(660_000.0, 0.30, 52_920.0),
        TaxBracket(960_000.0, 0.35, 85_920.0),
        TaxBracket(Double.MAX_VALUE, 0.45, 181_920.0)
    )

    /**
     * 计算五险一金个人扣款：
     * 养老/医疗/失业按社保缴费基数 × 个人比例，公积金按公积金缴费基数 × 比例。
     * 基数填 0 时回退为底薪，方便只维护一个工资数值的用户。
     */
    fun calculateInsurance(settings: OvertimeSettings): InsuranceDeduction {
        val socialBase = if (settings.socialInsuranceBase > 0) settings.socialInsuranceBase else settings.baseSalary
        val fundBase = if (settings.housingFundBase > 0) settings.housingFundBase else settings.baseSalary

        val pension = round2(socialBase * settings.pensionRate / 100.0)
        val medical = round2(socialBase * settings.medicalRate / 100.0)
        val unemployment = round2(socialBase * settings.unemploymentRate / 100.0)
        val housingFund = round2(fundBase * settings.housingFundRate / 100.0)

        return InsuranceDeduction(
            socialInsuranceBase = round2(socialBase),
            housingFundBase = round2(fundBase),
            pension = pension,
            medical = medical,
            unemployment = unemployment,
            housingFund = housingFund,
            total = round2(pension + medical + unemployment + housingFund)
        )
    }

    /** 按累计应纳税所得额计算累计应预扣个税（未扣除已预扣部分） */
    fun cumulativeTaxOf(taxableIncome: Double): Double {
        if (taxableIncome <= 0) return 0.0
        val bracket = TAX_BRACKETS.firstOrNull { taxableIncome <= it.limit } ?: TAX_BRACKETS.last()
        return round2(taxableIncome * bracket.rate - bracket.quickDeduction)
    }

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
        val grossSalary = round2(totalMonthlySalary + totalOvertimePay)

        // ---- 五险一金与累计预扣个税 ----
        val insurance = calculateInsurance(settings)
        val monthlyDeduction = TAX_FREE_THRESHOLD + settings.specialDeduction
        val cumulativeTaxableThisMonth =
            cumulativeTaxableIncome(attendanceList, settings, year, month, insurance.total)
        val cumulativeTaxablePrevMonth =
            cumulativeTaxableIncome(attendanceList, settings, year, month - 1, insurance.total)
        val cumulativeTax = cumulativeTaxOf(cumulativeTaxableThisMonth)
        val cumulativeTaxPrevMonth = cumulativeTaxOf(cumulativeTaxablePrevMonth)
        // 累计预扣法：本月应预扣 = 至本月累计应预扣 - 至上月累计已预扣（不为负）
        val incomeTax = round2(max(0.0, cumulativeTax - cumulativeTaxPrevMonth))
        val monthlyTaxableIncome = round2(max(0.0, grossSalary - insurance.total - monthlyDeduction))
        val netSalary = round2(grossSalary - insurance.total - incomeTax)

        return SalaryReport(
            year = year,
            month = month,
            baseSalary = settings.baseSalary,
            performanceBonus = round2(performanceBonus),
            normalOvertimePay = round2(normalPay),
            weekendOvertimePay = round2(weekendPay),
            holidayOvertimePay = round2(holidayPay),
            extraPay = round2(extraPayAmount),
            totalOvertimePay = round2(totalOvertimePay),
            totalSalary = grossSalary,
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
            overtimeRateHoliday = settings.overtimeRateHoliday,
            insurance = insurance,
            specialDeduction = round2(settings.specialDeduction),
            monthlyTaxableIncome = monthlyTaxableIncome,
            incomeTax = incomeTax,
            cumulativeTaxableIncome = round2(cumulativeTaxableThisMonth),
            cumulativeTax = cumulativeTax,
            netSalary = netSalary
        )
    }

    /**
     * 年初至指定月份的累计应纳税所得额：
     * 累计收入（底薪 + 绩效 + 当月加班工资）- 累计五险一金 - 累计减除费用（起征点 + 专项附加扣除）。
     *
     * 说明：应用只维护一套当前工资设置，历史月份按同一套设置推算，
     * 与真实工资条在调薪月份可能存在偏差，其余月份口径一致。
     */
    private fun cumulativeTaxableIncome(
        attendanceList: List<OvertimeRecord>,
        settings: OvertimeSettings,
        year: Int,
        month: Int,
        monthlyInsurance: Double
    ): Double {
        if (month <= 0) return 0.0

        val monthlyBase = settings.baseSalary * (1 + settings.performancePercent / 100.0)
        var cumulativeIncome = 0.0
        for (m in 1..month) {
            cumulativeIncome += monthlyBase + overtimePayOfMonth(attendanceList, settings, year, m)
        }
        val cumulativeDeduction = (monthlyInsurance + TAX_FREE_THRESHOLD + settings.specialDeduction) * month
        return max(0.0, cumulativeIncome - cumulativeDeduction)
    }

    /** 指定月份全部记录的加班工资合计（口径与 OvertimeCalculator 一致） */
    private fun overtimePayOfMonth(
        attendanceList: List<OvertimeRecord>,
        settings: OvertimeSettings,
        year: Int,
        month: Int
    ): Double {
        val prefix = String.format(Locale.getDefault(), "%04d-%02d", year, month)
        return attendanceList
            .filter { it.date.startsWith(prefix) }
            .sumOf { OvertimeCalculator.calculateOvertime(it, settings).estimatedPay }
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
            appendLine("🏦 五险一金（个人）:")
            appendLine("  养老: ${Formatter.formatMoney(report.insurance.pension)}")
            appendLine("  医疗: ${Formatter.formatMoney(report.insurance.medical)}")
            appendLine("  失业: ${Formatter.formatMoney(report.insurance.unemployment)}")
            appendLine("  公积金: ${Formatter.formatMoney(report.insurance.housingFund)}")
            appendLine("  合计: ${Formatter.formatMoney(report.insurance.total)}")
            appendLine()
            appendLine("🧾 个税（累计预扣）:")
            appendLine("  本月应纳税所得额: ${Formatter.formatMoney(report.monthlyTaxableIncome)}")
            appendLine("  累计应纳税所得额: ${Formatter.formatMoney(report.cumulativeTaxableIncome)}")
            appendLine("  本月预扣个税: ${Formatter.formatMoney(report.incomeTax)}")
            appendLine()
            appendLine("📋 总计:")
            appendLine("  总加班时长: ${Formatter.formatHours(report.totalOvertimeHours)}")
            appendLine("  总加点时长: ${Formatter.formatHours(report.totalExtraHours)}")
            appendLine("  加班总工资: ${Formatter.formatMoney(report.totalOvertimePay)}")
            appendLine()
            appendLine("💵 应发合计: ${Formatter.formatMoney(report.totalSalary)}")
            appendLine("💰 实发工资: ${Formatter.formatMoney(report.netSalary)}")
            appendLine("=".repeat(40))
        }
    }

    private fun round2(value: Double): Double = round(value * 100.0) / 100.0
}
