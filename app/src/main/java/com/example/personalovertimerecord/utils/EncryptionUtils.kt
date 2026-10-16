package com.example.personalovertimerecord.utils

import android.util.Base64
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 数据加密工具（密码派生 + 对称加密）
 *
 * 加密模式已从 AES-256-CBC 升级为 AES-256-GCM：
 * - GCM 是带认证的加密模式（AEAD），能检测密文被篡改/损坏，并消除
 *   CBC 无认证 + PKCS7 padding oracle 的安全风险；
 * - 每个文件/载荷的 salt 与 IV 均随机生成。
 *
 * 文件格式（当前 GCM，写入端）：
 *   magic(2B "G2") + salt(16B) + iv(12B) + AES/GCM/NoPadding 密文(含 16B 认证 tag)
 *
 * 历史 GCM 格式（旧版写入端，仅读取端兼容，magic 为 "GM"，PBKDF2 迭代 65536 次）：
 *   magic(2B "GM") + salt(16B) + iv(12B) + AES/GCM/NoPadding 密文(含 16B 认证 tag)
 *
 * 旧版 CBC 格式（历史 AES-CBC，仅读取端兼容，无 magic 头）：
 *   salt(16B) + iv(16B) + AES/CBC/PKCS7Padding 密文
 * 解密时会根据开头 magic 自动区分格式并按对应迭代次数派生密钥，
 * 因此历史加密备份/云端文件仍可解密。
 *
 * 说明：新格式使用 "G2" magic 并在解密端按 magic 选择 PBKDF2 迭代次数
 * （"GM"=65536、"G2"=600000），实现强度升级且保持历史文件可解。
 */
object EncryptionUtils {

    private const val AES_KEY_SIZE = 256
    private const val SALT_SIZE = 16

    // ---- PBKDF2 迭代次数 ----
    // OWASP 对 PBKDF2-HmacSHA256 的建议已从 60 万次起跳，旧的 65536 次可被离线暴力破解；
    // 解密时按 magic 兼容历史迭代次数，避免老文件无法读取。
    private const val PBKDF2_ITERATIONS_LEGACY = 65536
    private const val PBKDF2_ITERATIONS = 600_000

    // ---- 供信封加密（EnvelopeCrypto）复用的底层常量/原语，包内可见 ----
    // 直接使用字面量：GCM_IV_LENGTH 等常量在文件后部声明，const 前向引用无法初始化
    internal const val INTERNAL_SALT_SIZE = SALT_SIZE
    internal const val INTERNAL_GCM_IV_LENGTH = 12
    internal const val INTERNAL_PBKDF2_ITERATIONS = PBKDF2_ITERATIONS

    internal fun internalGenerateSalt(): ByteArray = generateSalt()

    internal fun internalDeriveKey(secret: String, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): SecretKeySpec =
        deriveKey(secret, salt, iterations)

    internal fun internalGcmEncrypt(key: SecretKeySpec, iv: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(plaintext)
    }

    internal fun internalGcmDecrypt(key: SecretKeySpec, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(data)
    }

    // ---- AES-GCM 参数 ----
    /** 历史 GCM 标识（PBKDF2 65,536 次），仅用于解密兼容 */
    private val GCM_MAGIC_LEGACY = byteArrayOf(0x47, 0x4D) // "GM"
    /** 当前 GCM 标识（PBKDF2 600,000 次），写入端使用 */
    private val GCM_MAGIC = byteArrayOf(0x47, 0x32) // "G2"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH_BITS = 128

    // ---- 旧版 AES-CBC 参数（仅用于解密历史数据） ----
    private const val LEGACY_CBC_IV_SIZE = 16

    /**
     * 使用 AES-256-GCM 加密数据（PBKDF2 600,000 次派生密钥）
     * @param data 要加密的数据
     * @param password 加密密码
     * @return 加密后的数据（magic + salt + iv + 密文(含认证 tag)）
     */
    fun encrypt(data: ByteArray, password: String): ByteArray {
        val salt = generateSalt()
        val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom.getInstanceStrong().nextBytes(it) }
        val key = deriveKey(password, salt, PBKDF2_ITERATIONS)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

        // doFinal 输出已包含 16 字节 GCM 认证 tag
        val encryptedData = cipher.doFinal(data)

