package com.example.personalovertimerecord

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.personalovertimerecord.adapter.AttendanceAdapter
import com.example.personalovertimerecord.data.OvertimeRecord
import com.example.personalovertimerecord.data.OvertimeSettings
import com.example.personalovertimerecord.data.SettingsManager
import com.example.personalovertimerecord.data.db.AppDatabase
import com.example.personalovertimerecord.databinding.ActivityMainBinding
import com.example.personalovertimerecord.dialog.AddOvertimeDialog
import com.example.personalovertimerecord.dialog.UpdateDialog
import com.example.personalovertimerecord.utils.AppLogger
import com.example.personalovertimerecord.utils.BiometricManager
import com.example.personalovertimerecord.utils.Constants
import com.example.personalovertimerecord.utils.DateUtils
import com.example.personalovertimerecord.utils.Formatter
import com.example.personalovertimerecord.utils.NetworkUtils
import com.example.personalovertimerecord.utils.OvertimeCalculator
import com.example.personalovertimerecord.utils.SyncDirection
import com.example.personalovertimerecord.utils.SyncManager
import com.example.personalovertimerecord.utils.SyncOptions
import com.example.personalovertimerecord.utils.SyncPresets
import com.example.personalovertimerecord.utils.WebDAVManager
import com.example.personalovertimerecord.utils.SyncResult
import com.example.personalovertimerecord.utils.UpdateManager
import com.example.personalovertimerecord.view.CalendarView
import com.example.personalovertimerecord.viewmodel.AttendanceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date

class MainActivity : AppCompatActivity() {
    
    companion object {
        /**
         * 生物识别验证通过的标志，由 BiometricActivity 验证成功后
         * 携带此标志重新进入 MainActivity，避免无限循环触发验证。
         */
        const val EXTRA_BIOMETRIC_PASSED = "biometric_passed"
    }
    
    private val viewModel: AttendanceViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: AttendanceAdapter
    private lateinit var settingsManager: SettingsManager
    private lateinit var syncManager: SyncManager
    private lateinit var calendarView: CalendarView
    private lateinit var statusCard: com.google.android.material.card.MaterialCardView
    private lateinit var tvStatus: android.widget.TextView
    private lateinit var statusProgress: ProgressBar
    private var currentSettings: OvertimeSettings = OvertimeSettings()
    
    private var currentYear: Int = Calendar.getInstance().get(Calendar.YEAR)
    private var currentMonth: Int = Calendar.getInstance().get(Calendar.MONTH)
    
    private var timeUpdateJob: kotlinx.coroutines.Job? = null
    private var startupCheckJob: kotlinx.coroutines.Job? = null
    private val handler = Handler()

