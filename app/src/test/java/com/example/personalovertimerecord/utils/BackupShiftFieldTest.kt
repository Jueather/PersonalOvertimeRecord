package com.example.personalovertimerecord.utils

import com.example.personalovertimerecord.data.ShiftType
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份 JSON 的班次字段（shiftType）：可空 String，Gson 缺字段为 null 自动兼容旧备份；
 * 写端字段声明序固定（shiftType 在 customRate 之后、isDeleted 之前），checksum 依赖该顺序。
 */
class BackupShiftFieldTest {

    private val gson = Gson()

    @Test
    fun serializedJson_containsShiftType_beforeIsDeleted() {
        val record = AttendanceEntityBackup(date = "2026-10-17", shiftType = "NIGHT")
        val obj = JsonParser.parseString(gson.toJson(record)).asJsonObject

        assertTrue("备份 JSON 应包含 shiftType", obj.has("shiftType"))
        assertEquals("NIGHT", obj.get("shiftType").asString)
        // 字段序：shiftType 必须在 isDeleted 之前（PC 端按键序复刻序列化计算 checksum）
        val keys = obj.keySet().toList()
        assertTrue(keys.indexOf("shiftType") < keys.indexOf("isDeleted"))
    }

    @Test
    fun jsonRoundTrip_keepsShiftType() {
        val record = AttendanceEntityBackup(date = "2026-10-17", shiftType = "NIGHT")
        val parsed = gson.fromJson(gson.toJson(record), AttendanceEntityBackup::class.java)
        assertEquals("NIGHT", parsed.shiftType)
    }

    @Test
    fun legacyJson_withoutShiftType_parsesAsNull_andFallsBackToDay() {
        // 模拟旧版本备份：无 shiftType 字段
        val legacy = """{"date":"2026-10-17","manualOvertimeHours":-1.0,"manualExtraHours":-1.0,"isDeleted":false}"""
        val parsed = gson.fromJson(legacy, AttendanceEntityBackup::class.java)

        assertNull(parsed.shiftType)
        assertEquals(ShiftType.DAY, ShiftType.fromStringOrDefault(parsed.shiftType))
    }

    @Test
    fun fromStringOrDefault_handlesUnknownAndNull() {
        assertEquals(ShiftType.DAY, ShiftType.fromStringOrDefault(null))
        assertEquals(ShiftType.DAY, ShiftType.fromStringOrDefault("XXX"))
        assertEquals(ShiftType.DAY, ShiftType.fromStringOrDefault("DAY"))
        assertEquals(ShiftType.NIGHT, ShiftType.fromStringOrDefault("NIGHT"))
    }
}
