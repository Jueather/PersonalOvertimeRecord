package com.example.personalovertimerecord.utils

import com.example.personalovertimerecord.data.DayType
import java.util.Calendar
import java.util.Locale

/**
 * 节假日管理器
 * 用于识别工作日、周末和法定节假日
 */
object HolidayManager {
    
    // 2024年法定节假日
    private val holidays2024 = mapOf(
        "2024-01-01" to "元旦",
        "2024-02-10" to "春节",
        "2024-02-11" to "春节",
        "2024-02-12" to "春节",
        "2024-02-13" to "春节",
        "2024-02-14" to "春节",
        "2024-02-15" to "春节",
        "2024-02-16" to "春节",
        "2024-02-17" to "春节",
        "2024-04-04" to "清明节",
        "2024-04-05" to "清明节",
        "2024-04-06" to "清明节",
        "2024-05-01" to "劳动节",
        "2024-05-02" to "劳动节",
        "2024-05-03" to "劳动节",
        "2024-05-04" to "劳动节",
        "2024-05-05" to "劳动节",
        "2024-06-10" to "端午节",
        "2024-09-15" to "中秋节",
        "2024-09-16" to "中秋节",
        "2024-09-17" to "中秋节",
        "2024-10-01" to "国庆节",
        "2024-10-02" to "国庆节",
        "2024-10-03" to "国庆节",
        "2024-10-04" to "国庆节",
        "2024-10-05" to "国庆节",
        "2024-10-06" to "国庆节",
        "2024-10-07" to "国庆节"
    )
    
    // 2025年法定节假日
    private val holidays2025 = mapOf(
        "2025-01-01" to "元旦",
        "2025-01-28" to "春节",
        "2025-01-29" to "春节",
        "2025-01-30" to "春节",
        "2025-01-31" to "春节",
        "2025-02-01" to "春节",
        "2025-02-02" to "春节",
        "2025-02-03" to "春节",
        "2025-02-04" to "春节",
        "2025-04-04" to "清明节",
        "2025-04-05" to "清明节",
        "2025-04-06" to "清明节",
        "2025-05-01" to "劳动节",
        "2025-05-02" to "劳动节",
        "2025-05-03" to "劳动节",
        "2025-05-04" to "劳动节",
        "2025-05-05" to "劳动节",
        "2025-05-31" to "端午节",
        "2025-06-01" to "端午节",
        "2025-06-02" to "端午节",
        "2025-09-29" to "中秋节",
        "2025-09-30" to "中秋节",
        "2025-10-01" to "国庆节",
        "2025-10-02" to "国庆节",
        "2025-10-03" to "国庆节",
        "2025-10-04" to "国庆节",
        "2025-10-05" to "国庆节",
        "2025-10-06" to "国庆节",
        "2025-10-07" to "国庆节",
        "2025-10-08" to "国庆节"
    )
    