        // 返回 magic + salt + iv + (ciphertext|tag)
        return GCM_MAGIC + salt + iv + encryptedData
    }

    /**
     * 解密数据（自动识别新版 GCM / 旧版 CBC 格式）
     * @param encryptedData 加密的数据
     * @param password 解密密码
     * @return 解密后的数据
     */
    fun decrypt(encryptedData: ByteArray, password: String): ByteArray {
        return if (looksLikeGcm(encryptedData)) {
            decryptGcm(encryptedData, password)
        } else {
            decryptCbcLegacy(encryptedData, password)
        }
    }

    /** 通过开头 magic 判断是否为新版 GCM 格式（兼容 "G2" 与历史 "GM"） */
    private fun looksLikeGcm(data: ByteArray): Boolean {
        // 长度需至少覆盖 magic + salt + iv + 最小 tag
        if (data.size < GCM_MAGIC.size + SALT_SIZE + GCM_IV_LENGTH + 16) return false
        return (data[0] == GCM_MAGIC[0] && data[1] == GCM_MAGIC[1]) ||
            (data[0] == GCM_MAGIC_LEGACY[0] && data[1] == GCM_MAGIC_LEGACY[1])
    }

    private fun decryptGcm(data: ByteArray, password: String): ByteArray {
        // 按 magic 选择 PBKDF2 迭代次数：历史 "GM" 用 65536，当前 "G2" 用 600000
        val iterations = if (data[0] == GCM_MAGIC_LEGACY[0] && data[1] == GCM_MAGIC_LEGACY[1]) {
            PBKDF2_ITERATIONS_LEGACY
        } else {
            PBKDF2_ITERATIONS
        }
        var offset = GCM_MAGIC.size
        val salt = data.copyOfRange(offset, offset + SALT_SIZE).also { offset += SALT_SIZE }
        val iv = data.copyOfRange(offset, offset + GCM_IV_LENGTH).also { offset += GCM_IV_LENGTH }
        val body = data.copyOfRange(offset, data.size)

        val key = deriveKey(password, salt, iterations)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        // GCM 认证失败（密码错误或数据被篡改）会直接抛异常，不会返回损坏明文
        return cipher.doFinal(body)
    }

    /** 兼容解密历史 AES-CBC 数据（无 magic 头，历史写入时均使用 65,536 次迭代） */
    private fun decryptCbcLegacy(data: ByteArray, password: String): ByteArray {
        val salt = data.copyOfRange(0, SALT_SIZE)
        val iv = data.copyOfRange(SALT_SIZE, SALT_SIZE + LEGACY_CBC_IV_SIZE)
        val body = data.copyOfRange(SALT_SIZE + LEGACY_CBC_IV_SIZE, data.size)

        val key = deriveKey(password, salt, PBKDF2_ITERATIONS_LEGACY)

        val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
        return cipher.doFinal(body)
    }

    /**
     * 生成随机盐值
     */
    private fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_SIZE)
        SecureRandom.getInstanceStrong().nextBytes(salt)
        return salt
    }

    /**
     * 使用 PBKDF2 从密码派生密钥
     * @param iterations 迭代次数（解密时按文件格式 magic 选择，加密时使用当前强度）
     */
    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, AES_KEY_SIZE)
        try {
            val keyBytes = factory.generateSecret(spec).encoded
            return SecretKeySpec(keyBytes, "AES")
        } finally {
            // 清除 PBEKeySpec 内部缓存的密码 char[]（派生结束后不再需要）
            spec.clearPassword()
        }
    }

    /**
     * 加密字符串（Base64 编码）
     */
    fun encryptString(text: String, password: String): String {
        val encrypted = encrypt(text.toByteArray(Charsets.UTF_8), password)
        return Base64.encodeToString(encrypted, Base64.DEFAULT)
    }

    /**
     * 解密字符串（Base64 解码，自动识别新旧格式）
     */
    fun decryptString(encryptedBase64: String, password: String): String {
        val encrypted = Base64.decode(encryptedBase64, Base64.DEFAULT)
        val decrypted = decrypt(encrypted, password)
        return String(decrypted, Charsets.UTF_8)
    }

    /**
     * 加密文件
     * @param inputPath 输入文件路径
     * @param outputPath 输出文件路径（加密后）
     * @param password 加密密码
     */
    fun encryptFile(inputPath: String, outputPath: String, password: String) {
        val inputFile = File(inputPath)
        val outputFile = File(outputPath)

        val fileBytes = inputFile.readBytes()
        val encryptedBytes = encrypt(fileBytes, password)

        outputFile.writeBytes(encryptedBytes)
    }

    /**
     * 解密文件
     * @param inputPath 加密文件路径
     * @param outputPath 输出文件路径（解密后）
     * @param password 解密密码
     */
    fun decryptFile(inputPath: String, outputPath: String, password: String): Boolean {
        return try {
            val inputFile = File(inputPath)
            val outputFile = File(outputPath)

            val encryptedBytes = inputFile.readBytes()
            val decryptedBytes = decrypt(encryptedBytes, password)

            outputFile.writeBytes(decryptedBytes)
            true
        } catch (e: Exception) {
            AppLogger.e("Encryption", "Decrypt file failed", e)
            false
        }
    }

    /**
     * 检查数据是否为加密格式（新 GCM 或旧 CBC 容器都带随机盐，长度均大于此阈值）
     */
    fun isEncrypted(data: ByteArray): Boolean {
        return data.size > SALT_SIZE + LEGACY_CBC_IV_SIZE
    }

    /**
     * 验证密码是否正确
     * GCM 数据：密码错误必然抛认证异常；旧 CBC 数据：依赖 PKCS7 padding 校验
     */
    fun verifyPassword(encryptedData: ByteArray, password: String): Boolean {
        return try {
            decrypt(encryptedData, password)
            true
        } catch (e: Exception) {
            false
        }
    }
}
