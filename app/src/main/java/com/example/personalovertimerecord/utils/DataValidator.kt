package com.example.personalovertimerecord.utils

import androidx.annotation.StringRes
import com.example.personalovertimerecord.OvertimeApplication
import com.example.personalovertimerecord.R

/**
 * 数据验证工具类
 * 提供各种数据验证功能，确保数据完整性
 */
object DataValidator {

    /** 校验失败文案统一走资源，随系统语言切换；纯 JVM 单测无 Application 时回退占位串 */
    private fun str(@StringRes id: Int, vararg args: Any): String =
        try {
            OvertimeApplication.getInstance().getString(id, *args)
        } catch (e: UninitializedPropertyAccessException) {
            "validation_error_$id"
        }

    // 日期格式正则表达式
    private val DATE_PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val TIME_PATTERN = Regex("^\\d{2}:\\d{2}(:\\d{2})?$")
    
    // 加班时长范围（小时）
    private const val MIN_OVERTIME_HOURS = 0.0
    private const val MAX_OVERTIME_HOURS = 24.0
    
    // 班次时长范围（小时）
    private const val MIN_SHIFT_HOURS = 0.0
    private const val MAX_SHIFT_HOURS = 24.0

    /**
     * 验证结果
     */
    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class Invalid(override val errors: List<String>) : ValidationResult()
        
