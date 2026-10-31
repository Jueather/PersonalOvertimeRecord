package com.example.personalovertimerecord.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.annotation.StringRes
import com.example.personalovertimerecord.OvertimeApplication
import com.example.personalovertimerecord.R
import com.example.personalovertimerecord.data.OvertimeRecord
import com.example.personalovertimerecord.data.OvertimeSettings
import com.example.personalovertimerecord.utils.SalaryCalculator.SalaryReport
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PDF导出工具类
 * 用于生成考勤记录的PDF报告
 */
object PdfExporter {
    
    private const val PAGE_WIDTH = 595 // A4 width in points (72 dpi)
    private const val PAGE_HEIGHT = 842 // A4 height in points
    private const val MARGIN = 40f
    private const val LINE_HEIGHT = 20f

    private fun str(@StringRes id: Int, vararg args: Any): String =
        OvertimeApplication.getInstance().getString(id, *args)

    /**
     * 将 OvertimeRecord.dayType（枚举名 WORKDAY/WEEKEND/HOLIDAY 或中文）统一映射为中文标签
     * 仅用于统计计数，展示文案走 [getDayTypeDisplay]
     */
    private fun getDayTypeLabel(dayType: String): String {
        return when (dayType) {
            "WORKDAY", "平时" -> "平时"
            "WEEKEND", "周末" -> "周末"
            "HOLIDAY", "法定节假日", "法定假日" -> "法定节假日"
            else -> dayType
        }
    }

    /** 加班类型的本地化展示名称 */
    private fun getDayTypeDisplay(dayType: String): String {
        return when (getDayTypeLabel(dayType)) {
            "平时" -> str(R.string.common_day_workday)
            "周末" -> str(R.string.common_day_weekend)
            "法定节假日" -> str(R.string.common_day_holiday)
            else -> dayType
        }
    }
    
    /**
     * 导出考勤记录为PDF文件
     * @param context 上下文
     * @param records 考勤记录列表
     * @param settings 考勤设置
     * @param title 报告标题
     * @return 生成的PDF文件
     */
    fun exportToPdf(
        context: Context,
        records: List<OvertimeRecord>,
        settings: OvertimeSettings,
        title: String = str(R.string.pdf_title_default)
    ): File {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        var page = document.startPage(pageInfo)
        var canvas = page.canvas
        
        var yPosition = MARGIN
        val pageWidth = PAGE_WIDTH - 2 * MARGIN
        
        // 绘制标题
        yPosition = drawTitle(canvas, title, yPosition, pageWidth)
        
        // 绘制时间信息
        yPosition = drawInfo(canvas, yPosition, pageWidth)
        
        // 绘制表头
        yPosition = drawTableHeader(canvas, yPosition, pageWidth)
        
        // 绘制记录
        var recordCount = 0
        for (record in records) {
            recordCount++
            yPosition = drawTableRow(canvas, record, recordCount, yPosition, pageWidth)
            
            // 如果超出页面，创建新页面
            if (yPosition > PAGE_HEIGHT - MARGIN - 50) {
                document.finishPage(page)
                val newPageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, document.pages.size + 1).create()
                page = document.startPage(newPageInfo)
                canvas = page.canvas
                yPosition = MARGIN
            }
        }
        
        // 绘制汇总信息
        yPosition += LINE_HEIGHT
        yPosition = drawSummary(canvas, records, settings, yPosition, pageWidth)
        
        // 绘制页脚
        drawFooter(canvas)
        
        document.finishPage(page)
        
        // 保存PDF文件
        val fileName = str(
            R.string.pdf_file_name_fmt,
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        )
        val file = File(context.getExternalFilesDir(null), fileName)
        
        try {
            FileOutputStream(file).use { outputStream ->
                document.writeTo(outputStream)
            }
        } finally {
            document.close()
        }
        
