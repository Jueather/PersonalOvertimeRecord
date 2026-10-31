package com.example.personalovertimerecord.utils

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 解压小游戏的节日活动配置
 *
 * 只下发"展示层"参数（活动标题、时间范围、配色），玩法仍由本地代码完成：
 * - 进入解压页时读取本机缓存（离线可用），超过 [REFRESH_INTERVAL_MS] 才尝试联网刷新
 * - 远程配置不可达或格式非法时，继续使用缓存；缓存也没有则保持默认配色
 * - 活动到期后自动失效，无需发版
 *
 * JSON 结构（仓库根目录 relax/campaign.json）：
 * {
 *   "version": 1,
 *   "updatedAt": "2026-10-31",
 *   "campaigns": [
 *     { "id": "spring-festival-2027", "title": "春节限定", "subtitle": "泡泡纸也过年",
 *       "startDate": "2027-01-20", "endDate": "2027-02-20",
 *       "colors": ["#D32F2F", "#FFC107", "#FF7043"] }
 *   ]
 * }
 */
object RelaxCampaignStore {

    private const val TAG = "RelaxCampaignStore"
    private const val PREFS_NAME = "relax_campaign"
    private const val KEY_JSON = "json"
    private const val KEY_LAST_CHECK = "last_check"

    /** 与节假日数据一致：仓库固定地址 + CDN 镜像兜底 */
    const val SOURCE_URL =
        "https://raw.githubusercontent.com/Jueather/PersonalOvertimeRecord/main/relax/campaign.json"

    private val FALLBACK_URLS = listOf(
        "https://cdn.jsdelivr.net/gh/Jueather/PersonalOvertimeRecord@main/relax/campaign.json",
        "https://fastly.jsdelivr.net/gh/Jueather/PersonalOvertimeRecord@main/relax/campaign.json",
        "https://gcore.jsdelivr.net/gh/Jueather/PersonalOvertimeRecord@main/relax/campaign.json"
    )

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 7_000
    private const val MAX_BYTES = 64 * 1024
    private const val REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private const val MAX_CAMPAIGNS = 20
    private const val MAX_ID_LENGTH = 32
    private const val MAX_TITLE_LENGTH = 40
    private const val MAX_SUBTITLE_LENGTH = 80

    private val DATE_REGEX = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val COLOR_REGEX = Regex("""^#[0-9A-Fa-f]{6}$""")

    /** 游戏页面通过 Intent 携带主题色的 key */
    const val EXTRA_THEME_COLORS = "relax_theme_colors"

    private val gson = Gson()

    data class ActiveCampaign(
        val id: String,
        val title: String,
        val subtitle: String,
        val colors: IntArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ActiveCampaign) return false
            return id == other.id && title == other.title && subtitle == other.subtitle &&
                colors.contentEquals(other.colors)
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + title.hashCode()
            result = 31 * result + subtitle.hashCode()
            result = 31 * result + colors.contentHashCode()
            return result
        }
    }

    private data class CampaignFile(
        val version: Int = 0,
        val updatedAt: String? = null,
        val campaigns: List<Campaign> = emptyList()
    )

    private data class Campaign(
        val id: String = "",
        val title: String = "",
        val subtitle: String = "",
        val startDate: String = "",
        val endDate: String = "",
        val colors: List<String> = emptyList()
    )

    @Volatile
    private var active: ActiveCampaign? = null

    /** 当前生效的活动（无活动时为 null） */
    fun activeCampaign(): ActiveCampaign? = active

    /**
     * 进入解压页时调用：先用缓存，超过刷新间隔且联网时再尝试更新。
     * 网络请求在 IO 线程执行，调用方在协程里 await 结果即可（失败静默）。
     */
    suspend fun loadForDisplay(context: Context): ActiveCampaign? = withContext(Dispatchers.IO) {
        loadCached(context)
        val stale = System.currentTimeMillis() - lastCheck(context) > REFRESH_INTERVAL_MS
        if (stale && NetworkUtils.isNetworkAvailable(context)) {
            refresh(context)
        }
        active
    }

    /** 读取本机缓存的配置（不联网、失败静默） */
    fun loadCached(context: Context) {
        val raw = prefs(context).getString(KEY_JSON, null) ?: return
        val file = runCatching { gson.fromJson(raw, CampaignFile::class.java) }.getOrNull() ?: return
        active = resolve(file)
    }

    private suspend fun refresh(context: Context) {
        val sources = listOf(SOURCE_URL) + FALLBACK_URLS
        for (source in sources) {
            val raw = runCatching { fetch(source) }.getOrNull()
            if (raw.isNullOrBlank()) continue

            val file = runCatching { gson.fromJson(raw, CampaignFile::class.java) }.getOrNull()
            if (file == null || file.version < 0 || file.campaigns.size > MAX_CAMPAIGNS) {
                AppLogger.w(TAG, "配置格式非法或超出上限，跳过该源：$source")
                continue
            }

            val resolved = resolve(file)
            prefs(context).edit()
                .putString(KEY_JSON, raw)
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                .apply()
            active = resolved
            AppLogger.i(TAG, "解压活动配置已更新：${resolved?.id ?: "无生效活动"}（$source）")
            return
        }

        // 全部源失败也记录检查时间，避免每次进入都发起重试
        prefs(context).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        AppLogger.w(TAG, "解压活动配置拉取失败，继续使用缓存")
    }

    /** 选出今天生效的第一条合法活动 */
    private fun resolve(file: CampaignFile): ActiveCampaign? {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val campaign = file.campaigns.firstOrNull { isValid(it) && today in it.startDate..it.endDate }
            ?: return null
        return ActiveCampaign(
            id = campaign.id,
            title = campaign.title,
            subtitle = campaign.subtitle,
            colors = campaign.colors.map { Color.parseColor(it) }.toIntArray()
        )
    }

    private fun isValid(campaign: Campaign): Boolean {
        if (campaign.id.isBlank() || campaign.id.length > MAX_ID_LENGTH) return false
        if (campaign.title.isBlank() || campaign.title.length > MAX_TITLE_LENGTH) return false
        if (campaign.subtitle.length > MAX_SUBTITLE_LENGTH) return false
        if (!DATE_REGEX.matches(campaign.startDate) || !DATE_REGEX.matches(campaign.endDate)) return false
        if (!isRealDate(campaign.startDate) || !isRealDate(campaign.endDate)) return false
        if (campaign.startDate > campaign.endDate) return false
        if (campaign.colors.size != THEME_COLOR_COUNT) return false
        return campaign.colors.all { COLOR_REGEX.matches(it) }
    }

    private fun isRealDate(date: String): Boolean {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
        return runCatching { format.parse(date) }.getOrNull() != null
    }

    private fun fetch(url: String): String {
        if (!url.startsWith("https://")) throw IllegalStateException("仅支持 https")
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "PersonalOvertimeRecord")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            if (connection.contentLengthLong > MAX_BYTES) throw IllegalStateException("响应过大")
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

    private fun lastCheck(context: Context): Long = prefs(context).getLong(KEY_LAST_CHECK, 0L)

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 活动配色固定三个色值，呼吸三阶段/泡泡配色共用 */
    private const val THEME_COLOR_COUNT = 3
}
