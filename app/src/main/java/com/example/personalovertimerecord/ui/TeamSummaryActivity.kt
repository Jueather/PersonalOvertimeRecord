package com.example.personalovertimerecord.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.personalovertimerecord.R
import com.example.personalovertimerecord.adapter.TeamSummaryAdapter
import com.example.personalovertimerecord.data.SettingsManager
import com.example.personalovertimerecord.data.model.TeamAdminUserDto
import com.example.personalovertimerecord.data.model.TeamApiException
import com.example.personalovertimerecord.data.model.TeamSummaryEntry
import com.example.personalovertimerecord.databinding.ActivityTeamSummaryBinding
import com.example.personalovertimerecord.utils.AppLogger
import com.example.personalovertimerecord.utils.TeamApiClient
import com.example.personalovertimerecord.utils.TeamErrors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

/**
 * 管理员汇总页：成员管理（建号/重置密码/停启用）+ 全员按月加班汇总（薪资不上云，仅工时与次数）。
 * 401 时清空本地登录态并退出，由设置页引导重新登录。
 */
class TeamSummaryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTeamSummaryBinding
    private lateinit var settingsManager: SettingsManager
    private var client: TeamApiClient? = null
    private var currentUserId: Long = -1L
    private var members: List<TeamAdminUserDto> = emptyList()
    private var entries: List<TeamSummaryEntry> = emptyList()
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTeamSummaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        settingsManager = SettingsManager(this)
        val auth = settingsManager.getTeamAuth()
        if (auth == null || !auth.isAdmin) {
            Toast.makeText(this, getString(R.string.team_member_no_summary), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        currentUserId = auth.userId
        client = TeamApiClient(auth.serverUrl, auth.token)

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = TeamSummaryAdapter(
            currentUserId = currentUserId,
            onResetPassword = { confirmResetPassword(it) },
            onToggleActive = { confirmToggleActive(it) }
        )

        binding.btnQuery.setOnClickListener { loadData() }
        binding.btnRefresh.setOnClickListener { loadData() }
        binding.btnCreateUser.setOnClickListener { showCreateUserDialog() }

        loadData()
    }

    private fun loadData() {
        val api = client ?: return
        if (loading) return
        val from = binding.etFrom.text?.toString()?.trim().orEmpty()
        val to = binding.etTo.text?.toString()?.trim().orEmpty()
        loading = true
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val users = api.listUsers()
                val summary = api.summary(from.ifBlank { null }, to.ifBlank { null })
                members = users
                entries = summary.entries
                render()
            } catch (e: TeamApiException) {
                handleApiError(e)
            } catch (e: Exception) {
                AppLogger.e(TAG, "加载团队数据失败", e)
                toast(getString(R.string.team_error_network))
            } finally {
                loading = false
                binding.progress.visibility = View.GONE
            }
        }
    }

    private fun render() {
        val adapter = binding.recyclerView.adapter as? TeamSummaryAdapter ?: return
        val items = mutableListOf<Any>()
        items.add(getString(R.string.team_summary_members_fmt, members.size))
        items.addAll(members)
        items.add(
            if (entries.isEmpty()) getString(R.string.team_summary_title_empty)
            else getString(R.string.team_summary_title_by_month)
        )
        items.addAll(entries)
        adapter.submitItems(items)
    }

    private fun confirmResetPassword(user: TeamAdminUserDto) {
        val api = client ?: return
        val name = user.displayName.ifBlank { user.username }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.team_reset_password))
            .setMessage(getString(R.string.team_reset_password_confirm, name, user.username))
            .setPositiveButton(R.string.common_ok) { _, _ ->
                lifecycleScope.launch {
                    try {
                        val resp = api.resetPassword(user.id)
                        MaterialAlertDialogBuilder(this@TeamSummaryActivity)
                            .setTitle(getString(R.string.team_reset_password))
                            .setMessage(getString(R.string.team_reset_password_done, resp.initialPassword))
                            .setPositiveButton(R.string.common_ok, null)
                            .show()
                    } catch (e: TeamApiException) {
                        handleApiError(e)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmToggleActive(user: TeamAdminUserDto) {
        val api = client ?: return
        if (user.id == currentUserId) {
            toast(getString(R.string.team_cannot_disable_self))
            return
        }
        val name = user.displayName.ifBlank { user.username }
        val action = if (user.isActive) {
            getString(R.string.team_set_active_disable)
        } else {
            getString(R.string.team_set_active_enable)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(action)
            .setMessage(getString(R.string.team_toggle_active_confirm, action, name, user.username))
            .setPositiveButton(R.string.common_ok) { _, _ ->
                lifecycleScope.launch {
                    try {
                        api.setActive(user.id, !user.isActive)
                        toast(
                            getString(
                                if (user.isActive) R.string.team_set_active_done_off
                                else R.string.team_set_active_done_on,
                                name
                            )
                        )
                        loadData()
                    } catch (e: TeamApiException) {
                        handleApiError(e)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCreateUserDialog() {
        val api = client ?: return

        fun makeInput(hint: String, isPassword: Boolean = false): TextInputEditText =
            TextInputEditText(this).apply {
                this.hint = hint
                if (isPassword) {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }

        val etUsername = makeInput(getString(R.string.team_create_user_username))
        val etDisplay = makeInput(getString(R.string.team_create_user_display))
        val etPassword = makeInput(getString(R.string.team_create_user_password_hint), isPassword = true)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            listOf(etUsername, etDisplay, etPassword).forEach { input ->
                addView(
                    TextInputLayout(this@TeamSummaryActivity).apply { addView(input) },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.team_create_user))
            .setView(container)
            .setPositiveButton(R.string.team_create_user_confirm, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        // 拦截确认按钮：用户名非法时不关闭对话框
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val username = etUsername.text?.toString()?.trim().orEmpty()
                when {
                    username.isBlank() -> etUsername.error = getString(R.string.team_username)
                    username.length < 3 || username.length > 32 -> etUsername.error = getString(R.string.team_username_length)
                    else -> {
                        dialog.dismiss()
                        createUser(
                            api,
                            username,
                            etPassword.text?.toString()?.trim()?.ifBlank { null },
                            etDisplay.text?.toString()?.trim().orEmpty()
                        )
                    }
                }
            }
        }
        dialog.show()
    }

    private fun createUser(api: TeamApiClient, username: String, password: String?, displayName: String) {
        lifecycleScope.launch {
            try {
                val resp = api.createUser(username, password, displayName)
                MaterialAlertDialogBuilder(this@TeamSummaryActivity)
                    .setTitle(getString(R.string.team_create_user))
                    .setMessage(getString(R.string.team_create_user_done, username, resp.initialPassword))
                    .setPositiveButton(R.string.common_ok, null)
                    .show()
                loadData()
            } catch (e: TeamApiException) {
                handleApiError(e)
            }
        }
    }

    private fun handleApiError(e: TeamApiException) {
        AppLogger.e(TAG, "团队管理请求失败", e)
        toast(TeamErrors.message(e))
        if (e.kind == TeamApiException.Kind.UNAUTHORIZED) {
            // token 失效：清空登录态并退出，由设置页重新登录
            settingsManager.clearTeamAuth()
            finish()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "TeamSummary"
    }
}
