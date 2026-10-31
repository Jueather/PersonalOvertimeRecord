package com.example.personalovertimerecord

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.personalovertimerecord.data.DayType
import com.example.personalovertimerecord.data.OvertimeRecord
import com.example.personalovertimerecord.data.OvertimeSettings
import com.example.personalovertimerecord.data.SettingsManager
import com.example.personalovertimerecord.databinding.ActivityReportBinding
import com.example.personalovertimerecord.utils.CsvExporter
import com.example.personalovertimerecord.utils.Formatter
import com.example.personalovertimerecord.utils.OvertimeCalculator
import com.example.personalovertimerecord.utils.PdfExporter
import com.example.personalovertimerecord.utils.SalaryCalculator
import com.example.personalovertimerecord.viewmodel.AttendanceViewModel
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

class ReportActivity : AppCompatActivity() {
    
    private val viewModel: AttendanceViewModel by viewModels()
    
    private lateinit var binding: ActivityReportBinding
    private lateinit var settingsManager: SettingsManager
    private lateinit var settings: OvertimeSettings
    private var currentYear = Calendar.getInstance().get(Calendar.YEAR)
    private var currentMonth = Calendar.getInstance().get(Calendar.MONTH) + 1
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        settingsManager = SettingsManager(this)
        settings = settingsManager.getSettings()
        
        clearOldStorageData()
        
