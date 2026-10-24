package com.example.personalovertimerecord.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 节假日在线更新：
 * - 应用启动时把本地缓存注入 [HolidayManager]（离线可用，不联网）
 * - 设置页"检查更新"时从 [SOURCE_URL] 拉取 JSON，校验通过后覆盖缓存并立即生效
 * - 仓库 JSON 与其 CDN 镜像全部不可达时，最后改用 api.apihubs.cn 接口兜底，
 *   把接口结果转成同一份 JSON 结构再写入缓存（只覆盖当前年与下一年）
 * - 远程数据按日期覆盖内置数据，未覆盖的日期仍走内置兜底；任何失败都不影响原有判定
 *
 * JSON 结构：
 * {
 *   "version": 1,
 *   "updatedAt": "2026-10-24",
 *   "years": {
 *     "2026": { "holidays": { "2026-01-01": "元旦" }, "makeupWorkdays": ["2026-01-04"] }
 *   }
 * }
 */
object HolidayStore {

    private const val TAG = "HolidayStore"
    private const val PREFS_NAME = "holiday_data"
    private const val KEY_CACHED_JSON = "cached_json"
    private const val KEY_CACHED_VERSION = "cached_version"
    private const val KEY_CACHED_UPDATED_AT = "cached_updated_at"
    private const val KEY_LAST_CHECK = "last_check"

    /** 数据源：仓库根目录 holiday/holidays.json（固定地址，发版随仓库更新） */
    const val SOURCE_URL =
        "https://raw.githubusercontent.com/Jueather/PersonalOvertimeRecord/main/holiday/holidays.json"

    /**
     * 备用数据源：raw.githubusercontent.com 在部分网络环境不可达（连接超时），
     * 依次改用 jsDelivr 的三个 CDN 节点读取同一仓库同一文件，内容一致。
     */
    private val FALLBACK_URLS = listOf(
        "https://cdn.jsdelivr.net/gh/Jueather/PersonalOvertimeRecord@main/holiday/holidays.json",
        "https://fastly.jsdelivr.net/gh/Jueather/PersonalOvertimeRecord@main/holiday/holidays.json",
        "https://gcore.jsdelivr.net/gh/Jueather/PersonalOvertimeRecord@main/holiday/holidays.json"
    )

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 7_000

    /** 第三方兜底数据源：仓库 JSON 与 CDN 镜像都不可达时，按年拉取当前年与下一年的数据 */
    private const val API_URL = "https://api.apihubs.cn/holiday/get"
    private const val API_PAGE_SIZE = 400
    private const val API_READ_TIMEOUT_MS = 12_000
    private const val API_REQUEST_INTERVAL_MS = 500L
    private const val API_CODE_RATE_LIMIT = 80006

    /** 接口中 holiday_recess=1 表示该日放假 */
    private const val API_RECESS_YES = 1

    /** 接口不提供版本号，缓存统一记为 1（updatedAt 记录拉取日期） */
    private const val API_VERSION = 1

    /** 接口返回的节日 ID 未收录时的兜底名称 */
    private const val API_FALLBACK_NAME = "节假日"

    private const val MAX_BYTES = 512 * 1024
    private val DATE_REGEX = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private const val MIN_YEAR = 2000
    private const val MAX_YEAR = 2100
    private const val MAX_HOLIDAY_NAME_LENGTH = 20

    private val gson = Gson()

    data class HolidayDataSet(
        val version: Int = 0,
        val updatedAt: String? = null,
        val years: Map<String, HolidayYear> = emptyMap()
    )

    data class HolidayYear(
        val holidays: Map<String, String> = emptyMap(),
        val makeupWorkdays: List<String> = emptyList()
    )

    /** api.apihubs.cn 响应外层结构 */
    private data class ApiResponse(
        val code: Int = -1,
        val msg: String? = null,
        val data: ApiBody? = null
    )

    private data class ApiBody(
        val list: List<ApiDay> = emptyList()
    )

    /** 接口单日记录，只取用到的字段 */
    private data class ApiDay(
        val date: Int = 0,
        val holiday: Int = HolidayIdNames.NONE,
        @SerializedName("holiday_overtime") val holidayOvertime: Int = HolidayIdNames.NONE,
        @SerializedName("holiday_recess") val holidayRecess: Int = 0
    )

    sealed interface RefreshResult {
        data class Success(val version: Int, val years: List<Int>) : RefreshResult
        data class Failure(val message: String) : RefreshResult
    }

