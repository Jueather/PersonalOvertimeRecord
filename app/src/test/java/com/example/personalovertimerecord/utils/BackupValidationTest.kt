package com.example.personalovertimerecord.utils

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份记录字段级校验（validateBackupRecord）：
 * 全量恢复遇非法记录整体中止，增量/团队同步跳过并计数；
 * shiftType 非法不判非法——展示性字段由 ShiftType.fromStringOrDefault 兜底白班。
 */
class BackupValidationTest {

    private fun validRecord() = AttendanceEntityBackup(
        date = "2026-10-17",
        checkInTime = "09:00",
        checkOutTime = "21:30",
        manualOvertimeHours = 4.0,
        manualExtraHours = 1.5,
        leaveType = "ANNUAL_LEAVE",
        customRate = 2.0,
        shiftType = "NIGHT"
    )

    @Test
    fun validRecord_passes() {
        assertTrue(validateBackupRecord(validRecord()).isEmpty())
    }

    @Test
    fun nullOptionalFields_pass() {
        // 旧备份：可空字段全部缺失（Gson 反序列化为 null）
        assertTrue(validateBackupRecord(AttendanceEntityBackup(date = "2026-10-17")).isEmpty())
    }

    @Test
    fun invalidDate_rejected() {
        assertTrue(validateBackupRecord(validRecord().copy(date = "2026-13-40")).isNotEmpty())
        assertTrue(validateBackupRecord(validRecord().copy(date = "")).isNotEmpty())
        assertTrue(validateBackupRecord(validRecord().copy(date = "2026/10/17")).isNotEmpty())
    }

    @Test
    fun futureDate_allowedForRestore() {
        // 恢复路径 allowFuture=true：未来日期不判非法
        assertTrue(validateBackupRecord(validRecord().copy(date = "2999-01-01")).isEmpty())
    }

    @Test
    fun hoursOutOfRange_rejected() {
        assertTrue(validateBackupRecord(validRecord().copy(manualOvertimeHours = 99.0))
            .any { it.contains("加班时长") })
        assertTrue(validateBackupRecord(validRecord().copy(manualExtraHours = 25.0))
            .any { it.contains("加点时长") })
        // -1 哨兵（未手工设置，由打卡计算）合法
        assertTrue(validateBackupRecord(validRecord().copy(
            manualOvertimeHours = -1.0, manualExtraHours = -1.0)).isEmpty())
    }

    @Test
    fun leaveHoursOutOfRange_rejected() {
        assertTrue(validateBackupRecord(validRecord().copy(leaveHours = 745.0))
            .any { it.contains("请假时长") })
        assertTrue(validateBackupRecord(validRecord().copy(leaveHours = -0.5)).isNotEmpty())
    }

    @Test
    fun customRateOutOfRange_rejected() {
        assertTrue(validateBackupRecord(validRecord().copy(customRate = 5.0))
            .any { it.contains("自定义倍率") })
        assertTrue(validateBackupRecord(validRecord().copy(customRate = 0.5)).isNotEmpty())
        assertTrue(validateBackupRecord(validRecord().copy(customRate = null)).isEmpty())
    }

    @Test
    fun unparseableLeaveType_rejected() {
        assertTrue(validateBackupRecord(validRecord().copy(leaveType = "NOT_A_TYPE"))
            .any { it.contains("请假类型") })
        assertTrue(validateBackupRecord(validRecord().copy(leaveType = null)).isEmpty())
    }

    @Test
    fun invalidCheckTime_rejected() {
        assertTrue(validateBackupRecord(validRecord().copy(checkInTime = "25:00"))
            .any { it.contains("上班时间") })
        assertTrue(validateBackupRecord(validRecord().copy(checkOutTime = "9:00"))
            .any { it.contains("下班时间") })
    }

    @Test
    fun invalidShiftType_notRejected() {
        // 班次非法不丢整条记录：由 ShiftType.fromStringOrDefault 兜底白班
        assertTrue(validateBackupRecord(validRecord().copy(shiftType = "XXX")).isEmpty())
        assertTrue(validateBackupRecord(validRecord().copy(shiftType = null)).isEmpty())
    }
}
