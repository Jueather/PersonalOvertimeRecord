package com.example.personalovertimerecord.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import com.example.personalovertimerecord.R

/**
 * 主题管理模式枚举
 */
enum class ThemeMode(val value: Int, val labelRes: Int) {
    SYSTEM(0, R.string.settings_theme_system),
    LIGHT(1, R.string.settings_theme_light),
    DARK(2, R.string.settings_theme_dark);

    companion object {
        fun fromValue(value: Int): ThemeMode {
            return entries.find { it.value == value } ?: SYSTEM
        }
    }
}

/**
 * 主题管理器
 * 负责管理应用的主题切换和持久化
 */
object ThemeManager {
    
    private const val PREF_NAME = "theme_prefs"
    private const val KEY_THEME_MODE = "theme_mode"
    
    private lateinit var prefs: SharedPreferences
    
    /**
     * 初始化主题管理器
     * 必须在 Application 或 Activity 中调用
     */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * 获取当前主题模式
     */
    fun getThemeMode(): ThemeMode {
        val value = prefs.getInt(KEY_THEME_MODE, ThemeMode.SYSTEM.value)
        return ThemeMode.fromValue(value)
    }
    
    /**
     * 设置主题模式
     */
    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putInt(KEY_THEME_MODE, mode.value).apply()
        applyTheme(mode)
    }
    
    /**
     * 应用主题
     */
    fun applyTheme(mode: ThemeMode = getThemeMode()) {
        val nightMode = when (mode) {
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }
    
    /**
     * 切换到下一个主题模式
     */
    fun toggleTheme(): ThemeMode {
        val currentMode = getThemeMode()
        val nextMode = when (currentMode) {
            ThemeMode.SYSTEM -> ThemeMode.LIGHT
            ThemeMode.LIGHT -> ThemeMode.DARK
            ThemeMode.DARK -> ThemeMode.SYSTEM
        }
        setThemeMode(nextMode)
        return nextMode
    }
}