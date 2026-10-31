package com.example.personalovertimerecord.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import com.example.personalovertimerecord.OvertimeApplication
import com.example.personalovertimerecord.R
import com.example.personalovertimerecord.data.OvertimeRecord
import com.example.personalovertimerecord.data.OvertimeSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CSV导出工具类
 * 用于生成Excel兼容的CSV文件
 *
 * 安全说明：
 * - 加密导出时先在内存中完成 AES 加密再直接写入 ".enc" 文件，
 *   不再“先写明文文件再加密删除”，杜绝中途崩溃导致的明文残留；
 * - 非加密导出固定以 UTF-8（含 BOM）写盘，保证 Excel 打开中文不乱码。
 */
object CsvExporter {

    private fun str(@StringRes id: Int, vararg args: Any): String =
        OvertimeApplication.getInstance().getString(id, *args)

    /** 加班类型的本地化展示名称（仅用于人工阅读的报表列，导入逻辑仍按中文匹配） */
    private fun dayTypeDisplay(dayType: String): String {
        return when (dayTypeLabel(dayType)) {
            "平时" -> str(R.string.common_day_workday)
            "周末" -> str(R.string.common_day_weekend)
            "法定节假日" -> str(R.string.common_day_holiday)
            else -> dayType
        }
    }

    /**
     * 导出考勤记录为CSV文件
     * @param context 上下文
     * @param records 考勤记录列表
     * @param settings 考勤设置
     * @param password 加密密码（可选，为空则不加密）
     * @return 生成的CSV文件（加密时返回加密文件）
     */
    fun exportToCsv(
        context: Context,
        records: List<OvertimeRecord>,
        settings: OvertimeSettings,
        password: String? = null
    ): File {
        val fileName = str(
            R.string.csv_file_name_fmt,
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        )
        val content = buildString {
            // UTF-8 BOM，解决Excel打开CSV中文乱码问题
            append("\uFEFF")

            // 标题行与数据行统一由 CsvCodec 标准契约构造，保证"导出→再导入"不丢字段
            append(CSV_HEADER)
            append('\n')
            for (record in records) {
                append(buildCsvDataRow(record))
            }

            // 写入汇总行
            append('\n')
            append(str(R.string.csv_summary_title)).append('\n')

            val totalOvertime = records.sumByDouble { it.overtimeHours }
            val totalPay = records.sumByDouble { it.totalPay }
            val normalDays = records.count { dayTypeLabel(it.dayType) == "平时" }
            val weekendDays = records.count { dayTypeLabel(it.dayType) == "周末" }
            val holidayDays = records.count { dayTypeLabel(it.dayType) == "法定节假日" }

            append(str(R.string.csv_total_hours)).append(',')
                .append(String.format("%.1f", totalOvertime)).append(str(R.string.csv_unit_hours)).append('\n')
            append(str(R.string.csv_total_pay)).append(',')
                .append(String.format("%.2f", totalPay)).append(str(R.string.csv_unit_yuan)).append('\n')
            append(str(R.string.csv_normal_days)).append(',').append(normalDays).append('\n')
            append(str(R.string.csv_weekend_days)).append(',').append(weekendDays).append('\n')
            append(str(R.string.csv_holiday_days)).append(',').append(holidayDays).append('\n')

            // 写入生成时间
            val generateTime = SimpleDateFormat(str(R.string.pdf_datetime_pattern), Locale.getDefault()).format(Date())
            append('\n')
            append(str(R.string.csv_generated_at)).append(',').append(generateTime).append('\n')
        }

        return writeExportFile(context, fileName, content, password)
    }

