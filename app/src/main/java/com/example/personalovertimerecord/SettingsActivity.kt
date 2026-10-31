package com.example.personalovertimerecord

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewTreeObserver
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.personalovertimerecord.data.OvertimeSettings
import com.example.personalovertimerecord.data.SettingsManager
import com.example.personalovertimerecord.data.db.AppDatabase
import com.example.personalovertimerecord.databinding.ActivitySettingsBinding
import com.example.personalovertimerecord.dialog.UpdateDialog
import com.example.personalovertimerecord.utils.AppLogger
import com.example.personalovertimerecord.utils.AutoSyncManager
import com.example.personalovertimerecord.utils.BiometricManager
import com.example.personalovertimerecord.utils.CalendarSyncManager
import com.example.personalovertimerecord.utils.EnvelopeCrypto
import com.example.personalovertimerecord.utils.HolidayStore
import com.example.personalovertimerecord.utils.KeyBootstrapResult
import com.example.personalovertimerecord.utils.NetworkUtils
import com.example.personalovertimerecord.utils.RecoveryCodeManager
import com.example.personalovertimerecord.utils.SensitiveTextField
import com.example.personalovertimerecord.utils.SyncManager
import com.example.personalovertimerecord.utils.SyncResult
import com.example.personalovertimerecord.utils.ThemeManager
import com.example.personalovertimerecord.utils.ThemeMode
import com.example.personalovertimerecord.utils.TrialLicenseManager
import com.example.personalovertimerecord.utils.LocalLicenseState
import com.example.personalovertimerecord.utils.LicenseSource
import com.example.personalovertimerecord.utils.RedeemResult
import com.example.personalovertimerecord.utils.UpdateManager
import com.example.personalovertimerecord.utils.WebDAVConfig
import com.example.personalovertimerecord.utils.WebDAVManager
import com.example.personalovertimerecord.utils.maskMiddle
import com.example.personalovertimerecord.utils.maskServerUrl
import com.example.personalovertimerecord.utils.setupHoldToRevealPassword
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settingsManager: SettingsManager
    private lateinit var webDAVManager: WebDAVManager
    private val syncManager: SyncManager by lazy {
        SyncManager(this, settingsManager, AppDatabase.getDatabase(this))
    }
    private var lastResponseCode: Int = 0
    private var isUpdatingAutoSync = false

    private val calendarSyncManager: CalendarSyncManager by lazy {
        CalendarSyncManager(this)
    }

    /** 日历权限申请结果：两个权限都授予后才开始同步 */
    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.READ_CALENDAR] == true &&
            result[Manifest.permission.WRITE_CALENDAR] == true
        if (granted) {
            startCalendarSync()
        } else {
            Toast.makeText(this, getString(R.string.calendar_no_permission), Toast.LENGTH_LONG).show()
            refreshCalendarStatus()
        }
    }

    /** WebDAV 服务器地址/用户名脱敏显示，聚焦修改时恢复明文 */
    private val webdavServerField by lazy {
        SensitiveTextField(binding.etWebDAVServer, ::maskServerUrl)
    }
    private val webdavUsernameField by lazy {
        SensitiveTextField(binding.etWebDAVUsername, ::maskMiddle)
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        
        settingsManager = SettingsManager(this)
        webDAVManager = WebDAVManager(this)
        
        // 初始化管理器
        AutoSyncManager.init(this)
        
        loadSettings()
        loadWebDAVConfig()
        setupHoldToRevealPassword(binding.tilWebDAVPassword, binding.etWebDAVPassword)
        setupThemeGroup()
        setupBiometric()
        setupAutoSync()
        setupEncryption()
        setupButtons()
        setupShiftGroup()
        setupUpdate()
        setupHolidayData()
        setupCalendarSync()
        setupTeamSync()
        setupTrialLicense()
        setupScrollToFocusedView()
    }

    /** 团队同步（方案 B）：登录态展示、立即同步、修改密码、退出登录、管理员汇总入口 */
    private fun setupTeamSync() {
        binding.btnTeamLogin.setOnClickListener {
            com.example.personalovertimerecord.ui.LoginActivity.start(
                this,
                settingsManager.getTeamAuth()?.serverUrl
            )
        }

        binding.btnTeamSync.setOnClickListener {
            lifecycleScope.launch {
                binding.btnTeamSync.isEnabled = false
                binding.btnTeamSync.text = getString(R.string.common_syncing)
                try {
                    val report = withContext(Dispatchers.IO) { teamSyncManager().performSync() }
                    val message = when (report.result) {
                        com.example.personalovertimerecord.utils.TeamSyncManager.TeamSyncResult.SUCCESS ->
                            getString(
                                R.string.team_sync_success,
                                report.cloudRecordCount,
                                report.localRecordCount,
                                report.changedCount
                            )
                        com.example.personalovertimerecord.utils.TeamSyncManager.TeamSyncResult.NO_AUTH ->
                            getString(R.string.team_not_logged_in)
                        com.example.personalovertimerecord.utils.TeamSyncManager.TeamSyncResult.NO_NETWORK ->
                            getString(R.string.team_error_network)
                        com.example.personalovertimerecord.utils.TeamSyncManager.TeamSyncResult.FAILED ->
                            getString(
                                R.string.team_sync_failed,
                                report.message ?: getString(R.string.team_error_server)
                            )
                    }
                    Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
                } finally {
                    binding.btnTeamSync.isEnabled = true
                    binding.btnTeamSync.text = getString(R.string.settings_sync_now)
                }
            }
        }

        binding.btnTeamChangePassword.setOnClickListener {
            showTeamChangePasswordDialog()
        }

        binding.btnTeamLogout.setOnClickListener {
            if (settingsManager.getTeamAuth() == null) return@setOnClickListener
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.team_logout)
                .setMessage(R.string.team_logout_confirm)
                .setPositiveButton(R.string.team_logout) { _, _ ->
                    settingsManager.clearTeamAuth()
                    refreshTeamStatus()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.btnTeamAdminSummary.setOnClickListener {
            startActivity(android.content.Intent(this, com.example.personalovertimerecord.ui.TeamSummaryActivity::class.java))
        }

        refreshTeamStatus()
    }

    private fun teamSyncManager(): com.example.personalovertimerecord.utils.TeamSyncManager =
        com.example.personalovertimerecord.utils.TeamSyncManager(
            this,
            settingsManager,
            AppDatabase.getDatabase(this)
        )

    private fun refreshTeamStatus() {
        val auth = settingsManager.getTeamAuth()
        if (auth == null) {
            binding.tvTeamStatus.text = getString(R.string.team_not_logged_in)
            binding.btnTeamAdminSummary.visibility = android.view.View.GONE
            binding.btnTeamChangePassword.isEnabled = false
            binding.btnTeamLogout.isEnabled = false
            binding.btnTeamSync.isEnabled = false
            return
        }
        val roleText = if (auth.isAdmin) getString(R.string.team_role_admin) else getString(R.string.team_role_member)
        binding.tvTeamStatus.text = getString(
            R.string.team_logged_in_as,
            auth.displayName.ifBlank { auth.username },
            roleText
        )
        binding.btnTeamChangePassword.isEnabled = true
        binding.btnTeamLogout.isEnabled = true
        binding.btnTeamSync.isEnabled = true
        binding.btnTeamAdminSummary.visibility =
            if (auth.isAdmin) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** 修改团队密码对话框：原密码 + 新密码（至少 6 位） */
    private fun showTeamChangePasswordDialog() {
        val auth = settingsManager.getTeamAuth() ?: return
        val context = this
        val oldInput = com.google.android.material.textfield.TextInputEditText(context).apply {
            hint = getString(R.string.team_old_password)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val newInput = com.google.android.material.textfield.TextInputEditText(context).apply {
            hint = getString(R.string.team_new_password)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(
                com.google.android.material.textfield.TextInputLayout(context).apply { addView(oldInput) },
                android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                com.google.android.material.textfield.TextInputLayout(context).apply { addView(newInput) },
                android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = pad / 2 }
            )
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle(R.string.team_change_password)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val oldPwd = oldInput.text?.toString().orEmpty()
                val newPwd = newInput.text?.toString().orEmpty()
                if (oldPwd.isBlank() || newPwd.length < 6) {
                    Toast.makeText(context, getString(R.string.team_new_password_short), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            com.example.personalovertimerecord.utils.TeamApiClient(auth.serverUrl, auth.token)
                                .changePassword(oldPwd, newPwd)
                        }
                        Toast.makeText(context, getString(R.string.team_password_changed), Toast.LENGTH_SHORT).show()
                    } catch (e: com.example.personalovertimerecord.data.model.TeamApiException) {
                        Toast.makeText(
                            context,
                            getString(R.string.team_sync_failed, com.example.personalovertimerecord.utils.TeamErrors.message(e)),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    /** 节假日在线更新：展示当前覆盖年份，手动拉取仓库最新数据 */
    private fun setupHolidayData() {
        refreshHolidayInfo()

        binding.btnHolidayUpdate.setOnClickListener {
            if (!NetworkUtils.isNetworkAvailable(this)) {
                Toast.makeText(this, getString(R.string.settings_no_network), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.btnHolidayUpdate.isEnabled = false
            binding.btnHolidayUpdate.text = getString(R.string.settings_checking)

            lifecycleScope.launch {
                val result = HolidayStore.refresh(this@SettingsActivity)

                binding.btnHolidayUpdate.isEnabled = true
                binding.btnHolidayUpdate.text = getString(R.string.settings_check_holiday_update)

                val message = when (result) {
                    is HolidayStore.RefreshResult.Success ->
                        getString(
                            R.string.settings_holiday_update_success,
                            result.version,
                            result.years.firstOrNull()?.toString().orEmpty(),
                            result.years.lastOrNull()?.toString().orEmpty()
                        )
                    is HolidayStore.RefreshResult.Failure ->
                        getString(R.string.settings_holiday_update_failed, result.message)
                }
                Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
                refreshHolidayInfo()
            }
        }
    }

    private fun refreshHolidayInfo() {
        binding.tvHolidayDataInfo.text = HolidayStore.describe()
    }

    /** 系统日历双向同步：权限申请、手动同步、状态展示 */
    private fun setupCalendarSync() {
        refreshCalendarStatus()

        binding.btnCalendarSync.setOnClickListener {
            if (calendarSyncManager.hasPermission()) {
                startCalendarSync()
            } else {
                AlertDialog.Builder(this)
                    .setTitle(R.string.settings_calendar_permission_title)
                    .setMessage(R.string.settings_calendar_permission_message)
                    .setPositiveButton(R.string.settings_go_authorize) { _, _ ->
                        calendarPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_CALENDAR,
                                Manifest.permission.WRITE_CALENDAR
                            )
                        )
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun startCalendarSync() {
        binding.btnCalendarSync.isEnabled = false
        binding.btnCalendarSync.text = getString(R.string.common_syncing)

        lifecycleScope.launch {
            val report = calendarSyncManager.sync()

            binding.btnCalendarSync.isEnabled = true
            binding.btnCalendarSync.text = getString(R.string.settings_calendar_sync_now)

            val message = if (report.success) {
                buildString {
                    append(getString(R.string.settings_calendar_sync_done, report.pushed, report.pulled))
                    if (report.deletedFromCalendar > 0) {
                        append(getString(R.string.settings_calendar_deleted_from_calendar, report.deletedFromCalendar))
                    }
                    if (report.deletedFromApp > 0) {
                        append(getString(R.string.settings_calendar_deleted_from_app, report.deletedFromApp))
                    }
                    if (report.skipped > 0) {
                        append(getString(R.string.settings_calendar_skipped, report.skipped))
                    }
                }
            } else {
                report.error ?: getString(R.string.common_sync_failed)
            }
            Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
            refreshCalendarStatus()
        }
    }

    private fun refreshCalendarStatus() {
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) { calendarSyncManager.describeStatus() }
            binding.tvCalendarSyncStatus.text = status
        }
    }

    private fun setupUpdate() {
        binding.tvCurrentVersion.text =
            getString(R.string.settings_current_version, UpdateManager.getCurrentVersionName(this))

        binding.btnCheckUpdate.setOnClickListener {
            checkForUpdate()
        }

        binding.btnChangelog.setOnClickListener {
            showChangelogDialog()
        }
    }

    /** 应用内修改日志：读取 assets/changelog.md（构建期从根目录 CHANGELOG.md 拷贝），离线可看 */
    private fun showChangelogDialog() {
        lifecycleScope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    assets.open("changelog.md").bufferedReader(Charsets.UTF_8).use { it.readText() }
                }.getOrDefault("")
            }
            val content = formatChangelog(raw.ifBlank { getString(R.string.settings_changelog_empty) })
            val view = layoutInflater.inflate(R.layout.dialog_changelog, null)
            view.findViewById<android.widget.TextView>(R.id.tvChangelog).text = content
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(R.string.settings_change_log_title)
                .setView(view)
                .setPositiveButton(R.string.cancel, null)
                .show()
        }
    }

    /** 轻量 Markdown 渲染：`## `/`### ` 标题行加粗，其余原样保留 */
    private fun formatChangelog(raw: String): CharSequence {
        val builder = android.text.SpannableStringBuilder()
        raw.lines().forEachIndexed { index, line ->
            val isHeading = line.startsWith("## ") || line.startsWith("### ")
            val text = if (isHeading) line.trimStart('#').trim() else line
            val start = builder.length
            builder.append(text)
            if (isHeading) {
                builder.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    start, builder.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (index < raw.lines().size - 1) builder.append('\n')
        }
        return builder
    }
    
    private fun checkForUpdate() {
        if (!NetworkUtils.isNetworkAvailable(this)) {
            Toast.makeText(this, getString(R.string.settings_no_network), Toast.LENGTH_SHORT).show()
            return
        }
        
        binding.btnCheckUpdate.isEnabled = false
        binding.btnCheckUpdate.text = getString(R.string.settings_checking)
        
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                UpdateManager.fetchUpdateInfo(this@SettingsActivity)
            }
            
            binding.btnCheckUpdate.isEnabled = true
            binding.btnCheckUpdate.text = getString(R.string.settings_check_update)
            
            if (info == null) {
                UpdateDialog.showError(
                    this@SettingsActivity,
                    getString(R.string.settings_update_fetch_failed)
                )
                return@launch
            }
            
            UpdateManager.markChecked(this@SettingsActivity)
            
            if (UpdateManager.isUpdateAvailable(this@SettingsActivity, info)) {
                val force = UpdateManager.isForceUpdate(this@SettingsActivity, info)
                UpdateDialog.showUpdateAvailable(
                    this@SettingsActivity,
                    info,
                    force
                ) {
                    UpdateDialog.startUpdateFlow(lifecycleScope, this@SettingsActivity, info)
                }
            } else {
                UpdateDialog.showNoUpdate(this@SettingsActivity)
            }
        }
    }
    
    private fun setupBiometric() {
        val isSupported = BiometricManager.isBiometricSupported(this)
        
        if (!isSupported) {
            binding.switchBiometric.isEnabled = false
            binding.switchBiometric.isChecked = false
            binding.tvBiometricStatus.text = getString(R.string.settings_biometric_unsupported)
            binding.tvBiometricStatus.setTextColor(android.graphics.Color.GRAY)
            return
        }
        
        binding.switchBiometric.isChecked = BiometricManager.isBiometricEnabled(this)
        binding.tvBiometricStatus.text = getString(R.string.settings_biometric_ready)
        binding.tvBiometricStatus.setTextColor(android.graphics.Color.GREEN)
        
        binding.switchBiometric.setOnCheckedChangeListener { _, isChecked ->
            BiometricManager.setBiometricEnabled(this, isChecked)
            Toast.makeText(
                this,
                getString(
                    if (isChecked) R.string.settings_biometric_enabled
                    else R.string.settings_biometric_disabled
                ),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    private fun setupAutoSync() {
        // 设置自动同步开关
        binding.switchAutoSync.isChecked = AutoSyncManager.isSyncEnabled()
        updateAutoSyncSettingsVisibility()
        
        // 设置同步间隔选项
        val intervalOptions = AutoSyncManager.SYNC_INTERVALS.values.toList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, intervalOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSyncInterval.adapter = adapter
        
        // 设置当前同步间隔
        val currentInterval = AutoSyncManager.getSyncInterval()
        val intervalIndex = AutoSyncManager.SYNC_INTERVALS.keys.toList().indexOf(currentInterval)
        if (intervalIndex >= 0) {
            binding.spinnerSyncInterval.setSelection(intervalIndex)
        }
        
        // WiFi下同步开关
        binding.switchWifiOnly.isChecked = AutoSyncManager.isWifiOnly()
        
        // 更新上次同步时间
        updateLastSyncTime()
        
        // 自动同步开关监听
        binding.switchAutoSync.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingAutoSync) {
                AutoSyncManager.setSyncEnabled(this, isChecked)
                updateAutoSyncSettingsVisibility()
                Toast.makeText(
                    this,
                    getString(
                        if (isChecked) R.string.settings_auto_sync_enabled
                        else R.string.settings_auto_sync_disabled
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        
        // 同步间隔选择监听
        binding.spinnerSyncInterval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isUpdatingAutoSync) {
                    val intervals = AutoSyncManager.SYNC_INTERVALS.keys.toList()
                    if (position < intervals.size) {
                        AutoSyncManager.setSyncInterval(this@SettingsActivity, intervals[position])
                    }
                }
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // WiFi下同步开关监听
        binding.switchWifiOnly.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingAutoSync) {
                AutoSyncManager.setWifiOnly(isChecked)
            }
        }
        
        // 立即同步按钮
        binding.btnSyncNow.setOnClickListener {
            performManualSync()
        }
    }
    
    private fun updateAutoSyncSettingsVisibility() {
        binding.autoSyncSettingsLayout.visibility = if (binding.switchAutoSync.isChecked) View.VISIBLE else View.GONE
    }
    
    private fun updateLastSyncTime() {
        binding.tvLastSyncTime.text =
            getString(R.string.settings_last_sync_fmt, AutoSyncManager.getLastSyncTimeString(this@SettingsActivity))
    }
    
    private fun performManualSync() {
        if (!NetworkUtils.isNetworkAvailable(this)) {
            Toast.makeText(this, getString(R.string.settings_no_network), Toast.LENGTH_SHORT).show()
            return
        }
        
        binding.btnSyncNow.isEnabled = false
        binding.btnSyncNow.text = getString(R.string.common_syncing)
        
        AutoSyncManager.performSync(this) { success, message ->
            binding.btnSyncNow.isEnabled = true
            binding.btnSyncNow.text = getString(R.string.settings_sync_now)
            updateLastSyncTime()
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun setupThemeGroup() {
        // 根据当前主题模式设置选中的 RadioButton
        val currentTheme = ThemeManager.getThemeMode()
        when (currentTheme) {
            ThemeMode.SYSTEM -> binding.themeSystem.isChecked = true
            ThemeMode.LIGHT -> binding.themeLight.isChecked = true
            ThemeMode.DARK -> binding.themeDark.isChecked = true
        }
        
        binding.themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val newTheme = when (checkedId) {
                R.id.themeSystem -> ThemeMode.SYSTEM
                R.id.themeLight -> ThemeMode.LIGHT
                R.id.themeDark -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            }
            ThemeManager.setThemeMode(newTheme)
            Toast.makeText(this, getString(R.string.settings_theme_changed, getString(newTheme.labelRes)), Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun setupEncryption() {
        val currentSettings = settingsManager.getSettings()
        
        // 导出加密开关
        binding.switchExportEncryption.isChecked = currentSettings.exportEncryptionEnabled
        updateExportPasswordVisibility()
        
        // 同步加密开关
        binding.switchSyncEncryption.isChecked = currentSettings.syncEncryptionEnabled
        updateSyncPasswordVisibility()
        
        // 设置密码
        binding.etExportPassword.setText(currentSettings.exportPassword)
        binding.etSyncPassword.setText(currentSettings.syncPassword)
        
        // 导出加密开关监听
        binding.switchExportEncryption.setOnCheckedChangeListener { _, isChecked ->
            updateExportPasswordVisibility()
            Toast.makeText(
                this,
                getString(
                    if (isChecked) R.string.settings_export_encrypt_enabled
                    else R.string.settings_export_encrypt_disabled
                ),
                Toast.LENGTH_SHORT
            ).show()
        }
        
        // 同步加密开关监听
        binding.switchSyncEncryption.setOnCheckedChangeListener { _, isChecked ->
            updateSyncPasswordVisibility()
            Toast.makeText(
                this,
                getString(
                    if (isChecked) R.string.settings_sync_encrypt_enabled
                    else R.string.settings_sync_encrypt_disabled
                ),
                Toast.LENGTH_SHORT
            ).show()
            // 密码留空时“加密”实际不生效（SyncManager 以密码是否为空为准），及时提醒
            if (isChecked && binding.etSyncPassword.text?.toString().isNullOrBlank()) {
                Toast.makeText(this, getString(R.string.settings_sync_encrypt_need_password), Toast.LENGTH_LONG).show()
            }
        }

        // 查看同步密码（生物识别通过后展示）
        binding.btnViewSyncPassword.setOnClickListener {
            withBiometricAuth(getString(R.string.settings_view_sync_password)) {
                val pwd = binding.etSyncPassword.text?.toString()
                    ?.takeIf { it.isNotBlank() }
                    ?: settingsManager.getSettings().syncPassword
                showSimpleTextDialog(
                    title = getString(R.string.settings_sync_password_title),
                    message = pwd.ifBlank { getString(R.string.settings_not_set) },
                    copyable = true
                )
            }
        }

        // 查看/更换恢复码（生物识别通过后展示）
        binding.btnViewRecoveryCode.setOnClickListener {
            withBiometricAuth(getString(R.string.settings_view_recovery_code)) { showRecoveryCodeOptions() }
        }
        binding.tvRecoveryPending.setOnClickListener {
            withBiometricAuth(getString(R.string.settings_view_recovery_code)) { showRecoveryCodeOptions() }
        }

        // 忘记同步密码：用恢复码重置并恢复云端数据
        binding.btnRecoverWithCode.setOnClickListener {
            startRecoveryWithCode()
        }
    }

    private fun updateExportPasswordVisibility() {
        binding.exportPasswordLayout.visibility = if (binding.switchExportEncryption.isChecked) View.VISIBLE else View.GONE
    }

    private fun updateSyncPasswordVisibility() {
        val on = binding.switchSyncEncryption.isChecked
        binding.syncPasswordLayout.visibility = if (on) View.VISIBLE else View.GONE
        binding.tvRecoveryPending.visibility =
            if (on && settingsManager.isRecoveryCodePending()) View.VISIBLE else View.GONE
    }

    /**
     * 保存后处理同步加密密钥材料：
     * - 关闭加密：清除本机 DEK/恢复码（云端历史文件仍为密文）；
     * - 本机已有材料：沿用（恢复槽跨设备原样复用，恢复码持续有效）；
     * - 本机无材料（首次开启 / 重装 / 换机）：必须先探测云端——
     *   云端已有信封则接入原 DEK 与恢复槽（旧恢复码继续有效）；
     *   只有云端确认无加密备份时才生成新 DEK + 新恢复码。
     *   绝不能在不看云端的情况下直接生成，否则首次同步会覆盖云端信封，使原恢复码失效。
     */
    private fun handleSyncKeyMaterialAfterSave(prevOn: Boolean, nowOn: Boolean, afterDone: () -> Unit) {
        if (!nowOn) {
            if (prevOn) {
                settingsManager.clearSyncKeyMaterial()
                Toast.makeText(this, getString(R.string.settings_sync_encrypt_off_notice), Toast.LENGTH_LONG).show()
            }
            afterDone()
            return
        }
        if (settingsManager.getSyncKeyMaterial() != null) {
            afterDone()
            return
        }

        val password = settingsManager.getSettings().syncPassword
        val progress = AlertDialog.Builder(this)
            .setTitle(R.string.settings_check_cloud_title)
            .setMessage(R.string.settings_check_cloud_message)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    syncManager.bootstrapKeyMaterialOnEnable(password)
                }
            } catch (e: Exception) {
                AppLogger.e("探测云端密钥材料异常", e)
                KeyBootstrapResult.Unavailable
            }
            runCatching { progress.dismiss() }
            if (isFinishing) return@launch

            when (result) {
                is KeyBootstrapResult.Adopted -> {
                    updateSyncPasswordVisibility()
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle(R.string.settings_adopted_title)
                        .setMessage(R.string.settings_adopted_message)
                        .setPositiveButton(R.string.settings_got_it) { _, _ -> afterDone() }
                        .setCancelable(false)
                        .show()
                }

                is KeyBootstrapResult.Generated -> {
                    updateSyncPasswordVisibility()
                    showRecoveryCodeDialog(result.recoveryCode) {
                        settingsManager.setRecoveryCodePending(false)
                        updateSyncPasswordVisibility()
                        afterDone()
                    }
                }

                is KeyBootstrapResult.WrongPassword -> {
                    // 留在当前页让用户立即修正密码；材料未生成，任何同步都会因密码不匹配而中止，不会覆盖云端
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle(R.string.settings_password_mismatch_title)
                        .setMessage(R.string.settings_password_mismatch_message)
                        .setPositiveButton(R.string.settings_reenter, null)
                        .setNeutralButton(R.string.settings_recover_short) { _, _ -> startRecoveryWithCode() }
                        .setCancelable(false)
                        .show()
                }

                else -> {
                    // NoConfig / Unavailable：设置已保存，材料推迟到首次联网同步时自动接入或生成
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.settings_cloud_unavailable),
                        Toast.LENGTH_LONG
                    ).show()
                    afterDone()
                }
            }
        }
    }
    
    private fun setupScrollToFocusedView() {
        val rootView = binding.root
        
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val rect = android.graphics.Rect()
            rootView.getWindowVisibleDisplayFrame(rect)
            val screenHeight = rootView.rootView.height
            val keypadHeight = screenHeight - rect.bottom
            
            if (keypadHeight > screenHeight * 0.15) {
                val focusedView = currentFocus
                if (focusedView != null) {
                    binding.scrollView.post {
                        binding.scrollView.requestChildFocus(focusedView, focusedView)
                    }
                }
            }
        }
    }
    
    private fun setupShiftGroup() {
        binding.shiftGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.shiftCustom) {
                binding.customTimeLayout.visibility = View.VISIBLE
            } else {
                binding.customTimeLayout.visibility = View.GONE
            }
        }
    }
    
    private fun loadSettings() {
        val currentSettings = settingsManager.getSettings()

        binding.shiftGroup.check(R.id.shiftNormal)
        binding.customTimeLayout.visibility = View.GONE

        binding.etWorkStart.setText(currentSettings.workStartTime)
        binding.etWorkEnd.setText(currentSettings.workEndTime)
        binding.etBaseSalary.setText(currentSettings.baseSalary.toString())
        binding.etPerformancePercent.setText(currentSettings.performancePercent.toString())
        binding.etFixedBonus.setText(currentSettings.fixedBonus.toString())
        binding.etMonthlyWorkDays.setText(currentSettings.monthlyWorkDays.toString())
        binding.etDailyWorkHours.setText(currentSettings.dailyWorkHours.toString())

        // 五险一金与个税
        binding.etSocialInsuranceBase.setText(currentSettings.socialInsuranceBase.toString())
        binding.etHousingFundBase.setText(currentSettings.housingFundBase.toString())
        binding.etPensionRate.setText(currentSettings.pensionRate.toString())
        binding.etMedicalRate.setText(currentSettings.medicalRate.toString())
        binding.etUnemploymentRate.setText(currentSettings.unemploymentRate.toString())
        binding.etHousingFundRate.setText(currentSettings.housingFundRate.toString())
        binding.etSpecialDeduction.setText(currentSettings.specialDeduction.toString())
        binding.switchQuickEntryNotification.isChecked = settingsManager.isQuickEntryNotificationEnabled()
    }
    
    private fun loadWebDAVConfig() {
        val config = settingsManager.getWebDAVConfig()
        config?.let {
            // 服务器地址/用户名脱敏显示，聚焦修改时恢复明文
            webdavServerField.applySavedValue(it.serverUrl)
            webdavUsernameField.applySavedValue(it.username)
            binding.etWebDAVPassword.setText(it.password)
            binding.etWebDAVPath.setText(it.remotePath)
        } ?: run {
            binding.etWebDAVPath.setText("/")
        }
    }
    
    private fun setupButtons() {
        binding.btnSave.setOnClickListener {
            saveSettings()
        }
        
        binding.btnTestConnection.setOnClickListener {
            testWebDAVConnection()
        }
        
        binding.btnSaveWebDAV.setOnClickListener {
            saveWebDAVConfig()
        }
    }
    
    private fun saveSettings() {
        try {
            val workStart = binding.etWorkStart.text?.toString() ?: "08:00"
            val workEnd = binding.etWorkEnd.text?.toString() ?: "17:00"

            val baseSalary = binding.etBaseSalary.text?.toString()?.toDoubleOrNull() ?: 5000.0
            val performancePercent = binding.etPerformancePercent.text?.toString()?.toDoubleOrNull() ?: 0.0
            val fixedBonus = binding.etFixedBonus.text?.toString()?.toDoubleOrNull() ?: 0.0
            val monthlyWorkDays = binding.etMonthlyWorkDays.text?.toString()?.toDoubleOrNull() ?: 21.75
            val dailyWorkHours = binding.etDailyWorkHours.text?.toString()?.toDoubleOrNull() ?: 8.0

            if (!validateInput(baseSalary, fixedBonus, monthlyWorkDays, dailyWorkHours)) {
                return
            }

            // 五险一金与个税
            val socialInsuranceBase = binding.etSocialInsuranceBase.text?.toString()?.toDoubleOrNull() ?: 0.0
            val housingFundBase = binding.etHousingFundBase.text?.toString()?.toDoubleOrNull() ?: 0.0
            val pensionRate = binding.etPensionRate.text?.toString()?.toDoubleOrNull() ?: 8.0
            val medicalRate = binding.etMedicalRate.text?.toString()?.toDoubleOrNull() ?: 2.0
            val unemploymentRate = binding.etUnemploymentRate.text?.toString()?.toDoubleOrNull() ?: 0.5
            val housingFundRate = binding.etHousingFundRate.text?.toString()?.toDoubleOrNull() ?: 12.0
            val specialDeduction = binding.etSpecialDeduction.text?.toString()?.toDoubleOrNull() ?: 0.0

            if (!validateDeductionInput(
                    socialInsuranceBase, housingFundBase,
                    pensionRate, medicalRate, unemploymentRate, housingFundRate,
                    specialDeduction
                )
            ) {
                return
            }

            // 工资倍率已从设置页移除，改在添加/编辑加班记录时按条自定义；
            // 保留本地已存储的倍率值，作为新记录的默认倍率来源（老用户已改过的默认值不丢）
            val currentRates = settingsManager.getSettings()
            val prevSyncEncryptionOn = currentRates.syncEncryptionEnabled

            val settings = OvertimeSettings(
                workStartTime = workStart,
                workEndTime = workEnd,
                overtimeRateNormal = currentRates.overtimeRateNormal,
                overtimeRateWeekend = currentRates.overtimeRateWeekend,
                overtimeRateHoliday = currentRates.overtimeRateHoliday,
                baseSalary = baseSalary,
                performancePercent = performancePercent,
                fixedBonus = fixedBonus,
                monthlyWorkDays = monthlyWorkDays,
                dailyWorkHours = dailyWorkHours,
                // 五险一金与个税
                socialInsuranceBase = socialInsuranceBase,
                housingFundBase = housingFundBase,
                pensionRate = pensionRate,
                medicalRate = medicalRate,
                unemploymentRate = unemploymentRate,
                housingFundRate = housingFundRate,
                specialDeduction = specialDeduction,
                salaryConfigVersion = 2,
                // 加密设置
                exportEncryptionEnabled = binding.switchExportEncryption.isChecked,
                exportPassword = binding.etExportPassword.text?.toString() ?: "",
                syncEncryptionEnabled = binding.switchSyncEncryption.isChecked,
                syncPassword = binding.etSyncPassword.text?.toString() ?: ""
            )

            settingsManager.saveSettings(settings)
            // 快捷登记通知开关（设备本地偏好）
            settingsManager.setQuickEntryNotificationEnabled(binding.switchQuickEntryNotification.isChecked)
            com.example.personalovertimerecord.widget.QuickRecordWidget.refresh(this)
            com.example.personalovertimerecord.utils.QuickEntryNotification.sync(this)

            // 同步加密密钥材料处理（首次开启时生成恢复码并强制展示），完成后退出页面
            handleSyncKeyMaterialAfterSave(
                prevOn = prevSyncEncryptionOn,
                nowOn = settings.syncEncryptionEnabled
            ) {
                Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
                finish()
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.settings_save_failed_fmt, e.message.orEmpty()), Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun testWebDAVConnection() {
        val config = getCurrentWebDAVConfig() ?: return
        
        // 检查网络连接
        if (!NetworkUtils.isNetworkAvailable(this)) {
            Toast.makeText(this, getString(R.string.settings_no_network), Toast.LENGTH_SHORT).show()
            return
        }
        
        binding.btnTestConnection.isEnabled = false
        binding.btnTestConnection.text = getString(R.string.settings_testing)
        
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val testUrl = buildTestUrl(config)
                    AppLogger.d("开始测试连接: $testUrl")
                    webDAVManager.testConnection(config)
                } catch (e: Exception) {
                    AppLogger.e("测试连接异常", e)
                    false
                }
            }
            
            binding.btnTestConnection.isEnabled = true
            binding.btnTestConnection.text = getString(R.string.settings_test_connection)
            
            if (success) {
                Toast.makeText(this@SettingsActivity, getString(R.string.settings_connect_success), Toast.LENGTH_SHORT).show()
            } else {
                showWebDAVHelpDialog(config)
            }
        }
    }
    
    private fun buildTestUrl(config: WebDAVConfig): String {
        val cleanBaseUrl = config.serverUrl.trimEnd('/')
        val cleanPath = config.remotePath.trim('/')
        
        return if (cleanPath.isEmpty()) {
            "$cleanBaseUrl/.test_connection"
        } else {
            "$cleanBaseUrl/$cleanPath/.test_connection"
        }
    }
    
    // ================== 同步加密：恢复码 / 生物识别 / 找回流程 ==================

    /** 设备支持生物识别（含锁屏凭证）时先验证，再执行敏感操作；不支持时直接执行 */
    private fun withBiometricAuth(title: String, action: () -> Unit) {
        if (BiometricManager.isBiometricSupported(this)) {
            BiometricManager.showAuthenticationPrompt(
                activity = this,
                title = title,
                subtitle = getString(R.string.biometric_continue_subtitle),
                onSuccess = action,
                onError = { msg ->
                    Toast.makeText(this, getString(R.string.biometric_auth_failed, msg), Toast.LENGTH_SHORT).show()
                },
                onCancel = { }
            )
        } else {
            action()
        }
    }

    /** 通用文本展示弹窗，可一键复制 */
    private fun showSimpleTextDialog(title: String, message: String, copyable: Boolean) {
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.common_close, null)
        if (copyable) {
            builder.setNeutralButton(R.string.settings_copy) { _, _ -> copyToClipboard(title, message) }
        }
        builder.show()
    }

    /**
     * 展示恢复码，引导用户离线保存。
     * @param onAck 用户点击"我已离线保存"后回调（用于清除待保存标记、关闭页面）
     */
    private fun showRecoveryCodeDialog(code: String, onAck: (() -> Unit)? = null) {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_recovery_title)
            .setMessage(getString(R.string.settings_recovery_message, code))
            .setCancelable(false)
            .setPositiveButton(R.string.settings_recovery_saved) { _, _ -> onAck?.invoke() }
            .setNeutralButton(R.string.settings_copy) { _, _ ->
                copyToClipboard(getString(R.string.settings_recovery_label), code)
            }
            .show()
    }

    /** 查看恢复码入口：本机持有明文则展示；否则说明并允许重新生成 */
    private fun showRecoveryCodeOptions() {
        val material = settingsManager.getSyncKeyMaterial()
        if (material == null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_recovery_label)
                .setMessage(R.string.settings_recovery_not_generated)
                .setPositiveButton(R.string.settings_got_it, null)
                .show()
            return
        }

        if (material.recoveryCode.isNotBlank()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_recovery_label)
                .setMessage(getString(R.string.settings_recovery_current, material.recoveryCode))
                .setPositiveButton(R.string.common_close, null)
                .setNeutralButton(R.string.settings_copy) { _, _ ->
                    copyToClipboard(getString(R.string.settings_recovery_label), material.recoveryCode)
                }
                .setNegativeButton(R.string.settings_recovery_regenerate) { _, _ -> confirmRegenerateRecoveryCode() }
                .show()
        } else {
            // 通过同步密码接入的新设备：本机不知道恢复码明文，但可用本地 DEK 重新签发一个
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_recovery_local_missing_title)
                .setMessage(R.string.settings_recovery_local_missing_message)
                .setPositiveButton(R.string.settings_regenerate) { _, _ -> confirmRegenerateRecoveryCode() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun confirmRegenerateRecoveryCode() {
        val material = settingsManager.getSyncKeyMaterial() ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_recovery_regenerate)
            .setMessage(R.string.settings_regenerate_confirm)
            .setPositiveButton(R.string.settings_regenerate_confirm_button) { _, _ ->
                try {
                    val dek = Base64.decode(material.dek, Base64.NO_WRAP)
                    val newCode = RecoveryCodeManager.generate()
                    val newSlot = EnvelopeCrypto.wrapDek(dek, RecoveryCodeManager.normalize(newCode))
                    settingsManager.saveSyncKeyMaterial(
                        material.copy(
                            recoveryCode = newCode,
                            recoverySlotB64 = Base64.encodeToString(newSlot.toBytes(), Base64.NO_WRAP)
                        )
                    )
                    settingsManager.setRecoveryCodePending(true)
                    updateSyncPasswordVisibility()
                    showRecoveryCodeDialog(newCode) {
                        settingsManager.setRecoveryCodePending(false)
                        updateSyncPasswordVisibility()
                    }
                    Toast.makeText(this, getString(R.string.settings_recovery_changed), Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    AppLogger.e("更换恢复码失败", e)
                    Toast.makeText(this, getString(R.string.settings_recovery_change_failed, e.message.orEmpty()), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 忘记同步密码的找回流程：
     * 输入恢复码 → 设置新密码 → 校验云端信封 → 落盘新材料 → 立即同步恢复数据。
     */
    private fun startRecoveryWithCode() {
        if (settingsManager.getWebDAVConfig() == null) {
            Toast.makeText(this, getString(R.string.settings_webdav_config_missing), Toast.LENGTH_LONG).show()
            return
        }

        val codeInput = EditText(this).apply {
            hint = getString(R.string.settings_recovery_code_hint)
            inputType = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setPadding(60, 30, 60, 10)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_recover_short)
            .setMessage(R.string.settings_recover_message)
            .setView(codeInput)
            .setPositiveButton(R.string.settings_next) { _, _ ->
                val code = codeInput.text?.toString().orEmpty()
                if (!RecoveryCodeManager.isValid(code)) {
                    Toast.makeText(this, getString(R.string.settings_recovery_invalid), Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                askNewPasswordForRecovery(code)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askNewPasswordForRecovery(recoveryCode: String) {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 30, 60, 10) }
        val pwdInput = EditText(this).apply {
            hint = getString(R.string.settings_new_sync_password_hint)
            inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val confirmInput = EditText(this).apply {
            hint = getString(R.string.settings_confirm_password_hint)
            inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(pwdInput)
        container.addView(confirmInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_set_new_sync_password)
            .setView(container)
            .setPositiveButton(R.string.settings_confirm_recover) { _, _ ->
                val pwd = pwdInput.text?.toString().orEmpty()
                val confirm = confirmInput.text?.toString().orEmpty()
                when {
                    pwd.isBlank() -> Toast.makeText(this, getString(R.string.settings_password_empty), Toast.LENGTH_SHORT).show()
                    pwd != confirm -> Toast.makeText(this, getString(R.string.settings_password_mismatch_input), Toast.LENGTH_SHORT).show()
                    else -> runRecovery(recoveryCode, pwd)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runRecovery(recoveryCode: String, newPassword: String) {
        val progress = AlertDialog.Builder(this)
            .setTitle(R.string.settings_recovering_title)
            .setMessage(R.string.settings_recovering_message)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                syncManager.validateAndApplyRecovery(recoveryCode, newPassword)
            }
            if (result != SyncResult.SUCCESS) {
                progress.dismiss()
                Toast.makeText(this@SettingsActivity, recoveryErrorText(result), Toast.LENGTH_LONG).show()
                return@launch
            }
            // 恢复码验证通过：立即执行一次双向同步，把云端数据拉回本机
            val report = withContext(Dispatchers.IO) { syncManager.performSync() }
            progress.dismiss()

            binding.switchSyncEncryption.isChecked = true
            binding.etSyncPassword.setText(newPassword)
            updateSyncPasswordVisibility()

            val msg = if (report.isSuccess) {
                getString(R.string.settings_recovery_success, report.toSummaryString())
            } else {
                getString(R.string.settings_recovery_sync_incomplete, report.result.toString())
            }
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(R.string.settings_recovery_done_title)
                .setMessage(msg)
                .setPositiveButton(R.string.settings_got_it, null)
                .show()
        }
    }

    private fun recoveryErrorText(result: SyncResult): String = when (result) {
        SyncResult.ENCRYPTION_MISMATCH -> getString(R.string.settings_recovery_error_mismatch)
        SyncResult.NO_CONFIG -> getString(R.string.settings_recovery_error_no_config)
        SyncResult.NO_NETWORK -> getString(R.string.settings_recovery_error_no_network)
        SyncResult.DOWNLOAD_FAILED ->
            getString(R.string.settings_recovery_error_download, WebDAVManager.lastResponseCode)
        else -> getString(R.string.settings_recovery_error_generic, result.toString())
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, getString(R.string.settings_copied_fmt, label), Toast.LENGTH_SHORT).show()
    }

    private fun showWebDAVHelpDialog(config: WebDAVConfig) {
        val responseCodeInfo = if (WebDAVManager.lastResponseCode != 0) {
            val codeDesc = when (WebDAVManager.lastResponseCode) {
                200 -> getString(R.string.webdav_status_200)
                201 -> getString(R.string.webdav_status_201)
                204 -> getString(R.string.webdav_status_204)
                401 -> getString(R.string.webdav_status_401)
                403 -> getString(R.string.webdav_status_403)
                404 -> getString(R.string.webdav_status_404)
                405 -> getString(R.string.webdav_status_405)
                429 -> getString(R.string.webdav_status_429)
                500 -> getString(R.string.webdav_status_500)
                503 -> getString(R.string.webdav_status_503)
                -1 -> getString(R.string.webdav_status_connection)
                else -> getString(R.string.webdav_status_unknown)
            }
            getString(R.string.webdav_status_prefix, WebDAVManager.lastResponseCode, codeDesc)
        } else {
            ""
        }

        // 回显用户当前实际填写的地址，方便核对（不要展示与本账号无关的示例专属节点）
        val currentServerUrl = config.serverUrl.ifBlank { getString(R.string.settings_not_filled) }
        val currentRemotePath = config.remotePath.ifBlank { "/" }

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_connect_failed_title)
            .setMessage(responseCodeInfo + getString(R.string.settings_webdav_help, currentServerUrl, currentRemotePath))
            .setPositiveButton(R.string.common_ok, null)
            .show()
    }
    
    /**
     * 保存 WebDAV 配置。
     * 首次配置且“同步加密”未生效（开关未开或密码为空）时，弹出安全提醒：
     * 让用户意识到未加密的数据会以明文上传到第三方服务器。
     */
    private fun saveWebDAVConfig() {
        val config = getCurrentWebDAVConfig() ?: return

        val isFirstConfig = settingsManager.getWebDAVConfig() == null
        val syncEncryptionEffective = binding.switchSyncEncryption.isChecked &&
            !binding.etSyncPassword.text?.toString().isNullOrBlank()

        if (isFirstConfig && !syncEncryptionEffective) {
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_cloud_unencrypted_title)
                .setMessage(R.string.settings_cloud_unencrypted_message)
                .setPositiveButton(R.string.settings_enable_encryption) { _, _ ->
                    binding.switchSyncEncryption.isChecked = true
                    binding.etSyncPassword.requestFocus()
                }
                .setNegativeButton(R.string.settings_save_plaintext) { _, _ -> doSaveWebDAVConfig(config) }
                .show()
        } else {
            doSaveWebDAVConfig(config)
        }
    }

    private fun doSaveWebDAVConfig(config: WebDAVConfig) {
        settingsManager.saveWebDAVConfig(config)
        // 保存后恢复脱敏显示，后续仅聚焦修改时才显示明文
        webdavServerField.applySavedValue(config.serverUrl)
        webdavUsernameField.applySavedValue(config.username)
        Toast.makeText(this, getString(R.string.settings_webdav_saved), Toast.LENGTH_SHORT).show()
    }

    private fun getCurrentWebDAVConfig(): WebDAVConfig? {
        val serverUrl = webdavServerField.value().trim()
        val username = webdavUsernameField.value().trim()
        val password = binding.etWebDAVPassword.text?.toString()
        val remotePath = binding.etWebDAVPath.text?.toString()?.trim() ?: "/"
        
        if (serverUrl.isNullOrEmpty() || username.isNullOrEmpty() || password.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.settings_webdav_incomplete), Toast.LENGTH_SHORT).show()
            return null
        }
        
        // 自动添加 https:// 如果没有协议
        val normalizedServerUrl = if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            "https://$serverUrl"
        } else {
            serverUrl
        }

        // 明文 HTTP 已禁止（见 network_security_config.xml），提示用户改用 https
        if (normalizedServerUrl.startsWith("http://")) {
            Toast.makeText(
                this,
                getString(R.string.settings_http_disabled),
                Toast.LENGTH_LONG
            ).show()
        }

        return WebDAVConfig(
            serverUrl = normalizedServerUrl,
            username = username,
            password = password,
            remotePath = remotePath
        )
    }
    
    /**
     * 五险一金/个税配置校验：基数不可为负，比例 0~100%，专项附加扣除不可为负。
     */
    private fun validateDeductionInput(
        socialInsuranceBase: Double,
        housingFundBase: Double,
        pensionRate: Double,
        medicalRate: Double,
        unemploymentRate: Double,
        housingFundRate: Double,
        specialDeduction: Double
    ): Boolean {
        if (socialInsuranceBase < 0 || housingFundBase < 0) {
            Toast.makeText(this, getString(R.string.settings_base_negative), Toast.LENGTH_SHORT).show()
            return false
        }
        val rates = listOf(pensionRate, medicalRate, unemploymentRate, housingFundRate)
        if (rates.any { it < 0 || it > 100 }) {
            Toast.makeText(this, getString(R.string.settings_ratio_invalid), Toast.LENGTH_SHORT).show()
            return false
        }
        if (specialDeduction < 0) {
            Toast.makeText(this, getString(R.string.settings_deduction_negative), Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun validateInput(
        baseSalary: Double,
        fixedBonus: Double,
        monthlyWorkDays: Double,
        dailyWorkHours: Double
    ): Boolean {
        if (baseSalary <= 0) {
            Toast.makeText(this, getString(R.string.salary_invalid), Toast.LENGTH_SHORT).show()
            return false
        }
        if (fixedBonus < 0) {
            Toast.makeText(this, getString(R.string.settings_fixed_bonus_negative), Toast.LENGTH_SHORT).show()
            return false
        }
        if (monthlyWorkDays <= 0 || monthlyWorkDays > 31) {
            Toast.makeText(this, getString(R.string.work_days_invalid), Toast.LENGTH_SHORT).show()
            return false
        }
        if (dailyWorkHours <= 0 || dailyWorkHours > 24) {
            Toast.makeText(this, getString(R.string.work_hours_invalid), Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    // ========== 软件激活 / 试用 ==========

    /** 试用/激活卡片：展示状态 + 兑换激活码 */
    private fun setupTrialLicense() {
        val licenseManager = TrialLicenseManager(this)
        val deviceId = licenseManager.getDeviceId()
        val readableDeviceId = licenseManager.getDeviceIdReadable()

        // 判断服务器是否可用（有无配置）
        val hasServerConfigured = SettingsManager(this).getTrialServerUrl() != null
        binding.layoutOfflineHint.visibility = if (hasServerConfigured) View.GONE else View.VISIBLE
        binding.tvDeviceId.text = readableDeviceId

        binding.btnCopyDeviceId.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText(getString(R.string.settings_device_id_label), deviceId)
            )
            Toast.makeText(this, getString(R.string.settings_device_id_copied), Toast.LENGTH_SHORT).show()
        }

        fun refreshStatus() {
            val state = licenseManager.checkLocalStatus()
            val daysLeft = licenseManager.remainingDays()
            binding.tvTrialStatus.text = when (state) {
                is LocalLicenseState.Active -> {
                    val days = daysLeft ?: 0
                    val sourceLabel = when (state.source) {
                        LicenseSource.SERVER -> getString(R.string.license_source_server)
                        LicenseSource.OFFLINE -> getString(R.string.license_source_offline)
                    }
                    if (days > 0) getString(R.string.license_active_days, sourceLabel, days)
                    else getString(R.string.license_expired, sourceLabel)
                }
                is LocalLicenseState.FreeTrial -> {
                    val days = daysLeft ?: 0
                    if (days > 0) getString(R.string.license_trial_days, days)
                    else getString(R.string.license_trial_ended)
                }
                LocalLicenseState.NO_LICENSE -> getString(R.string.license_unactivated)
            }
            // 剩余 ≤3 天 / 已过期时用醒目的卡片底色
            val warnBg = when {
                state is LocalLicenseState.Active && daysLeft != null && daysLeft in 1..3 -> 0xFFFFF3E0.toInt()
                state is LocalLicenseState.Active && daysLeft != null && daysLeft <= 0 -> 0xFFFDECEA.toInt()
                state is LocalLicenseState.FreeTrial && daysLeft != null && daysLeft in 1..3 -> 0xFFFFF3E0.toInt()
                state is LocalLicenseState.FreeTrial && daysLeft != null && daysLeft <= 0 -> 0xFFFDECEA.toInt()
                else -> 0xFFFFFFFF.toInt()
            }
            binding.cardTrialLicense.setCardBackgroundColor(warnBg)
        }

        refreshStatus()

        // 页面打开时后台尝试从服务器同步一次（有网络时刷新状态）
        lifecycleScope.launch {
            licenseManager.syncFromServer() // 网络失败自动保留本地缓存
            refreshStatus()
        }

        binding.btnActivateTrial.setOnClickListener {
            val code = binding.etTrialCode.text?.toString()?.trim().orEmpty()
            if (code.isEmpty()) {
                binding.tilTrialCode.error = getString(R.string.settings_enter_activation_code)
                return@setOnClickListener
            }
            binding.tilTrialCode.error = null
            binding.btnActivateTrial.isEnabled = false
            binding.btnActivateTrial.text = getString(R.string.settings_activating)
            lifecycleScope.launch {
                val result = licenseManager.redeemCode(code)
                binding.btnActivateTrial.isEnabled = true
                binding.btnActivateTrial.text = getString(R.string.settings_activate_button)
                when (result) {
                    is RedeemResult.Success -> {
                        val days = result.response.remainingMs / (24L * 60L * 60L * 1000L)
                        val sourceLabel = when (result.source) {
                            LicenseSource.SERVER -> getString(R.string.license_source_server_verified)
                            LicenseSource.OFFLINE -> getString(R.string.license_source_offline_verified)
                        }
                        Toast.makeText(
                            this@SettingsActivity,
                            getString(R.string.settings_activation_success, sourceLabel, days),
                            Toast.LENGTH_LONG
                        ).show()
                        binding.etTrialCode.text?.clear()
                        refreshStatus()
                    }
                    is RedeemResult.Error -> {
                        Toast.makeText(
                            this@SettingsActivity,
                            result.message,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }
}
