package com.example.personalovertimerecord.utils

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.example.personalovertimerecord.data.LeaveType
import com.example.personalovertimerecord.data.ShiftType
import com.example.personalovertimerecord.data.db.AppDatabase
import com.example.personalovertimerecord.data.db.AttendanceEntity
import com.example.personalovertimerecord.data.db.CalendarLinkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.floor

/**
 * 系统日历双向同步：
 *
 * 写入（App → 日历）：把加班/请假记录写成目标日历（优先系统主日历）中的全天日程，
 * 标题如 `[加班] 加班2h 加点1h ×1.5 夜班`、`[请假] 年假 1天`；备注写在描述里，
 * 并带 [DESC_MARKER] 标记，便于识别本应用管理的日程。
 *
 * 回写（日历 → App）：
 * - 日程被删除 → 对应记录软删除（参与后续云同步删除）
 * - 日程被改到别的日期 → 记录跟随移动（目标日期已有记录时跳过并保留本地）
 * - 标题被改（时长/请假类型/倍率/班次）→ 记录按新标题更新
 * - 用户在目标日历里新建标题以 `[加班]`/`[请假]` 开头的事件 → 自动导入为记录
 *
 * 冲突：以 `syncedAt` 为分界。记录在同步后又被 App 修改过（modifiedAt > syncedAt）时保留本地，
 * 下次同步覆盖日历；否则以日历为准。
 *
 * 说明：映射表仅本机使用，不参与备份/云同步；日历操作全部在 IO 线程执行。
 */
class CalendarSyncManager(private val context: Context) {

    data class SyncReport(
        val pushed: Int = 0,
        val pulled: Int = 0,
        val deletedFromApp: Int = 0,
        val deletedFromCalendar: Int = 0,
        val skipped: Int = 0,
        val calendarName: String? = null,
        val error: String? = null
    ) {
        val success: Boolean get() = error == null
    }

    private data class TargetCalendar(val id: Long, val name: String)

    private data class ParsedEvent(
        val date: String,
        val isLeave: Boolean,
        val leaveType: String?,
        val leaveHours: Double,
        val overtimeHours: Double,
        val extraHours: Double,
        val customRate: Double,
        val shiftType: String,
        val note: String?
    )

    private data class ManagedEvent(
        val eventId: Long,
        val date: String,
        val parsed: ParsedEvent?
    )

    private val db = AppDatabase.getDatabase(context)
    private val dao = db.attendanceDao()
    private val linkDao = db.calendarLinkDao()