    /** 应用启动时调用：加载本机缓存并注入内存（不联网、失败静默） */
    fun loadCached(context: Context) {
        val raw = prefs(context).getString(KEY_CACHED_JSON, null)
        if (raw.isNullOrBlank()) return
        val data = runCatching { gson.fromJson(raw, HolidayDataSet::class.java) }.getOrNull() ?: return
        if (apply(data)) {
            AppLogger.i(TAG, "Loaded cached holiday data v${data.version}")
        }
    }

    /** 在线拉取并应用；耗时操作，调用方需在 IO 线程或协程中执行 */
    suspend fun refresh(context: Context, sourceUrl: String = SOURCE_URL): RefreshResult =
        withContext(Dispatchers.IO) {
            if (!NetworkUtils.isNetworkAvailable(context)) {
                return@withContext RefreshResult.Failure("请先检查网络连接")
            }

            // 主源失败（如 raw.githubusercontent.com 不可达或文件未发布）时依次尝试 CDN 镜像
            val sources = listOf(sourceUrl) + FALLBACK_URLS.filter { it != sourceUrl }
            var lastError = "没有可用的数据源"

            for (source in sources) {
                if (!source.startsWith("https://")) {
                    lastError = "数据源必须使用 https"
                    continue
                }

                val raw = try {
                    fetch(source)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Fetch holiday data failed: $source", e)
                    lastError = "下载失败（${e.message ?: e.javaClass.simpleName}）"
                    continue
                }

                val data = try {
                    gson.fromJson(raw, HolidayDataSet::class.java)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Parse holiday data failed: $source", e)
                    lastError = "数据格式错误"
                    continue
                }

                if (data == null || !apply(data)) {
                    lastError = "数据校验失败，已保留原有数据"
                    continue
                }

                prefs(context).edit()
                    .putString(KEY_CACHED_JSON, raw)
                    .putInt(KEY_CACHED_VERSION, data.version)
                    .putString(KEY_CACHED_UPDATED_AT, data.updatedAt)
                    .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                    .apply()

                AppLogger.i(TAG, "Holiday data updated to v${data.version} from $source")
                return@withContext RefreshResult.Success(
                    data.version,
                    HolidayManager.getEffectiveYears()
                )
            }

            // 三个 JSON 源都失败时，最后用第三方接口兜底
            val apiResult = refreshFromApi(context)
            if (apiResult is RefreshResult.Success) {
                return@withContext apiResult
            }
            RefreshResult.Failure("$lastError；兜底接口：${(apiResult as RefreshResult.Failure).message}")
        }

    /**
     * 兜底：从 [API_URL] 按年拉取数据，转成与 [HolidayDataSet] 相同的结构后写入缓存。
     * 只覆盖当前年与下一年，更早/更晚的年份仍由内置数据兜底。
     * 单年请求失败不影响其他年份，两年都无数据才判定为失败。
     */
    private suspend fun refreshFromApi(context: Context): RefreshResult {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val years = listOf(currentYear, currentYear + 1)
        val holidaysByYear = LinkedHashMap<Int, MutableMap<String, String>>()
        val makeupByYear = LinkedHashMap<Int, MutableSet<String>>()
        var lastError = "接口暂无可用的节假日数据"

        years.forEachIndexed { index, year ->
            if (index > 0) delay(API_REQUEST_INTERVAL_MS)

            val raw = try {
                fetch(apiUrl(year), API_READ_TIMEOUT_MS)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Fetch holiday data from api failed: year=$year", e)
                lastError = "请求失败（${e.message ?: e.javaClass.simpleName}）"
                return@forEachIndexed
            }

            val response = runCatching { gson.fromJson(raw, ApiResponse::class.java) }.getOrNull()
            if (response == null) {
                AppLogger.e(TAG, "Parse api response failed: year=$year")
                lastError = "返回数据格式错误"
                return@forEachIndexed
            }
            if (response.code != 0) {
                AppLogger.w(TAG, "Api returned error: year=$year, code=${response.code}, msg=${response.msg}")
                lastError = if (response.code == API_CODE_RATE_LIMIT) {
                    "请求过于频繁，请稍后再试"
                } else {
                    "返回错误（code=${response.code}）"
                }
                return@forEachIndexed
            }

            val holidays = holidaysByYear.getOrPut(year) { LinkedHashMap() }
            val makeup = makeupByYear.getOrPut(year) { LinkedHashSet() }
            response.data?.list.orEmpty().forEach { day ->
                val date = formatApiDate(day.date) ?: return@forEach
                if (day.holidayRecess == API_RECESS_YES) {
                    holidays[date] = HolidayIdNames.nameOf(day.holiday) ?: API_FALLBACK_NAME
                }
                if (day.holidayOvertime != HolidayIdNames.NONE) {
                    makeup.add(date)
                }
            }
        }

        if (holidaysByYear.values.all { it.isEmpty() } && makeupByYear.values.all { it.isEmpty() }) {
            return RefreshResult.Failure(lastError)
        }

        val data = HolidayDataSet(
            version = API_VERSION,
            updatedAt = String.format(Locale.US, "%tY-%<tm-%<td", Date()),
            years = years.associate { year ->
                year.toString() to HolidayYear(
                    holidays = holidaysByYear[year] ?: emptyMap(),
                    makeupWorkdays = (makeupByYear[year] ?: emptySet()).toList()
                )
            }
        )

        if (!apply(data)) {
            return RefreshResult.Failure("接口数据校验失败，已保留原有数据")
        }

        // 与仓库 JSON 共用缓存结构，下次启动 loadCached 可直接注入
        prefs(context).edit()
            .putString(KEY_CACHED_JSON, gson.toJson(data))
            .putInt(KEY_CACHED_VERSION, data.version)
            .putString(KEY_CACHED_UPDATED_AT, data.updatedAt)
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .apply()

        AppLogger.i(TAG, "Holiday data updated from api (v${data.version}, years=${data.years.keys})")
        return RefreshResult.Success(data.version, HolidayManager.getEffectiveYears())
    }

