package com.example.personalovertimerecord.utils

import java.security.SecureRandom

/**
 * 同步加密恢复码（Recovery Code）生成与规范化。
 *
 * - 10 个随机字节（80 位熵）按 Crockford Base32 编码为 16 个字符，展示为 4 组 × 4 位：
 *   例：`7K2M-9XQF-4BT8-N6VD`；
 * - Crockford 字母表剔除了易混淆字符（没有 I/L/O/U），并在输入时做容错归一化
 *   （小写转大写、O→0、I/L→1、去除连字符与空格），用户手抄后录入成功率高；
 * - 80 位熵足以抵御在线/离线猜测（恢复码只以 GCM 包裹形式存在云端，无法离线批量验证）。
 */
object RecoveryCodeManager {

    // Crockford Base32 字母表（无 I/L/O/U）
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val RAW_BYTES = 10 // 80 bit → 恰好 16 个 Base32 字符
    private const val CODE_LENGTH = 16
    private const val GROUP_SIZE = 4

    /** 生成新的随机恢复码（带分组连字符的展示形式，如 7K2M-9XQF-4BT8-N6VD） */
    fun generate(): String {
        val bytes = ByteArray(RAW_BYTES).also { SecureRandom.getInstanceStrong().nextBytes(it) }
        // 每 5 bit 映射一个字符，RAW_BYTES=10 时恰好 16 个字符，无填充位
        val sb = StringBuilder(CODE_LENGTH)
        var bitBuffer = 0
        var bitsInBuffer = 0
        for (b in bytes) {
            bitBuffer = (bitBuffer shl 8) or (b.toInt() and 0xFF)
            bitsInBuffer += 8
            while (bitsInBuffer >= 5) {
                bitsInBuffer -= 5
                val index = (bitBuffer shr bitsInBuffer) and 0x1F
                sb.append(ALPHABET[index])
            }
        }
        check(sb.length == CODE_LENGTH) { "恢复码长度异常：${sb.length}" }
        return sb.toString().chunked(GROUP_SIZE).joinToString("-")
    }

    /**
     * 把用户输入规范化为可参与密钥派生的规范形式：
     * 去空白/连字符 → 大写 → 常见误读映射（O→0、I/L→1），与 Crockford 解码规则一致。
     */
    fun normalize(input: String): String {
        val cleaned = input.trim().uppercase()
            .replace("-", "")
            .replace(" ", "")
        val sb = StringBuilder(cleaned.length)
        for (c in cleaned) {
            val mapped = when (c) {
                'O' -> '0'
                'I', 'L' -> '1'
                else -> c
            }
            sb.append(mapped)
        }
        return sb.toString()
    }

    /** 校验恢复码格式（规范化后必须为 16 位 Crockford 字符） */
    fun isValid(input: String): Boolean {
        val normalized = normalize(input)
        if (normalized.length != CODE_LENGTH) return false
        return normalized.all { it in ALPHABET }
    }
}
