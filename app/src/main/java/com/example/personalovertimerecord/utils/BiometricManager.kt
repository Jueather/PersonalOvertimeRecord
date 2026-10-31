package com.example.personalovertimerecord.utils

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.fragment.app.FragmentActivity

/**
 * 生物识别管理器
 * 负责管理指纹/面容解锁功能
 */
object BiometricManager {
    
    private const val PREFS_NAME = "biometric_prefs"
    private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
    
    /**
     * 检查设备是否支持生物识别
     */
    fun isBiometricSupported(context: Context): Boolean {
        val biometricManager = androidx.biometric.BiometricManager.from(context)
        return when (biometricManager.canAuthenticate(
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or 
            androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )) {
            androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }
    }
    
    /**
     * 检查是否已启用生物识别保护
     */
    fun isBiometricEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)
    }
    
    /**
     * 设置是否启用生物识别保护
     */
    fun setBiometricEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
    }
    
    /**
     * 检查是否需要验证生物识别
     */
    fun needsAuthentication(context: Context): Boolean {
        return isBiometricEnabled(context) && isBiometricSupported(context)
    }
    
    /**
     * 显示生物识别验证对话框
     */
    fun showAuthenticationPrompt(
        activity: FragmentActivity,
        title: String? = null,
        subtitle: String? = null,
        description: String = "",
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
        onCancel: () -> Unit
    ) {
        val executor = ActivityCompat.getMainExecutor(activity)
        val resolvedTitle = title ?: activity.getString(com.example.personalovertimerecord.R.string.biometric_title)
        val resolvedSubtitle = subtitle ?: activity.getString(com.example.personalovertimerecord.R.string.biometric_subtitle)
        
        val biometricPrompt = androidx.biometric.BiometricPrompt(activity, executor, 
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode != androidx.biometric.BiometricPrompt.ERROR_USER_CANCELED && 
                        errorCode != androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        onError(errString.toString())
                    } else {
                        onCancel()
                    }
                }
                
                override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }
                
                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // 非致命失败（如指纹未识别），系统弹窗会提示并允许重试；
                    // 之前调用 onError 会让调用方把用户踢出应用，这里保持静默重试
                }
            })
        
        val promptInfoBuilder = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
            .setTitle(resolvedTitle)
            .setSubtitle(resolvedSubtitle)
            .setDescription(description)
            .setAllowedAuthenticators(
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or 
                androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        
        // 当不允许设备凭证时才能设置取消按钮
        // if (allowsDeviceCredential) 不需要设置取消按钮
        val promptInfo = promptInfoBuilder.build()
        
        biometricPrompt.authenticate(promptInfo)
    }
    
    /**
     * 检查是否设置了锁屏密码
     */
    fun isDeviceSecured(context: Context): Boolean {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            keyguardManager.isDeviceSecure
        } else {
            keyguardManager.isKeyguardSecure
        }
    }
}