    // 2026年法定节假日（依据《国务院办公厅关于2026年部分节假日安排的通知》
    // 国办发明电〔2025〕7号，2025-11-04 发布）
    private val holidays2026 = mapOf(
        // 元旦：1月1日（周四）至3日（周六）放假调休，共3天
        "2026-01-01" to "元旦",
        "2026-01-02" to "元旦",
        "2026-01-03" to "元旦",
        // 春节：2月15日（周日，腊月二十八）至23日（周一，正月初七）放假调休，共9天
        "2026-02-15" to "春节",
        "2026-02-16" to "春节",
        "2026-02-17" to "春节",
        "2026-02-18" to "春节",
        "2026-02-19" to "春节",
        "2026-02-20" to "春节",
        "2026-02-21" to "春节",
        "2026-02-22" to "春节",
        "2026-02-23" to "春节",
        // 清明节：4月4日（周六）至6日（周一）放假，共3天
        "2026-04-04" to "清明节",
        "2026-04-05" to "清明节",
        "2026-04-06" to "清明节",
        // 劳动节：5月1日（周五）至5日（周二）放假调休，共5天
        "2026-05-01" to "劳动节",
        "2026-05-02" to "劳动节",
        "2026-05-03" to "劳动节",
        "2026-05-04" to "劳动节",
        "2026-05-05" to "劳动节",
        // 端午节：6月19日（周五，五月初五）至21日（周日）放假，共3天
        "2026-06-19" to "端午节",
        "2026-06-20" to "端午节",
        "2026-06-21" to "端午节",
        // 中秋节：9月25日（周五，八月十五）至27日（周日）放假，共3天
        "2026-09-25" to "中秋节",
        "2026-09-26" to "中秋节",
        "2026-09-27" to "中秋节",
        // 国庆节：10月1日（周四）至7日（周三）放假调休，共7天
        "2026-10-01" to "国庆节",
        "2026-10-02" to "国庆节",
        "2026-10-03" to "国庆节",
        "2026-10-04" to "国庆节",
        "2026-10-05" to "国庆节",
        "2026-10-06" to "国庆节",
        "2026-10-07" to "国庆节"
    )
    
    // 2027年法定节假日（预测：春节按农历除夕至初七估算，正月初一为2月6日）
    private val holidays2027 = mapOf(
        "2027-01-01" to "元旦",
        "2027-02-05" to "春节",
        "2027-02-06" to "春节",
        "2027-02-07" to "春节",
        "2027-02-08" to "春节",
        "2027-02-09" to "春节",
        "2027-02-10" to "春节",
        "2027-02-11" to "春节",
        "2027-02-12" to "春节",
        "2027-04-03" to "清明节",
        "2027-04-04" to "清明节",
        "2027-04-05" to "清明节",
        "2027-04-06" to "清明节",
        "2027-05-01" to "劳动节",
        "2027-05-02" to "劳动节",
        "2027-05-03" to "劳动节",
        "2027-05-04" to "劳动节",
        "2027-05-05" to "劳动节",
        "2027-06-10" to "端午节",
        "2027-06-11" to "端午节",
        "2027-06-12" to "端午节",
        "2027-09-15" to "中秋节",
        "2027-09-16" to "中秋节",
        "2027-09-17" to "中秋节",
        "2027-10-01" to "国庆节",
        "2027-10-02" to "国庆节",
        "2027-10-03" to "国庆节",
        "2027-10-04" to "国庆节",
        "2027-10-05" to "国庆节",
        "2027-10-06" to "国庆节",
        "2027-10-07" to "国庆节"
    )
    
    // 2028年法定节假日（预测：春节按农历除夕至初七估算，正月初一为1月26日）
    private val holidays2028 = mapOf(
        "2028-01-01" to "元旦",
        "2028-01-25" to "春节",
        "2028-01-26" to "春节",
        "2028-01-27" to "春节",
        "2028-01-28" to "春节",
        "2028-01-29" to "春节",
        "2028-01-30" to "春节",
        "2028-01-31" to "春节",
        "2028-02-01" to "春节",
        "2028-04-04" to "清明节",
        "2028-04-05" to "清明节",
        "2028-04-06" to "清明节",
        "2028-04-07" to "清明节",
        "2028-05-01" to "劳动节",
        "2028-05-02" to "劳动节",
        "2028-05-03" to "劳动节",
        "2028-05-04" to "劳动节",
        "2028-05-05" to "劳动节",
        "2028-05-29" to "端午节",
        "2028-05-30" to "端午节",
        "2028-05-31" to "端午节",
        "2028-10-01" to "国庆节",
        "2028-10-02" to "国庆节",
        "2028-10-03" to "国庆节",
        "2028-10-04" to "国庆节",
        "2028-10-05" to "国庆节",
        "2028-10-06" to "国庆节",
        "2028-10-07" to "国庆节",
        "2028-10-08" to "国庆节"
    )
    
