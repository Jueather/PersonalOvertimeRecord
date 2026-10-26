package com.example.personalovertimerecord.utils

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.example.personalovertimerecord.data.SettingsManager
import com.example.personalovertimerecord.data.model.TeamApiException
import com.example.personalovertimerecord.data.model.TrialRedeemResponse
import com.example.personalovertimerecord.data.model.TrialStatus
import com.example.personalovertimerecord.data.model.TrialStatusResponse
import java.security.MessageDigest
import java.util.UUID

/**
 * 试用/激活本地管理器 — 三层冗余模型：
 *
 *   Layer 1（强信任）:  服务器在线 → 调用 /api/trial/redeem，绑定设备后由服务端决定有效期
 *   Layer 2（中等信任）: 服务器离线 → HMAC-SHA256 签名离线码校验 [OfflineCodeValidator]
 *                       码内 encode 绝对过期时间 + deviceId 前缀，防止改时钟和跨设备
 *   Layer 3（最弱兜底）: 服务器未配置/离线、也没激活码 → 免费安装试用
 *                       用 PackageManager.firstInstallTime 作为起点（非 SharedPreferences，防清数据绕过）
 *
 * 设计铁律：
 *   - 服务器结果永远优先（同为 ACTIVE 时取较长的 expiresAt）
 *   - syncFromServer 网络失败时 **静默保留本地缓存**，绝不覆盖成 NO_LICENSE
 *   - 剩余天数用 Math.floor，避免"永远显示 1 天"的错觉
 *   - 设备指纹稳定：SHA-256(Android ID)，取不到时 fallback 持久化固定 UUID
 */
class TrialLicenseManager(private val context: Context) {

    private val prefs = SecurePreferencesManager.getEncryptedPrefs(context)

    // ========== 设备指纹 ==========

    /**
     * 稳定设备 ID（跨应用安装持久）。
     * 优先 SHA-256(Secure.ANDROID_ID)，取不到时使用首次生成并持久化的固定 UUID。
     */
    fun getDeviceId(): String {
        prefs.getString(KEY_DEVICE_ID_FALLBACK, null)?.let { return it }

        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        if (!androidId.isNullOrBlank()) {
            val hash = sha256Hex(androidId)
            prefs.edit().putString(KEY_DEVICE_ID_FALLBACK, hash).apply()
            return hash
        }

        val fallback = UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString(KEY_DEVICE_ID_FALLBACK, fallback).apply()
        return fallback
    }

    /** 用户可读的设备 ID（截短 + 分组），方便发给开发者生成离线码 */
    fun getDeviceIdReadable(): String {
        val raw = getDeviceId()
        return raw.chunked(4).take(4).joinToString("-") + "…"
    }

    // ========== 本地状态存储 ==========

    private var cached: LocalLicenseState? = null

    private fun readLocal(): LocalLicenseState {
        cached?.let { return it }

        val source = prefs.getString(KEY_LICENSE_SOURCE, null) // "SERVER" | "OFFLINE" | null(免费试用)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, -1L)

        if (source != null && expiresAt >= 0) {
            val activatedAt = prefs.getLong(KEY_ACTIVATED_AT, 0L)
            val trialDays = prefs.getInt(KEY_TRIAL_DAYS, 0)
            val state = LocalLicenseState.Active(
                expiresAt = expiresAt,
                activatedAt = activatedAt,
                trialDays = trialDays,
                source = LicenseSource.valueOf(source)
            )
            cached = state
            return state
        }

        // 从未激活过 → 检查是否有免费安装试用
        val firstInstall = getFirstInstallTime()
        if (firstInstall > 0) {
            val freeExpires = firstInstall + FREE_TRIAL_DAYS_MS
            cached = LocalLicenseState.FreeTrial(
                expiresAt = freeExpires,
                firstInstallAt = firstInstall,
                trialDays = FREE_TRIAL_DAYS
            )
            return cached!!
        }

        return LocalLicenseState.NO_LICENSE.also { cached = it }
    }

