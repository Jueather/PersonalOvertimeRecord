package com.example.personalovertimerecord

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.personalovertimerecord.utils.BiometricManager

class BiometricActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (!BiometricManager.needsAuthentication(this)) {
            proceedToMain()
            return
        }
        
        BiometricManager.showAuthenticationPrompt(
            activity = this,
            title = getString(R.string.biometric_title),
            subtitle = getString(R.string.biometric_subtitle),
            description = getString(R.string.biometric_description),
            onSuccess = {
                proceedToMain()
            },
            onError = { error ->
                Toast.makeText(this, getString(R.string.biometric_auth_failed, error), Toast.LENGTH_SHORT).show()
                finish()
            },
            onCancel = {
                finish()
            }
        )
    }
    
    private fun proceedToMain() {
        val intent = Intent(this, MainActivity::class.java)
        // 携带"已验证通过"标志，避免 MainActivity 再次触发验证造成循环
        intent.putExtra(MainActivity.EXTRA_BIOMETRIC_PASSED, true)
        // 转发小部件/通知的快捷登记请求，验证通过后继续弹出登记框
        if (getIntent().getBooleanExtra(MainActivity.EXTRA_QUICK_ADD, false)) {
            intent.putExtra(MainActivity.EXTRA_QUICK_ADD, true)
        }
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }
}