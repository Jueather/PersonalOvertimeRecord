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
            Toast.makeText(this, "未授予日历权限，无法同步日历", Toast.LENGTH_LONG).show()
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
                binding.btnTeamSync.text = "同步中..."
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
                    binding.btnTeamSync.text = "立即同步"
                }
            }
        }

        binding.btnTeamChangePassword.setOnClickListener {
            showTeamChangePasswordDialog()
        }

        binding.btnTeamLogout.setOnClickListener {
            if (settingsManager.getTeamAuth() == null) return@setOnClickListener
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("退出登录")
                .setMessage("退出后将停止团队同步（本地数据不受影响），确定退出？")
                .setPositiveButton(R.string.delete) { _, _ ->
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
                    Toast.makeText(context, "新密码至少 6 位", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "请先检查网络连接", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.btnHolidayUpdate.isEnabled = false
            binding.btnHolidayUpdate.text = "检查中..."

            lifecycleScope.launch {
                val result = HolidayStore.refresh(this@SettingsActivity)

                binding.btnHolidayUpdate.isEnabled = true
                binding.btnHolidayUpdate.text = "检查节假日更新"

                val message = when (result) {
                    is HolidayStore.RefreshResult.Success ->
                        "节假日数据已更新到 v${result.version}（覆盖 ${result.years.firstOrNull()} - ${result.years.lastOrNull()}）"
                    is HolidayStore.RefreshResult.Failure ->
                        "更新失败：${result.message}\n继续使用当前数据。"
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
                    .setTitle("日历权限")
                    .setMessage(
                        "日历同步需要读取与写入系统日历的权限：\n" +
                            "• 把加班/请假记录写成全天日程\n" +
                            "• 把日历里的修改、改期与删除回写到应用"
                    )
                    .setPositiveButton("去授权") { _, _ ->
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
        binding.btnCalendarSync.text = "同步中..."

        lifecycleScope.launch {
            val report = calendarSyncManager.sync()

            binding.btnCalendarSync.isEnabled = true
            binding.btnCalendarSync.text = "立即同步日历"

            val message = if (report.success) {
                buildString {
                    append("日历同步完成：推送 ${report.pushed} 条，回写 ${report.pulled} 条")
                    if (report.deletedFromCalendar > 0) append("，日历删除 ${report.deletedFromCalendar} 条")
                    if (report.deletedFromApp > 0) append("，清理事件 ${report.deletedFromApp} 条")
                    if (report.skipped > 0) append("，跳过 ${report.skipped} 条")
                }
            } else {
                report.error ?: "同步失败"
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
        binding.tvCurrentVersion.text = "当前版本：v${UpdateManager.getCurrentVersionName(this)}"

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
            val content = formatChangelog(raw.ifBlank { "暂无日志" })
            val view = layoutInflater.inflate(R.layout.dialog_changelog, null)
            view.findViewById<android.widget.TextView>(R.id.tvChangelog).text = content
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("修改日志")
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
            Toast.makeText(this, "请先检查网络连接", Toast.LENGTH_SHORT).show()
            return
        }
        
        binding.btnCheckUpdate.isEnabled = false
        binding.btnCheckUpdate.text = "检查中..."
        
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                UpdateManager.fetchUpdateInfo(this@SettingsActivity)
            }
            
            binding.btnCheckUpdate.isEnabled = true
            binding.btnCheckUpdate.text = "检查更新"
            
            if (info == null) {
                UpdateDialog.showError(
                    this@SettingsActivity,
                    "无法获取更新信息，请检查网络后重试。\n\n若为刚发布的版本，GitHub 可能需要几分钟生成更新清单。"
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
            binding.tvBiometricStatus.text = "设备不支持生物识别功能"
            binding.tvBiometricStatus.setTextColor(android.graphics.Color.GRAY)
            return
        }
        
        binding.switchBiometric.isChecked = BiometricManager.isBiometricEnabled(this)
        binding.tvBiometricStatus.text = "已准备就绪（支持指纹/面容解锁）"
        binding.tvBiometricStatus.setTextColor(android.graphics.Color.GREEN)
        
        binding.switchBiometric.setOnCheckedChangeListener { _, isChecked ->
            BiometricManager.setBiometricEnabled(this, isChecked)
            Toast.makeText(this, if (isChecked) "已启用生物识别保护" else "已禁用生物识别保护", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, if (isChecked) "已启用自动同步" else "已禁用自动同步", Toast.LENGTH_SHORT).show()
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
        binding.tvLastSyncTime.text = "上次同步：${AutoSyncManager.getLastSyncTimeString(this@SettingsActivity)}"
    }
    
    private fun performManualSync() {
        if (!NetworkUtils.isNetworkAvailable(this)) {
            Toast.makeText(this, "请先检查网络连接", Toast.LENGTH_SHORT).show()
            return
        }
        
        binding.btnSyncNow.isEnabled = false
        binding.btnSyncNow.text = "同步中..."
        
        AutoSyncManager.performSync(this) { success, message ->
            binding.btnSyncNow.isEnabled = true
            binding.btnSyncNow.text = "立即同步"
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
            Toast.makeText(this, "主题已切换为${newTheme.displayName}", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, if (isChecked) "已启用导出加密" else "已禁用导出加密", Toast.LENGTH_SHORT).show()
        }
        
        // 同步加密开关监听
        binding.switchSyncEncryption.setOnCheckedChangeListener { _, isChecked ->
            updateSyncPasswordVisibility()
            Toast.makeText(this, if (isChecked) "已启用同步加密" else "已禁用同步加密", Toast.LENGTH_SHORT).show()
            // 密码留空时“加密”实际不生效（SyncManager 以密码是否为空为准），及时提醒
            if (isChecked && binding.etSyncPassword.text?.toString().isNullOrBlank()) {
                Toast.makeText(this, "请设置同步加密密码；密码留空时等同于未加密", Toast.LENGTH_LONG).show()
            }
        }

        // 查看同步密码（生物识别通过后展示）
        binding.btnViewSyncPassword.setOnClickListener {
            withBiometricAuth("查看同步密码") {
                val pwd = binding.etSyncPassword.text?.toString()
                    ?.takeIf { it.isNotBlank() }
                    ?: settingsManager.getSettings().syncPassword
                showSimpleTextDialog(
                    title = "同步密码",
                    message = pwd.ifBlank { "（未设置）" },
                    copyable = true
                )
            }
        }

        // 查看/更换恢复码（生物识别通过后展示）
        binding.btnViewRecoveryCode.setOnClickListener {
            withBiometricAuth("查看恢复码") { showRecoveryCodeOptions() }
        }
        binding.tvRecoveryPending.setOnClickListener {
            withBiometricAuth("查看恢复码") { showRecoveryCodeOptions() }
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
                Toast.makeText(this, "已关闭同步加密：之后上传不再加密，云端历史文件仍为密文", Toast.LENGTH_LONG).show()
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
            .setTitle("正在检查云端备份")
            .setMessage("正在确认云端是否已有加密备份，请稍候…")
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
                        .setTitle("已接入现有加密备份")
                        .setMessage(
                            "检测到云端已有使用该密码的加密备份，已直接接入：\n\n" +
                                "· 原恢复码继续有效，无需重新保存；\n" +
                                "· 本机不保存恢复码明文。若原恢复码已遗失，可在下方通过生物识别后重新签发。"
                        )
                        .setPositiveButton("知道了") { _, _ -> afterDone() }
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
                        .setTitle("同步密码不匹配")
                        .setMessage("云端已有加密备份，但当前填写的密码无法解开。\n\n请输入与原设备一致的同步密码后重新保存；若已忘记密码，可使用恢复码找回。")
                        .setPositiveButton("重新输入", null)
                        .setNeutralButton("用恢复码找回") { _, _ -> startRecoveryWithCode() }
                        .setCancelable(false)
                        .show()
                }

                else -> {
                    // NoConfig / Unavailable：设置已保存，材料推迟到首次联网同步时自动接入或生成
                    Toast.makeText(
                        this@SettingsActivity,
                        "暂时无法检查云端备份，将在下次联网同步时自动确认并处理",
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
            val monthlyWorkDays = binding.etMonthlyWorkDays.text?.toString()?.toDoubleOrNull() ?: 21.75
            val dailyWorkHours = binding.etDailyWorkHours.text?.toString()?.toDoubleOrNull() ?: 8.0

            if (!validateInput(baseSalary, monthlyWorkDays, dailyWorkHours)) {
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
                salaryConfigVersion = 1,
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
                Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
                finish()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun testWebDAVConnection() {
        val config = getCurrentWebDAVConfig() ?: return
        
        // 检查网络连接
        if (!NetworkUtils.isNetworkAvailable(this)) {
            Toast.makeText(this, "请先检查网络连接", Toast.LENGTH_SHORT).show()
            return
        }
        
        binding.btnTestConnection.isEnabled = false
        binding.btnTestConnection.text = "测试中..."
        
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
            binding.btnTestConnection.text = "测试连接"
            
            if (success) {
                Toast.makeText(this@SettingsActivity, "连接成功！", Toast.LENGTH_SHORT).show()
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
                subtitle = "请验证身份以继续",
                onSuccess = action,
                onError = { msg -> Toast.makeText(this, "验证失败：$msg", Toast.LENGTH_SHORT).show() },
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
            .setPositiveButton("关闭", null)
        if (copyable) {
            builder.setNeutralButton("复制") { _, _ -> copyToClipboard(title, message) }
        }
        builder.show()
    }

    /**
     * 展示恢复码，引导用户离线保存。
     * @param onAck 用户点击"我已离线保存"后回调（用于清除待保存标记、关闭页面）
     */
    private fun showRecoveryCodeDialog(code: String, onAck: (() -> Unit)? = null) {
        AlertDialog.Builder(this)
            .setTitle("你的同步恢复码")
            .setMessage(
                "恢复码是忘记同步密码后找回云端数据的唯一方式，请务必抄写或截图保存在安全的地方（不要存在被同步的网盘里）。\n\n" +
                    "恢复码：\n$code\n\n" +
                    "更换设备后，凭 WebDAV 配置 + 恢复码即可重置同步密码并恢复数据。"
            )
            .setCancelable(false)
            .setPositiveButton("我已离线保存") { _, _ -> onAck?.invoke() }
            .setNeutralButton("复制") { _, _ -> copyToClipboard("恢复码", code) }
            .show()
    }

    /** 查看恢复码入口：本机持有明文则展示；否则说明并允许重新生成 */
    private fun showRecoveryCodeOptions() {
        val material = settingsManager.getSyncKeyMaterial()
        if (material == null) {
            AlertDialog.Builder(this)
                .setTitle("恢复码")
                .setMessage("尚未生成恢复码。请先开启同步加密并保存设置，系统会自动生成恢复码。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        if (material.recoveryCode.isNotBlank()) {
            AlertDialog.Builder(this)
                .setTitle("恢复码")
                .setMessage("当前恢复码：\n${material.recoveryCode}\n\n请离线妥善保管。")
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制") { _, _ -> copyToClipboard("恢复码", material.recoveryCode) }
                .setNegativeButton("更换恢复码") { _, _ -> confirmRegenerateRecoveryCode() }
                .show()
        } else {
            // 通过同步密码接入的新设备：本机不知道恢复码明文，但可用本地 DEK 重新签发一个
            AlertDialog.Builder(this)
                .setTitle("本机未保存恢复码")
                .setMessage(
                    "这台设备是通过同步密码接入的，恢复码明文只保存在最初开启加密的设备上。\n\n" +
                        "你可以用离线保存的恢复码继续使用；如果已无法找到，可以在本机重新生成一个——" +
                        "重新生成后旧恢复码立即失效（下次同步后对云端生效），请重新离线保存。"
                )
                .setPositiveButton("重新生成") { _, _ -> confirmRegenerateRecoveryCode() }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun confirmRegenerateRecoveryCode() {
        val material = settingsManager.getSyncKeyMaterial() ?: return
        AlertDialog.Builder(this)
            .setTitle("更换恢复码")
            .setMessage("更换后旧恢复码立即失效，新恢复码需重新离线保存。确认更换？")
            .setPositiveButton("确认更换") { _, _ ->
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
                    Toast.makeText(this, "恢复码已更换，下次同步后对云端生效", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    AppLogger.e("更换恢复码失败", e)
                    Toast.makeText(this, "更换失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 忘记同步密码的找回流程：
     * 输入恢复码 → 设置新密码 → 校验云端信封 → 落盘新材料 → 立即同步恢复数据。
     */
    private fun startRecoveryWithCode() {
        if (settingsManager.getWebDAVConfig() == null) {
            Toast.makeText(this, "请先填写并保存 WebDAV 服务器配置", Toast.LENGTH_LONG).show()
            return
        }

        val codeInput = EditText(this).apply {
            hint = "请输入离线保存的恢复码（如 7K2M-9XQF-4BT8-N6VD）"
            inputType = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setPadding(60, 30, 60, 10)
        }
        AlertDialog.Builder(this)
            .setTitle("用恢复码找回")
            .setMessage("将从云端读取备份验证恢复码，验证通过后可设置新的同步密码并恢复数据。")
            .setView(codeInput)
            .setPositiveButton("下一步") { _, _ ->
                val code = codeInput.text?.toString().orEmpty()
                if (!RecoveryCodeManager.isValid(code)) {
                    Toast.makeText(this, "恢复码格式不正确（应为 4 组 × 4 位字符）", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                askNewPasswordForRecovery(code)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun askNewPasswordForRecovery(recoveryCode: String) {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 30, 60, 10) }
        val pwdInput = EditText(this).apply {
            hint = "新同步密码"; inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val confirmInput = EditText(this).apply {
            hint = "再次输入新密码"; inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(pwdInput)
        container.addView(confirmInput)

        AlertDialog.Builder(this)
            .setTitle("设置新的同步密码")
            .setView(container)
            .setPositiveButton("确认找回") { _, _ ->
                val pwd = pwdInput.text?.toString().orEmpty()
                val confirm = confirmInput.text?.toString().orEmpty()
                when {
                    pwd.isBlank() -> Toast.makeText(this, "新密码不能为空", Toast.LENGTH_SHORT).show()
                    pwd != confirm -> Toast.makeText(this, "两次输入的密码不一致", Toast.LENGTH_SHORT).show()
                    else -> runRecovery(recoveryCode, pwd)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runRecovery(recoveryCode: String, newPassword: String) {
        val progress = AlertDialog.Builder(this)
            .setTitle("正在找回")
            .setMessage("正在验证恢复码并同步云端数据，请稍候…")
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
                "密码已重置，云端数据已恢复（${report.toSummaryString()}）"
            } else {
                "新同步密码已生效，但数据同步未完成：${report.result}，可稍后在首页手动同步"
            }
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("找回完成")
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private fun recoveryErrorText(result: SyncResult): String = when (result) {
        SyncResult.ENCRYPTION_MISMATCH ->
            "恢复码错误，或云端备份仍是旧版加密格式（需原设备先同步一次升级格式）"
        SyncResult.NO_CONFIG -> "请先配置并保存 WebDAV 服务器信息"
        SyncResult.NO_NETWORK -> "网络不可用，请检查连接后重试"
        SyncResult.DOWNLOAD_FAILED -> "无法读取云端备份（HTTP ${WebDAVManager.lastResponseCode}）"
        else -> "找回失败：$result"
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "$label 已复制", Toast.LENGTH_SHORT).show()
    }

    private fun showWebDAVHelpDialog(config: WebDAVConfig) {
        val responseCodeInfo = if (WebDAVManager.lastResponseCode != 0) {
            val codeDesc = when (WebDAVManager.lastResponseCode) {
                200 -> "成功"
                201 -> "创建成功"
                204 -> "无内容（成功）"
                401 -> "认证失败，请检查用户名和密码"
                403 -> "禁止访问"
                404 -> "路径不存在"
                405 -> "方法不允许"
                429 -> "请求过于频繁，被服务端限流，请稍后重试"
                500 -> "服务器内部错误"
                503 -> "服务暂不可用（限流或过载），请稍后重试"
                -1 -> "连接异常，请检查网络"
                else -> "未知错误"
            }
            "服务器响应码: ${WebDAVManager.lastResponseCode}\n说明: $codeDesc\n\n"
        } else {
            ""
        }

        // 回显用户当前实际填写的地址，方便核对（不要展示与本账号无关的示例专属节点）
        val currentServerUrl = config.serverUrl.ifBlank { "（未填写）" }
        val currentRemotePath = config.remotePath.ifBlank { "/" }

        AlertDialog.Builder(this)
            .setTitle("连接失败")
            .setMessage(
                """
                $responseCodeInfo
                连接失败，请检查以下配置：

                • 服务器地址：${currentServerUrl}
                • 远程路径：${currentRemotePath}
                • 用户名和密码：确保正确填写（部分服务需使用应用专用密码）
                • 网络连接：确保设备可以访问外网

                建议：
                1. 先尝试把远程路径改为 "/"
                2. 确认网盘已开启 WebDAV 功能
                3. 401 时优先检查账号与密码（部分服务需使用应用专用密码，而非登录密码）
                4. 尝试关闭 VPN 再测试
                """.trimIndent()
            )
            .setPositiveButton("确定", null)
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
                .setTitle("云端备份未加密")
                .setMessage(
                    "即将保存 WebDAV 配置，但当前未开启有效的同步加密。\n\n" +
                        "未加密时，考勤与工资数据会以明文上传到第三方 WebDAV 服务器" +
                        "（仅靠 https 和账号密码保护）。\n\n" +
                        "建议先开启上方的“同步加密”并设置密码，数据将经 AES-256 加密后再上传。"
                )
                .setPositiveButton("去开启加密") { _, _ ->
                    binding.switchSyncEncryption.isChecked = true
                    binding.etSyncPassword.requestFocus()
                }
                .setNegativeButton("仍以明文保存") { _, _ -> doSaveWebDAVConfig(config) }
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
        Toast.makeText(this, "WebDAV配置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun getCurrentWebDAVConfig(): WebDAVConfig? {
        val serverUrl = webdavServerField.value().trim()
        val username = webdavUsernameField.value().trim()
        val password = binding.etWebDAVPassword.text?.toString()
        val remotePath = binding.etWebDAVPath.text?.toString()?.trim() ?: "/"
        
        if (serverUrl.isNullOrEmpty() || username.isNullOrEmpty() || password.isNullOrEmpty()) {
            Toast.makeText(this, "请填写完整的WebDAV配置信息", Toast.LENGTH_SHORT).show()
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
                "应用已禁止明文 HTTP 流量，请使用 https:// 地址",
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
            Toast.makeText(this, "缴费基数不能为负数", Toast.LENGTH_SHORT).show()
            return false
        }
        val rates = listOf(pensionRate, medicalRate, unemploymentRate, housingFundRate)
        if (rates.any { it < 0 || it > 100 }) {
            Toast.makeText(this, "个人缴纳比例应在0-100%之间", Toast.LENGTH_SHORT).show()
            return false
        }
        if (specialDeduction < 0) {
            Toast.makeText(this, "专项附加扣除不能为负数", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun validateInput(
        baseSalary: Double,
        monthlyWorkDays: Double,
        dailyWorkHours: Double
    ): Boolean {
        if (baseSalary <= 0) {
            Toast.makeText(this, "基本工资必须大于0", Toast.LENGTH_SHORT).show()
            return false
        }
        if (monthlyWorkDays <= 0 || monthlyWorkDays > 31) {
            Toast.makeText(this, "每月工作天数应在1-31之间", Toast.LENGTH_SHORT).show()
            return false
        }
        if (dailyWorkHours <= 0 || dailyWorkHours > 24) {
            Toast.makeText(this, "每日工作时长应在0.1-24之间", Toast.LENGTH_SHORT).show()
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
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("设备ID", deviceId))
            Toast.makeText(this, "设备 ID 已复制", Toast.LENGTH_SHORT).show()
        }

        fun refreshStatus() {
            val state = licenseManager.checkLocalStatus()
            val daysLeft = licenseManager.remainingDays()
            binding.tvTrialStatus.text = when (state) {
                is LocalLicenseState.Active -> {
                    val days = daysLeft ?: 0
                    val sourceLabel = when (state.source) {
                        LicenseSource.SERVER -> "正式激活"
                        LicenseSource.OFFLINE -> "离线激活"
                    }
                    if (days > 0) "$sourceLabel，剩余 $days 天"
                    else "激活已过期（$sourceLabel）"
                }
                is LocalLicenseState.FreeTrial -> {
                    val days = daysLeft ?: 0
                    if (days > 0) "免费试用中，剩余 $days 天"
                    else "免费试用已结束（可输入激活码继续使用）"
                }
                LocalLicenseState.NO_LICENSE -> "未激活，输入激活码开始试用"
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
                binding.tilTrialCode.error = "请输入激活码"
                return@setOnClickListener
            }
            binding.tilTrialCode.error = null
            binding.btnActivateTrial.isEnabled = false
            binding.btnActivateTrial.text = "激活中..."
            lifecycleScope.launch {
                val result = licenseManager.redeemCode(code)
                binding.btnActivateTrial.isEnabled = true
                binding.btnActivateTrial.text = "激活 / 续期"
                when (result) {
                    is RedeemResult.Success -> {
                        val days = result.response.remainingMs / (24L * 60L * 60L * 1000L)
                        val sourceLabel = when (result.source) {
                            LicenseSource.SERVER -> "服务器验证"
                            LicenseSource.OFFLINE -> "离线签名验证"
                        }
                        Toast.makeText(
                            this@SettingsActivity,
                            "🎉 激活成功（$sourceLabel）！剩余 ${days} 天",
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