    private fun writeLocal(state: LocalLicenseState) {
        cached = state
        prefs.edit().apply {
            when (state) {
                LocalLicenseState.NO_LICENSE -> {
                    remove(KEY_LICENSE_SOURCE)
                    remove(KEY_EXPIRES_AT)
                    remove(KEY_ACTIVATED_AT)
                    remove(KEY_TRIAL_DAYS)
                }
                is LocalLicenseState.Active -> {
                    putString(KEY_LICENSE_SOURCE, state.source.name)
                    putLong(KEY_EXPIRES_AT, state.expiresAt)
                    putLong(KEY_ACTIVATED_AT, state.activatedAt)
                    putInt(KEY_TRIAL_DAYS, state.trialDays)
                }
                is LocalLicenseState.FreeTrial -> {
                    // FreeTrial 是 computed state（基于 firstInstallTime），不需要持久化 expiresAt
                    // 但存一下标记，方便判断"是否首次安装期"
                    remove(KEY_LICENSE_SOURCE)
                    remove(KEY_EXPIRES_AT)
                    remove(KEY_ACTIVATED_AT)
                    remove(KEY_TRIAL_DAYS)
                }
            }
            apply()
        }
    }

    /** 纯离线判定（包含免费试用） */
    fun checkLocalStatus(): LocalLicenseState = readLocal()

    /**
     * 当前是否处于活跃使用期（未过期）。
     * NO_LICENSE 且无免费试用 → false；有激活且未过期 → true。
     */
    fun isCurrentlyValid(): Boolean {
        val state = checkLocalStatus()
        return when (state) {
            LocalLicenseState.NO_LICENSE -> false
            is LocalLicenseState.Active -> state.expiresAt > System.currentTimeMillis()
            is LocalLicenseState.FreeTrial -> state.expiresAt > System.currentTimeMillis()
        }
    }

    /**
     * 剩余完整天数（向下取整）。未激活/已过期返回 null/负值。
     */
    fun remainingDays(): Int? {
        val state = checkLocalStatus()
        val expiresAt = when (state) {
            LocalLicenseState.NO_LICENSE -> return null
            is LocalLicenseState.Active -> state.expiresAt
            is LocalLicenseState.FreeTrial -> state.expiresAt
        }
        val msLeft = expiresAt - System.currentTimeMillis()
        return (msLeft / (24L * 60L * 60L * 1000L)).toInt()
    }

    /** 是否有任何形式的有效激活（含免费试用） */
    fun hasAnyLicense(): Boolean = when (checkLocalStatus()) {
        LocalLicenseState.NO_LICENSE -> false
        else -> true
    }

    /** 是否处于免费试用而非正式激活 */
    fun isFreeTrial(): Boolean = checkLocalStatus() is LocalLicenseState.FreeTrial

    // ========== redeem 三层降级 ==========

    /**
     * 兑换激活码（三层降级）：
     *   1. 服务器在线 → 走 /api/trial/redeem
     *   2. 网络失败 → OfflineCodeValidator 本地 HMAC 校验
     *   3. 都不行 → 返回 Error（但免费试用仍自动生效，不阻塞）
     */
    suspend fun redeemCode(code: String): RedeemResult {
        val deviceId = getDeviceId()
        val serverUrl = getServerUrl()

        // Layer 1: 尝试服务器 redeem
        var serverSuccess = false
        var serverErrorMsg: String? = null
        var serverReachable = false

        if (serverUrl != null) {
            val result = runCatching {
                TeamApiClient(serverUrl).redeemTrial(code, deviceId)
            }
            result.getOrNull()?.let { resp ->
                serverSuccess = true
                writeLocal(
                    LocalLicenseState.Active(
                        expiresAt = resp.expiresAt,
                        activatedAt = resp.activatedAt,
                        trialDays = resp.trialDays,
                        source = LicenseSource.SERVER
                    )
                )
                return RedeemResult.Success(resp, LicenseSource.SERVER)
            }
            result.exceptionOrNull()?.let { e ->
                serverReachable = e !is TeamApiException || e.kind != TeamApiException.Kind.NETWORK
                serverErrorMsg = when (e) {
                    is TeamApiException -> TeamErrors.message(e)
                    else -> e.message ?: "服务器连接失败"
                }
            }
        }

        // Layer 2: 离线 HMAC 校验（无论服务器是否可达，都走一遍）
        val validation = OfflineCodeValidator.validateCode(code, deviceId)
        if (validation is ValidationResult.Valid) {
            val now = System.currentTimeMillis()
            writeLocal(
                LocalLicenseState.Active(
                    expiresAt = validation.expiresAtMs,
                    activatedAt = validation.activatedAtMs,
                    trialDays = validation.trialDays,
                    source = LicenseSource.OFFLINE
                )
            )
            val response = TrialRedeemResponse(
                status = TrialStatus.ACTIVE,
                remainingMs = (validation.expiresAtMs - now).coerceAtLeast(0L),
                expiresAt = validation.expiresAtMs,
                activatedAt = validation.activatedAtMs,
                trialDays = validation.trialDays,
                serverTime = now
            )
            return RedeemResult.Success(response, LicenseSource.OFFLINE)
        }

        // 都不行：优先展示服务器的错误（服务器可达说明用户输入的码格式可能是服务器码但业务上无效），
        // 否则展示离线验证的错误
        return when {
            serverReachable && serverErrorMsg != null -> RedeemResult.Error(serverErrorMsg!!)
            else -> RedeemResult.Error(validation.userMessage())
        }
    }

