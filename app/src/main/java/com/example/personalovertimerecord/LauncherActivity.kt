package com.example.personalovertimerecord

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

/**
 * 纯中转启动页：唯一的 exported 入口 Activity。
 *
 * 生物识别保护依赖 MainActivity 不被外部应用直接启动——
 * MainActivity 已设为 exported=false，外部应用（或 adb）无法再
 * 通过携带 Intent Extra（如 biometric_passed=true）绕过身份验证
 * 直接进入主界面；一切外部启动请求都先经过本页，再转交 MainActivity
 * 由其内部的验证逻辑决定是否要求生物识别。
 */
class LauncherActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logInstalledApps()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    /**
     * 测试：获取并输出设备上已安装应用的列表（仅对本应用可见的包）。
     *
     * Android 11+ 存在包可见性限制，未声明 QUERY_ALL_PACKAGES 时
     * 只能看到部分应用，此处仅作测试用，结果输出到 Logcat。
     */
    private fun logInstalledApps() {
        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
        Log.d(TAG, "installed apps count=${apps.size}")
        apps.forEach { info ->
            val label = pm.getApplicationLabel(info).toString()
            Log.d(TAG, "app: $label (${info.packageName})")
        }
    }

    private companion object {
        const val TAG = "LauncherActivity"
    }
}