    // 所有节假日数据
    private val allHolidays = mapOf(
        2024 to holidays2024,
        2025 to holidays2025,
        2026 to holidays2026,
        2027 to holidays2027,
        2028 to holidays2028
    )

    // 调休补班日（周末上班的日子，加班应按工作日1.5倍而非周末2倍计算）
    // 依据国务院官方放假安排整理（2024/2025/2026 已核实）；
    // 2027年及以后的调休日官方通知未发布，暂不内置，避免误判
    private val makeupWorkdays2024 = setOf(
        "2024-02-04", "2024-02-18", "2024-04-07", "2024-04-28",
        "2024-05-11", "2024-09-14", "2024-09-29", "2024-10-12"
    )

    private val makeupWorkdays2025 = setOf(
        "2025-01-26", "2025-02-08", "2025-04-27", "2025-09-28", "2025-10-11"
    )

    // 2026年调休补班日（国办发明电〔2025〕7号）：
    // 1月4日（周日，元旦调休）、2月14日（周六）与2月28日（周六，春节调休）、
    // 5月9日（周六，劳动节调休）、9月20日（周日）与10月10日（周六，国庆调休）
    private val makeupWorkdays2026 = setOf(
        "2026-01-04", "2026-02-14", "2026-02-28",
        "2026-05-09", "2026-09-20", "2026-10-10"
    )

    private val allMakeupWorkdays = mapOf(
        2024 to makeupWorkdays2024,
        2025 to makeupWorkdays2025,
        2026 to makeupWorkdays2026
    )

    // ---- 在线更新数据（覆盖/补充内置数据） ----
    // 由 HolidayStore 在应用启动时注入缓存、或在"检查更新"成功后注入；
    // 按日期覆盖内置值，未覆盖的日期仍以内置数据兜底，拉取失败不影响原有判定。

    @Volatile
    private var remoteHolidays: Map<String, String> = emptyMap()

    @Volatile
    private var remoteMakeupWorkdays: Set<String> = emptySet()

    @Volatile
    private var remoteVersion: Int = 0

    /**
     * 注入在线更新的节假日数据。
     * @param holidays 日期 -> 节日名称（yyyy-MM-dd）
     * @param makeupWorkdays 调休补班日（yyyy-MM-dd）
     * @param version 远端数据版本号（用于设置页展示）
     */
    fun applyRemoteData(holidays: Map<String, String>, makeupWorkdays: Set<String>, version: Int) {
        remoteHolidays = holidays
        remoteMakeupWorkdays = makeupWorkdays
        remoteVersion = version
    }

    /** 是否已加载在线更新数据 */
    fun hasRemoteData(): Boolean = remoteHolidays.isNotEmpty() || remoteMakeupWorkdays.isNotEmpty()

    /** 在线更新数据版本号（0 表示未加载） */
    fun getRemoteVersion(): Int = remoteVersion

    /** 当前生效的全部年份（内置 + 在线更新），升序 */
    fun getEffectiveYears(): List<Int> {
        val years = allHolidays.keys.toMutableSet()
        remoteHolidays.keys.forEach { date ->
            date.substring(0, 4).toIntOrNull()?.let { years.add(it) }
        }
        remoteMakeupWorkdays.forEach { date ->
            date.substring(0, 4).toIntOrNull()?.let { years.add(it) }
        }
        return years.sorted()
    }

    /**
     * 获取日期类型
     * @param date 日期字符串，格式：yyyy-MM-dd
     * @return 日期类型
     */
    fun getDayType(date: String): DayType {
        // 首先检查是否为法定节假日
        if (isHoliday(date)) {
            return DayType.HOLIDAY
        }
        // 然后检查是否为调休补班日（周末上班，按工作日计算）
        if (isMakeupWorkday(date)) {
            return DayType.WORKDAY
        }
        // 然后检查是否为周末
        if (isWeekend(date)) {
            return DayType.WEEKEND
        }
        // 其他情况为工作日
        return DayType.WORKDAY
    }

