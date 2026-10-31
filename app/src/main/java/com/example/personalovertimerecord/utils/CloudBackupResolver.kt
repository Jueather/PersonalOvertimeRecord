package com.example.personalovertimerecord.utils

import android.util.Base64
import com.example.personalovertimerecord.data.SettingsManager

/**
 * 云端备份解析器
 * 负责下载云端备份文件并统一解析三种格式：明文 JSON（历史未加密）/ 信封（PORE_ENV1，
 * 密码或恢复码包裹 DEK）/ 旧版裸 Base64 密文，产出 [CloudSnapshot] 供同步流程使用。
 * 信封格式密码正确时，顺带为本机补齐 DEK 与恢复槽（新设备接入引导）。
 * 同时持有"恢复码找回刚验证过的云端原文"短时缓存（单次消费 + 60 秒过期），
 * 供紧接着的正常同步复用，省一次 HTTP GET。
 */
internal class CloudBackupResolver(
    private val webDAVManager: WebDAVManager,
    private val settingsManager: SettingsManager
) {

    companion object {
        /** 恢复码找回后云端快照缓存的有效期（毫秒），过期即丢弃 */
        private const val RECOVERED_CACHE_TTL_MS = 60_000L
    }

    /** 已解密的云端数据快照 */
    internal class CloudSnapshot(
        val json: String,
        /** 信封格式解出的 DEK；明文 JSON / 旧版密文为 null */
        val dek: ByteArray?,
        /** 信封中的恢复槽（Base64）；用于新设备接入后继续沿用原恢复码 */
        val recoverySlotB64: String?,
        /** 信封中的密码槽（Base64）；接入时缓存复用可省一次 PBKDF2 派生 */
        val pwdSlotB64: String? = null
    )

    internal sealed class CloudResolve {
        object NotFound : CloudResolve()
        object DownloadFailed : CloudResolve()
        object EncryptionMismatch : CloudResolve()
        data class Available(val snapshot: CloudSnapshot) : CloudResolve()
    }

    /**
     * 恢复码找回流程刚下载并验证通过的云端原文（[stashRecoveredSnapshot] 写入）。
     * 找回成功后设置页会立刻触发一次正常同步拉取数据，复用此快照可省一次 HTTP GET。
     * 单次消费 + 短时过期（[RECOVERED_CACHE_TTL_MS]）：同步未及时发生或失败后，
     * 陈旧快照会被丢弃，绝不参与后续正常同步。
     */
    @Volatile
    private var recoveredCloudCache: Pair<String, Long>? = null

    /** 恢复码找回验证通过后调用：缓存已验证的云端原文，供紧接着的 resolveCloud 单次复用 */
    internal fun stashRecoveredSnapshot(raw: String) {
        recoveredCloudCache = raw to android.os.SystemClock.elapsedRealtime()
    }

    private fun consumeRecoveredCloudCache(): String? {
        val cached = recoveredCloudCache ?: return null
        recoveredCloudCache = null
        val age = android.os.SystemClock.elapsedRealtime() - cached.second
        return cached.first.takeIf { age in 0..RECOVERED_CACHE_TTL_MS }
    }

    /**
     * 下载云端文件并按三种格式解析：
     * 明文 JSON（历史未加密）/ 信封（PORE_ENV1，密码或恢复码包裹 DEK）/ 旧版裸 Base64 密文。
     * 信封格式密码正确时，顺带为本机补齐 DEK 与恢复槽（新设备接入引导）。
     */
    internal suspend fun resolveCloud(config: WebDAVConfig, password: String?): CloudResolve {
        // 恢复码找回刚下载验证过的快照可直接复用（单次消费，60 秒过期），省一次 HTTP GET
        val raw = consumeRecoveredCloudCache()?.also {
            AppLogger.d("复用恢复码找回时已验证的云端快照，跳过本次下载")
        } ?: webDAVManager.downloadRawFile(config)
        if (raw == null) {
            return if (WebDAVManager.lastResponseCode == 404) {
                CloudResolve.NotFound
            } else {
                CloudResolve.DownloadFailed
            }
        }

        if (isJsonLike(raw)) {
            return CloudResolve.Available(CloudSnapshot(raw, null, null))
        }

        // 未加密的压缩线路格式：PORE_GZ1: + Base64(gzip(JSON))
        if (BackupCompression.isCompressedWire(raw)) {
            val json = runCatching { BackupCompression.decodeCompressedWire(raw) }.getOrNull()
            if (json != null && isJsonLike(json)) {
                return CloudResolve.Available(CloudSnapshot(json, null, null))
            }
            return CloudResolve.EncryptionMismatch
        }

        if (EnvelopeCrypto.isEnvelope(raw)) {
            val local = settingsManager.getSyncKeyMaterial()
            val pwd = password?.takeIf { it.isNotBlank() }
            // 一次解析同时得到明文/DEK/双槽，避免对同一密码重复 PBKDF2 派生
            var opened = pwd?.let { EnvelopeCrypto.openWithPassword(raw, it) }
            val openedViaPassword = opened != null
            // 本地 DEK 兜底（仅当已配置同步密码时）：恢复码找回只修本地材料、不上传，
            // 云端密码槽仍由被遗忘的旧密码包裹，新密码开不了信封属预期。本地已接入同谱系
            // DEK 时直接解正文，否则找回后的同步会永久卡死在 ENCRYPTION_MISMATCH
            // （上传被"解密失败即中止"门禁挡住，唯一能重包云端密码槽的 buildUploadWire
            // 永远执行不到）。密码未配置时保持原有 EncryptionMismatch 语义，
            // 避免关闭加密开关后用残留材料读出云端密文并以明文覆盖上传。
            if (opened == null) {
                if (pwd == null || local == null) {
                    return CloudResolve.EncryptionMismatch
                }
                opened = runCatching {
                    EnvelopeCrypto.openWithDek(raw, Base64.decode(local.dek, Base64.NO_WRAP))
                }.getOrNull()
            }
            opened ?: return CloudResolve.EncryptionMismatch
            val dek = opened.dek
            // String 构造会按 UTF-8 拷贝字节，明文原数组此后立即清零；新格式正文是 gzip，
            // 由 BackupCompression 按 magic 判断，旧版未压缩信封原样读取
            val json = runCatching { BackupCompression.decodePlaintext(opened.plaintext) }.getOrNull()
            java.util.Arrays.fill(opened.plaintext, 0)
            if (json == null) return CloudResolve.EncryptionMismatch
            val dekB64 = Base64.encodeToString(dek, Base64.NO_WRAP)
            val slotB64 = Base64.encodeToString(opened.recSlot.toBytes(), Base64.NO_WRAP)
            val pwdSlotB64 = Base64.encodeToString(opened.pwdSlot.toBytes(), Base64.NO_WRAP)
            // 接入云端密钥谱系：本地无材料（新设备），或本地 DEK 与云端不一致
            // （重装后曾误生成新 DEK）时，一律以云端为准，并清掉失效的本地恢复码明文
            if (openedViaPassword && pwd != null) {
                if (local == null || local.dek != dekB64) {
                    settingsManager.adoptCloudKeyMaterial(dekB64, slotB64, pwdSlotB64, pwd)
                } else if (local.pwdSlotB64.isBlank() || local.pwdSlotPassword != pwd) {
                    // DEK 相同但本机缺密码槽缓存（旧版本升级 / 改过密码），顺手补上
                    settingsManager.savePwdSlot(pwdSlotB64, pwd)
                }
            }
            // 经本地 DEK 兜底打开时跳过密码槽缓存配对：云端旧密码槽未经当前密码验证，
            // 配对缓存会毒化 buildUploadWire 的快路径（上传出打不开的信封）。
            // 找回流程已用新密码本地重包密码槽，云端自愈交给下一次上传完成。
            return CloudResolve.Available(CloudSnapshot(json, dek, slotB64, pwdSlotB64))
        }

        // 旧版裸 Base64 密文（密码直加密，无 magic 前缀）：用当前密码尝试解密
        if (!password.isNullOrBlank()) {
            val json = runCatching { EncryptionUtils.decryptString(raw, password) }.getOrNull()
            if (json != null && isJsonLike(json)) {
                return CloudResolve.Available(CloudSnapshot(json, null, null))
            }
        }
        return CloudResolve.EncryptionMismatch
    }

    /**
     * 判断下载内容是否为 JSON 格式
     * 加密备份内容为 Base64 文本（非 JSON），据此可区分"未加密数据"与"密码错误的加密数据"
     */
    internal fun isJsonLike(content: String): Boolean {
        val trimmed = content.trimStart()
        return trimmed.startsWith("{") || trimmed.startsWith("[")
    }
}
