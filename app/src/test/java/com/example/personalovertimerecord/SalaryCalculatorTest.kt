package com.example.personalovertimerecord

import com.example.personalovertimerecord.data.OvertimeRecord
import com.example.personalovertimerecord.data.OvertimeSettings
import com.example.personalovertimerecord.utils.SalaryCalculator
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 五险一金与累计预扣个税单元测试。
 */
class SalaryCalculatorTest {

    private val settings = OvertimeSettings(
        baseSalary = 10_000.0,
        performancePercent = 0.0,
        socialInsuranceBase = 10_000.0,
        pensionRate = 8.0,
        medicalRate = 2.0,
        unemploymentRate = 0.5,
        housingFundBase = 10_000.0,
        housingFundRate = 12.0,
        specialDeduction = 0.0
    )

    @Test
    fun testCalculateInsurance_RatesApplied() {
        val insurance = SalaryCalculator.calculateInsurance(settings)

        assertEquals(10_000.0, insurance.socialInsuranceBase, 0.01)
        assertEquals(10_000.0, insurance.housingFundBase, 0.01)
        assertEquals(800.0, insurance.pension, 0.01)
        assertEquals(200.0, insurance.medical, 0.01)
        assertEquals(50.0, insurance.unemployment, 0.01)
        assertEquals(1_200.0, insurance.housingFund, 0.01)
        assertEquals(2_250.0, insurance.total, 0.01)
    }

    @Test
    fun testCalculateInsurance_BaseFallbackToSalary() {
        val fallback = settings.copy(
            baseSalary = 8_000.0,
            socialInsuranceBase = 0.0,
            housingFundBase = 0.0
        )

        val insurance = SalaryCalculator.calculateInsurance(fallback)

        assertEquals(8_000.0, insurance.socialInsuranceBase, 0.01)
        assertEquals(8_000.0, insurance.housingFundBase, 0.01)
        assertEquals(640.0, insurance.pension, 0.01)
        assertEquals(960.0, insurance.housingFund, 0.01)
    }

    @Test
    fun testCumulativeTaxOf_BracketBoundaries() {
        assertEquals(0.0, SalaryCalculator.cumulativeTaxOf(0.0), 0.01)
        assertEquals(1_080.0, SalaryCalculator.cumulativeTaxOf(36_000.0), 0.01)
        assertEquals(7_480.0, SalaryCalculator.cumulativeTaxOf(100_000.0), 0.01)
        assertEquals(23_080.0, SalaryCalculator.cumulativeTaxOf(200_000.0), 0.01)
    }

    @Test
    fun testMonthlyIncomeTax_CumulativeWithholding() {
        val empty = emptyList<OvertimeRecord>()

        val january = SalaryCalculator.calculateMonthlySalary(empty, settings, 2026, 1)
        // 10000 - 2250（五险一金）- 5000（起征点）= 2750 → 3% = 82.5
        assertEquals(2_750.0, january.monthlyTaxableIncome, 0.01)
        assertEquals(82.5, january.incomeTax, 0.01)
        assertEquals(10_000.0 - 2_250.0 - 82.5, january.netSalary, 0.01)

        val february = SalaryCalculator.calculateMonthlySalary(empty, settings, 2026, 2)
        // 累计：20000 - 14500 = 5500 → 165；本月 = 165 - 82.5 = 82.5
        assertEquals(5_500.0, february.cumulativeTaxableIncome, 0.01)
        assertEquals(82.5, february.incomeTax, 0.01)
    }

    @Test
    fun testMonthlyIncomeTax_SpecialDeduction() {
        val withDeduction = settings.copy(specialDeduction = 1_000.0)

        val january = SalaryCalculator.calculateMonthlySalary(
            emptyList(),
            withDeduction,
            2026,
            1
        )

        // 10000 - 2250 - 5000 - 1000 = 1750 → 3% = 52.5
        assertEquals(1_750.0, january.monthlyTaxableIncome, 0.01)
        assertEquals(52.5, january.incomeTax, 0.01)
    }

    @Test
    fun testOvertimeIncomeIncludedInTaxableIncome() {
        // 2026-01-05 是工作日，登记加班 8h 按周末倍率计算（登记即分类）
        val records = listOf(
            OvertimeRecord(date = "2026-01-05", overtimeHours = 8.0, extraHours = -1.0)
        )
        val hourlyWage = 10_000.0 / (21.75 * 8.0)
        val overtimePay = 8.0 * hourlyWage * 2.0

        val january = SalaryCalculator.calculateMonthlySalary(records, settings, 2026, 1)

        assertEquals(10_000.0 + overtimePay, january.totalSalary, 0.01)
        assertEquals(
            10_000.0 + overtimePay - 2_250.0 - 5_000.0,
            january.monthlyTaxableIncome,
            0.01
        )
    }
}
