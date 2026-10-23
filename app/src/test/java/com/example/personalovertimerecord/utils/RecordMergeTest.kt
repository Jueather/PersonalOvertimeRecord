package com.example.personalovertimerecord.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 智能合并纯算法测试：覆盖冲突策略、墓碑（软删除）传播、去重与空日期防御。
 */
class RecordMergeTest {

    private fun rec(
        date: String,
        createdAt: Long = 100L,
        modifiedAt: Long = createdAt,
        note: String = "",
        isDeleted: Boolean = false
    ) = AttendanceEntityBackup(
        date = date,
        note = note,
        createdAt = createdAt,
        modifiedAt = modifiedAt,
        isDeleted = isDeleted
    )

    // ---------- 冲突策略 ----------

    @Test
    fun newerWins_cloudNewer_takesCloud() {
        val local = rec("2026-01-01", modifiedAt = 100, note = "local")
        val cloud = rec("2026-01-01", modifiedAt = 200, note = "cloud")
        val result = mergeAttendanceBackups(listOf(local), listOf(cloud), ConflictStrategy.NEWER_WINS)
        assertEquals(1, result.size)
        assertEquals("cloud", result.single().note)
    }

    @Test
    fun newerWins_localNewer_keepsLocal() {
        val local = rec("2026-01-01", modifiedAt = 300, note = "local")
        val cloud = rec("2026-01-01", modifiedAt = 200, note = "cloud")
        val result = mergeAttendanceBackups(listOf(local), listOf(cloud), ConflictStrategy.NEWER_WINS)
        assertEquals("local", result.single().note)
    }

    @Test
    fun newerWins_tie_keepsLocal() {
        val local = rec("2026-01-01", modifiedAt = 200, note = "local")
        val cloud = rec("2026-01-01", modifiedAt = 200, note = "cloud")
        val result = mergeAttendanceBackups(listOf(local), listOf(cloud), ConflictStrategy.NEWER_WINS)
        assertEquals("local", result.single().note)
    }

    @Test
    fun newerWins_cloudMissingModifiedAt_fallsBackToCreatedAt() {
        // 历史/旧版本云端数据可能没有 modifiedAt（反序列化后为 0）
        val local = rec("2026-01-01", createdAt = 100, modifiedAt = 100, note = "local")
        val cloud = rec("2026-01-01", createdAt = 500, modifiedAt = 0, note = "oldCloud")
        val result = mergeAttendanceBackups(listOf(local), listOf(cloud), ConflictStrategy.NEWER_WINS)
        assertEquals("oldCloud", result.single().note)
    }

    @Test
    fun localWins_keepsLocalEvenIfCloudNewer() {
        val local = rec("2026-01-01", modifiedAt = 100, note = "local")
        val cloud = rec("2026-01-01", modifiedAt = 999, note = "cloud")
        val result = mergeAttendanceBackups(listOf(local), listOf(cloud), ConflictStrategy.LOCAL_WINS)
        assertEquals("local", result.single().note)
    }

    @Test
    fun cloudWins_takesCloudEvenIfLocalNewer() {
        val local = rec("2026-01-01", modifiedAt = 999, note = "local")
        val cloud = rec("2026-01-01", modifiedAt = 100, note = "cloud")
        val result = mergeAttendanceBackups(listOf(local), listOf(cloud), ConflictStrategy.CLOUD_WINS)
        assertEquals("cloud", result.single().note)
    }

    // ---------- 墓碑（软删除）传播 ----------

    @Test
    fun cloudTombstoneNewer_propagatesDeletion() {
        val local = rec("2026-01-01", modifiedAt = 100, note = "active-local")
        val cloud = rec("2026-01-01", modifiedAt = 200, isDeleted = true, note = "deleted-cloud")
        val result = mergeAttendanceBackups(listOf(local), listOf(cloud), ConflictStrategy.NEWER_WINS)
        assertTrue(result.single().isDeleted)
    }