    fun hasPermission(): Boolean {
        val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
        val write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR)
        return read == PackageManager.PERMISSION_GRANTED && write == PackageManager.PERMISSION_GRANTED
    }

    /** 设置页状态文案：权限 / 目标日历 / 上次同步结果 */
    fun describeStatus(): String {
        if (!hasPermission()) return "未授权日历读写权限"
        val calendar = findTargetCalendar() ?: return "未找到可写入的系统日历"
        val prefs = prefs()
        val lastSync = prefs.getLong(KEY_LAST_SYNC, 0L)
        val summary = prefs.getString(KEY_LAST_SUMMARY, null)
        return if (lastSync > 0L && !summary.isNullOrBlank()) {
            "目标日历：${calendar.name}\n上次同步：${formatTime(lastSync)} · $summary"
        } else {
            "目标日历：${calendar.name}\n尚未同步"
        }
    }

    suspend fun sync(): SyncReport = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            return@withContext SyncReport(error = "请先授予日历读写权限")
        }
        val target = findTargetCalendar()
            ?: return@withContext SyncReport(error = "没有可写入的系统日历，请先在系统日历中登录账号")

        try {
            val report = SyncGate.lock { performSync(target) }
            saveLastSync(report)
            AppLogger.i(
                TAG,
                "Calendar sync done: pushed=${report.pushed}, pulled=${report.pulled}, " +
                    "deletedApp=${report.deletedFromApp}, deletedCalendar=${report.deletedFromCalendar}, skipped=${report.skipped}"
            )
            report
        } catch (e: Exception) {
            AppLogger.e(TAG, "Calendar sync failed", e)
            SyncReport(error = "同步失败：${e.message ?: e.javaClass.simpleName}", calendarName = target.name)
        }
    }

    private suspend fun performSync(target: TargetCalendar): SyncReport {
        val now = System.currentTimeMillis()
        var pushed = 0
        var pulled = 0
        var deletedFromApp = 0
        var deletedFromCalendar = 0
        var skipped = 0

        val managedEvents = queryManagedEvents(target.id)
        val links = linkDao.getAll().associateBy { it.eventId }

        // ---------- 1. 日历 → App ----------
        for (link in links.values) {
            if (link.calendarId != target.id) {
                // 目标日历已变更（新增账号/系统主日历变化）：旧日历事件查不到，
                // 不能当成"日历侧已删除"，保留记录与映射，交给推送阶段迁移到新日历
                skipped++
                continue
            }

            val event = managedEvents[link.eventId]
            if (event == null) {
                // 日历端删除了事件：软删除本地记录，保证删除能继续参与云同步
                val entity = dao.getRecordByDate(link.recordDate)
                if (entity != null) {
                    dao.markDeleted(entity.id, now)
                    deletedFromCalendar++
                }
                linkDao.deleteByEventId(link.eventId)
                continue
            }

            val parsed = event.parsed
            if (parsed == null) {
                skipped++
                continue
            }

            val entity = dao.getRecordByDate(link.recordDate)
            if (entity == null) {
                // 本地已删除（软删除）：同步后半段负责清理日历事件
                skipped++
                continue
            }

            if (parsed.date != entity.date) {
                // 日历端把日程挪到了别的日期：目标日期必须完全空闲
                // （含软删除墓碑与残留映射，否则 REPLACE 会覆盖墓碑/他人映射）
                if (isDateOccupied(parsed.date)) {
                    skipped++
                    continue
                }
                val moved = applyToEntity(entity, parsed, now).copy(date = parsed.date)
                dao.insertOrUpdate(moved)
                linkDao.upsert(link.copy(recordDate = parsed.date, syncedAt = now))
                pulled++
                continue
            }

            if (!differs(entity, parsed)) continue

            val localModified = entity.modifiedAt ?: entity.createdAt
            if (localModified <= link.syncedAt) {
                // 上次同步后本地没再改过 → 以日历为准
                dao.insertOrUpdate(applyToEntity(entity, parsed, now))
                linkDao.upsert(link.copy(syncedAt = now))
                pulled++
            } else {
                // 本地更新更晚 → 保留本地，由推送阶段覆盖日历
                skipped++
            }
        }

        // 日历里新建的、标题带前缀的事件 → 导入为记录
        val linkedIds = links.keys
        for (event in managedEvents.values) {
            if (event.eventId in linkedIds) continue
            val parsed = event.parsed ?: continue
            if (!isSyncable(parsed)) continue
            // 目标日期已有记录（含软删除墓碑）或残留映射时跳过，避免覆盖删除标记/他人映射
            if (isDateOccupied(parsed.date)) {
                skipped++
                continue
            }
            val inserted = AttendanceEntity(
                date = parsed.date,
                manualOvertimeHours = if (parsed.isLeave) -1.0 else parsed.overtimeHours,
                manualExtraHours = if (parsed.isLeave) -1.0 else parsed.extraHours,
                note = parsed.note,
                createdAt = now,
                modifiedAt = now,
                isLeave = parsed.isLeave,
                leaveType = parsed.leaveType,
                leaveHours = parsed.leaveHours,
                customRate = parsed.customRate,
                shiftType = parsed.shiftType
            )
            dao.insertOrUpdate(inserted)
            linkDao.upsert(CalendarLinkEntity(event.eventId, target.id, parsed.date, now))
            pulled++
        }

        // ---------- 2. App → 日历 ----------
        val allRecords = dao.getAllRecordsIncludingDeletedSync().associateBy { it.date }
        val currentLinks = linkDao.getAll().associateBy { it.recordDate }

        // App 端删除的记录 → 删除对应日历事件；记录已不存在（如整库恢复）→ 清理孤儿映射
        for ((date, link) in currentLinks) {
            val record = allRecords[date]
            if (record == null) {
                deleteEvent(link.eventId)
                linkDao.deleteByEventId(link.eventId)
                skipped++
                continue
            }
            if (!record.isDeleted) continue
            deleteEvent(link.eventId)
            linkDao.deleteByEventId(link.eventId)
            deletedFromApp++
        }

        for (entity in dao.getAllRecordsSync()) {
            val link = currentLinks[entity.date]
            val syncable = isSyncable(entity)

            if (!syncable) {
                // 时长清零/改为空记录：清理旧事件，避免日历里留下过期日程
                if (link != null) {
                    deleteEvent(link.eventId)
                    linkDao.deleteByEventId(link.eventId)
                    deletedFromApp++
                }
                continue
            }

            if (link == null) {
                val eventId = insertEvent(target.id, entity)
                if (eventId == null) {
                    skipped++
                } else {
                    linkDao.upsert(CalendarLinkEntity(eventId, target.id, entity.date, now))
                    pushed++
                }
                continue
            }

            // 目标日历被切换（主日历变更等）：迁移到新日历
            if (link.calendarId != target.id) {
                deleteEvent(link.eventId)
                linkDao.deleteByEventId(link.eventId)
                val eventId = insertEvent(target.id, entity)
                if (eventId == null) {
                    skipped++
                } else {
                    linkDao.upsert(CalendarLinkEntity(eventId, target.id, entity.date, now))
                    pushed++
                }
                continue
            }

            val localModified = entity.modifiedAt ?: entity.createdAt
            if (localModified > link.syncedAt) {
                if (updateEvent(link.eventId, entity)) {
                    linkDao.upsert(link.copy(syncedAt = now))
                    pushed++
                } else {
                    // 事件已在日历侧消失（例如同步期间被删）：清掉映射，下次重新创建
                    linkDao.deleteByEventId(link.eventId)
                    skipped++
                }
            }
        }

        return SyncReport(
            pushed = pushed,
            pulled = pulled,
            deletedFromApp = deletedFromApp,
            deletedFromCalendar = deletedFromCalendar,
            skipped = skipped,
            calendarName = target.name
        )
    }

    // ---------------- 日历读写 ----------------

    private fun findTargetCalendar(): TargetCalendar? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.IS_PRIMARY
        )
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        val sort = "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars._ID} ASC"

        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            args,
            sort
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return TargetCalendar(
                    id = cursor.getLong(0),
                    name = cursor.getString(1) ?: "系统日历"
                )
            }
        }
        return null
    }

    private fun queryManagedEvents(calendarId: Long): Map<Long, ManagedEvent> {
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.DELETED,
            CalendarContract.Events.STATUS
        )
        val selection = "${CalendarContract.Events.CALENDAR_ID} = ? " +
            "AND ${CalendarContract.Events.DELETED} = 0 " +
            "AND (${CalendarContract.Events.TITLE} LIKE ? " +
            "OR ${CalendarContract.Events.TITLE} LIKE ? " +
            "OR ${CalendarContract.Events.DESCRIPTION} LIKE ?)"
        val args = arrayOf(
            calendarId.toString(),
            "$TITLE_OVERTIME%",
            "$TITLE_LEAVE%",
            "$DESC_MARKER%"
        )

        val result = LinkedHashMap<Long, ManagedEvent>()
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            args,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val status = cursor.getInt(6)
                if (status == CalendarContract.Events.STATUS_CANCELED) continue
                val eventId = cursor.getLong(0)
                val title = cursor.getString(1) ?: continue
                val description = cursor.getString(2)
                val dtStart = cursor.getLong(3)
                val allDay = cursor.getInt(4) == 1
                val date = eventDate(dtStart, allDay)
                val note = extractNote(description)
                val parsed = parseTitle(title, date)?.copy(note = note)
                result[eventId] = ManagedEvent(eventId, date, parsed)
            }
        }
        return result
    }

    private fun insertEvent(calendarId: Long, entity: AttendanceEntity): Long? {
        val values = buildEventValues(calendarId, entity)
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        return uri?.lastPathSegment?.toLongOrNull()
    }

    private fun updateEvent(eventId: Long, entity: AttendanceEntity): Boolean {
        val values = buildEventValues(null, entity)
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        return context.contentResolver.update(uri, values, null, null) > 0
    }

    private fun deleteEvent(eventId: Long) {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    private fun buildEventValues(calendarId: Long?, entity: AttendanceEntity): ContentValues {
        val start = utcMidnight(entity.date)
        return ContentValues().apply {
            if (calendarId != null) put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, buildTitle(entity))
            put(
                CalendarContract.Events.DESCRIPTION,
                DESC_MARKER + (entity.note?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: "")
            )
            put(CalendarContract.Events.ALL_DAY, 1)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, start + DAY_MS)
            put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            put(CalendarContract.Events.HAS_ALARM, 0)
        }
    }

    // ---------------- 标题编解码 ----------------

    private fun buildTitle(entity: AttendanceEntity): String {
        val night = entity.shiftType == ShiftType.NIGHT.name
        return if (entity.isLeave) {
            val type = LeaveType.fromString(entity.leaveType)?.displayName ?: "请假"
            "${TITLE_LEAVE} $type ${formatNumber(entity.leaveHours)}天" + if (night) " 夜班" else ""
        } else {
            val parts = mutableListOf<String>()
            if (entity.manualOvertimeHours > 0) parts += "加班${formatNumber(entity.manualOvertimeHours)}h"
            if (entity.manualExtraHours > 0) parts += "加点${formatNumber(entity.manualExtraHours)}h"
            if (parts.isEmpty()) parts += "0h"
            if (entity.customRate > 0) parts += "×${formatNumber(entity.customRate)}"
            if (night) parts += "夜班"
            "${TITLE_OVERTIME} " + parts.joinToString(" ")
        }
    }

    private fun parseTitle(title: String, date: String): ParsedEvent? {
        val night = title.contains("夜班")
        val shift = if (night) ShiftType.NIGHT.name else ShiftType.DAY.name

        if (title.startsWith(TITLE_OVERTIME)) {
            val overtime = OVERTIME_REGEX.find(title)?.groupValues?.get(1)?.toDoubleOrNull()
            val extra = EXTRA_REGEX.find(title)?.groupValues?.get(1)?.toDoubleOrNull()
            if (overtime == null && extra == null) return null
            val rate = RATE_REGEX.find(title)?.groupValues?.get(1)?.toDoubleOrNull() ?: -1.0
            return ParsedEvent(
                date = date,
                isLeave = false,
                leaveType = null,
                leaveHours = 0.0,
                overtimeHours = overtime ?: 0.0,
                extraHours = extra ?: 0.0,
                customRate = rate,
                shiftType = shift,
                note = null
            )
        }

        if (title.startsWith(TITLE_LEAVE)) {
            val hours = LEAVE_HOURS_REGEX.find(title)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
            val typeName = LEAVE_TYPE_REGEX.find(title)?.groupValues?.get(1)
            val leaveType = LeaveType.entries.find { it.displayName == typeName }?.name
            return ParsedEvent(
                date = date,
                isLeave = true,
                leaveType = leaveType,
                leaveHours = hours,
                overtimeHours = -1.0,
                extraHours = -1.0,
                customRate = -1.0,
                shiftType = shift,
                note = null
            )
        }

        return null
    }

    private fun extractNote(description: String?): String? {
        if (description.isNullOrBlank()) return null
        if (!description.startsWith(DESC_MARKER)) return null
        return description.removePrefix(DESC_MARKER).trim().ifBlank { null }
    }

    // ---------------- 冲突比较 ----------------

    private fun applyToEntity(entity: AttendanceEntity, parsed: ParsedEvent, now: Long): AttendanceEntity {
        return if (parsed.isLeave) {
            entity.copy(
                isLeave = true,
                leaveType = parsed.leaveType,
                leaveHours = parsed.leaveHours,
                manualOvertimeHours = -1.0,
                manualExtraHours = -1.0,
                customRate = -1.0,
                shiftType = parsed.shiftType,
                note = parsed.note ?: entity.note,
                modifiedAt = now
            )
        } else {
            entity.copy(
                isLeave = false,
                leaveType = null,
                leaveHours = 0.0,
                manualOvertimeHours = parsed.overtimeHours,
                manualExtraHours = parsed.extraHours,
                customRate = parsed.customRate,
                shiftType = parsed.shiftType,
                note = parsed.note ?: entity.note,
                modifiedAt = now
            )
        }
    }

    private fun differs(entity: AttendanceEntity, parsed: ParsedEvent): Boolean {
        if (parsed.isLeave != entity.isLeave) return true
        if (parsed.shiftType != entity.shiftType) return true
        if (parsed.isLeave) {
            if (parsed.leaveType != entity.leaveType) return true
            if (!nearlyEqual(parsed.leaveHours, entity.leaveHours)) return true
        } else {
            if (!nearlyEqual(parsed.overtimeHours, entity.manualOvertimeHours)) return true
            if (!nearlyEqual(parsed.extraHours, entity.manualExtraHours)) return true
            if (!nearlyEqual(parsed.customRate, entity.customRate)) return true
        }
        if (parsed.note != null && parsed.note != entity.note) return true
        return false
    }

    /** 日期是否已被占用：含软删除墓碑，也含残留的日历映射（其事件可能仍在日历中） */
    private suspend fun isDateOccupied(date: String): Boolean =
        dao.getAnyRecordByDate(date) != null || linkDao.findByDate(date) != null

    private fun isSyncable(entity: AttendanceEntity): Boolean {
        if (entity.isDeleted) return false
        return if (entity.isLeave) entity.leaveHours > 0
        else entity.manualOvertimeHours > 0 || entity.manualExtraHours > 0
    }

    private fun isSyncable(parsed: ParsedEvent): Boolean {
        return if (parsed.isLeave) parsed.leaveHours > 0
        else parsed.overtimeHours > 0 || parsed.extraHours > 0
    }

    private fun nearlyEqual(a: Double, b: Double): Boolean = abs(a - b) < 0.001

    // ---------------- 日期/格式化 ----------------

    private fun utcMidnight(date: String): Long {
        val parts = date.split("-")
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(
                parts.getOrNull(0)?.toIntOrNull() ?: 1970,
                (parts.getOrNull(1)?.toIntOrNull() ?: 1) - 1,
                parts.getOrNull(2)?.toIntOrNull() ?: 1
            )
        }
        return calendar.timeInMillis
    }

    private fun eventDate(dtStart: Long, allDay: Boolean): String {
        val zone = if (allDay) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = dtStart }
        return String.format(
            Locale.US,
            "%04d-%02d-%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun formatNumber(value: Double): String {
        return if (value == floor(value) && abs(value) < 1000) {
            value.toInt().toString()
        } else {
            String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
        }
    }

    private fun formatTime(timestamp: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun saveLastSync(report: SyncReport) {
        val summary = buildString {
            append("推送 ${report.pushed}")
            append(" · 回写 ${report.pulled}")
            if (report.deletedFromCalendar > 0) append(" · 日历删除 ${report.deletedFromCalendar}")
            if (report.deletedFromApp > 0) append(" · 清理事件 ${report.deletedFromApp}")
            if (report.skipped > 0) append(" · 跳过 ${report.skipped}")
        }
        prefs().edit()
            .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
            .putString(KEY_LAST_SUMMARY, summary)
            .apply()
    }

    companion object {
        private const val TAG = "CalendarSyncManager"
        private const val PREFS_NAME = "calendar_sync"
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_LAST_SUMMARY = "last_summary"

        private const val TITLE_OVERTIME = "[加班]"
        private const val TITLE_LEAVE = "[请假]"
        private const val DESC_MARKER = "[OVERTIME_RECORD]"
        private const val DAY_MS = 24L * 60 * 60 * 1000

        private val OVERTIME_REGEX = Regex("""加班([0-9]+(?:\.[0-9]+)?)h""")
        private val EXTRA_REGEX = Regex("""加点([0-9]+(?:\.[0-9]+)?)h""")
        private val RATE_REGEX = Regex("""×([0-9]+(?:\.[0-9]+)?)""")
        private val LEAVE_HOURS_REGEX = Regex("""([0-9]+(?:\.[0-9]+)?)天""")
        private val LEAVE_TYPE_REGEX = Regex("""^\[请假\]\s*(\S+)""")
    }
}