        val isValid: Boolean get() = this is Valid
        open val errors: List<String> get() = emptyList()
    }

    /**
     * 验证日期格式
     * @param allowFuture 是否允许未来日期（同步/恢复路径传 true，录入表单默认 false）
     */
    fun validateDate(date: String?, allowFuture: Boolean = false): ValidationResult {
        if (date.isNullOrBlank()) {
            return ValidationResult.Invalid(listOf(str(R.string.validate_date_required)))
        }
        
        if (!DATE_PATTERN.matches(date)) {
            return ValidationResult.Invalid(listOf(str(R.string.validate_date_format)))
        }
        
        val parts = date.split("-")
        val year = parts[0].toIntOrNull() ?: return ValidationResult.Invalid(listOf(str(R.string.validate_year_invalid)))
        val month = parts[1].toIntOrNull() ?: return ValidationResult.Invalid(listOf(str(R.string.validate_month_invalid)))
        val day = parts[2].toIntOrNull() ?: return ValidationResult.Invalid(listOf(str(R.string.validate_day_invalid)))
        
        if (month < 1 || month > 12) {
            return ValidationResult.Invalid(listOf(str(R.string.validate_month_range)))
        }
        
        if (day < 1 || day > 31) {
            return ValidationResult.Invalid(listOf(str(R.string.validate_day_range)))
        }
        
        // 检查每个月的最大天数
        val maxDay = when (month) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            2 -> if (isLeapYear(year)) 29 else 28
            else -> 31
        }
        
        if (day > maxDay) {
            return ValidationResult.Invalid(
                listOf(str(R.string.validate_date_days_in_month, year, month, maxDay))
            )
        }
        
        // 检查是否在未来（允许今天）；恢复/同步路径允许未来日期
        if (!allowFuture) {
            val today = java.time.LocalDate.now().toString()
            if (date > today) {
                return ValidationResult.Invalid(listOf(str(R.string.validate_date_future)))
            }
        }
        
        return ValidationResult.Valid
    }

    /**
     * 验证时间格式
     */
    fun validateTime(time: String?): ValidationResult {
        if (time.isNullOrBlank()) {
            return ValidationResult.Invalid(listOf(str(R.string.validate_time_required)))
        }
        
        if (!TIME_PATTERN.matches(time)) {
            return ValidationResult.Invalid(listOf(str(R.string.validate_time_format)))
        }
        
        val parts = time.split(":")
        val hour = parts[0].toIntOrNull() ?: return ValidationResult.Invalid(listOf(str(R.string.validate_hour_invalid)))
        val minute = parts[1].toIntOrNull() ?: return ValidationResult.Invalid(listOf(str(R.string.validate_minute_invalid)))
        
        if (hour < 0 || hour > 23) {
            return ValidationResult.Invalid(listOf(str(R.string.validate_hour_range)))
        }
        
        if (minute < 0 || minute > 59) {
            return ValidationResult.Invalid(listOf(str(R.string.validate_minute_range)))
        }
        
        return ValidationResult.Valid
    }

    /**
     * 验证加班时长
     */
    fun validateOvertimeHours(hours: Double?): ValidationResult {
        if (hours == null) {
            return ValidationResult.Invalid(listOf(str(R.string.validate_overtime_required)))
        }
        
        if (hours < MIN_OVERTIME_HOURS) {
            return ValidationResult.Invalid(listOf(str(R.string.validate_overtime_negative)))
        }
        
        if (hours > MAX_OVERTIME_HOURS) {
            return ValidationResult.Invalid(listOf(str(R.string.validate_overtime_exceed)))
        }
        
        // 精度检查（最多2位小数）
        if (hours != hours.toLong().toDouble() && hours.toString().split(".")[1].length > 2) {
            return ValidationResult.Invalid(listOf(str(R.string.validate_overtime_precision)))
        }
        
        return ValidationResult.Valid
    }

    /**
     * 验证班次时长
     */
    fun validateShiftHours(hours: Double?): ValidationResult {
        if (hours == null) {
            return ValidationResult.Invalid(listOf(str(R.string.validate_shift_required)))
        }
        
        if (hours < MIN_SHIFT_HOURS) {
            return ValidationResult.Invalid(listOf(str(R.string.validate_shift_negative)))
        }
        
        if (hours > MAX_SHIFT_HOURS) {
            return ValidationResult.Invalid(listOf(str(R.string.validate_shift_exceed)))
        }
        
        return ValidationResult.Valid
    }

    /**
     * 验证上班时间早于下班时间
     */
    fun validateWorkTimeRange(checkIn: String?, checkOut: String?): ValidationResult {
        val checkInResult = validateTime(checkIn)
        if (!checkInResult.isValid) {
            return checkInResult
        }
        
        val checkOutResult = validateTime(checkOut)
        if (!checkOutResult.isValid) {
            return checkOutResult
        }
        
        if (checkIn != null && checkOut != null) {
            if (checkIn >= checkOut) {
                return ValidationResult.Invalid(listOf(str(R.string.validate_work_time_order)))
            }
        }
        
        return ValidationResult.Valid
    }

    /**
     * 验证 WebDAV 配置
     */
    fun validateWebDAVConfig(
        serverUrl: String?,
        username: String?,
        password: String?
    ): ValidationResult {
        val errors = mutableListOf<String>()
        
        if (serverUrl.isNullOrBlank()) {
            errors.add(str(R.string.validate_server_required))
        } else if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            errors.add(str(R.string.validate_server_scheme))
        }
        
        if (username.isNullOrBlank()) {
            errors.add(str(R.string.validate_username_required))
        }
        
        // 密码可以为空（匿名访问）
        
        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors)
        }
    }

    /**
     * 验证设置参数
     */
    fun validateOvertimeSettings(
        workHours: Double?,
        hourlyRate: Double?,
        overtimeRate: Double?
    ): ValidationResult {
        val errors = mutableListOf<String>()
        
        val workHoursResult = validateShiftHours(workHours)
        if (!workHoursResult.isValid) {
            errors.add(
                str(R.string.validate_work_hours_prefix, workHoursResult.errors.joinToString())
            )
        }
        
        if (hourlyRate != null && hourlyRate < 0) {
            errors.add(str(R.string.validate_hourly_rate_negative))
        }
        
        if (overtimeRate != null && overtimeRate < 0) {
            errors.add(str(R.string.validate_overtime_rate_negative))
        }
        
        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors)
        }
    }

    /**
     * 综合验证考勤记录
     */
    fun validateAttendanceRecord(
        date: String?,
        checkIn: String?,
        checkOut: String?,
        overtimeHours: Double?
    ): ValidationResult {
        val errors = mutableListOf<String>()
        
        val dateResult = validateDate(date)
        if (!dateResult.isValid) {
            errors.addAll(dateResult.errors.map { str(R.string.validate_date_prefix, it) })
        }
        
        if (checkIn != null || checkOut != null) {
            val timeResult = validateWorkTimeRange(checkIn, checkOut)
            if (!timeResult.isValid) {
                errors.addAll(timeResult.errors)
            }
        }
        
        if (overtimeHours != null) {
            val overtimeResult = validateOvertimeHours(overtimeHours)
            if (!overtimeResult.isValid) {
                errors.addAll(overtimeResult.errors.map { str(R.string.validate_overtime_prefix, it) })
            }
        }
        
        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors)
        }
    }

    /**
     * 验证字符串长度
     */
    fun validateStringLength(
        value: String?,
        minLength: Int = 0,
        maxLength: Int = Int.MAX_VALUE,
        fieldName: String? = null
    ): ValidationResult {
        val name = fieldName ?: str(R.string.validate_default_field)
        if (value == null) {
            return ValidationResult.Invalid(listOf(str(R.string.validate_field_required, name)))
        }
        
        if (value.length < minLength) {
            return ValidationResult.Invalid(
                listOf(str(R.string.validate_field_min_length, name, minLength))
            )
        }
        
        if (value.length > maxLength) {
            return ValidationResult.Invalid(
                listOf(str(R.string.validate_field_max_length, name, maxLength))
            )
        }
        
        return ValidationResult.Valid
    }

    /**
     * 验证数字范围
     */
    fun validateNumberRange(
        value: Double?,
        min: Double = Double.MIN_VALUE,
        max: Double = Double.MAX_VALUE,
        fieldName: String? = null
    ): ValidationResult {
        val name = fieldName ?: str(R.string.validate_default_number)
        if (value == null) {
            return ValidationResult.Invalid(listOf(str(R.string.validate_field_required, name)))
        }
        
        if (value < min) {
            return ValidationResult.Invalid(listOf(str(R.string.validate_field_min, name, min)))
        }
        
        if (value > max) {
            return ValidationResult.Invalid(listOf(str(R.string.validate_field_max, name, max)))
        }
        
        return ValidationResult.Valid
    }

    /**
     * 判断是否为闰年
     */
    private fun isLeapYear(year: Int): Boolean {
        return (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
    }
}

/**
 * 验证结果扩展
 */
fun DataValidator.ValidationResult.getFirstError(): String? {
    return errors.firstOrNull()
}

fun DataValidator.ValidationResult.getAllErrors(): String {
    return errors.joinToString("\n")
}

inline fun <T> T.validate(validator: (T) -> DataValidator.ValidationResult): DataValidator.ValidationResult {
    return validator(this)
}

inline fun <T> T.validateOrThrow(validator: (T) -> DataValidator.ValidationResult): T {
    val result = validator(this)
    if (!result.isValid) {
        throw IllegalArgumentException(result.getAllErrors())
    }
    return this
}