    /**
     * 检查是否为调休补班日（周末上班）
     * @param date 日期字符串，格式：yyyy-MM-dd
     * @return 是否为补班日
     */
    fun isMakeupWorkday(date: String): Boolean {
        if (remoteMakeupWorkdays.contains(date)) return true
        val year = date.takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull() ?: return false
        return allMakeupWorkdays[year]?.contains(date) == true
    }
    
    /**
     * 检查是否为法定节假日
     * @param date 日期字符串，格式：yyyy-MM-dd
     * @return 是否为节假日
     */
    fun isHoliday(date: String): Boolean {
        if (remoteHolidays.containsKey(date)) return true
        val year = date.takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull() ?: return false
        return allHolidays[year]?.containsKey(date) == true
    }
    
    /**
     * 获取节假日名称
     * @param date 日期字符串，格式：yyyy-MM-dd
     * @return 节假日名称，如果不为节假日则返回null
     */
    fun getHolidayName(date: String): String? {
        remoteHolidays[date]?.let { return it }
        val year = date.takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull() ?: return null
        return allHolidays[year]?.get(date)
    }
    
    /**
     * 检查是否为周末
     * @param date 日期字符串，格式：yyyy-MM-dd
     * @return 是否为周末
     */
    fun isWeekend(date: String): Boolean {
        val calendar = DateUtils.parseDateString(date) ?: return false
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        return dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY
    }
    
    /**
     * 获取星期几
     * @param date 日期字符串，格式：yyyy-MM-dd
     * @return 星期几（1=周日，2=周一... 7=周六）
     */
    fun getDayOfWeek(date: String): Int {
        val calendar = DateUtils.parseDateString(date) ?: return 0
        return calendar.get(Calendar.DAY_OF_WEEK)
    }
    
    /**
     * 获取星期几的中文名称
     * @param date 日期字符串，格式：yyyy-MM-dd
     * @return 星期几的中文名称
     */
    fun getDayOfWeekName(date: String): String {
        return when (getDayOfWeek(date)) {
            Calendar.SUNDAY -> "周日"
            Calendar.MONDAY -> "周一"
            Calendar.TUESDAY -> "周二"
            Calendar.WEDNESDAY -> "周三"
            Calendar.THURSDAY -> "周四"
            Calendar.FRIDAY -> "周五"
            Calendar.SATURDAY -> "周六"
            else -> ""
        }
    }
    
    /**
     * 获取某月的所有节假日
     * @param year 年份
     * @param month 月份（1-12）
     * @return 节假日列表
     */
    fun getMonthHolidays(year: Int, month: Int): List<Pair<String, String>> {
        val monthPrefix = String.format(Locale.getDefault(), "%04d-%02d", year, month)
        val merged = LinkedHashMap<String, String>()
        allHolidays[year]?.forEach { (date, name) ->
            if (date.startsWith(monthPrefix)) merged[date] = name
        }
        // 在线更新数据按日期覆盖内置值
        remoteHolidays.forEach { (date, name) ->
            if (date.startsWith(monthPrefix)) merged[date] = name
        }
        return merged.map { it.key to it.value }.sortedBy { it.first }
    }
    
    /**
     * 获取某年的所有节假日
     * @param year 年份
     * @return 节假日列表
     */
    fun getYearHolidays(year: Int): List<Pair<String, String>> {
        val yearPrefix = "$year-"
        val merged = LinkedHashMap<String, String>()
        allHolidays[year]?.forEach { (date, name) ->
            if (date.startsWith(yearPrefix)) merged[date] = name
        }
        // 在线更新数据按日期覆盖内置值
        remoteHolidays.forEach { (date, name) ->
            if (date.startsWith(yearPrefix)) merged[date] = name
        }
        return merged.map { it.key to it.value }.sortedBy { it.first }
    }
}