package com.example.personalovertimerecord.utils

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 签名的离线激活码生成器 + 校验器。
 *
 * 设计要点：
 *   - 离线码必须 encode 绝对过期日期（epoch days），不是相对天数——
 *     否则用户改系统时钟就能无限续期。
 *   - Payload 含 deviceId 的前 4 字节哈希前缀，防止码跨设备复用。
 *   - 最终格式：base32(payload) + '-' + base32(hmac)[:8] —— 紧凑好打。
 *   - 开发者持有 SECRET_KEY 生成码（独立工具/脚本）；
 *     App 内嵌 VERIFY_KEY 做校验（不是同一个 key：HMAC 需要 secret，但验证时用同一份 key 即可）。
 *   - **HMAC 的 key 同时用于生成和校验**（对称签名）。
 *     开发者和 App 都持有同一把 key，App 里做字符串拆分混淆。
 */
object OfflineCodeValidator {

    // ====== Payload 布局（小端序 8 字节） ======
    // trialDays       : 2 bytes (uint16)   最大 65535 天
    // deviceIdPrefix  : 4 bytes            deviceId SHA-256 的前 4 字节
    // expiryEpochDays : 2 bytes (uint16)   2025-01-01 起算的天数偏移，最大 ~2100 年

    /**
     * Payload 的 expiryEpochDays 是从 2025-01-01 00:00:00 UTC 起算的天数偏移。
     * 2025-01-01 epoch ms = 1735689600000（固定值，用它减去 (2025-01-01 起始偏移天数 * 86400000) 得到 0）
     */
    private const val EPOCH_DAY_ORIGIN_MS = 1_735_689_600_000L // 2025-01-01 00:00:00 UTC

    // ====== BuildConfig 密钥（生产环境替换为 BuildConfig.KEY_PART_A + BuildConfig.KEY_PART_B） ======
    // Debug build 下简单字符串拼接，R8 release 构建时启用字符串混淆（minifyEnabled + proguardFiles）
    private val VERIFY_KEY: String = buildString {
        append("OTR-LICENSE-")
        append("HMAC-SECRET-2026")
    }

