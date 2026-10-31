package com.example.personalovertimerecord.utils

import android.content.Context
import android.util.Base64
import com.example.personalovertimerecord.data.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 开启同步加密时云端探测结果（见 [SyncKeyManager.bootstrapKeyMaterialOnEnable]） */
sealed class KeyBootstrapResult {
    /** 已接入云端既有信封：原恢复码继续有效，本机不保存其明文 */
    object Adopted : KeyBootstrapResult()
    /** 云端无信封（无备份/明文/旧版密文）：已生成全新 DEK 与恢复码，请引导用户离线保存 */
    data class Generated(val recoveryCode: String) : KeyBootstrapResult()
    /** 云端是加密备份但当前密码无法解开（信封密码错，或旧密文密码错） */
    object WrongPassword : KeyBootstrapResult()
    /** 未配置 WebDAV */
    object NoConfig : KeyBootstrapResult()
    /** 无网络或云端暂时不可读：不生成新材料，推迟到下次同步时自动接入/生成 */
    object Unavailable : KeyBootstrapResult()
}

/**
 * 同步密钥管理器
 * 负责同步加密的密钥材料生命周期：DEK 的生成/接入/落盘（经 SettingsManager）、
 * 上传内容的信封封装、恢复码找回、以及开启加密时的云端探测（重装/换机密钥谱系保护）。
 * 不负责同步流程编排与数据合并（见 [SyncManager]）。
 */
