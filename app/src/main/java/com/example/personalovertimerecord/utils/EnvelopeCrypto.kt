package com.example.personalovertimerecord.utils

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

/**
 * 信封加密（Envelope Encryption）——同步数据的"密码 + 恢复码"双钥匙兜底方案。
 *
 * 背景：旧方案直接用同步密码经 PBKDF2 派生密钥加密云端数据，密码遗忘即数据永久不可恢复。
 * 信封方案把"数据密钥（DEK）"与"用户记忆的秘密"解耦：
 *   1. 每次备份生成/复用一个随机 256 位 DEK，正文由 DEK 做 AES-256-GCM 加密；
 *   2. DEK 不直接落盘，而是被两份独立秘密分别包裹（wrap）：
 *      - 同步密码包裹一份（日常解密）；
 *      - 随机恢复码包裹另一份（遗忘密码时兜底）；
 *   3. 任一把"钥匙"都能解开 DEK；用恢复码解开后可设置新密码重新包裹，恢复码可继续沿用。
 *
 * 线路格式（字符串）：[WIRE_PREFIX] + Base64(二进制信封)
 *
 * 二进制信封布局（大端无关，全部定长拼接）：
 *   magic(4B "PEV1") + version(1B=1)
 *   pwdSlot: salt(16) + iv(12) + wrappedDek(32B DEK + 16B GCM tag = 48B)  共 76B
 *   recSlot: salt(16) + iv(12) + wrappedDek(48B)                          共 76B
 *   payloadIv(12B) + payloadCiphertext(变长，含 16B GCM tag)
 *
 * 安全性质：
 * - 每份包裹各自随机 salt/iv，KEK 由 PBKDF2(秘密, salt, 600000) 派生，与正文强度一致；
 * - GCM 认证保证秘密错误/数据篡改时直接抛异常，不会得到错误明文；
 * - 恢复槽（recSlot）可在设备间原样透传：新设备用密码解开 DEK 后，
 *   上传时保留云端旧 recSlot 字节即可，无需知道恢复码明文，恢复码依然有效。
 */
object EnvelopeCrypto {

    /** 信封数据在线路上的文本前缀（区别于旧版裸 Base64 密文与明文 JSON） */
    const val WIRE_PREFIX = "PORE_ENV1:"

    // 复用实例，避免每次加解密都重新查找 Provider（Android 默认 SecureRandom 已正确播种）
    private val secureRandom = SecureRandom()

    private val MAGIC = byteArrayOf(0x50, 0x45, 0x56, 0x31) // "PEV1"
    private const val VERSION: Byte = 1
    private const val DEK_SIZE = 32
    private const val WRAPPED_DEK_SIZE = DEK_SIZE + 16 // 32B DEK + 16B GCM tag
    // 非 const：Kotlin const 不允许跨对象引用（即使对方是 const），编译期固定值即可
    private val SLOT_SIZE = EncryptionUtils.INTERNAL_SALT_SIZE +
        EncryptionUtils.INTERNAL_GCM_IV_LENGTH + WRAPPED_DEK_SIZE // 16+12+48 = 76
    private val HEADER_SIZE = MAGIC.size + 1 + SLOT_SIZE * 2 + EncryptionUtils.INTERNAL_GCM_IV_LENGTH