    /** 接口按年整年拉取，只用必要字段控制响应体积 */
    private fun apiUrl(year: Int): String {
        val fields = URLEncoder.encode("date,holiday,holiday_overtime,holiday_recess", "UTF-8")
        return "$API_URL?year=$year&page=1&size=$API_PAGE_SIZE&field=$fields"
    }

    /** 接口日期为 yyyymmdd 整数，转为 yyyy-MM-dd；位数不对直接丢弃 */
    private fun formatApiDate(value: Int): String? {
        val text = value.toString()
        if (text.length != 8) return null
        return "${text.substring(0, 4)}-${text.substring(4, 6)}-${text.substring(6, 8)}"
    }

    /** 设置页展示用：内置/在线数据覆盖的年份与版本 */
    fun describe(): String {
        val years = HolidayManager.getEffectiveYears()
        if (years.isEmpty()) return "暂无可用数据"
        val range = if (years.size >= 2) "${years.first()} - ${years.last()}" else "${years.first()}"
        return if (HolidayManager.hasRemoteData()) {
            "覆盖年份：$range · 在线数据 v${HolidayManager.getRemoteVersion()}"
        } else {
            "覆盖年份：$range · 当前使用内置数据"
        }
    }

    fun getLastCheckTime(context: Context): Long = prefs(context).getLong(KEY_LAST_CHECK, 0L)

    private fun fetch(url: String, readTimeoutMs: Int = READ_TIMEOUT_MS): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = readTimeoutMs
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "PersonalOvertimeRecord")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val declared = connection.contentLengthLong
            if (declared > MAX_BYTES) throw IllegalStateException("响应过大")
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            connection.inputStream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > MAX_BYTES) throw IllegalStateException("响应过大")
                    output.write(buffer, 0, read)
                }
            }
            return output.toString("UTF-8")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 校验并注入数据。空数据或任一非法日期/名称都判定为失败，
     * 避免半截/被篡改的数据覆盖内置判定。
     */
    private fun apply(data: HolidayDataSet): Boolean {
        if (data.version < 0) return false
        val holidays = LinkedHashMap<String, String>()
        val makeup = LinkedHashSet<String>()

        for ((yearKey, yearData) in data.years) {
            val year = yearKey.toIntOrNull() ?: return false
            if (year !in MIN_YEAR..MAX_YEAR) return false
            for ((date, name) in yearData.holidays) {
                if (!isValidDate(date, year)) return false
                if (name.isBlank() || name.length > MAX_HOLIDAY_NAME_LENGTH) return false
                holidays[date] = name
            }
            for (date in yearData.makeupWorkdays) {
                if (!isValidDate(date, year)) return false
                makeup.add(date)
            }
        }

        if (holidays.isEmpty() && makeup.isEmpty()) return false
        HolidayManager.applyRemoteData(holidays, makeup, data.version)
        return true
    }

    private fun isValidDate(date: String, year: Int): Boolean {
        if (!DATE_REGEX.matches(date)) return false
        if (date.substring(0, 4).toIntOrNull() != year) return false
        // 不用 java.time（minSdk 24 且未开启脱糖），并关闭宽松解析防止 2026-02-30 通过
        val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
            isLenient = false
        }
        return runCatching { format.parse(date) }.getOrNull() != null
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
