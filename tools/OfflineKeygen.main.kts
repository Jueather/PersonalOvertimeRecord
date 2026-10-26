#!/usr/bin/env kotlin
/**
 * 离线激活码生成工具（独立 Kotlin 脚本，无需编译，直接 `kotlin script OfflineKeygen.main.kts` 运行）。
 *
 * 用法：
 *   ./gradlew.bat --no-daemon -q runOfflineKeygen  - 或 - 直接运行此文件
 *
 * 参数（按顺序，后两者可省略）：
 *   deviceId  - 目标设备的 deviceId（从 App 设置页的"软件激活"卡片复制）
 *   days      - 试用天数，默认 30
 *   count     - 批量生成数量，默认 1
 *
 * 示例：
 *   $ kotlinc OfflineKeygen.main.kts -include-runtime -d keygen.jar
 *   $ java -jar keygen.jar ABCD-1234-EFGH-5678 90 5
 *   (会输出 5 个 90 天离线码，绑定 deviceId 前缀 ABCD-1234...)
 *
 * 安全说明：此脚本与 App 内嵌的 VERIFY_KEY 为同一把密钥。
 *   - App 侧做了字符串拆分混淆，避免被直接反编译搜索到
 *   - 你本人应妥善保管此密钥，泄露后任何人都能生成无限续期离线码
 */

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// ====== 与 App 保持完全一致的常量 ======
private const val VERIFY_KEY = "OTR-LICENSE-HMAC-SECRET-2026"
private const val EPOCH_DAY_ORIGIN_MS = 1_735_689_600_000L // 2025-01-01 00:00:00 UTC
private const val B32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

// ====== Base32 ======
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
    if (bits > 0) sb.append(B32_ALPHABET[(buffer shl (5 - bits)) and 0x1F])
    return sb.toString()
}

// ====== 核心逻辑（与 OfflineCodeValidator.kt 完全镜像） ======
private fun encodePayload(trialDays: Int, deviceIdHashPrefix: Int, expiryEpochDays: Int): ByteArray {
    require(trialDays in 1..65535)
    require(expiryEpochDays in 0..65535)
    return ByteArray(8).apply {
        this[0] = (trialDays and 0xFF).toByte()
        this[1] = ((trialDays shr 8) and 0xFF).toByte()
        this[2] = ((deviceIdHashPrefix shr 24) and 0xFF).toByte()
        this[3] = ((deviceIdHashPrefix shr 16) and 0xFF).toByte()
        this[4] = ((deviceIdHashPrefix shr 8) and 0xFF).toByte()
        this[5] = (deviceIdHashPrefix and 0xFF).toByte()
        this[6] = (expiryEpochDays and 0xFF).toByte()
        this[7] = ((expiryEpochDays shr 8) and 0xFF).toByte()
    }
}

private fun deviceIdHashPrefix(deviceId: String): Int {
    val hash = MessageDigest.getInstance("SHA-256").digest(deviceId.toByteArray(Charsets.UTF_8))
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

fun generateCode(trialDays: Int, deviceId: String): String {
    val devicePrefix = deviceIdHashPrefix(deviceId)
    val expiryMs = System.currentTimeMillis() + trialDays * 24L * 60L * 60L * 1000L
    val expiryEpochDays = ((expiryMs - EPOCH_DAY_ORIGIN_MS) / (24L * 60L * 60L * 1000L)).toInt()
    require(expiryEpochDays in 0..65535) { "过期日期越界" }
    val payload = encodePayload(trialDays, devicePrefix, expiryEpochDays)
    val sigPart = hmacSha256(payload, VERIFY_KEY).copyOfRange(0, 8).toBase32()
    val payloadPart = payload.toBase32()
    return "${payloadPart}-$sigPart"
}

// ====== CLI 入口 ======
fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] == "-h" || args[0] == "--help") {
        println(
            """
            Offline Activation Code Generator
            Usage: keygen.jar <deviceId> [days] [count]
              deviceId : from App Settings → 软件激活 → "复制" 按钮
              days     : trial days per code, default 30
              count    : batch count, default 1
            """.trimIndent()
        )
        return
    }
    val deviceId = args[0].trim()
    val days = args.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 3650) ?: 30
    val count = args.getOrNull(2)?.toIntOrNull()?.coerceIn(1, 500) ?: 1

    println("\n🔑 Offline Activation Codes")
    println("   Target device ID: $deviceId")
    println("   Trial duration  : $days days")
    println("   Count           : $count")
    println("   Generated at    : ${java.time.Instant.now()}")
    println("   --------------------------------------------------------")
    repeat(count) {
        val code = generateCode(days, deviceId)
        println("  ${String.format("%02d", it + 1)}.  $code")
    }
    println("   --------------------------------------------------------")
    println("   These codes are SIGNED with HMAC-SHA256 and BOUND to the")
    println("   device ID's SHA-256 prefix. Expiry is absolute, encoded in")
    println("   the code payload — clock tampering won't extend the trial.\n")
}

main(args)
