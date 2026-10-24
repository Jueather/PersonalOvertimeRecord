package com.example.personalovertimerecord.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.example.personalovertimerecord.utils.EnvelopeCrypto
import com.example.personalovertimerecord.utils.SecurePreferencesManager
import com.example.personalovertimerecord.utils.WebDAVConfig

/**
 * 同步加密的本地密钥材料（信封加密方案）。
 *
 * @param dek 数据密钥（32B Base64）。保存在 Android Keystore 支撑的 EncryptedSharedPreferences 中，
 *        与同步密码处于同一信任边界；App 数据被清除后随之一并销毁，此时靠恢复码从云端信封找回。
 * @param recoveryCode 恢复码明文展示形式。仅"发起加密的设备"或"已用恢复码完成找回的设备"持有；
 *        通过同步密码接入的新设备为空串（不影响正常同步，但无法在本机展示/更换恢复码）。
 * @param recoverySlotB64 恢复槽（EnvelopeCrypto.WrappedSlot 字节的 Base64）。
 *        换密码/换设备上传新信封时原样复用，保证恢复码长期有效。
 */
data class SyncKeyMaterial(
    val dek: String,
    val recoveryCode: String,
    val recoverySlotB64: String,
    /** 当前同步密码包裹 DEK 的密码槽（缓存复用，避免每次上传重复 PBKDF2 派生） */
    val pwdSlotB64: String = "",
    /** [pwdSlotB64] 对应的同步密码；密码变更后需重新包裹 */
    val pwdSlotPassword: String = ""
) {
    val isComplete: Boolean get() = dek.isNotBlank() && recoverySlotB64.isNotBlank()
}

class SettingsManager(context: Context) {
    
    private val prefs: SharedPreferences = SecurePreferencesManager.getEncryptedPrefs(context)
    
    fun saveSettings(settings: OvertimeSettings) {
        prefs.edit().apply {
            putString(KEY_WORK_START, settings.workStartTime)
            putString(KEY_WORK_END, settings.workEndTime)
            putString(KEY_OVERTIME_RATE_NORMAL, settings.overtimeRateNormal.toString())
            putString(KEY_OVERTIME_RATE_WEEKEND, settings.overtimeRateWeekend.toString())
            putString(KEY_OVERTIME_RATE_HOLIDAY, settings.overtimeRateHoliday.toString())
            putString(KEY_BASE_SALARY, settings.baseSalary.toString())
            putString(KEY_PERFORMANCE_PERCENT, settings.performancePercent.toString())
            putString(KEY_MONTHLY_WORK_DAYS, settings.monthlyWorkDays.toString())
            putString(KEY_DAILY_WORK_HOURS, settings.dailyWorkHours.toString())
            // 五险一金与个税
            putString(KEY_SOCIAL_INSURANCE_BASE, settings.socialInsuranceBase.toString())
            putString(KEY_PENSION_RATE, settings.pensionRate.toString())
            putString(KEY_MEDICAL_RATE, settings.medicalRate.toString())
            putString(KEY_UNEMPLOYMENT_RATE, settings.unemploymentRate.toString())
            putString(KEY_HOUSING_FUND_BASE, settings.housingFundBase.toString())
            putString(KEY_HOUSING_FUND_RATE, settings.housingFundRate.toString())
            putString(KEY_SPECIAL_DEDUCTION, settings.specialDeduction.toString())
            putInt(KEY_SALARY_CONFIG_VERSION, settings.salaryConfigVersion)
            // 保存加密设置
            putBoolean(KEY_EXPORT_ENCRYPTION_ENABLED, settings.exportEncryptionEnabled)
            putString(KEY_EXPORT_PASSWORD, settings.exportPassword)
            putBoolean(KEY_SYNC_ENCRYPTION_ENABLED, settings.syncEncryptionEnabled)
            putString(KEY_SYNC_PASSWORD, settings.syncPassword)
            apply()
        }
    }
    
