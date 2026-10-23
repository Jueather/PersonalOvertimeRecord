package com.example.personalovertimerecord.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.personalovertimerecord.R
import com.example.personalovertimerecord.data.SettingsManager
import com.example.personalovertimerecord.utils.AppLogger
import com.example.personalovertimerecord.utils.NetworkUtils
import com.example.personalovertimerecord.utils.TeamApiClient
import com.example.personalovertimerecord.data.model.TeamApiException
import com.example.personalovertimerecord.utils.SensitiveTextField
import com.example.personalovertimerecord.utils.TeamErrors
import com.example.personalovertimerecord.utils.maskMiddle
import com.example.personalovertimerecord.utils.maskServerUrl
import com.example.personalovertimerecord.utils.setupHoldToRevealPassword
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

/**
 * 团队服务器登录页：输入服务器地址 + 账号密码，成功后写入 [SettingsManager.saveTeamAuth]
 * 并 setResult(RESULT_OK) 返回调用方（设置页）。
 */
class LoginActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SERVER_URL = "extra_server_url"

        fun start(context: Context, serverUrl: String?) {
            context.startActivity(Intent(context, LoginActivity::class.java).apply {
                putExtra(EXTRA_SERVER_URL, serverUrl)
            })
        }
    }

    private lateinit var settingsManager: SettingsManager
    private lateinit var etServerUrl: TextInputEditText
    private lateinit var etUsername: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnLogin: Button
    private lateinit var progress: ProgressBar

    /** 服务器地址/用户名脱敏显示，聚焦修改时恢复明文 */
    private lateinit var serverField: SensitiveTextField
    private lateinit var usernameField: SensitiveTextField

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        title = getString(R.string.team_sync)

        settingsManager = SettingsManager(this)
        etServerUrl = findViewById(R.id.etServerUrl)
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        progress = findViewById(R.id.progress)

        serverField = SensitiveTextField(etServerUrl, ::maskServerUrl)
        usernameField = SensitiveTextField(etUsername, ::maskMiddle)
        setupHoldToRevealPassword(findViewById<TextInputLayout>(R.id.tilPassword), etPassword)

        val prefill = intent.getStringExtra(EXTRA_SERVER_URL)
            ?: settingsManager.getTeamAuth()?.serverUrl.orEmpty()
        if (prefill.isNotBlank()) serverField.applySavedValue(prefill)
        settingsManager.getTeamAuth()?.let { usernameField.applySavedValue(it.username) }

        btnLogin.setOnClickListener { lifecycleScope.launch { doLogin() } }
    }

    private suspend fun doLogin() {
        val serverUrl = serverField.value().trim()
        val username = usernameField.value().trim()
        val password = etPassword.text?.toString().orEmpty()

        if (serverUrl.isBlank() || !serverUrl.startsWith("http")) {
            etServerUrl.error = getString(R.string.team_server_url)
            return
        }
        if (username.isBlank()) {
            etUsername.error = getString(R.string.team_username)
            return
        }
        if (password.isBlank()) {
            etPassword.error = getString(R.string.team_password)
            return
        }
        if (!NetworkUtils.isNetworkAvailable(this)) {
            Toast.makeText(this, getString(R.string.team_error_network), Toast.LENGTH_LONG).show()
            return
        }

        btnLogin.isEnabled = false
        progress.visibility = android.view.View.VISIBLE
        try {
            val response = TeamApiClient(serverUrl).login(username, password)
            settingsManager.saveTeamAuth(
                SettingsManager.TeamAuthConfig(
                    serverUrl = serverUrl,
                    token = response.token,
                    userId = response.user.id,
                    username = response.user.username,
                    displayName = response.user.displayName,
                    role = response.user.role
                )
            )
            Toast.makeText(this, getString(R.string.team_login_success), Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        } catch (e: TeamApiException) {
            AppLogger.e("团队服务器登录失败", e)
            Toast.makeText(
                this,
                getString(R.string.team_login_failed, TeamErrors.message(e)),
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            AppLogger.e("团队服务器登录异常", e)
            Toast.makeText(
                this,
                getString(R.string.team_login_failed, getString(R.string.team_error_network)),
                Toast.LENGTH_LONG
            ).show()
        } finally {
            btnLogin.isEnabled = true
            progress.visibility = android.view.View.GONE
        }
    }
}
