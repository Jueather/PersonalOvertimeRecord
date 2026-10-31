package com.example.personalovertimerecord.utils

import android.content.Context
import androidx.annotation.StringRes
import com.example.personalovertimerecord.OvertimeApplication
import com.example.personalovertimerecord.R
import com.example.personalovertimerecord.data.db.AttendanceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * CSV导入工具类
 * 解析统一走 [CsvCodec]（与导出共用同一套列头/转义/数字契约），
 * 支持引号包裹字段内的换行（字段内换行的备注不再断行错位）。
 */
object CsvImporter {

    private fun str(@StringRes id: Int, vararg args: Any): String =
        OvertimeApplication.getInstance().getString(id, *args)

    data class ImportResult(
        val success: Boolean,
        val importedCount: Int = 0,
        val failedCount: Int = 0,
        val message: String = "",
        val errors: List<String> = emptyList()
    )

    /**
     * 从CSV输入流导入数据
     * 规则：
     * - 首行必须是有效表头（含“日期/date”列），列序不限；表头兼容旧版“加班时长(小时)”等写法；
     * - 若首行首列直接是日期，则按无表头简单顺序解析：日期,上班时间,下班时间,加班时长,额外时长,备注；
     * - 其它文件（如月度汇总报表）会整体中止，避免把非明细行当数据导入。
     */
    suspend fun importFromCsv(context: Context, inputStream: InputStream): ImportResult {
        val errors = mutableListOf<String>()
        var importedCount = 0
        var failedCount = 0
        var aborted = false

        try {
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                var headers: List<String>? = null
                var headerlessMode = false
                var firstRecord = true
                var recordIndex = 0
                val entities = mutableListOf<AttendanceEntity>()

                while (!aborted) {
                    val raw = readNextCsvRecord(reader) ?: break
                    if (raw.isBlank()) continue

                    // 首条记录去除 UTF-8 BOM
                    val line = if (firstRecord) raw.removePrefix("\uFEFF") else raw
                    firstRecord = false
                    recordIndex++

                    val values = parseCsvLine(line)

                    // 首行决定模式：有效表头 或 无表头（首列是日期）或 无法识别（中止）
                    if (headers == null && !headerlessMode) {
                        val normalized = normalizeHeaders(values)
                        when {
                            isValidHeader(normalized) -> {
                                headers = normalized
                                continue
                            }

                            isDateLeadingDataRow(values) -> headerlessMode = true

                            else -> {
                                aborted = true
                                errors.add(str(R.string.csv_import_unrecognized_file))
                            }
                        }
                    }
                    if (aborted) break

                    val record = if (headerlessMode) {
                        parseCsvRecord(values, null)
                    } else {
                        parseCsvRecord(values, headers)
                    }

                    if (record != null) {
                        entities.add(AttendanceEntity.fromRecord(record))
                    } else {
                        failedCount++
                        errors.add(str(R.string.csv_import_row_error, recordIndex))
                    }
                }

                if (!aborted && entities.isNotEmpty()) {
                    val toInsert = withContext(Dispatchers.IO) {
                        val app = context.applicationContext as com.example.personalovertimerecord.OvertimeApplication
                        val attendanceDao = app.database.attendanceDao()
                        // 按日期去重：跳过库中已存在日期与文件内重复日期，
                        // 避免插入重复记录破坏"按日期唯一"的同步/查询假设
                        val existingDates = attendanceDao.getAllRecordsSync().map { it.date }.toSet()
                        val seenDates = mutableSetOf<String>()
                        entities.filter { entity ->
                            entity.date !in existingDates && seenDates.add(entity.date)
                        }
                    }
                    if (toInsert.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            val app = context.applicationContext as com.example.personalovertimerecord.OvertimeApplication
                            app.database.attendanceDao().insertAll(toInsert)
                        }
                    }
                    importedCount = toInsert.size
                    val skippedCount = entities.size - toInsert.size
                    if (skippedCount > 0) {
                        errors.add(str(R.string.csv_import_skipped_existing, skippedCount))
                    }
                }
            }

            val success = !aborted && failedCount == 0
            val message = when {
                aborted -> str(
                    R.string.csv_import_aborted_fmt,
                    errors.firstOrNull() ?: str(R.string.csv_import_unrecognized)
                )
                success -> str(R.string.csv_import_success, importedCount)
                importedCount > 0 -> str(R.string.csv_import_partial, importedCount, failedCount)
                else -> str(R.string.csv_import_failed_all)
            }

            return ImportResult(
                success = success,
                importedCount = importedCount,
                failedCount = failedCount,
                message = message,
                errors = errors.take(10) // 最多返回10条错误
            )
        } catch (e: Exception) {
            return ImportResult(
                success = false,
                message = str(R.string.csv_import_failed_fmt, e.message ?: ""),
                errors = listOf(e.message ?: str(R.string.csv_import_unknown_error))
            )
        }
    }

    /**
     * 读取一条完整的 CSV 逻辑记录：若当前物理行仍处于未闭合的引号内（如备注字段内含换行），
     * 继续读取下一物理行并拼接，直到引号闭合或文件结束。
     */
    private fun readNextCsvRecord(reader: BufferedReader): String? {
        val sb = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: break
            if (sb.isEmpty()) {
                sb.append(line)
            } else {
                sb.append('\n').append(line)
            }
            if (csvRecordClosed(sb.toString())) return sb.toString()
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    /**
     * 获取支持的CSV格式说明
     */
    fun getSupportedFormatDescription(): String {
        return str(R.string.csv_import_format_desc, CSV_HEADER)
    }
}