    /**
     * 保存来自备份文件/云端同步的设置。
     * 加密密码不随备份传输（OvertimeSettings 中为 transient），
     * 恢复时保留本机已保存的密码，避免密码被清空后下次同步变为明文上传。
     */
    fun saveSyncedSettings(settings: OvertimeSettings) {
        val current = getSettings()
        // 旧备份/旧云端数据没有五险一金字段（Gson 缺失字段为 0），
        // 此时保留本机已配置的缴费基数与比例，避免恢复后被静默清零
        val normalized = if (settings.salaryConfigVersion <= 0) {
            settings.copy(
                socialInsuranceBase = current.socialInsuranceBase,
                pensionRate = current.pensionRate,
                medicalRate = current.medicalRate,
                unemploymentRate = current.unemploymentRate,
                housingFundBase = current.housingFundBase,
                housingFundRate = current.housingFundRate,
                specialDeduction = current.specialDeduction,
                salaryConfigVersion = 1
            )
        } else {
            settings
        }
        saveSettings(
            normalized.copy(
                exportPassword = current.exportPassword,
                syncPassword = current.syncPassword
            )
        )
    }

    fun getSettings(): OvertimeSettings {
        return OvertimeSettings(
            workStartTime = prefs.getString(KEY_WORK_START, "08:00") ?: "08:00",
            workEndTime = prefs.getString(KEY_WORK_END, "17:00") ?: "17:00",
            overtimeRateNormal = prefs.getString(KEY_OVERTIME_RATE_NORMAL, "1.5")?.toDoubleOrNull() ?: 1.5,
            overtimeRateWeekend = prefs.getString(KEY_OVERTIME_RATE_WEEKEND, "2.0")?.toDoubleOrNull() ?: 2.0,
            overtimeRateHoliday = prefs.getString(KEY_OVERTIME_RATE_HOLIDAY, "3.0")?.toDoubleOrNull() ?: 3.0,
            baseSalary = prefs.getString(KEY_BASE_SALARY, "5000.0")?.toDoubleOrNull() ?: 5000.0,
            performancePercent = prefs.getString(KEY_PERFORMANCE_PERCENT, "0.0")?.toDoubleOrNull() ?: 0.0,
            monthlyWorkDays = prefs.getString(KEY_MONTHLY_WORK_DAYS, "21.75")?.toDoubleOrNull() ?: 21.75,
            dailyWorkHours = prefs.getString(KEY_DAILY_WORK_HOURS, "8.0")?.toDoubleOrNull() ?: 8.0,
            // 五险一金与个税
            socialInsuranceBase = prefs.getString(KEY_SOCIAL_INSURANCE_BASE, "0.0")?.toDoubleOrNull() ?: 0.0,
            pensionRate = prefs.getString(KEY_PENSION_RATE, "8.0")?.toDoubleOrNull() ?: 8.0,
            medicalRate = prefs.getString(KEY_MEDICAL_RATE, "2.0")?.toDoubleOrNull() ?: 2.0,
            unemploymentRate = prefs.getString(KEY_UNEMPLOYMENT_RATE, "0.5")?.toDoubleOrNull() ?: 0.5,
            housingFundBase = prefs.getString(KEY_HOUSING_FUND_BASE, "0.0")?.toDoubleOrNull() ?: 0.0,
            housingFundRate = prefs.getString(KEY_HOUSING_FUND_RATE, "12.0")?.toDoubleOrNull() ?: 12.0,
            specialDeduction = prefs.getString(KEY_SPECIAL_DEDUCTION, "0.0")?.toDoubleOrNull() ?: 0.0,
            salaryConfigVersion = prefs.getInt(KEY_SALARY_CONFIG_VERSION, 1),
            // 读取加密设置
            exportEncryptionEnabled = prefs.getBoolean(KEY_EXPORT_ENCRYPTION_ENABLED, false),
            exportPassword = prefs.getString(KEY_EXPORT_PASSWORD, "") ?: "",
            syncEncryptionEnabled = prefs.getBoolean(KEY_SYNC_ENCRYPTION_ENABLED, false),
            syncPassword = prefs.getString(KEY_SYNC_PASSWORD, "") ?: ""
        )
    }

    fun saveWebDAVConfig(config: WebDAVConfig) {
        prefs.edit().apply {
            putString(KEY_WEBDAV_SERVER_URL, config.serverUrl)
            putString(KEY_WEBDAV_USERNAME, config.username)
            putString(KEY_WEBDAV_PASSWORD, config.password)
            putString(KEY_WEBDAV_REMOTE_PATH, config.remotePath)
            apply()
        }
    }