    // Android 13+ 通知权限请求（自动同步失败通知需要）
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 用户选择即可，不强制处理结果 */ }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 生物识别保护：启用后进入主界面必须先通过身份验证。
        // 验证成功后 BiometricActivity 会携带 EXTRA_BIOMETRIC_PASSED 标志重新进入本页。
        if (BiometricManager.needsAuthentication(this) &&
            !intent.getBooleanExtra(EXTRA_BIOMETRIC_PASSED, false)
        ) {
            startActivity(Intent(this, BiometricActivity::class.java))
            finish()
            return
        }
        
        window.decorView.animation = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        settingsManager = SettingsManager(this)
        val database = try {
            AppDatabase.getDatabase(this)
        } catch (e: Exception) {
            AppLogger.e("MainActivity", "数据库初始化失败，已停止启动（防止明文降级）", e)
            showDatabaseInitError(e)
            return
        }
        syncManager = SyncManager(this, settingsManager, database)
        
        requestNotificationPermissionIfNeeded()
        
        initStatusViews()
        setupCalendar()
        setupRecyclerView()
        setupButtons()
        observeViewModel()
        updateCurrentTime()
        updateCurrentDate()
        updateCalendarData()
        
        // 启动后台检查
        runStartupChecks()
        
        // 静默检查更新（24 小时节流，不打扰用户；发现新版本才弹窗）
        checkUpdateSilently()
    }
    
    /**
     * 静默检查更新：距上次成功检查超过 24 小时才请求服务器。
     * 检查失败静默忽略（不打扰启动流程），设置页可手动重试。
     *
     * Debug 包不参与自动更新检查：本地构建号每次构建自动递增，很容易超过
     * GitHub 最新发布的 versionCode（tag 映射值较小），导致应用误判"已是最新"、
     * 拉不到远端新版本。需要验证更新流程时可在设置页手动"检查更新"。
     */
    private fun checkUpdateSilently() {
        // 本地调试包跳过自动更新检查
        if (BuildConfig.DEBUG) return
        if (!UpdateManager.shouldCheck(this, Constants.UPDATE_CHECK_INTERVAL_MS)) return
        if (!NetworkUtils.isNetworkAvailable(this)) return
        
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                UpdateManager.fetchUpdateInfo(this@MainActivity)
            } ?: return@launch
            
            UpdateManager.markChecked(this@MainActivity)
            
            if (!UpdateManager.isUpdateAvailable(this@MainActivity, info)) return@launch
            
            val force = UpdateManager.isForceUpdate(this@MainActivity, info)
            UpdateDialog.showUpdateAvailable(this@MainActivity, info, force) {
                UpdateDialog.startUpdateFlow(lifecycleScope, this@MainActivity, info)
            }
        }
    }
    
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun initStatusViews() {
        statusCard = binding.statusCard
        tvStatus = binding.tvStatus
        statusProgress = binding.statusProgress
        binding.btnCloseStatus.setOnClickListener {
            statusCard.visibility = View.GONE
        }
    }
    
    private fun runStartupChecks() {
        statusCard.visibility = View.VISIBLE
        statusProgress.visibility = View.VISIBLE
        tvStatus.text = "系统检查中..."
        
        startupCheckJob = lifecycleScope.launch {
            val errors = mutableListOf<String>()
            
            try {
                // 检查1: 数据库完整性
                val database = AppDatabase.getDatabase(this@MainActivity)
                database.attendanceDao().getAllRecordsSync() // 使用正确的方法名
                // 加密库降级为明文库时向用户明示，避免数据保护被静默关闭
                AppDatabase.encryptionFallbackReason?.let { reason ->
                    errors.add("数据库加密不可用（$reason），数据保护已降级")
                }

                // 检查2: 设置加载
                val settings = settingsManager.getSettings()
                if (settings.baseSalary <= 0) {
                    errors.add("基本工资未设置")
                }
            } catch (e: Exception) {
                errors.add("检查失败: ${e.message}")
            }
            
            // 显示检查结果
            statusProgress.visibility = View.GONE
            if (errors.isEmpty()) {
                tvStatus.text = "系统就绪"
                statusCard.setCardBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.status_success_container))
                tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_success_on_container))
                handler.postDelayed({
                    statusCard.visibility = View.GONE
                }, 2000)
            } else {
                tvStatus.text = errors.joinToString("; ")
                statusCard.setCardBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.status_error_container))
                tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_error_on_container))
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        startTimeUpdates()
        currentSettings = settingsManager.getSettings()
        adapter.updateSettings(currentSettings)
        viewModel.allAttendance.value?.let { attendanceList ->
            val monthRecords = getCurrentMonthRecords(attendanceList)
            updateMonthlyStats(monthRecords)
            refreshMonthRecords(monthRecords)
        }
    }
    
    override fun onPause() {
        super.onPause()
        stopTimeUpdates()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopTimeUpdates()
        handler.removeCallbacksAndMessages(null)
    }
    
    private fun startTimeUpdates() {
        timeUpdateJob?.cancel()
        timeUpdateJob = lifecycleScope.launch {
            while (true) {
                updateCurrentTime()
                // 对齐到下一个整秒再刷新：时间显示精确到秒，固定 delay 会漂移，
                // 导致秒数偶尔跳变不均匀（表现为时间"一卡一卡"）
                delay(
                    Constants.TIME_UPDATE_INTERVAL -
                        System.currentTimeMillis() % Constants.TIME_UPDATE_INTERVAL
                )
            }
        }
    }
    
    private fun stopTimeUpdates() {
        timeUpdateJob?.cancel()
        timeUpdateJob = null
    }

    /**
     * 数据库加密初始化失败时的引导对话框。
     * 不提供“忽略并继续”，避免以明文/无加密方式运行导致隐私数据落盘。
     */
    private fun showDatabaseInitError(e: Exception) {
        val reason = AppDatabase.encryptionFallbackReason ?: e.message ?: "未知错误"
        AlertDialog.Builder(this)
            .setTitle("数据库初始化失败")
            .setMessage(
                "无法以加密方式打开考勤数据库（$reason）。\n\n" +
                    "为避免记录以明文保存，应用将退出。请稍后重试；" +
                    "若持续失败，请先在旧版本中导出备份，再重新安装应用。"
            )
            .setCancelable(false)
            .setPositiveButton("退出") { _, _ -> finish() }
            .show()
    }
    
    private fun setupCalendar() {
        calendarView = binding.calendarView
        
        updateMonthYearText()
        
        binding.btnPrevMonth.setOnClickListener { changeMonth(-1) }
        binding.btnNextMonth.setOnClickListener { changeMonth(1) }
        
        calendarView.setOnDateClickListener(object : CalendarView.OnDateClickListener {
            override fun onDateClick(year: Int, month: Int, day: Int) {
                showAttendanceDialog(year, month, day)
            }
        })
    }
    
    private fun changeMonth(delta: Int) {
        currentMonth += delta
        when {
            currentMonth < 0 -> {
                currentMonth = 11
                currentYear--
            }
            currentMonth > 11 -> {
                currentMonth = 0
                currentYear++
            }
        }
        updateMonthYearText()
        updateCalendarData()
        // 切换月份后更新统计数据与当月记录列表（单次过滤复用）
        viewModel.allAttendance.value?.let { attendanceList ->
            val monthRecords = getCurrentMonthRecords(attendanceList)
            updateMonthlyStats(monthRecords)
            refreshMonthRecords(monthRecords)
        }
    }
    
    private fun showAttendanceDialog(year: Int, month: Int, day: Int) {
        val dateStr = DateUtils.formatDate(year, month, day)
        val existingRecord = viewModel.allAttendance.value?.find { it.date == dateStr }
        
        val dialog = AddOvertimeDialog(
            context = this,
            year = year,
            month = month,
            day = day,
            existingRecord = existingRecord,
            onSaveRecord = { record ->
                if (existingRecord != null) {
                    viewModel.updateAttendance(record)
                } else {
                    viewModel.addAttendance(record)
                }
                updateCalendarData()
            },
            onDeleteRecord = { id ->
                viewModel.allAttendance.value?.find { it.id == id }?.let {
                    viewModel.deleteAttendance(it)
                }
                updateCalendarData()
            }
        )
        dialog.show()
    }
    
    private fun showAttendanceDialog(attendance: OvertimeRecord) {
        val dateParts = DateUtils.extractDateParts(attendance.date)
        if (dateParts != null) {
            val (year, month, day) = dateParts
            showAttendanceDialog(year, month, day)
        }
    }
    
    private fun updateMonthYearText() {
        binding.tvMonthYear.text = "${currentYear}年${currentMonth + 1}月"
        binding.tvRecordSectionTitle.text = "${currentYear}年${currentMonth + 1}月加班记录"
        calendarView.setMonth(currentYear, currentMonth)
    }
    
    private fun updateCalendarData() {
        viewModel.allAttendance.value?.let {
            calendarView.setAttendanceData(it, settingsManager)
        }
    }
    
    private fun updateCurrentTime() {
        binding.tvCurrentTime.text = DateUtils.getCurrentTime()
    }
    
    private fun updateCurrentDate() {
        val currentDate = Date()
        binding.tvCurrentDate.text = DateUtils.formatToChineseDate(currentDate)
        binding.tvCurrentWeekday.text = DateUtils.getChineseWeekday(currentDate)
    }
    
    private fun setupRecyclerView() {
        adapter = AttendanceAdapter(
            settings = currentSettings,
            onItemClick = { attendance -> showAttendanceDialog(attendance) }
        )
        binding.recyclerView.adapter = adapter
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
    }
    
    private fun setupButtons() {
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        binding.btnReport.setOnClickListener {
            startActivity(Intent(this, ReportActivity::class.java))
        }
        
        binding.btnData.setOnClickListener {
            startActivity(Intent(this, DataManagerActivity::class.java))
        }
        
        binding.btnCloudSync.setOnClickListener {
            showSyncDialog()
        }
        
    }
    
    private fun showSyncDialog() {
        val options = arrayOf(
            "智能双向同步（推荐）",
            "仅上传到云端",
            "仅从云端下载",
            "完全覆盖同步"
        )
        
        AlertDialog.Builder(this)
            .setTitle("WebDAV 同步")
            .setItems(options) { _, which ->
                val (direction, options) = when (which) {
                    0 -> SyncDirection.BIDIRECTIONAL to SyncPresets.SMART_SYNC
                    1 -> SyncDirection.UPLOAD_ONLY to SyncPresets.SMART_SYNC
                    2 -> SyncDirection.DOWNLOAD_ONLY to SyncPresets.SMART_SYNC
                    3 -> SyncDirection.BIDIRECTIONAL to SyncPresets.FULL_BACKUP
                    else -> SyncDirection.BIDIRECTIONAL to SyncPresets.SMART_SYNC
                }
                performSync(direction, options)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun performSync(
        direction: SyncDirection,
        options: SyncOptions = SyncOptions()
    ) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("正在同步...")
            .setMessage("请稍候...")
            .setCancelable(false)
            .create()
        progressDialog.show()
        
        lifecycleScope.launch {
            val report = syncManager.performSync(direction, options)
            
            progressDialog.dismiss()
            
            val baseMessage = when (report.result) {
                SyncResult.SUCCESS -> "同步成功！"
                SyncResult.NO_CONFIG -> "请先在设置中配置 WebDAV"
                SyncResult.NO_NETWORK -> "网络不可用，请检查网络连接"
                SyncResult.CONNECTION_FAILED -> "连接失败（服务器响应码 ${WebDAVManager.lastResponseCode}），请检查网络和配置"
                SyncResult.UPLOAD_FAILED -> "上传失败"
                SyncResult.DOWNLOAD_FAILED -> "下载失败（服务器响应码 ${WebDAVManager.lastResponseCode}）"
                SyncResult.RESTORE_FAILED -> "恢复数据失败"
                SyncResult.NO_CHANGES -> "没有需要同步的更改"
                SyncResult.CONFLICT -> "存在数据冲突，请手动处理"
                SyncResult.ENCRYPTION_MISMATCH -> "云端数据已加密且密码不匹配，可在「设置-云端同步加密」中检查密码，或用恢复码找回"
            }
            // 成功时附带同步统计细节
            val message = if (report.isSuccess) {
                "$baseMessage（${report.toSummaryString()}）"
            } else {
                baseMessage
            }
            
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()

            // 旧加密数据首次迁移为信封格式时，新恢复码已生成，必须醒目提醒用户离线保存
            if (report.recoveryCodeGenerated) {
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("请立即保存恢复码")
                    .setMessage(
                        "你的云端备份已升级为恢复码兜底加密。恢复码是忘记同步密码后找回数据的唯一方式，" +
                            "请马上到「设置 → 云端同步加密 → 查看恢复码」抄写或截图离线保存。"
                    )
                    .setCancelable(false)
                    .setPositiveButton("去查看") { _, _ ->
                        startActivity(android.content.Intent(this@MainActivity, SettingsActivity::class.java))
                    }
                    .setNegativeButton("稍后", null)
                    .show()
            }
        }
    }
    
    private fun observeViewModel() {
        viewModel.allAttendance.observe(this) { attendanceList ->
            // 只过滤一次，供记录列表/空状态/本月统计复用，避免对每条记录重复解析日期
            val monthRecords = getCurrentMonthRecords(attendanceList)
            adapter.submitList(monthRecords)
            updateCalendarData()
            updateMonthlyStats(monthRecords)
            updateEmptyState(monthRecords)
        }
        
        viewModel.errorMessage.observe(this) { errorMsg ->
            if (!errorMsg.isNullOrEmpty()) {
                Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }
    }
    
    private fun updateEmptyState(monthRecords: List<OvertimeRecord>) {
        if (monthRecords.isEmpty()) {
            binding.recyclerView.visibility = View.GONE
            binding.emptyStateLayout.visibility = View.VISIBLE
        } else {
            binding.recyclerView.visibility = View.VISIBLE
            binding.emptyStateLayout.visibility = View.GONE
        }
    }
    
    private fun updateMonthlyStats(monthRecords: List<OvertimeRecord>) {
        var totalHours = 0.0
        var totalOvertimePay = 0.0
        
        monthRecords.forEach { attendance ->
            val result = OvertimeCalculator.calculateOvertime(attendance, currentSettings)
            totalHours += result.overtimeHours + result.extraHours
            totalOvertimePay += result.estimatedPay
        }
        
        val performanceBonus = currentSettings.baseSalary * (currentSettings.performancePercent / 100.0)
        val totalPay = currentSettings.baseSalary + performanceBonus + totalOvertimePay
        
        binding.tvTotalOvertime.text = Formatter.formatHours(totalHours)
        binding.tvTotalPay.text = Formatter.formatMoney(totalPay)
    }
    
    /**
     * 过滤出当前所选月份（currentYear/currentMonth）的加班记录，
     * 供"加班记录"卡片列表显示，与"本月统计"卡片口径一致。
     * 日期格式固定为 yyyy-MM-dd，用字符串前缀匹配即可，避免逐条解析日期。
     */
    private fun getCurrentMonthRecords(attendanceList: List<OvertimeRecord>): List<OvertimeRecord> {
        val monthPrefix = String.format("%04d-%02d", currentYear, currentMonth + 1)
        return attendanceList.filter { it.date.startsWith(monthPrefix) }
    }
    
    /**
     * 切换月份后，刷新"加班记录"列表与空状态显示。
     * @param monthRecords 已按当前所选月份过滤好的记录
     */
    private fun refreshMonthRecords(monthRecords: List<OvertimeRecord>) {
        adapter.submitList(monthRecords)
        updateEmptyState(monthRecords)
    }
}