        return file
    }
    
    /**
     * 导出月度工资条 PDF：收入明细、五险一金、个税与实发工资。
     */
    fun exportSalarySlip(context: Context, report: SalaryReport): File {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        val pageWidth = PAGE_WIDTH - 2 * MARGIN

        var yPosition = MARGIN
        yPosition = drawTitle(canvas, str(R.string.pdf_payslip_title, report.year, report.month), yPosition, pageWidth)
        yPosition = drawInfo(canvas, yPosition, pageWidth)

        yPosition = drawPayslipSection(
            canvas,
            str(R.string.pdf_payslip_income),
            listOf(
                str(R.string.report_base_salary) to formatMoney(report.baseSalary),
                str(R.string.report_performance) to formatMoney(report.performanceBonus),
                str(R.string.report_fixed_bonus) to formatMoney(report.fixedBonus),
                str(R.string.common_overtime_wage) to formatMoney(report.totalOvertimePay),
                str(R.string.report_gross_total) to formatMoney(report.totalSalary)
            ),
            yPosition
        )

        yPosition = drawPayslipSection(
            canvas,
            str(R.string.report_insurance),
            listOf(
                str(R.string.pdf_payslip_pension_base, formatMoney(report.insurance.socialInsuranceBase)) to formatMoney(report.insurance.pension),
                str(R.string.report_medical_insurance) to formatMoney(report.insurance.medical),
                str(R.string.report_unemployment_insurance) to formatMoney(report.insurance.unemployment),
                str(R.string.pdf_payslip_fund_base, formatMoney(report.insurance.housingFundBase)) to formatMoney(report.insurance.housingFund),
                str(R.string.pdf_payslip_total) to formatMoney(report.insurance.total)
            ),
            yPosition
        )

        yPosition = drawPayslipSection(
            canvas,
            str(R.string.pdf_tax_section),
            listOf(
                str(R.string.report_special_deduction) to formatMoney(report.specialDeduction),
                str(R.string.report_current_taxable) to formatMoney(report.monthlyTaxableIncome),
                str(R.string.report_cumulative_taxable) to formatMoney(report.cumulativeTaxableIncome),
                str(R.string.pdf_tax_monthly) to formatMoney(report.incomeTax)
            ),
            yPosition
        )

        yPosition = drawPayslipSection(
            canvas,
            str(R.string.report_net_salary),
            listOf(str(R.string.pdf_net_formula) to formatMoney(report.netSalary)),
            yPosition
        )

        drawFooter(canvas)
        document.finishPage(page)

        val fileName = str(
            R.string.pdf_payslip_file_name_fmt,
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        )
        val file = File(context.getExternalFilesDir(null), fileName)
        try {
            FileOutputStream(file).use { outputStream ->
                document.writeTo(outputStream)
            }
        } finally {
            document.close()
        }
        return file
    }

    private fun drawPayslipSection(
        canvas: Canvas,
        title: String,
        rows: List<Pair<String, String>>,
        y: Float
    ): Float {
        var currentY = y

        val titlePaint = Paint().apply {
            color = Color.rgb(98, 0, 238)
            textSize = 13f
            isFakeBoldText = true
            isAntiAlias = true
        }
        canvas.drawText(title, MARGIN, currentY + 14f, titlePaint)
        currentY += LINE_HEIGHT + 4f

        val labelPaint = Paint().apply {
            color = Color.BLACK
            textSize = 11f
            isAntiAlias = true
        }
        val valuePaint = Paint().apply {
            color = Color.BLACK
            textSize = 11f
            isFakeBoldText = true
            isAntiAlias = true
        }
        val rowBgPaint = Paint().apply { color = Color.rgb(248, 248, 248) }

        rows.forEachIndexed { index, (label, value) ->
            if (index % 2 == 0) {
                canvas.drawRect(MARGIN, currentY, PAGE_WIDTH - MARGIN, currentY + LINE_HEIGHT, rowBgPaint)
            }
            canvas.drawText(label, MARGIN + 6f, currentY + 14f, labelPaint)
            val valueWidth = valuePaint.measureText(value)
            canvas.drawText(value, PAGE_WIDTH - MARGIN - valueWidth - 6f, currentY + 14f, valuePaint)
            currentY += LINE_HEIGHT
        }

        return currentY + 10f
    }

    private fun formatMoney(amount: Double): String = String.format(Locale.getDefault(), "¥%.2f", amount)

    private fun drawTitle(canvas: Canvas, title: String, y: Float, width: Float): Float {
        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 24f
            isFakeBoldText = true
            isAntiAlias = true
        }
        
        val x = (PAGE_WIDTH - titlePaint.measureText(title)) / 2
        canvas.drawText(title, x, y + 24f, titlePaint)
        
        // 绘制分隔线
        val linePaint = Paint().apply {
            color = Color.GRAY
            strokeWidth = 1f
        }
        canvas.drawLine(MARGIN, y + 35f, PAGE_WIDTH - MARGIN, y + 35f, linePaint)
        
        return y + 50f
    }
    
    private fun drawInfo(canvas: Canvas, y: Float, width: Float): Float {
        val infoPaint = Paint().apply {
            color = Color.GRAY
            textSize = 10f
            isAntiAlias = true
        }
        
        val dateFormat = SimpleDateFormat(str(R.string.pdf_datetime_pattern), Locale.getDefault())
        val info = str(R.string.pdf_info_generated_at, dateFormat.format(Date()))
        canvas.drawText(info, MARGIN, y + 12f, infoPaint)
        
        return y + LINE_HEIGHT + 10f
    }
    
    private fun drawTableHeader(canvas: Canvas, y: Float, width: Float): Float {
        val headerPaint = Paint().apply {
            color = Color.WHITE
            textSize = 10f
            isFakeBoldText = true
            isAntiAlias = true
        }
        
        val bgPaint = Paint().apply {
            color = Color.rgb(98, 0, 238) // Purple
        }
        
        // 绘制表头背景
        canvas.drawRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + LINE_HEIGHT, bgPaint)
        
        // 绘制表头文字
        val columns = listOf(
            str(R.string.pdf_col_date),
            str(R.string.pdf_col_check_in),
            str(R.string.pdf_col_check_out),
            str(R.string.pdf_col_overtime),
            str(R.string.pdf_col_day_type),
            str(R.string.pdf_col_pay)
        )
        val colWidths = listOf(80f, 70f, 70f, 80f, 80f, 80f)
        var x = MARGIN + 5f
        
        for ((index, column) in columns.withIndex()) {
            canvas.drawText(column, x, y + 14f, headerPaint)
            x += colWidths.getOrElse(index) { 70f }
        }
        
        return y + LINE_HEIGHT + 5f
    }
    
    private fun drawTableRow(canvas: Canvas, record: OvertimeRecord, rowNum: Int, y: Float, width: Float): Float {
        val textPaint = Paint().apply {
            color = Color.BLACK
            textSize = 9f
            isAntiAlias = true
        }
        
        val bgPaint = Paint().apply {
            color = if (rowNum % 2 == 0) Color.rgb(245, 245, 245) else Color.WHITE
        }
        
        // 绘制行背景
        canvas.drawRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + LINE_HEIGHT, bgPaint)
        
        val dateFormat = SimpleDateFormat("MM-dd", Locale.getDefault())
        val fullDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        // 必须带时间部分解析，否则打卡时间会被丢弃，PDF 里恒显示 00:00
        val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        
        val columns = listOf(
            dateFormat.format(fullDateFormat.parse(record.date) ?: Date()),
            record.checkInTime?.let { timeFormat.format(dateTimeFormat.parse(record.date + " " + it) ?: Date()) } ?: "--:--",
            record.checkOutTime?.let { timeFormat.format(dateTimeFormat.parse(record.date + " " + it) ?: Date()) } ?: "--:--",
            str(R.string.pdf_hours_cell, record.overtimeHours),
            getDayTypeDisplay(record.dayType),
            String.format("¥%.2f", record.totalPay)
        )
        
        val colWidths = listOf(80f, 70f, 70f, 80f, 80f, 80f)
        var x = MARGIN + 5f
        
        for ((index, text) in columns.withIndex()) {
            // 加班费列右对齐
            if (index == 5) {
                val textWidth = textPaint.measureText(text)
                canvas.drawText(text, PAGE_WIDTH - MARGIN - textWidth - 5f, y + 14f, textPaint)
            } else {
                canvas.drawText(text, x, y + 14f, textPaint)
            }
            x += colWidths.getOrElse(index) { 70f }
        }
        
        return y + LINE_HEIGHT
    }
    
    private fun drawSummary(canvas: Canvas, records: List<OvertimeRecord>, settings: OvertimeSettings, y: Float, width: Float): Float {
        var currentY = y
        
        // 分隔线
        val linePaint = Paint().apply {
            color = Color.GRAY
            strokeWidth = 1f
        }
        canvas.drawLine(MARGIN, currentY, PAGE_WIDTH - MARGIN, currentY, linePaint)
        currentY += LINE_HEIGHT
        
        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 14f
            isFakeBoldText = true
            isAntiAlias = true
        }
        
        val textPaint = Paint().apply {
            color = Color.BLACK
            textSize = 11f
            isAntiAlias = true
        }
        
        canvas.drawText(str(R.string.pdf_summary_title), MARGIN, currentY + 14f, titlePaint)
        currentY += LINE_HEIGHT + 10f
        
        val totalOvertime = records.sumOf { it.overtimeHours }
        val totalPay = records.sumOf { it.totalPay }
        val normalDays = records.count { getDayTypeLabel(it.dayType) == "平时" }
        val weekendDays = records.count { getDayTypeLabel(it.dayType) == "周末" }
        val holidayDays = records.count { getDayTypeLabel(it.dayType) == "法定节假日" }
        
        val summaryItems = listOf(
            str(R.string.pdf_summary_total_hours, totalOvertime),
            str(R.string.pdf_summary_total_pay, totalPay),
            str(R.string.pdf_summary_normal, normalDays),
            str(R.string.pdf_summary_weekend, weekendDays),
            str(R.string.pdf_summary_holiday, holidayDays)
        )
        
        for (item in summaryItems) {
            canvas.drawText(item, MARGIN, currentY + 12f, textPaint)
            currentY += LINE_HEIGHT
        }
        
        return currentY
    }
    
    private fun drawFooter(canvas: Canvas) {
        val footerPaint = Paint().apply {
            color = Color.GRAY
            textSize = 8f
            isAntiAlias = true
        }
        
        val footer = str(R.string.pdf_footer)
        val x = (PAGE_WIDTH - footerPaint.measureText(footer)) / 2
        canvas.drawText(footer, x, PAGE_HEIGHT - 20f, footerPaint)
    }
}