    fun getWebDAVConfig(): WebDAVConfig? {
        val serverUrl = prefs.getString(KEY_WEBDAV_SERVER_URL, null) ?: return null
        val username = prefs.getString(KEY_WEBDAV_USERNAME, null) ?: return null
        val password = prefs.getString(KEY_WEBDAV_PASSWORD, null) ?: return null
        val remotePath = prefs.getString(KEY_WEBDAV_REMOTE_PATH, "/overtime_record/") ?: "/overtime_record/"
        
        return WebDAVConfig(
            serverUrl = serverUrl,
            username = username,
            password = password,
            remotePath = remotePath
        )
    }

    // ---- 快捷登记通知（设备本地偏好，不随备份同步） ----

    fun setQuickEntryNotificationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_QUICK_ENTRY_NOTIFICATION, enabled).apply()
    }

    fun isQuickEntryNotificationEnabled(): Boolean =
        prefs.getBoolean(KEY_QUICK_ENTRY_NOTIFICATION, true)

    fun saveLastSyncTime(time: Long) {
        prefs.edit().putLong(KEY_LAST_SYNC_TIME, time).apply()
    }

    fun getLastSyncTime(): Long {
        return prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
    }

    // ---- 团队同步账号（方案 B：多用户小团队工具） ----

    /**
     * 团队服务端登录态。[token] 为 JWT；[role] 为 "admin"/"member"（仅控制 UI 入口，
     * 服务端会实时查库二次校验）。与 WebDAV 配置同存于加密 SharedPreferences。
     */
    data class TeamAuthConfig(
        val serverUrl: String,
        val token: String,
        val userId: Long,
        val username: String,
        val displayName: String,
        val role: String
    ) {
        val isAdmin: Boolean get() = role == "admin"
    }

    fun saveTeamAuth(config: TeamAuthConfig) {
        prefs.edit().apply {
            putString(KEY_TEAM_SERVER_URL, config.serverUrl)
            putString(KEY_TEAM_TOKEN, config.token)
            putLong(KEY_TEAM_USER_ID, config.userId)
            putString(KEY_TEAM_USERNAME, config.username)
            putString(KEY_TEAM_DISPLAY_NAME, config.displayName)
            putString(KEY_TEAM_ROLE, config.role)
            apply()
        }
    }

    /** 读取团队登录态；未登录或字段缺失返回 null */
    fun getTeamAuth(): TeamAuthConfig? {
        val serverUrl = prefs.getString(KEY_TEAM_SERVER_URL, null) ?: return null
        val token = prefs.getString(KEY_TEAM_TOKEN, null) ?: return null
        if (serverUrl.isBlank() || token.isBlank()) return null
        return TeamAuthConfig(
            serverUrl = serverUrl,
            token = token,
            userId = prefs.getLong(KEY_TEAM_USER_ID, 0L),
            username = prefs.getString(KEY_TEAM_USERNAME, "") ?: "",
            displayName = prefs.getString(KEY_TEAM_DISPLAY_NAME, "") ?: "",
            role = prefs.getString(KEY_TEAM_ROLE, "member") ?: "member"
        )
    }

    /** 退出登录：清空账号态（保留团队服务器地址，方便下次登录） */
    fun clearTeamAuth() {
        prefs.edit().apply {
            remove(KEY_TEAM_TOKEN)
            remove(KEY_TEAM_USER_ID)
            remove(KEY_TEAM_USERNAME)
            remove(KEY_TEAM_DISPLAY_NAME)
            remove(KEY_TEAM_ROLE)
            apply()
        }
    }

    // ---- 信封加密：本地密钥材料 ----

    /** 读取同步加密密钥材料；未生成时返回 null（字段不全视为无效材料） */
    fun getSyncKeyMaterial(): SyncKeyMaterial? {
        val dek = prefs.getString(KEY_SYNC_DEK, null) ?: return null
        val recoverySlot = prefs.getString(KEY_SYNC_RECOVERY_SLOT, null) ?: return null
        if (dek.isBlank() || recoverySlot.isBlank()) return null
        return SyncKeyMaterial(
            dek = dek,
            recoveryCode = prefs.getString(KEY_SYNC_RECOVERY_CODE, "") ?: "",
            recoverySlotB64 = recoverySlot,
            pwdSlotB64 = prefs.getString(KEY_SYNC_PWD_SLOT, "") ?: "",
            pwdSlotPassword = prefs.getString(KEY_SYNC_PWD_SLOT_PASSWORD, "") ?: ""
        )
    }

    fun saveSyncKeyMaterial(material: SyncKeyMaterial) {
        prefs.edit().apply {
            putString(KEY_SYNC_DEK, material.dek)
            putString(KEY_SYNC_RECOVERY_CODE, material.recoveryCode)
            putString(KEY_SYNC_RECOVERY_SLOT, material.recoverySlotB64)
            putString(KEY_SYNC_PWD_SLOT, material.pwdSlotB64)
            putString(KEY_SYNC_PWD_SLOT_PASSWORD, material.pwdSlotPassword)
            apply()
        }
    }

    /** 缓存/更新密码槽（密码变更后重新包裹 DEK 时调用），避免每次上传重复 PBKDF2 派生 */
    fun savePwdSlot(pwdSlotB64: String, password: String) {
        prefs.edit().apply {
            putString(KEY_SYNC_PWD_SLOT, pwdSlotB64)
            putString(KEY_SYNC_PWD_SLOT_PASSWORD, password)
            apply()
        }
    }

    /**
     * 接入云端既有信封的 DEK / 恢复槽（重装、换机，或本地材料与云端 DEK 不一致时）。
     * 与密码槽同时可用时一并缓存（[pwdSlotB64]/[pwdSlotPassword] 传空串则清除旧缓存）。
     * 必须清掉本机可能误存的恢复码明文与待保存标记，
     * 否则设置页会展示一个已失效的"新恢复码"，而云端真正生效的仍是原恢复码。
     */
    fun adoptCloudKeyMaterial(
        dekB64: String,
        recoverySlotB64: String,
        pwdSlotB64: String = "",
        pwdSlotPassword: String = ""
    ) {
        prefs.edit().apply {
            putString(KEY_SYNC_DEK, dekB64)
            putString(KEY_SYNC_RECOVERY_SLOT, recoverySlotB64)
            putString(KEY_SYNC_PWD_SLOT, pwdSlotB64)
            putString(KEY_SYNC_PWD_SLOT_PASSWORD, pwdSlotPassword)
            remove(KEY_SYNC_RECOVERY_CODE)
            putBoolean(KEY_SYNC_RECOVERY_PENDING, false)
            apply()
        }
    }

    /** 清除同步加密密钥材料（关闭同步加密时调用） */
    fun clearSyncKeyMaterial() {
        prefs.edit().apply {
            remove(KEY_SYNC_DEK)
            remove(KEY_SYNC_RECOVERY_CODE)
            remove(KEY_SYNC_RECOVERY_SLOT)
            remove(KEY_SYNC_PWD_SLOT)
            remove(KEY_SYNC_PWD_SLOT_PASSWORD)
            putBoolean(KEY_SYNC_RECOVERY_PENDING, false)
            apply()
        }
    }

    /**
     * 标记"有新生成的恢复码等待用户离线保存"。
     * 由同步流程在旧格式数据首次迁移为信封格式时置位，设置页展示醒目横幅提醒。
     */
    fun setRecoveryCodePending(pending: Boolean) {
        prefs.edit().putBoolean(KEY_SYNC_RECOVERY_PENDING, pending).apply()
    }

    fun isRecoveryCodePending(): Boolean = prefs.getBoolean(KEY_SYNC_RECOVERY_PENDING, false)

    /**
     * 生成全新的同步加密密钥材料（DEK + 恢复码 + 恢复槽 + 密码槽）并持久化。
     * 首次开启同步加密、或主动更换恢复码时调用；[password] 用于预包裹密码槽，
     * 使首次上传即可复用（省一次 PBKDF2 派生）。
     */
    fun generateAndSaveSyncKeyMaterial(password: String): SyncKeyMaterial {
        val dek = EnvelopeCrypto.generateDek()
        val recoveryCode = com.example.personalovertimerecord.utils.RecoveryCodeManager.generate()
        val slot = EnvelopeCrypto.wrapDek(
            dek,
            com.example.personalovertimerecord.utils.RecoveryCodeManager.normalize(recoveryCode)
        )
        val pwdSlot = EnvelopeCrypto.wrapDek(dek, password)
        val material = SyncKeyMaterial(
            dek = Base64.encodeToString(dek, Base64.NO_WRAP),
            recoveryCode = recoveryCode,
            recoverySlotB64 = Base64.encodeToString(slot.toBytes(), Base64.NO_WRAP),
            pwdSlotB64 = Base64.encodeToString(pwdSlot.toBytes(), Base64.NO_WRAP),
            pwdSlotPassword = password
        )
        saveSyncKeyMaterial(material)
        return material
    }

    companion object {
        private const val PREFS_NAME = "overtime_settings"
        private const val KEY_WORK_START = "work_start"
        private const val KEY_WORK_END = "work_end"
        private const val KEY_OVERTIME_RATE_NORMAL = "overtime_rate_normal"
        private const val KEY_OVERTIME_RATE_WEEKEND = "overtime_rate_weekend"
        private const val KEY_OVERTIME_RATE_HOLIDAY = "overtime_rate_holiday"
        private const val KEY_BASE_SALARY = "base_salary"
        private const val KEY_PERFORMANCE_PERCENT = "performance_percent"
        private const val KEY_MONTHLY_WORK_DAYS = "monthly_work_days"
        private const val KEY_DAILY_WORK_HOURS = "daily_work_hours"
        // 五险一金与个税
        private const val KEY_SOCIAL_INSURANCE_BASE = "social_insurance_base"
        private const val KEY_PENSION_RATE = "pension_rate"
        private const val KEY_MEDICAL_RATE = "medical_rate"
        private const val KEY_UNEMPLOYMENT_RATE = "unemployment_rate"
        private const val KEY_HOUSING_FUND_BASE = "housing_fund_base"
        private const val KEY_HOUSING_FUND_RATE = "housing_fund_rate"
        private const val KEY_SPECIAL_DEDUCTION = "special_deduction"
        private const val KEY_SALARY_CONFIG_VERSION = "salary_config_version"
        // 快捷登记通知
        private const val KEY_QUICK_ENTRY_NOTIFICATION = "quick_entry_notification"
        private const val KEY_WEBDAV_SERVER_URL = "webdav_server_url"
        private const val KEY_WEBDAV_USERNAME = "webdav_username"
        private const val KEY_WEBDAV_PASSWORD = "webdav_password"
        private const val KEY_WEBDAV_REMOTE_PATH = "webdav_remote_path"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        // 团队同步账号键
        private const val KEY_TEAM_SERVER_URL = "team_server_url"
        private const val KEY_TEAM_TOKEN = "team_token"
        private const val KEY_TEAM_USER_ID = "team_user_id"
        private const val KEY_TEAM_USERNAME = "team_username"
        private const val KEY_TEAM_DISPLAY_NAME = "team_display_name"
        private const val KEY_TEAM_ROLE = "team_role"
        // 加密设置键
        private const val KEY_EXPORT_ENCRYPTION_ENABLED = "export_encryption_enabled"
        private const val KEY_EXPORT_PASSWORD = "export_password"
        private const val KEY_SYNC_ENCRYPTION_ENABLED = "sync_encryption_enabled"
        private const val KEY_SYNC_PASSWORD = "sync_password"
        // 信封加密密钥材料键
        private const val KEY_SYNC_DEK = "sync_dek"
        private const val KEY_SYNC_RECOVERY_CODE = "sync_recovery_code"
        private const val KEY_SYNC_RECOVERY_SLOT = "sync_recovery_slot"
        private const val KEY_SYNC_RECOVERY_PENDING = "sync_recovery_pending"
        private const val KEY_SYNC_PWD_SLOT = "sync_pwd_slot"
        private const val KEY_SYNC_PWD_SLOT_PASSWORD = "sync_pwd_slot_password"
    }
}