internal class SyncKeyManager(
    private val context: Context,
    private val settingsManager: SettingsManager,
    private val webDAVManager: WebDAVManager,
    private val cloudResolver: CloudBackupResolver
) {

    /**
     * 遗忘同步密码时的恢复码找回（不获取全局同步互斥锁：只下载校验与写本地材料，不上传）。
     * 校验恢复码能解开云端信封后，保存新同步密码与 DEK；调用方随后应执行一次正常同步拉取数据。
     * 已验证的云端原文会暂存到 [cloudResolver]，同一实例随后的 performSync 可复用（单次、60 秒过期）。
     *
     * @return [SyncResult.SUCCESS] 表示新密码已生效；其余为失败原因
     */
    suspend fun validateAndApplyRecovery(recoveryCode: String, newPassword: String): SyncResult =
        withContext(Dispatchers.IO) {
            val config = settingsManager.getWebDAVConfig()
                ?: return@withContext SyncResult.NO_CONFIG
            if (!NetworkUtils.isNetworkAvailable(context)) {
                return@withContext SyncResult.NO_NETWORK
            }

            val raw = webDAVManager.downloadRawFile(config)
                ?: return@withContext SyncResult.DOWNLOAD_FAILED // 云端无备份或不可读，无法凭恢复码找回

            if (!EnvelopeCrypto.isEnvelope(raw)) {
                // 云端仍是旧格式（密码直加密），信封中没有恢复槽，恢复码无法使用
                return@withContext SyncResult.ENCRYPTION_MISMATCH
            }

            val extracted = EnvelopeCrypto.extractDekAndRecoverySlotWithRecoveryCode(raw, recoveryCode)
                ?: return@withContext SyncResult.ENCRYPTION_MISMATCH
            val (dek, slot) = extracted

            // 恢复码验证通过：保存新密码（保留其他设置），落盘 DEK/恢复槽
            val current = settingsManager.getSettings()
            settingsManager.saveSettings(
                current.copy(syncEncryptionEnabled = true, syncPassword = newPassword)
            )
            settingsManager.adoptCloudKeyMaterial(
                Base64.encodeToString(dek, Base64.NO_WRAP),
                Base64.encodeToString(slot.toBytes(), Base64.NO_WRAP)
            )
            // 用新密码本地重包 DEK 的密码槽：云端信封的密码槽仍由被遗忘的旧密码包裹，
            // 下次上传时 buildUploadWire 复用此缓存即可用新密码槽重建信封（云端自愈），
            // 否则云端永远无法用新密码打开（上传又被"解密失败即中止"门禁挡住）
            val newPwdSlot = EnvelopeCrypto.wrapDek(dek, newPassword)
            settingsManager.savePwdSlot(
                Base64.encodeToString(newPwdSlot.toBytes(), Base64.NO_WRAP),
                newPassword
            )
            settingsManager.setRecoveryCodePending(false)
            // DEK 局部副本已写入加密 prefs，不再使用，立即清零
            java.util.Arrays.fill(dek, 0)
            // 缓存已验证的云端原文，供紧接着的 performSync 复用（单次、60 秒过期）
            cloudResolver.stashRecoveredSnapshot(raw)
            AppLogger.d("恢复码验证成功，已重置同步密码")
            SyncResult.SUCCESS
        }

    /**
     * 开启同步加密且本机无密钥材料时调用（重装/换机场景的关键保护）：
     * 必须先读云端再决定，不能直接生成新 DEK——否则首次同步会用新 DEK 覆盖云端信封，
     * 导致用户离线保存的原恢复码永久失效。
     *
     * - 云端是信封且密码正确：接入其 DEK 与恢复槽（恢复码明文本机不可见，但继续有效）；
     * - 云端无文件 / 明文 JSON / 旧版密码密文（密码可解）：不存在信封谱系，生成全新材料；
     * - 密码解不开：[KeyBootstrapResult.WrongPassword]，不生成任何材料；
     * - 网络等暂时失败：[KeyBootstrapResult.Unavailable]，交由后续同步流程兜底处理。
     */
    suspend fun bootstrapKeyMaterialOnEnable(password: String): KeyBootstrapResult =
        withContext(Dispatchers.IO) {
            val config = settingsManager.getWebDAVConfig()
                ?: return@withContext KeyBootstrapResult.NoConfig
            if (!NetworkUtils.isNetworkAvailable(context)) {
                return@withContext KeyBootstrapResult.Unavailable
            }

            val raw = try {
                webDAVManager.downloadRawFile(config)
            } catch (e: Exception) {
                AppLogger.e("开启加密时探测云端失败", e)
                return@withContext KeyBootstrapResult.Unavailable
            }

            // 云端无备份：全新启用，生成新材料
            if (raw == null) {
                return@withContext if (WebDAVManager.lastResponseCode == 404) {
                    generateFreshMaterial(password)
                } else {
                    KeyBootstrapResult.Unavailable
                }
            }

            // 明文备份：首次加密，生成新材料（下次同步完成迁移）
            if (cloudResolver.isJsonLike(raw)) {
                return@withContext generateFreshMaterial(password)
            }

            // 云端已是信封：只有密码正确才允许接入，绝不能另起 DEK 谱系
            if (EnvelopeCrypto.isEnvelope(raw)) {
                val slots = EnvelopeCrypto.extractDekAndSlotsWithPassword(raw, password)
                    ?: return@withContext KeyBootstrapResult.WrongPassword
                val (dek, pwdSlot, recSlot) = slots
                settingsManager.adoptCloudKeyMaterial(
                    Base64.encodeToString(dek, Base64.NO_WRAP),
                    Base64.encodeToString(recSlot.toBytes(), Base64.NO_WRAP),
                    Base64.encodeToString(pwdSlot.toBytes(), Base64.NO_WRAP),
                    password
                )
                // DEK 已写入加密 prefs，局部副本清零
                java.util.Arrays.fill(dek, 0)
                AppLogger.d("检测到云端加密备份，已接入现有恢复码谱系")
                return@withContext KeyBootstrapResult.Adopted
            }

            // 旧版裸密文：密码能解开才生成新材料（信封迁移）；解不开按密码错误处理
            val legacyJson = runCatching { EncryptionUtils.decryptString(raw, password) }.getOrNull()
            if (legacyJson != null && cloudResolver.isJsonLike(legacyJson)) {
                generateFreshMaterial(password)
            } else {
                KeyBootstrapResult.WrongPassword
            }
        }

    private fun generateFreshMaterial(password: String): KeyBootstrapResult {
        val material = settingsManager.generateAndSaveSyncKeyMaterial(password)
        settingsManager.setRecoveryCodePending(true)
        return KeyBootstrapResult.Generated(material.recoveryCode)
    }

    /**
     * 把备份 JSON 包装为上传内容。启用加密时统一输出信封格式：
     * DEK 来源优先级 = 本地密钥材料 → 云端信封解出的 DEK → 新生成（同时产生新恢复码）。
     * 恢复槽始终原样复用，保证更换同步密码/更换设备后恢复码继续有效。
     *
     * @return (线路内容, 是否新生成恢复码)
     */
    internal fun buildUploadWire(
        json: String,
        password: String,
        cloud: CloudBackupResolver.CloudSnapshot?
    ): Pair<String, Boolean> {
        val localMaterial = settingsManager.getSyncKeyMaterial()
        val dek: ByteArray
        val slotB64: String
        var cachedPwdSlotB64 = ""
        var cachedPwdSlotPassword = ""
        var generated = false

        when {
            localMaterial != null -> {
                dek = Base64.decode(localMaterial.dek, Base64.NO_WRAP)
                slotB64 = localMaterial.recoverySlotB64
                cachedPwdSlotB64 = localMaterial.pwdSlotB64
                cachedPwdSlotPassword = localMaterial.pwdSlotPassword
            }
            cloud?.dek != null && !cloud.recoverySlotB64.isNullOrBlank() -> {
                // 从云端信封接入的 DEK（理论上 resolveCloud 已落盘，这里再兜底一次）
                dek = cloud.dek
                slotB64 = cloud.recoverySlotB64
                settingsManager.adoptCloudKeyMaterial(
                    Base64.encodeToString(dek, Base64.NO_WRAP),
                    slotB64,
                    cloud.pwdSlotB64 ?: "",
                    password
                )
                cachedPwdSlotB64 = cloud.pwdSlotB64 ?: ""
                cachedPwdSlotPassword = password
            }
            else -> {
                // 首次启用加密 / 旧格式数据迁移：生成全新 DEK + 恢复码（同时预包裹密码槽）
                val fresh = settingsManager.generateAndSaveSyncKeyMaterial(password)
                dek = Base64.decode(fresh.dek, Base64.NO_WRAP)
                slotB64 = fresh.recoverySlotB64
                cachedPwdSlotB64 = fresh.pwdSlotB64
                cachedPwdSlotPassword = password
                generated = true
                settingsManager.setRecoveryCodePending(true)
            }
        }

        val recoverySlot = EnvelopeCrypto.WrappedSlot.fromStandaloneBytes(
            Base64.decode(slotB64, Base64.NO_WRAP)
        )
        // 密码未变且已缓存密码槽 → 直接复用（省一次 PBKDF2 派生）；否则重新包裹并更新缓存
        val passwordSlot: EnvelopeCrypto.WrappedSlot =
            if (cachedPwdSlotB64.isNotBlank() && cachedPwdSlotPassword == password) {
                EnvelopeCrypto.WrappedSlot.fromStandaloneBytes(Base64.decode(cachedPwdSlotB64, Base64.NO_WRAP))
            } else {
                val freshSlot = EnvelopeCrypto.wrapDek(dek, password)
                settingsManager.savePwdSlot(
                    Base64.encodeToString(freshSlot.toBytes(), Base64.NO_WRAP),
                    password
                )
                freshSlot
            }
        val wire = EnvelopeCrypto.buildEnvelopeWithSlots(
            dek = dek,
            pwdSlot = passwordSlot,
            existingRecSlot = recoverySlot,
            // 先压缩再加密：信封正文是密文，压缩必须在加密之前完成
            plaintext = BackupCompression.compress(json.toByteArray(Charsets.UTF_8))
        )
        return wire to generated
    }
}
