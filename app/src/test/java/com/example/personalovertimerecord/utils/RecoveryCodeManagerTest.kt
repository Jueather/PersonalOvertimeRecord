package com.example.personalovertimerecord.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 恢复码生成 / 规范化 / 校验测试。
 */
class RecoveryCodeManagerTest {

    @Test
    fun generate_hasFourGroupsOfFour_andValid() {
        repeat(50) {
            val code = RecoveryCodeManager.generate()
            val groups = code.split("-")
            assertEquals(4, groups.size)
            assertTrue(groups.all { it.length == 4 })
            assertTrue(RecoveryCodeManager.isValid(code))
            // 规范化后为 16 位
            assertEquals(16, RecoveryCodeManager.normalize(code).length)
        }
    }

    @Test
    fun generate_isRandomized() {
        // 连续两个恢复码不应相同（随机失败概率可忽略）
        assertFalse(RecoveryCodeManager.generate() == RecoveryCodeManager.generate())
    }

    @Test
    fun normalize_stripsHyphensSpacesAndLowercases() {
        val code = RecoveryCodeManager.generate()
        val normalized = RecoveryCodeManager.normalize(code)
        val sloppy = code.lowercase().replace("-", "  ")
        assertEquals(normalized, RecoveryCodeManager.normalize(sloppy))
    }

    @Test
    fun normalize_mapsCommonMisreads() {
        // 字母表里没有 I/L/O：用户误写时映射回 1/1/0
        assertEquals("1100", RecoveryCodeManager.normalize("ILO0"))
        assertEquals("1100", RecoveryCodeManager.normalize("ilo0"))
    }

    @Test
    fun isValid_rejectsMalformed() {
        assertFalse(RecoveryCodeManager.isValid(""))
        assertFalse(RecoveryCodeManager.isValid("123"))
        assertFalse(RecoveryCodeManager.isValid("AAAA-BBBB-CCCC-DDD")) // 少一组
        assertFalse(RecoveryCodeManager.isValid("AAAA-BBBB-CCCC-DDDU")) // U 不在字母表
    }
}
