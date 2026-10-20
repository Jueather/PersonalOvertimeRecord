package com.example.overtimeserver.util

/** date 字符串 → yyyyMM 月键（兼容 yyyy-MM-dd 与 yyyyMMdd；无法解析返回 null） */
object MonthKey {

    fun of(date: String): String? {
        val digits = date.take(10).replace("-", "").replace("/", "")
        if (digits.length < 6 || digits.any { !it.isDigit() }) return null
        return digits.take(6)
    }
}
