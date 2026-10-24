package com.example.personalovertimerecord.data

import kotlin.jvm.Transient

/**
 * 加班/请假记录模型（取代已废弃的 Attendance，作为 Room 与 UI 间的唯一模型）。
 *
 * 语义说明：
 * - overtimeHours / extraHours：< 0 表示用户未手工设置（由打卡自动计算），
 *   与数据库实体 manualOvertimeHours/manualExtraHours 的 -1 哨兵值保持一致；
 * - dayType / totalPay：仅供导出/展示使用，由 OvertimeCalculator 归一化后填充。
 */
data class OvertimeRecord(
    val id: Long = 0L,
    val date: String,
    val overtimeHours: Double = -1.0,
    val extraHours: Double = -1.0,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    // 打卡时间
    val checkInTime: String? = null,
    val checkOutTime: String? = null,
    val checkInTimestamp: Long? = null,
    val checkOutTimestamp: Long? = null,
    // 请假字段
    val isLeave: Boolean = false,
    val leaveType: String? = null,
    val leaveHours: Double = 0.0,
    // 自定义工资倍率：< 0 表示未自定义（按设置中的默认倍率 1.5/2.0/3.0 计算），
    // >= 0 表示该条记录整体按此倍率计算（在添加/编辑记录对话框中手动选择）
    val customRate: Double = -1.0,
    // 班次标记：DAY=白班（默认）/ NIGHT=夜班；仅作记录与统计展示，不影响工资计算
    val shiftType: String = ShiftType.DAY.name,
    // 导出/展示用归一化字段
    val dayType: String = "平时",
    val totalPay: Double = 0.0
)

data class OvertimeSettings(
    var baseSalary: Double = 5000.0,
    var performancePercent: Double = 0.0,
    var monthlyWorkDays: Double = 21.75,
    var dailyWorkHours: Double = 8.0,
    var overtimeRateNormal: Double = 1.5,
    var overtimeRateWeekend: Double = 2.0,
    var overtimeRateHoliday: Double = 3.0,
    var workStartTime: String = "08:00",
    var workEndTime: String = "17:00",
    // ---- 五险一金与个税 ----
    // 缴费基数 <= 0 时按底薪计算，避免用户重复录入
    var socialInsuranceBase: Double = 0.0,
    var pensionRate: Double = 8.0,
    var medicalRate: Double = 2.0,
    var unemploymentRate: Double = 0.5,
    var housingFundBase: Double = 0.0,
    var housingFundRate: Double = 12.0,
    // 每月专项附加扣除合计（子女教育、房贷、赡养老人等）
    var specialDeduction: Double = 0.0,
    // 配置版本：旧备份缺失该字段时 Gson 会置 0，用于识别并保留本机已有的五险一金配置
    var salaryConfigVersion: Int = 1,
    // 加密相关设置
    var exportEncryptionEnabled: Boolean = false,
    // 密码不参与 Gson 序列化（双保险：导出/上传处也会主动剥离），
    // 防止明文密码写入备份文件或 WebDAV 服务器
    @Transient
    var exportPassword: String = "",
    var syncEncryptionEnabled: Boolean = false,
    @Transient
    var syncPassword: String = ""
)

data class OvertimeResult(
    val workHours: Double = 0.0,
    val overtimeHours: Double = 0.0,
    val normalOvertime: Double = 0.0,
    val weekendOvertime: Double = 0.0,
    val holidayOvertime: Double = 0.0,
    val estimatedPay: Double = 0.0,
    val extraHours: Double = 0.0
)

enum class DayType { WORKDAY, WEEKEND, HOLIDAY }

/**
 * 班次枚举：仅 UI/转换层使用做类型安全封装，序列化层用可空 String（"DAY"/"NIGHT"）。
 * 旧备份缺失该字段时 Gson 反序列化为 null，由 fromStringOrDefault 兜底为白班。
 */
enum class ShiftType(val displayName: String) {
    DAY("白班"),
    NIGHT("夜班");

    companion object {
        /** 非法/未知值兜底为白班（默认），不阻断恢复 */
        fun fromStringOrDefault(value: String?): ShiftType =
            entries.find { it.name == value } ?: DAY
    }
}

/**
 * 请假类型枚举
 */
enum class LeaveType(val displayName: String, val color: Int) {
    ANNUAL_LEAVE("年假", 0xFF4CAF50.toInt()),      // 绿色
    SICK_LEAVE("病假", 0xFFFF9800.toInt()),       // 橙色
    PERSONAL_LEAVE("事假", 0xFF9C27B0.toInt()),   // 紫色
    MARRIAGE_LEAVE("婚假", 0xFFE91E63.toInt()),    // 粉红
    MATERNITY_LEAVE("产假", 0xFFFF5722.toInt()),   // 深橙
    PATERNITY_LEAVE("陪产假", 0xFF00BCD4.toInt()), // 青色
    FUNERAL_LEAVE("丧假", 0xFF795548.toInt()),     // 棕色
    WORK_INJURY("工伤假", 0xFFF44336.toInt()),     // 红色
    UNPAID_LEAVE("无薪假", 0xFF607D8B.toInt()),    // 蓝灰
    OTHER_LEAVE("其他", 0xFF9E9E9E.toInt());      // 灰色

    companion object {
        fun fromString(value: String?): LeaveType? {
            return entries.find { it.name == value }
        }
    }
}