    /**
     * 导出月度汇总CSV
     * @param context 上下文
     * @param year 年份
     * @param month 月份
     * @param records 考勤记录列表
     * @param settings 考勤设置
     * @param password 加密密码（可选，为空则不加密）
     * @return 生成的CSV文件
     */
    fun exportMonthlySummary(
        context: Context,
        year: Int,
        month: Int,
        records: List<OvertimeRecord>,
        settings: OvertimeSettings,
        password: String? = null
    ): File {
        val fileName = str(
            R.string.csv_month_summary_file_name_fmt,
            year,
            month,
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        )
        val content = buildString {
            // UTF-8 BOM
            append("\uFEFF")

            // 写入标题
            append(str(R.string.csv_month_summary_title, year, month)).append("\n\n")

            // 基本设置信息
            append(str(R.string.csv_basic_settings)).append('\n')
            append(str(R.string.csv_work_start)).append(',').append(settings.workStartTime).append('\n')
            append(str(R.string.csv_work_end)).append(',').append(settings.workEndTime).append('\n')
            append(str(R.string.csv_normal_rate)).append(',').append(settings.overtimeRateNormal).append('\n')
            append(str(R.string.csv_weekend_rate)).append(',').append(settings.overtimeRateWeekend).append('\n')
            append(str(R.string.csv_holiday_rate)).append(',').append(settings.overtimeRateHoliday).append('\n')
            append(str(R.string.csv_base_salary)).append(',').append(settings.baseSalary).append(str(R.string.csv_unit_yuan)).append('\n')
            append(str(R.string.csv_fixed_bonus)).append(',').append(settings.fixedBonus).append(str(R.string.csv_unit_yuan)).append('\n')
            append(str(R.string.csv_monthly_days)).append(',').append(settings.monthlyWorkDays).append(str(R.string.csv_unit_days)).append('\n')
            append(str(R.string.csv_daily_hours)).append(',').append(settings.dailyWorkHours).append(str(R.string.csv_unit_hours)).append("\n\n")

            // 写入明细
            append(str(R.string.csv_detail_title)).append('\n')
            append(str(R.string.csv_detail_header)).append('\n')

            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val dayOfWeekFormat = SimpleDateFormat("E", Locale.getDefault())

            for (record in records) {
                val date = dateFormat.parse(record.date) ?: Date()
                val dayOfWeek = dayOfWeekFormat.format(date)

                append(escapeCsvField(record.date)).append(',')
                append(escapeCsvField(dayOfWeek)).append(',')
                append(escapeCsvField(record.checkInTime ?: "--")).append(',')
                append(escapeCsvField(record.checkOutTime ?: "--")).append(',')
                append(String.format("%.1f", record.overtimeHours)).append(',')
                append(escapeCsvField(dayTypeDisplay(record.dayType))).append(',')
                append(String.format("%.2f", record.totalPay)).append(',')
                append(escapeCsvField(record.note ?: "")).append('\n')
            }

            // 写入汇总
            append('\n')
            append(str(R.string.csv_summary_title)).append('\n')

            val totalOvertime = records.sumByDouble { it.overtimeHours }
            val totalPay = records.sumByDouble { it.totalPay }
            val normalDays = records.count { dayTypeLabel(it.dayType) == "平时" }
            val weekendDays = records.count { dayTypeLabel(it.dayType) == "周末" }
            val holidayDays = records.count { dayTypeLabel(it.dayType) == "法定节假日" }
            val normalHours = records.filter { dayTypeLabel(it.dayType) == "平时" }.sumByDouble { it.overtimeHours }
            val weekendHours = records.filter { dayTypeLabel(it.dayType) == "周末" }.sumByDouble { it.overtimeHours }
            val holidayHours = records.filter { dayTypeLabel(it.dayType) == "法定节假日" }.sumByDouble { it.overtimeHours }

            append(str(R.string.csv_total_hours)).append(',')
                .append(String.format("%.1f", totalOvertime)).append(str(R.string.csv_unit_hours)).append('\n')
            append(str(R.string.csv_total_pay)).append(',')
                .append(String.format("%.2f", totalPay)).append(str(R.string.csv_unit_yuan)).append('\n')
            append(str(R.string.csv_normal_combined)).append(',').append(normalDays).append(str(R.string.csv_unit_days)).append(',')
                .append(String.format("%.1f", normalHours)).append(str(R.string.csv_unit_hours)).append('\n')
            append(str(R.string.csv_weekend_combined)).append(',').append(weekendDays).append(str(R.string.csv_unit_days)).append(',')
                .append(String.format("%.1f", weekendHours)).append(str(R.string.csv_unit_hours)).append('\n')
            append(str(R.string.csv_holiday_combined)).append(',').append(holidayDays).append(str(R.string.csv_unit_days)).append(',')
                .append(String.format("%.1f", holidayHours)).append(str(R.string.csv_unit_hours)).append('\n')
            append(str(R.string.csv_valid_workdays)).append(',').append(normalDays).append(str(R.string.csv_unit_days)).append('\n')

            // 写入生成时间
            val generateTime = SimpleDateFormat(str(R.string.pdf_datetime_pattern), Locale.getDefault()).format(Date())
            append('\n')
            append(str(R.string.csv_generated_at)).append(',').append(generateTime).append('\n')
        }

        return writeExportFile(context, fileName, content, password)
    }

    /**
     * 统一落盘：
     * - 设置密码时直接在内存中加密后写入 "$fileName.enc"，全程不落明文；
     * - 未设置密码时以 UTF-8（含 BOM）写入明文文件。
     */
    private fun writeExportFile(
        context: Context,
        fileName: String,
        content: String,
        password: String?
    ): File {
        val dir = context.getExternalFilesDir(null)
        return if (!password.isNullOrBlank()) {
            val encryptedBytes = EncryptionUtils.encrypt(content.toByteArray(Charsets.UTF_8), password)
            val encryptedFile = File(dir, "$fileName.enc")
            encryptedFile.writeBytes(encryptedBytes)
            encryptedFile
        } else {
            val file = File(dir, fileName)
            file.writeBytes(content.toByteArray(Charsets.UTF_8))
            file
        }
    }

    /**
     * 获取文件的分享Intent
     */
    fun getShareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = when {
                file.name.endsWith(".pdf") -> "application/pdf"
                file.name.endsWith(".csv") -> "text/csv"
                else -> "*/*"
            }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
