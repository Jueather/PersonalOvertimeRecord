package com.example.personalovertimerecord.utils

import android.annotation.SuppressLint
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.MotionEvent
import android.widget.EditText
import android.widget.ImageButton
import com.google.android.material.textfield.TextInputLayout

/**
 * 敏感文本字段脱敏显示：
 * 已保存的服务器地址/用户名默认显示掩码，聚焦（再次修改）时恢复明文，
 * 失焦且内容未改动则重新脱敏；取值时脱敏态返回真实保存值。
 */
class SensitiveTextField(
    private val editText: EditText,
    private val mask: (String) -> String = ::maskMiddle
) {
    /** 已保存的真实值；null 表示尚未保存过（首次设置，直接明文输入） */
    private var realValue: String? = null

    /** 当前是否处于脱敏显示状态 */
    private var masked = false

    init {
        editText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                // 再次修改时恢复明文便于编辑
                if (masked) {
                    masked = false
                    editText.setText(realValue)
                    editText.setSelection(editText.text?.length ?: 0)
                }
            } else {
                // 未改动则恢复脱敏显示
                val saved = realValue
                if (!masked && saved != null && editText.text?.toString() == saved) {
                    masked = true
                    editText.setText(mask(saved))
                }
            }
        }
    }

    /** 用已保存值填充并进入脱敏态 */
    fun applySavedValue(value: String) {
        realValue = value
        masked = true
        editText.setText(mask(value))
    }

    /** 当前有效值：脱敏态返回真实值，否则返回输入内容 */
    fun value(): String =
        if (masked) realValue.orEmpty() else editText.text?.toString().orEmpty()
}

/** 通用脱敏：保留首尾少量字符，中间以 *** 代替 */
fun maskMiddle(value: String): String = when {
    value.length <= 2 -> "*".repeat(value.length)
    value.length <= 6 -> value.take(1) + "***" + value.takeLast(1)
    else -> value.take(2) + "***" + value.takeLast(2)
}

/** 服务器地址脱敏：保留协议头，主体部分中间打码 */
fun maskServerUrl(url: String): String {
    val schemeEnd = url.indexOf("://")
    return if (schemeEnd > 0) {
        url.substring(0, schemeEnd + 3) + maskMiddle(url.substring(schemeEnd + 3))
    } else {
        maskMiddle(url)
    }
}

/**
 * 密码"按住查看"：按下眼睛图标显示明文，松开/取消自动恢复密文。
 * 要求 [til] 使用 app:endIconMode="custom" 并设置眼睛图标。
 */
@SuppressLint("ClickableViewAccessibility")
fun setupHoldToRevealPassword(til: TextInputLayout, editText: EditText) {
    val endIcon = til.findViewById<ImageButton>(
        com.google.android.material.R.id.text_input_end_icon
    ) ?: return
    endIcon.setOnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                editText.transformationMethod = HideReturnsTransformationMethod.getInstance()
                editText.setSelection(editText.text?.length ?: 0)
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                editText.transformationMethod = PasswordTransformationMethod.getInstance()
                editText.setSelection(editText.text?.length ?: 0)
                true
            }
            else -> false
        }
    }
}