    // ====== Base32 ======
    private const val B32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private fun ByteArray.toBase32(): String {
        if (isEmpty()) return ""
        val sb = StringBuilder()
        var bits = 0
        var buffer = 0
        for (b in this) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(B32_ALPHABET[(buffer shr bits) and 0x1F])
            }
        }
        if (bits > 0) {
            sb.append(B32_ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        }
        return sb.toString()
    }

    private fun String.fromBase32(): ByteArray {
        require(uppercase().all { it in B32_ALPHABET }) { "非法 Base32 字符" }
        val out = ArrayList<Byte>()
        var bits = 0
        var buffer = 0
        for (c in uppercase()) {
            buffer = (buffer shl 5) or B32_ALPHABET.indexOf(c)
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer shr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    // ====== Payload 编解码 ======

    private fun encodePayload(trialDays: Int, deviceIdHashPrefix: Int, expiryEpochDays: Int): ByteArray {
        require(trialDays in 1..65535) { "trialDays 越界" }
        require(deviceIdHashPrefix in 0..0xFFFFFFFF.toInt()) { "deviceIdPrefix 越界" }
        require(expiryEpochDays in 0..65535) { "expiryEpochDays 越界" }
        return ByteArray(8).apply {
            // trialDays: uint16 LE
            this[0] = (trialDays and 0xFF).toByte()
            this[1] = ((trialDays shr 8) and 0xFF).toByte()
            // deviceIdPrefix: uint32 BE
            this[2] = ((deviceIdHashPrefix shr 24) and 0xFF).toByte()
            this[3] = ((deviceIdHashPrefix shr 16) and 0xFF).toByte()
            this[4] = ((deviceIdHashPrefix shr 8) and 0xFF).toByte()
            this[5] = (deviceIdHashPrefix and 0xFF).toByte()
            // expiryEpochDays: uint16 LE
            this[6] = (expiryEpochDays and 0xFF).toByte()
            this[7] = ((expiryEpochDays shr 8) and 0xFF).toByte()
        }
    }

    private fun decodePayload(payload: ByteArray): Triple<Int, Int, Int> {
        require(payload.size == 8) { "payload 长度应为 8" }
        val trialDays = (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
        val prefix =
            ((payload[2].toInt() and 0xFF) shl 24) or
                ((payload[3].toInt() and 0xFF) shl 16) or
                ((payload[4].toInt() and 0xFF) shl 8) or
                (payload[5].toInt() and 0xFF)
        val expiryDays = (payload[6].toInt() and 0xFF) or ((payload[7].toInt() and 0xFF) shl 8)
        return Triple(trialDays, prefix, expiryDays)
    }

    private fun deviceIdHashPrefix(deviceId: String): Int {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(deviceId.toByteArray(Charsets.UTF_8))
        // 前 4 字节按大端序拼成 int
        return ((hash[0].toInt() and 0xFF) shl 24) or
            ((hash[1].toInt() and 0xFF) shl 16) or
            ((hash[2].toInt() and 0xFF) shl 8) or
            (hash[3].toInt() and 0xFF)
    }

    private fun hmacSha256(data: ByteArray, key: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data)
    }

    /**
     * 开发者端：生成离线码。
     * @param trialDays 试用天数
     * @param deviceId  目标设备的 deviceId（来自 App 内展示）
     * @param absoluteExpiryEpochMs 绝对过期时间（epoch ms），优先；若为 null 则用 trialDays 推算
     * @param hmacKey   签名密钥（App 端 VERIFY_KEY 的同一把）
     */
    fun generateCode(
        trialDays: Int,
        deviceId: String,
        absoluteExpiryEpochMs: Long? = null,
        hmacKey: String = VERIFY_KEY
    ): String {
        val devicePrefix = deviceIdHashPrefix(deviceId)
        val expiryMs = absoluteExpiryEpochMs ?: (System.currentTimeMillis() + trialDays * 24L * 60L * 60L * 1000L)
        val totalMsFromOrigin = expiryMs - EPOCH_DAY_ORIGIN_MS
        val expiryEpochDays = (totalMsFromOrigin / (24L * 60L * 60L * 1000L)).toInt()
        require(expiryEpochDays in 0..65535) { "过期日期越界（太早或太晚）" }
        val payload = encodePayload(trialDays, devicePrefix, expiryEpochDays)
        val sig = hmacSha256(payload, hmacKey)
        // 签名取前 8 字节 + base32 → ≈ 13 字符；最终码 ≈ 20 + 1 + 13 = 34 字符
        val sigPart = sig.copyOfRange(0, 8).toBase32()
        val payloadPart = payload.toBase32()
        return "${payloadPart}-$sigPart"
    }

    /**
     * App 端：校验离线码。成功返回 [ValidCode]；失败返回具体原因。
     *
     * **注意**：必须同时满足三个条件：
     *   1. HMAC 签名正确（证明码未被篡改，由持有同一密钥的一方签发）
     *   2. deviceId 匹配（防跨设备复用）
     *   3. 绝对过期时间未到（防改时钟续期）
     */
    fun validateCode(
        code: String,
        deviceId: String,
        nowMs: Long = System.currentTimeMillis(),
        hmacKey: String = VERIFY_KEY
    ): ValidationResult {
        val cleaned = code.trim().filter { it.isLetterOrDigit() || it == '-' }
        val parts = cleaned.split('-', limit = 2)
        if (parts.size != 2) return ValidationResult.Malformed
        val (payloadPart, sigPart) = parts

        val payload = try { payloadPart.fromBase32() } catch (_: Exception) { return ValidationResult.Malformed }
        if (payload.size != 8) return ValidationResult.Malformed

        // 1. 校验签名（防篡改）
        val expectedSig = hmacSha256(payload, hmacKey).copyOfRange(0, 8).toBase32()
        if (expectedSig != sigPart.uppercase()) return ValidationResult.BadSignature

        val (trialDays, devicePrefix, expiryEpochDays) = decodePayload(payload)

        // 2. 校验设备绑定
        val expectedPrefix = deviceIdHashPrefix(deviceId)
        if (devicePrefix != expectedPrefix) return ValidationResult.DeviceMismatch

        // 3. 校验绝对过期时间
        val expiryMs = EPOCH_DAY_ORIGIN_MS + expiryEpochDays * 24L * 60L * 60L * 1000L
        if (expiryMs <= nowMs) return ValidationResult.Expired

        return ValidationResult.Valid(
            trialDays = trialDays,
            expiresAtMs = expiryMs,
            activatedAtMs = nowMs
        )
    }
}

sealed class ValidationResult {
    data class Valid(
        val trialDays: Int,
        val expiresAtMs: Long,
        val activatedAtMs: Long
    ) : ValidationResult()

    data object Malformed : ValidationResult()
    data object BadSignature : ValidationResult()
    data object DeviceMismatch : ValidationResult()
    data object Expired : ValidationResult()

    fun userMessage(): String = when (this) {
        is Valid -> "离线码校验通过"
        is Malformed -> "激活码格式不正确"
        is BadSignature -> "激活码签名无效，可能被篡改或密钥不匹配"
        is DeviceMismatch -> "该激活码已绑定其他设备"
        is Expired -> "激活码已过期"
    }
}
