package com.example.overtimeserver.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import java.io.File
import java.security.SecureRandom
import java.util.Date

class JwtService(
    secretFile: String,
    private val issuer: String,
    private val audience: String,
    private val expiryDays: Long,
) {

    private val secret: ByteArray
    val algorithm: Algorithm

    init {
        secret = loadOrCreateSecret(File(secretFile))
        algorithm = Algorithm.HMAC256(secret)
    }

    /** 有效期（秒），登录响应用 */
    val expiresInSeconds: Long get() = expiryDays * 24 * 3600

    fun createToken(userId: Long, username: String): String =
        JWT.create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withClaim("uid", userId)
            .withClaim("username", username)
            .withExpiresAt(Date(System.currentTimeMillis() + expiresInSeconds * 1000))
            .sign(algorithm)

    fun verifier(): JWTVerifier =
        JWT.require(algorithm).withIssuer(issuer).withAudience(audience).build()

    /** 密钥文件不存在时生成并持久化（重启不踢线）；POSIX 600 加固，Windows 不支持则跳过 */
    private fun loadOrCreateSecret(file: File): ByteArray {
        file.absoluteFile.parentFile?.mkdirs()
        if (file.isFile) {
            val text = file.readText().trim()
            if (text.isNotEmpty()) return text.toByteArray()
        }
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = SecureRandom()
        val text = buildString(64) { repeat(64) { append(chars[rnd.nextInt(chars.length)]) } }
        file.writeText(text)
        try {
            java.nio.file.Files.setPosixFilePermissions(
                file.toPath(),
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
            )
        } catch (_: UnsupportedOperationException) {
            // Windows 文件系统不支持 POSIX 权限，忽略
        }
        return text.toByteArray()
    }
}