        setupToolbar()
        observeViewModel()
    }
    
    private fun clearOldStorageData() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val hasClearedOldData = prefs.getBoolean("cleared_old_data", false)
        
        if (!hasClearedOldData) {
            val oldPrefs = getSharedPreferences("attendance_prefs", MODE_PRIVATE)
            oldPrefs.edit().clear().apply()
            prefs.edit().putBoolean("cleared_old_data", true).apply()
        }
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.tvYear.text = getString(R.string.main_month_year, currentYear, currentMonth)
        
        // 上个月：跨年时回退到上一年的12月
        binding.btnPrevMonth.setOnClickListener {
            currentMonth--
            if (currentMonth < 1) {
                currentMonth = 12
                currentYear--
            }
            refreshReport()
        }
        
        // 下个月：跨年时前进到下一年的1月
        binding.btnNextMonth.setOnClickListener {
            currentMonth++
            if (currentMonth > 12) {
                currentMonth = 1
                currentYear++
            }
            refreshReport()
        }
        
        // 导出按钮点击事件
        binding.btnExport.setOnClickListener {
            showExportDialog()
        }
    }
    
    /**
     * 刷新当前选中月份的报表
     */
    private fun refreshReport() {
        binding.tvYear.text = getString(R.string.main_month_year, currentYear, currentMonth)
        val attendanceList = viewModel.allAttendance.value ?: emptyList()
        updateReport(attendanceList)
    }
    
    private fun showExportDialog() {
        val options = arrayOf(
            getString(R.string.report_export_pdf),
            getString(R.string.report_export_csv),
            getString(R.string.report_export_payslip)
        )
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.report_export))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> exportToPdf()
                    1 -> exportToCsv()
                    2 -> exportPayslip()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    /**
     * 导出当前月份工资条 PDF（应发、五险一金、个税、实发明细）
     */
    private fun exportPayslip() {
        binding.btnExport.isEnabled = false
        binding.btnExport.text = getString(R.string.report_exporting)
        
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val records = viewModel.allAttendance.value ?: emptyList()
                    val report = SalaryCalculator.calculateMonthlySalary(
                        records,
                        settings,
                        currentYear,
                        currentMonth
                    )
                    PdfExporter.exportSalarySlip(this@ReportActivity, report)
                }
                
                Toast.makeText(this@ReportActivity, getString(R.string.report_payslip_export_success), Toast.LENGTH_SHORT).show()
                shareFile(file)
            } catch (e: Exception) {
                Toast.makeText(this@ReportActivity, getString(R.string.report_export_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
            } finally {
                binding.btnExport.isEnabled = true
                binding.btnExport.text = getString(R.string.report_export)
            }
        }
    }
    
    private fun exportToPdf() {
        binding.btnExport.isEnabled = false
        binding.btnExport.text = getString(R.string.report_exporting)
        
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val records = viewModel.allAttendance.value ?: emptyList()
                    // 仅导出当前选中月份的数据（此前导出的是全年数据）
                    val monthPrefix = String.format(Locale.getDefault(), "%04d-%02d", currentYear, currentMonth)
                    val overtimeRecords = records
                        .filter { it.date.startsWith(monthPrefix) }
                        .map { it.toExportRecord() }
                    
                    PdfExporter.exportToPdf(
                        this@ReportActivity,
                        overtimeRecords,
                        settings,
                        getString(R.string.report_attendance_report_name, currentYear, currentMonth)
                    )
                }
                
                Toast.makeText(this@ReportActivity, getString(R.string.report_pdf_export_success), Toast.LENGTH_SHORT).show()
                shareFile(file)
            } catch (e: Exception) {
                Toast.makeText(this@ReportActivity, getString(R.string.report_export_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
            } finally {
                binding.btnExport.isEnabled = true
                binding.btnExport.text = getString(R.string.report_export)
            }
        }
    }
    
    private fun exportToCsv() {
        binding.btnExport.isEnabled = false
        binding.btnExport.text = getString(R.string.report_exporting)
        
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val records = viewModel.allAttendance.value ?: emptyList()
                    // 与 PDF 导出口径一致：仅导出当前选中月份的数据
                    val monthPrefix = String.format(Locale.getDefault(), "%04d-%02d", currentYear, currentMonth)
                    val overtimeRecords = records
                        .filter { it.date.startsWith(monthPrefix) }
                        .map { it.toExportRecord() }
                    
                    // 按"导出加密"设置对 CSV 加密（启用时生成 .enc 文件，原始文件删除）
                    val password = if (settings.exportEncryptionEnabled) settings.exportPassword else null
                    CsvExporter.exportMonthlySummary(
                        this@ReportActivity,
                        currentYear,
                        currentMonth,
                        overtimeRecords,
                        settings,
                        password
                    )
                }
                
                Toast.makeText(this@ReportActivity, getString(R.string.report_csv_export_success), Toast.LENGTH_SHORT).show()
                shareFile(file)
            } catch (e: Exception) {
                Toast.makeText(this@ReportActivity, getString(R.string.report_export_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
            } finally {
                binding.btnExport.isEnabled = true
                binding.btnExport.text = getString(R.string.report_export)
            }
        }
    }
    
    private fun shareFile(file: java.io.File) {
        try {
            val shareIntent = CsvExporter.getShareIntent(this, file)
            startActivity(Intent.createChooser(shareIntent, getString(R.string.report_share_file)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.report_share_failed), Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun observeViewModel() {
        viewModel.allAttendance.observe(this) { attendanceList ->
            updateReport(attendanceList)
        }
    }
    
    private fun updateReport(attendanceList: List<OvertimeRecord>) {
        // 单次遍历分组，供各图表/统计复用，避免每次刷新多次全表扫描
        val monthPrefix = String.format(Locale.getDefault(), "%04d-%02d", currentYear, currentMonth)
        val yearPrefix = "$currentYear-"
        val monthRecords = attendanceList.filter { it.date.startsWith(monthPrefix) }
        val yearRecords = attendanceList.filter { it.date.startsWith(yearPrefix) }
        // "yyyy-MM" -> 当月记录，柱状图/折线图直接查表
        val byMonth: Map<String, List<OvertimeRecord>> = yearRecords.groupBy { it.date.substring(0, 7) }
        
        updateMonthlyOverview(monthRecords)
        updateSalaryOverview(attendanceList)
        setupBarChart(byMonth)
        setupPieChart(monthRecords)
        setupLineChart(byMonth)
    }
    
    private fun updateMonthlyOverview(monthRecords: List<OvertimeRecord>) {
        var totalHours = 0.0
        var totalPay = 0.0
        val overtimeDays = mutableSetOf<String>()
        
        monthRecords.forEach { record ->
            val result = OvertimeCalculator.calculateOvertime(record, settings)
            totalHours += result.overtimeHours + result.extraHours
            totalPay += result.estimatedPay
            // 有加班或加点的日期计入加班天数
            if (result.overtimeHours > 0 || result.extraHours > 0) {
                overtimeDays.add(record.date)
            }
        }
        
        binding.tvTotalDays.text = getString(R.string.report_days_count, overtimeDays.size)
        binding.tvTotalHours.text = Formatter.formatHours(totalHours)
        binding.tvTotalPay.text = Formatter.formatMoney(totalPay)
    }
    
    private fun updateSalaryOverview(attendanceList: List<OvertimeRecord>) {
        val report = SalaryCalculator.calculateMonthlySalary(
            attendanceList,
            settings,
            currentYear,
            currentMonth
        )
        
        binding.tvBaseSalary.text = Formatter.formatMoney(report.baseSalary)
        binding.tvPerformance.text = Formatter.formatMoney(report.performanceBonus)
        binding.tvFixedBonus.text = Formatter.formatMoney(report.fixedBonus)
        binding.tvOvertimePay.text = Formatter.formatMoney(report.totalOvertimePay)
        
        // 应发 → 五险一金 / 个税 → 实发
        binding.tvGrossSalary.text = Formatter.formatMoney(report.totalSalary)
        binding.tvInsuranceTotal.text = "-" + Formatter.formatMoney(report.insurance.total)
        binding.tvIncomeTax.text = "-" + Formatter.formatMoney(report.incomeTax)
        binding.tvTotalSalary.text = Formatter.formatMoney(report.netSalary)
        
        // 工资条明细
        binding.tvSocialBase.text = Formatter.formatMoney(report.insurance.socialInsuranceBase)
        binding.tvHousingFundBase.text = Formatter.formatMoney(report.insurance.housingFundBase)
        binding.tvPension.text = "-" + Formatter.formatMoney(report.insurance.pension)
        binding.tvMedical.text = "-" + Formatter.formatMoney(report.insurance.medical)
        binding.tvUnemployment.text = "-" + Formatter.formatMoney(report.insurance.unemployment)
        binding.tvHousingFund.text = "-" + Formatter.formatMoney(report.insurance.housingFund)
        binding.tvSpecialDeduction.text = Formatter.formatMoney(report.specialDeduction)
        binding.tvMonthlyTaxable.text = Formatter.formatMoney(report.monthlyTaxableIncome)
        binding.tvCumulativeTaxable.text = Formatter.formatMoney(report.cumulativeTaxableIncome)
    }
    
    private fun setupBarChart(byMonth: Map<String, List<OvertimeRecord>>) {
        val monthLabels = mutableListOf<String>()
        val entries = mutableListOf<BarEntry>()
        
        for (month in 1..12) {
            val monthStr = getString(R.string.report_month_axis, month)
            val monthKey = String.format(Locale.getDefault(), "%04d-%02d", currentYear, month)
            val monthRecords = byMonth[monthKey] ?: emptyList()
            
            var overtimeHours = 0f
            var extraHours = 0f
            monthRecords.forEach { record ->
                if (record.overtimeHours >= 0) {
                    overtimeHours += record.overtimeHours.toFloat()
                }
                if (record.extraHours >= 0) {
                    extraHours += record.extraHours.toFloat()
                }
            }
            
            monthLabels.add(monthStr)
            // 堆叠柱状图：每个月份一根柱子，下半段为"加班"，上半段为"加点"
            entries.add(BarEntry((month - 1).toFloat(), floatArrayOf(overtimeHours, extraHours)))
        }
        
        val dataSet = BarDataSet(entries, getString(R.string.report_hours_legend)).apply {
            // 第一段(加班)紫色，第二段(加点)橙色
            colors = listOf(
                android.graphics.Color.parseColor("#6200EE"),
                android.graphics.Color.parseColor("#FF9800")
            )
            valueTextSize = 8f
            setDrawValues(false)
            stackLabels = arrayOf(
                getString(R.string.common_overtime),
                getString(R.string.common_extra_hours)
            )
        }
        
        binding.barChart.apply {
            data = BarData(dataSet)
            description.isEnabled = false
            xAxis.valueFormatter = IndexAxisValueFormatter(monthLabels)
            xAxis.position = com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            axisLeft.axisMinimum = 0f
            legend.isEnabled = true
            invalidate()
        }
    }
    
    private fun setupPieChart(monthRecords: List<OvertimeRecord>) {
        // 饼图按当前选中月份统计，形成月度加班类型分布（记录已按月份过滤）
        var normalHours = 0f
        var weekendHours = 0f
        var holidayHours = 0f
        
        monthRecords.forEach { record ->
            // 处理默认值：负数表示未设置，视为0
            val overtimeHours = if (record.overtimeHours >= 0) record.overtimeHours else 0.0
            val extraHours = if (record.extraHours >= 0) record.extraHours else 0.0
            val totalHours = overtimeHours + extraHours

            // 以登记内容分类（加点→工作日，加班→周末/节假日），与金额计算口径一致
            val dayType = OvertimeCalculator.effectiveDayType(record.date, overtimeHours, extraHours)

            when (dayType) {
                DayType.WORKDAY -> normalHours += totalHours.toFloat()
                DayType.WEEKEND -> weekendHours += totalHours.toFloat()
                DayType.HOLIDAY -> holidayHours += totalHours.toFloat()
            }
        }
        
        val entries = mutableListOf<PieEntry>()
        val colors = mutableListOf<Int>()
        
        if (normalHours > 0) {
            entries.add(PieEntry(normalHours, getString(R.string.workday)))
            colors.add(android.graphics.Color.parseColor("#2196F3"))
        }
        if (weekendHours > 0) {
            entries.add(PieEntry(weekendHours, getString(R.string.weekend)))
            colors.add(android.graphics.Color.parseColor("#4CAF50"))
        }
        if (holidayHours > 0) {
            entries.add(PieEntry(holidayHours, getString(R.string.holiday)))
            colors.add(android.graphics.Color.parseColor("#FF9800"))
        }
        
        if (entries.isNotEmpty()) {
            val dataSet = PieDataSet(entries, "").apply {
                this.colors = colors
                valueTextSize = 12f
                valueTextColor = android.graphics.Color.WHITE
            }
            
            binding.pieChart.apply {
                data = PieData(dataSet)
                description.isEnabled = false
                legend.isEnabled = true
                invalidate()
            }
        }
    }
    
    private fun setupLineChart(byMonth: Map<String, List<OvertimeRecord>>) {
        val monthData = mutableListOf<Pair<String, Float>>()
        for (month in 1..12) {
            val monthStr = getString(R.string.report_month_axis, month)
            val monthKey = String.format(Locale.getDefault(), "%04d-%02d", currentYear, month)
            val monthRecords = byMonth[monthKey] ?: emptyList()
            
            var totalPay = 0f
            monthRecords.forEach { record ->
                val result = OvertimeCalculator.calculateOvertime(record, settings)
                totalPay += result.estimatedPay.toFloat()
            }
            
            monthData.add(Pair(monthStr, totalPay))
        }
        
        val entries = monthData.mapIndexed { index, (_, pay) ->
            Entry(index.toFloat(), pay)
        }
        
        val dataSet = LineDataSet(entries, getString(R.string.common_overtime_wage)).apply {
            color = android.graphics.Color.parseColor("#4CAF50")
            lineWidth = 2f
            setDrawCircles(true)
            circleRadius = 4f
            setCircleColor(android.graphics.Color.parseColor("#4CAF50"))
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }
        
        binding.lineChart.apply {
            data = LineData(dataSet)
            description.isEnabled = false
            xAxis.valueFormatter = IndexAxisValueFormatter(monthData.map { it.first })
            xAxis.position = com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            axisLeft.axisMinimum = 0f
            legend.isEnabled = false
            invalidate()
        }
    }
    
    /**
     * 将记录归一化为导出用数据（PDF/CSV 共用，保证口径一致）：
     * 把 -1 哨兵值（未手工设置）归一为 0，并填充 dayType / totalPay 供导出展示。
     */
    private fun OvertimeRecord.toExportRecord(): OvertimeRecord {
        val overtime = if (overtimeHours >= 0) overtimeHours else 0.0
        val extra = if (extraHours >= 0) extraHours else 0.0
        return copy(
            overtimeHours = overtime,
            extraHours = extra,
            dayType = OvertimeCalculator.effectiveDayType(date, overtime, extra).name,
            totalPay = OvertimeCalculator.calculateOvertime(this, settings).estimatedPay
        )
    }
}