    @Test
    fun localActiveNewer_keepsLocalAliveAgainstCloudTombstone() {
        val local = rec("2026-01-01", modifiedAt = 300, note = "active-local")
        val cloud = rec("2026-01-01", modifiedAt = 200, isDeleted = true)
        val result = mergeAttendanceBackups(listOf(local), listOf(cloud), ConflictStrategy.NEWER_WINS)
        assertFalse(result.single().isDeleted)
    }

    @Test
    fun localOnlyTombstone_isIncludedToSyncDeletion() {
        // 本地独有的软删除记录必须进入合并结果，让云端也能执行删除
        val localTombstone = rec("2026-01-02", isDeleted = true)
        val result = mergeAttendanceBackups(listOf(localTombstone), emptyList(), ConflictStrategy.NEWER_WINS)
        assertEquals(1, result.size)
        assertTrue(result.single().isDeleted)
        assertEquals("2026-01-02", result.single().date)
    }

    @Test
    fun cloudOnlyRecord_isAdopted() {
        val cloud = rec("2026-01-03", note = "cloud-only")
        val result = mergeAttendanceBackups(emptyList(), listOf(cloud), ConflictStrategy.NEWER_WINS)
        assertEquals(listOf("2026-01-03"), result.map { it.date })
    }

    // ---------- 去重 / 空日期防御 ----------

    @Test
    fun duplicatesWithinCloud_keepFirstOnly() {
        val first = rec("2026-01-01", note = "first")
        val dup = rec("2026-01-01", note = "dup")
        val result = mergeAttendanceBackups(emptyList(), listOf(first, dup), ConflictStrategy.NEWER_WINS)
        assertEquals(1, result.count { it.date == "2026-01-01" })
        assertEquals("first", result.single().note)
    }

    @Test
    fun duplicatesWithinLocal_keepFirstOnly() {
        val first = rec("2026-01-01", note = "first-local")
        val dup = rec("2026-01-01", note = "dup-local")
        val result = mergeAttendanceBackups(listOf(first, dup), emptyList(), ConflictStrategy.NEWER_WINS)
        assertEquals(1, result.size)
        assertEquals("first-local", result.single().note)
    }

    @Test
    fun blankDatesAreDropped() {
        val blank = AttendanceEntityBackup(date = "", createdAt = 1, modifiedAt = 1)
        val cloud = rec("2026-01-05")
        val result = mergeAttendanceBackups(listOf(blank), listOf(cloud), ConflictStrategy.NEWER_WINS)
        assertEquals(listOf("2026-01-05"), result.map { it.date })
    }

    @Test
    fun merge_unionWithoutDuplicateDates() {
        val local = listOf(
            rec("2026-01-01", modifiedAt = 100, note = "local"),
            rec("2026-01-02", modifiedAt = 100, note = "local-only")
        )
        val cloud = listOf(
            rec("2026-01-01", modifiedAt = 200, note = "cloud-newer"),
            rec("2026-01-03", modifiedAt = 100, note = "cloud-only")
        )
        val result = mergeAttendanceBackups(local, cloud, ConflictStrategy.NEWER_WINS)
        assertEquals(3, result.size)
        assertEquals(setOf("2026-01-01", "2026-01-02", "2026-01-03"), result.map { it.date }.toSet())
        assertEquals("cloud-newer", result.first { it.date == "2026-01-01" }.note)
    }

    // ---------- 班次字段透传 ----------

    @Test
    fun shiftType_survivesMerge() {
        // NEWER_WINS 取云端时，班次标记必须随记录整体保留
        val local = rec("2026-01-01", modifiedAt = 100).copy(shiftType = "DAY")
        val cloud = rec("2026-01-01", modifiedAt = 200, note = "cloud").copy(shiftType = "NIGHT")
        val result = mergeAttendanceBackups(listOf(local), listOf(cloud), ConflictStrategy.NEWER_WINS)
        assertEquals("NIGHT", result.single().shiftType)
    }
}