    /**
     * 从服务器刷新试用状态。
     * 网络失败时 **静默保留本地缓存**，不做任何覆盖——这是信任边界的关键：
     * 服务器不可达时我们绝不怀疑之前的激活记录。
     */
    suspend fun syncFromServer(): TrialStatusResponse? {
        val serverUrl = getServerUrl() ?: return null
        val resp = runCatching {
            val client = TeamApiClient(serverUrl)
            client.checkTrialStatus(getDeviceId())
        }.getOrNull() ?: return null // 网络失败 → 保留本地缓存

        when (resp.status) {
            TrialStatus.ACTIVE, TrialStatus.EXPIRED -> {
                val exp = resp.expiresAt ?: 0L
                val act = resp.activatedAt ?: 0L
                val days = resp.trialDays ?: 0

                // 合并策略：服务器结果永远优先，但如果本地已有更长的有效期则保留较长者
                val current = cached
                if (current is LocalLicenseState.Active && current.expiresAt > exp && current.source == LicenseSource.SERVER) {
                    // 保留本地更长的（可能是之前成功 redeem 还没来得及同步）
                } else {
                    writeLocal(
                        LocalLicenseState.Active(
                            expiresAt = exp,
                            activatedAt = act,
                            trialDays = days,
                            source = LicenseSource.SERVER
                        )
                    )
                }
            }
            TrialStatus.NO_LICENSE -> {
                // 服务器说 NO_LICENSE，但本地可能有离线激活或免费试用 → 保留本地
                if (cached !is LocalLicenseState.Active) {
                    writeLocal(LocalLicenseState.NO_LICENSE)
                }
            }
        }
        return resp
    }

    // ========== 免费试用兜底 ==========

    private fun getFirstInstallTime(): Long {
        return try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            pi.firstInstallTime
        } catch (_: PackageManager.NameNotFoundException) {
            0L
        }
    }

    // ========== 辅助 ==========

    private fun getServerUrl(): String? = SettingsManager(context).getTrialServerUrl()

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_DEVICE_ID_FALLBACK = "trial_device_id_fallback"
        private const val KEY_LICENSE_SOURCE = "trial_license_source"
        private const val KEY_EXPIRES_AT = "trial_expires_at"
        private const val KEY_ACTIVATED_AT = "trial_activated_at"
        private const val KEY_TRIAL_DAYS = "trial_days"

        /** 未激活设备的免费安装试用天数兜底 */
        private const val FREE_TRIAL_DAYS = 7
        private const val FREE_TRIAL_DAYS_MS = FREE_TRIAL_DAYS * 24L * 60L * 60L * 1000L
    }
}

/** 激活来源：服务器强信任 / 离线 HMAC 签名 / 免费安装试用 */
enum class LicenseSource { SERVER, OFFLINE }

/** 本地授权状态（三种） */
sealed class LocalLicenseState {
    /** 从未有过任何激活（也过了免费试用期） */
    data object NO_LICENSE : LocalLicenseState()

    /** 有服务器/离线码激活记录（expiresAt 可能已过期） */
    data class Active(
        val expiresAt: Long,
        val activatedAt: Long,
        val trialDays: Int,
        val source: LicenseSource
    ) : LocalLicenseState()

    /** 免费安装试用：基于 PackageManager.firstInstallTime 计算 */
    data class FreeTrial(
        val expiresAt: Long,
        val firstInstallAt: Long,
        val trialDays: Int
    ) : LocalLicenseState()
}

/** redeem 返回结果 */
sealed class RedeemResult {
    data class Success(
        val response: TrialRedeemResponse,
        val source: LicenseSource
    ) : RedeemResult()

    data class Error(val message: String) : RedeemResult()
}
