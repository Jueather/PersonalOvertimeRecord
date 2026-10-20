package com.example.overtimeserver.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import java.security.SecureRandom

object PasswordHasher {

    private const val COST = 12

    fun hash(password: String): String =
        BCrypt.withDefaults().hashToString(COST, password.toCharArray())

    fun verify(password: String, hash: String): Boolean =
        BCrypt.verifyer().verify(password.toCharArray(), hash).verified

    /** 随机密码：16 位无歧义字符集（去掉 0O1lI 等） */
    fun generatePassword(length: Int = 16): String {
        val chars = "23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz"
        val rnd = SecureRandom()
        return buildString(length) { repeat(length) { append(chars[rnd.nextInt(chars.length)]) } }
    }
}