    /** 被包裹的 DEK（一个包裹槽）：salt/iv 随密文一起存储，解包时需要原秘密 */
    data class WrappedSlot(val salt: ByteArray, val iv: ByteArray, val wrapped: ByteArray) {
        internal fun toBytes(): ByteArray = salt + iv + wrapped

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is WrappedSlot) return false
            return salt.contentEquals(other.salt) && iv.contentEquals(other.iv) && wrapped.contentEquals(other.wrapped)
        }

        override fun hashCode(): Int {
            var result = salt.contentHashCode()
            result = 31 * result + iv.contentHashCode()
            result = 31 * result + wrapped.contentHashCode()
            return result
        }

        companion object {
            internal fun fromBytes(data: ByteArray, offset: Int): WrappedSlot {
                var pos = offset
                val salt = data.copyOfRange(pos, pos + EncryptionUtils.INTERNAL_SALT_SIZE).also { pos += it.size }
                val iv = data.copyOfRange(pos, pos + EncryptionUtils.INTERNAL_GCM_IV_LENGTH).also { pos += it.size }
                val wrapped = data.copyOfRange(pos, pos + WRAPPED_DEK_SIZE)
                return WrappedSlot(salt, iv, wrapped)
            }

            /** 从独立槽字节（salt+iv+wrapped，共 76B）解析恢复槽 */
            fun fromStandaloneBytes(bytes: ByteArray): WrappedSlot = fromBytes(bytes, 0)
        }
    }

    /** 解析后的信封（尚未解包 DEK） */
    private data class ParsedEnvelope(
        val pwdSlot: WrappedSlot,
        val recSlot: WrappedSlot,
        val payloadIv: ByteArray,
        val payload: ByteArray
    )

    /** 一次解析得到的完整信封内容：明文 + DEK + 双槽（避免重复 PBKDF2 派生） */
    class OpenedEnvelope internal constructor(
        val plaintext: ByteArray,
        val dek: ByteArray,
        val pwdSlot: WrappedSlot,
        val recSlot: WrappedSlot
    )

    /** 生成随机 256 位数据密钥 */
    fun generateDek(): ByteArray {
        val dek = ByteArray(DEK_SIZE)
        secureRandom.nextBytes(dek)
        return dek
    }

    /** 用秘密（同步密码或恢复码）包裹 DEK */
    fun wrapDek(dek: ByteArray, secret: String): WrappedSlot {
        val salt = EncryptionUtils.internalGenerateSalt()
        val iv = ByteArray(EncryptionUtils.INTERNAL_GCM_IV_LENGTH).also {
            secureRandom.nextBytes(it)
        }
        val kek = EncryptionUtils.internalDeriveKey(secret, salt)
        val wrapped = EncryptionUtils.internalGcmEncrypt(kek, iv, dek)
        return WrappedSlot(salt, iv, wrapped)
    }

    /**
     * 用秘密解开被包裹的 DEK。
     * @return DEK；秘密错误或数据被篡改时返回 null（GCM 认证失败）
     */
    fun unwrapDek(slot: WrappedSlot, secret: String): ByteArray? = try {
        val kek = EncryptionUtils.internalDeriveKey(secret, slot.salt)
        val dek = EncryptionUtils.internalGcmDecrypt(kek, slot.iv, slot.wrapped)
        dek.takeIf { it.size == DEK_SIZE }
    } catch (e: Exception) {
        null
    }

    private fun encryptPayload(dek: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val iv = ByteArray(EncryptionUtils.INTERNAL_GCM_IV_LENGTH).also {
            SecureRandom.getInstanceStrong().nextBytes(it)
        }
        val key = SecretKeySpec(dek, "AES")
        val ciphertext = EncryptionUtils.internalGcmEncrypt(key, iv, plaintext)
        return iv to ciphertext
    }

    private fun decryptPayload(dek: ByteArray, iv: ByteArray, payload: ByteArray): ByteArray =
        EncryptionUtils.internalGcmDecrypt(SecretKeySpec(dek, "AES"), iv, payload)

    /**
     * 构建完整信封（首次开启加密 / 同时握有密码与恢复码时使用）。
     * @return 线路字符串（[WIRE_PREFIX] + Base64）
     */
    fun buildEnvelope(
        dek: ByteArray,
        password: String,
        recoveryCode: String,
        plaintext: ByteArray
    ): String {
        val pwdSlot = wrapDek(dek, password)
        val recSlot = wrapDek(dek, RecoveryCodeManager.normalize(recoveryCode))
        return encodeWire(buildEnvelopeBytesWithSlots(dek, pwdSlot, recSlot, plaintext))
    }

    /**
     * 用已有包裹槽构建信封（更换同步密码、或新设备沿用云端恢复槽时使用）。
     * @param pwdSlot 用当前密码重新包裹的槽（[wrapDek]）
     * @param existingRecSlot 既有恢复槽（原样保留，保证恢复码持续有效）
     */
    fun buildEnvelopeWithSlots(
        dek: ByteArray,
        pwdSlot: WrappedSlot,
        existingRecSlot: WrappedSlot,
        plaintext: ByteArray
    ): String = encodeWire(buildEnvelopeBytesWithSlots(dek, pwdSlot, existingRecSlot, plaintext))

    // ---- 字节层原语（internal：供同一模块与单元测试使用，不接触 Android Base64） ----

    internal fun buildEnvelopeBytesWithSlots(
        dek: ByteArray,
        pwdSlot: WrappedSlot,
        recSlot: WrappedSlot,
        plaintext: ByteArray
    ): ByteArray {
        val (payloadIv, payloadCipher) = encryptPayload(dek, plaintext)
        return MAGIC + byteArrayOf(VERSION) +
            pwdSlot.toBytes() + recSlot.toBytes() + payloadIv + payloadCipher
    }

    private fun encodeWire(blob: ByteArray): String =
        WIRE_PREFIX + Base64.encodeToString(blob, Base64.NO_WRAP)

    /** 判断线路字符串是否为信封格式 */
    fun isEnvelope(wire: String): Boolean = wire.startsWith(WIRE_PREFIX)

    private fun parseBlob(blob: ByteArray): ParsedEnvelope {
        require(blob.size >= HEADER_SIZE) { "信封长度不足：${blob.size} < $HEADER_SIZE" }
        var pos = 0
        require(blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "信封 magic 不匹配" }
        pos += MAGIC.size
        require(blob[pos] == VERSION) { "不支持的信封版本：${blob[pos]}" }
        pos += 1
        val pwdSlot = WrappedSlot.fromBytes(blob, pos).also { pos += SLOT_SIZE }
        val recSlot = WrappedSlot.fromBytes(blob, pos).also { pos += SLOT_SIZE }
        val payloadIv = blob.copyOfRange(pos, pos + EncryptionUtils.INTERNAL_GCM_IV_LENGTH).also { pos += it.size }
        val payload = blob.copyOfRange(pos, blob.size)
        require(payload.size >= 16) { "信封正文缺失 GCM tag" }
        return ParsedEnvelope(pwdSlot, recSlot, payloadIv, payload)
    }

    private fun parse(wire: String): ParsedEnvelope =
        parseBlob(Base64.decode(wire.removePrefix(WIRE_PREFIX), Base64.DEFAULT))

    // ---- 密码路径：一次解析拿到全部内容（性能敏感，勿对同一密码重复 PBKDF2 派生） ----

    /** 用同步密码一次解开信封：明文 + DEK + 双槽；密码错误/格式损坏返回 null */
    internal fun openBytesWithPassword(blob: ByteArray, password: String): OpenedEnvelope? {
        return try {
            val env = parseBlob(blob)
            val dek = unwrapDek(env.pwdSlot, password)
                ?: return null
            val plaintext = runCatching { decryptPayload(dek, env.payloadIv, env.payload) }.getOrNull()
                ?: return null
            OpenedEnvelope(plaintext, dek, env.pwdSlot, env.recSlot)
        } catch (e: Exception) {
            null
        }
    }

    /** 用同步密码一次解开线路信封（见 [openBytesWithPassword]） */
    fun openWithPassword(wire: String, password: String): OpenedEnvelope? =
        runCatching {
            openBytesWithPassword(Base64.decode(wire.removePrefix(WIRE_PREFIX), Base64.DEFAULT), password)
        }.getOrNull()

    /** 用同步密码解密信封字节流；密码错误/格式损坏返回 null */
    internal fun decryptBytesWithPassword(blob: ByteArray, password: String): ByteArray? =
        openBytesWithPassword(blob, password)?.plaintext

    /** 用恢复码解密信封字节流；恢复码错误/格式损坏返回 null */
    internal fun decryptBytesWithRecoveryCode(blob: ByteArray, recoveryCode: String): ByteArray? =
        decryptBlob(blob) { env ->
            unwrapDek(env.recSlot, RecoveryCodeManager.normalize(recoveryCode))?.let { dek ->
                runCatching { decryptPayload(dek, env.payloadIv, env.payload) }.getOrNull()
            }
        }

    /** 用同步密码从字节信封解出 DEK 与双槽（不解正文）；失败返回 null */
    internal fun extractSlotsFromBytesWithPassword(
        blob: ByteArray,
        password: String
    ): Triple<ByteArray, WrappedSlot, WrappedSlot>? {
        return try {
            val env = parseBlob(blob)
            val dek = unwrapDek(env.pwdSlot, password) ?: return null
            Triple(dek, env.pwdSlot, env.recSlot)
        } catch (e: Exception) {
            null
        }
    }

    /** 用同步密码从字节信封解出 DEK 与恢复槽；失败返回 null */
    internal fun extractFromBytesWithPassword(blob: ByteArray, password: String): Pair<ByteArray, WrappedSlot>? =
        extractSlotsFromBytesWithPassword(blob, password)?.let { (dek, _, recSlot) -> dek to recSlot }

    /** 用恢复码从字节信封解出 DEK 与恢复槽；失败返回 null */
    internal fun extractFromBytesWithRecoveryCode(blob: ByteArray, code: String): Pair<ByteArray, WrappedSlot>? {
        return try {
            val env = parseBlob(blob)
            val dek = unwrapDek(env.recSlot, RecoveryCodeManager.normalize(code)) ?: return null
            dek to env.recSlot
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 用同步密码解密信封。
     * @return 明文；密码错误/格式损坏时返回 null
     */
    fun decryptWithPassword(wire: String, password: String): ByteArray? =
        openWithPassword(wire, password)?.plaintext

    /**
     * 用恢复码解密信封（忘记同步密码时的兜底路径）。
     * @return 明文；恢复码错误/格式损坏时返回 null
     */
    fun decryptWithRecoveryCode(wire: String, recoveryCode: String): ByteArray? = decrypt(wire) { env ->
        unwrapDek(env.recSlot, RecoveryCodeManager.normalize(recoveryCode))?.let { dek ->
            runCatching { decryptPayload(dek, env.payloadIv, env.payload) }.getOrNull()
        }
    }

    /**
     * 用同步密码一次解出 DEK 与双槽（不解正文；开启加密探测等只需槽位的场景用）。
     * @return (DEK, 密码槽, 恢复槽)；密码错误或格式损坏返回 null
     */
    fun extractDekAndSlotsWithPassword(
        wire: String,
        password: String
    ): Triple<ByteArray, WrappedSlot, WrappedSlot>? =
        runCatching {
            extractSlotsFromBytesWithPassword(
                Base64.decode(wire.removePrefix(WIRE_PREFIX), Base64.DEFAULT),
                password
            )
        }.getOrNull()

    /**
     * 用恢复码解开 DEK 并取出恢复槽（遗忘密码恢复流程：恢复槽原样保留，下次上传继续可用）。
     */
    fun extractDekAndRecoverySlotWithRecoveryCode(
        wire: String,
        recoveryCode: String
    ): Pair<ByteArray, WrappedSlot>? =
        runCatching {
            extractFromBytesWithRecoveryCode(
                Base64.decode(wire.removePrefix(WIRE_PREFIX), Base64.DEFAULT),
                recoveryCode
            )
        }.getOrNull()

    private inline fun decrypt(wire: String, opener: (ParsedEnvelope) -> ByteArray?): ByteArray? = try {
        opener(parse(wire))
    } catch (e: Exception) {
        null
    }

    private inline fun decryptBlob(blob: ByteArray, opener: (ParsedEnvelope) -> ByteArray?): ByteArray? = try {
        opener(parseBlob(blob))
    } catch (e: Exception) {
        null
    }
}
