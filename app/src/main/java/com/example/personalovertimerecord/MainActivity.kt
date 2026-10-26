package com.example.personalovertimerecord

import android.Manifest
import android.app.DatePickerDialog
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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.personalovertimerecord.adapter.AttendanceAdapter
import com.example.personalovertimerecord.adapter.RecordListItem
import com.example.personalovertimerecord.data.OvertimeRecord
import com.example.personalovertimerecord.data.OvertimeSettings
import com.example.personalovertimerecord.data.SettingsManager
import com.example.personalovertimerecord.data.ShiftType
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
import com.example.personalovertimerecord.utils.QuickEntryNotification
import com.example.personalovertimerecord.utils.SyncDirection
import com.example.personalovertimerecord.utils.SyncManager
import com.example.personalovertimerecord.utils.SyncOptions
import com.example.personalovertimerecord.utils.SyncPresets
import com.example.personalovertimerecord.utils.WebDAVManager
import com.example.personalovertimerecord.utils.SyncResult
import com.example.personalovertimerecord.ui.TeamSummaryActivity
import com.example.personalovertimerecord.utils.TrialLicenseManager
import com.example.personalovertimerecord.utils.LocalLicenseState
import com.example.personalovertimerecord.utils.UpdateManager
import com.example.personalovertimerecord.view.CalendarView
import com.example.personalovertimerecord.viewmodel.AttendanceViewModel
import com.example.personalovertimerecord.widget.QuickRecordWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    
    companion object {
        /**
         * 生物识别验证通过的标志，由 BiometricActivity 验证成功后
         * 携带此标志重新进入 MainActivity，避免无限循环触发验证。
         */
        const val EXTRA_BIOMETRIC_PASSED = "biometric_passed"

        /** 小部件/常驻通知点击后直接打开当日登记对话框 */
        const val EXTRA_QUICK_ADD = "quick_add"

        /** 试用提醒：SharedPreferences 名称与 key */
        private const val TRIAL_PREFS = "trial_reminder"
        private const val KEY_LAST_REMIND_DAY = "last_remind_day"
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

    /** 明细筛选条件 */
    private enum class RecordFilter { ALL, OVERTIME, EXTRA, LEAVE, NIGHT }

    private var recordFilter = RecordFilter.ALL
    private var sortByAmount = false
    /** 当前月份的原始记录（筛选/排序前），切换筛选时无需重新查库 */
    private var monthRecords: List<OvertimeRecord> = emptyList()
    
    private var currentYear: Int = Calendar.getInstance().get(Calendar.YEAR)
    private var currentMonth: Int = Calendar.getInstance().get(Calendar.MONTH)
    /** 来自小部件/通知的快捷登记请求，待当月记录加载完成后打开当日登记框 */
    private var pendingQuickAdd = false
    // 最近一次刷新的日期（yyyy-MM-dd），用于秒级 ticker 检测跨天
    private var lastKnownDate: String? = null
    
    private var timeUpdateJob: kotlinx.coroutines.Job? = null
    private var startupCheckJob: kotlinx.coroutines.Job? = null
    private var syncDebugHideJob: kotlinx.coroutines.Job? = null
    private val syncDebugLines = mutableListOf<String>()
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
            // 快捷登记请求需要在验证通过后继续生效，转发给 BiometricActivity
            val biometricIntent = Intent(this, BiometricActivity::class.java).apply {
                if (intent.getBooleanExtra(EXTRA_QUICK_ADD, false)) {
                    putExtra(EXTRA_QUICK_ADD, true)
                }
            }
            startActivity(biometricIntent)
            finish()
            return
        }
        
        window.decorView.animation = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 消费一次性的快捷登记标志，避免配置变更重建时重复弹窗
        pendingQuickAdd = intent.getBooleanExtra(EXTRA_QUICK_ADD, false)
        intent.removeExtra(EXTRA_QUICK_ADD)
        
        settingsManager = SettingsManager(this)
        val database = try {
            AppDatabase.getDatabase(this)
        } catch (e: Exception) {
            AppLogger.e("MainActivity", "数据库初始化失败，已停止启动（防止明文降级）", e)
            showDatabaseInitError(e)
            return
        }
        syncManager = SyncManager(this, settingsManager, database)

        // 团队管理员才显示全员汇总入口（普通成员隐藏）
        binding.btnTeamSummary.visibility =
            if (settingsManager.getTeamAuth()?.isAdmin == true) View.VISIBLE else View.GONE

        // 温和提醒：试用过期 / 剩余 ≤3 天时弹窗（不阻断核心功能）
        checkTrialReminder()

        requestNotificationPermissionIfNeeded()
        
        initStatusViews()
        setupCalendar()
        setupRecyclerView()
        setupButtons()
        observeViewModel()
        observeSyncProgress()
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
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!intent.getBooleanExtra(EXTRA_QUICK_ADD, false)) return
        intent.removeExtra(EXTRA_QUICK_ADD)
        if (viewModel.allAttendance.value != null) {
            openQuickAddDialog()
        } else {
            pendingQuickAdd = true
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
        // 工资设置变化会改变实发工资，回到前台时同步刷新小部件与常驻通知
        QuickRecordWidget.refresh(this)
        QuickEntryNotification.sync(this)
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
                refreshDateOnRollover()
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
    
    /** 打开今天的登记对话框（小部件/常驻通知的快捷入口） */
    private fun openQuickAddDialog() {
        val now = Calendar.getInstance()
        showAttendanceDialog(
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH),
            now.get(Calendar.DAY_OF_MONTH)
        )
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
        lastKnownDate = todayKey()
    }

    private fun todayKey(): String {
        val now = Calendar.getInstance()
        return DateUtils.formatDate(
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH),
            now.get(Calendar.DAY_OF_MONTH)
        )
    }

    /**
     * 应用长时间驻留时检测跨天：刷新日期/星期，并在跨月且用户仍停留在上一天所在月份时，
     * 跟随切换到新的当前月（避免列表与统计一直停在旧月份）。
     */
    private fun refreshDateOnRollover() {
        val today = todayKey()
        if (today == lastKnownDate) return

        val previousParts = lastKnownDate?.let { DateUtils.extractDateParts(it) }
        updateCurrentDate()

        val now = Calendar.getInstance()
        val newYear = now.get(Calendar.YEAR)
        val newMonth = now.get(Calendar.MONTH)
        val viewingPreviousMonth = previousParts != null &&
            currentYear == previousParts.first && currentMonth == previousParts.second - 1
        if (!viewingPreviousMonth || (currentYear == newYear && currentMonth == newMonth)) return

        currentYear = newYear
        currentMonth = newMonth
        updateMonthYearText()
        updateCalendarData()
        viewModel.allAttendance.value?.let { attendanceList ->
            val monthRecords = getCurrentMonthRecords(attendanceList)
            updateMonthlyStats(monthRecords)
            refreshMonthRecords(monthRecords)
        }
    }
    
    private fun setupRecyclerView() {
        // 点击卡片展开/收起详情；编辑、删除、复制走长按菜单
        adapter = AttendanceAdapter(
            settings = currentSettings,
            onItemLongClick = { attendance -> showRecordMenu(attendance) }
        )
        binding.recyclerView.adapter = adapter
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        attachSwipeToDelete()
        setupRecordFilter()
    }

    /** 左滑删除：滑动后先复位视图，再弹确认框（取消时记录原样保留） */
    private fun attachSwipeToDelete() {
        val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                val row = adapter.currentList.getOrNull(position) as? RecordListItem.Row ?: return
                adapter.notifyItemChanged(position)
                confirmDeleteRecord(row.record)
            }

            // 周分组头不可滑动删除
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                val position = viewHolder.bindingAdapterPosition
                val swipable = adapter.currentList.getOrNull(position) is RecordListItem.Row
                return if (swipable) makeMovementFlags(0, ItemTouchHelper.LEFT) else makeMovementFlags(0, 0)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.recyclerView)
    }

    /** 长按快捷菜单：编辑 / 删除 / 复制到其他日期 */
    private fun showRecordMenu(record: OvertimeRecord) {
        val options = arrayOf("编辑记录", "删除记录", "复制到其他日期")
        AlertDialog.Builder(this)
            .setTitle(Formatter.formatDate(record.date))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAttendanceDialog(record)
                    1 -> confirmDeleteRecord(record)
                    2 -> copyRecordToAnotherDate(record)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteRecord(record: OvertimeRecord) {
        AlertDialog.Builder(this)
            .setTitle("删除记录")
            .setMessage("确定删除 ${Formatter.formatDate(record.date)} 的记录吗？删除会同步到其他设备。")
            .setPositiveButton("删除") { _, _ ->
                viewModel.deleteAttendance(record)
                updateCalendarData()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 复制当天记录到其他日期：仅复制时长/倍率/备注等登记内容，目标日期已存在记录时拒绝，
     * 避免同一日期出现两行（与录入表单的互斥约定保持一致）。
     */
    private fun copyRecordToAnotherDate(record: OvertimeRecord) {
        val parts = DateUtils.extractDateParts(record.date) ?: return
        val (year, month, day) = parts
        DatePickerDialog(this, { _, newYear, newMonth, newDay ->
            val newDate = DateUtils.formatDate(newYear, newMonth, newDay)
            when {
                newDate > todayKey() -> Toast.makeText(
                    this, "不能复制到未来日期", Toast.LENGTH_SHORT
                ).show()
                viewModel.allAttendance.value?.any { it.date == newDate } == true ->
                    Toast.makeText(this, "该日期已有记录，请直接编辑", Toast.LENGTH_SHORT).show()
                else -> {
                    viewModel.addAttendance(
                        record.copy(id = 0L, date = newDate, createdAt = System.currentTimeMillis())
                    )
                    updateCalendarData()
                    Toast.makeText(this, "已复制到 ${Formatter.formatDate(newDate)}", Toast.LENGTH_SHORT).show()
                }
            }
        }, year, month, day).show()
    }

    /** 筛选 chip 与排序切换：只在内存中过滤当前月记录，不触发数据库查询 */
    private fun setupRecordFilter() {
        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            recordFilter = when (checkedIds.firstOrNull()) {
                R.id.chipOvertime -> RecordFilter.OVERTIME
                R.id.chipExtra -> RecordFilter.EXTRA
                R.id.chipLeave -> RecordFilter.LEAVE
                R.id.chipNight -> RecordFilter.NIGHT
                else -> RecordFilter.ALL
            }
            adapter.clearExpandedState()
            applyRecordFilter()
        }

        binding.chipSort.setOnClickListener {
            sortByAmount = !sortByAmount
            binding.chipSort.text = if (sortByAmount) "按金额" else "按日期"
            adapter.clearExpandedState()
            applyRecordFilter()
        }
    }

    /** 按当前筛选/排序条件刷新列表 */
    private fun applyRecordFilter() {
        val filtered = monthRecords.filter { record ->
            when (recordFilter) {
                RecordFilter.ALL -> true
                RecordFilter.OVERTIME -> !record.isLeave && record.overtimeHours > 0
                RecordFilter.EXTRA -> !record.isLeave && record.extraHours > 0
                RecordFilter.LEAVE -> record.isLeave && record.leaveHours > 0
                RecordFilter.NIGHT -> record.shiftType == ShiftType.NIGHT.name
            }
        }
        val sorted = if (sortByAmount) {
            filtered.sortedByDescending {
                OvertimeCalculator.calculateOvertime(it, currentSettings).estimatedPay
            }
        } else {
            filtered.sortedByDescending { it.date }
        }

        // 月均基准取当月全部记录（不受筛选影响），排序按金额时不插入周分组头
        val (averagePay, sampleCount) = payStats(monthRecords)
        adapter.updateMonthAverage(averagePay, sampleCount)
        adapter.submitList(buildRecordListItems(sorted))
        updateEmptyState(sorted)
    }

    /** 当月非请假记录的平均加班费与参与平均的条数（条数不足 2 时明细不显示对比） */
    private fun payStats(records: List<OvertimeRecord>): Pair<Double, Int> {
        val pays = records.filter { !it.isLeave }
            .map { OvertimeCalculator.calculateOvertime(it, currentSettings).estimatedPay }
            .filter { it > 0 }
        return (if (pays.isEmpty()) 0.0 else pays.average()) to pays.size
    }

    /**
     * 按日期排序时插入周分组头（周一为一周起点），按金额排序时保持扁平列表，
     * 否则分组顺序与金额顺序冲突。
     */
    private fun buildRecordListItems(records: List<OvertimeRecord>): List<RecordListItem> {
        if (sortByAmount) return records.map { RecordListItem.Row(it) }

        val items = mutableListOf<RecordListItem>()
        var lastWeekKey: String? = null
        records.forEach { record ->
            val week = weekHeaderOf(record.date)
            if (week != null && week.first != lastWeekKey) {
                items.add(RecordListItem.WeekHeader(week.first, week.second))
                lastWeekKey = week.first
            }
            items.add(RecordListItem.Row(record))
        }
        return items
    }

    /** 计算日期所属周：返回（周一日期键，显示文案"第 N 周 · 10.06 – 10.12"） */
    private fun weekHeaderOf(date: String): Pair<String, String>? {
        val parsed = DateUtils.parseDate(date) ?: return null
        val parts = DateUtils.extractDateParts(date) ?: return null
        val (year, monthZero, day) = parts

        val weekStart = Calendar.getInstance().apply {
            time = parsed
            // 周一=0 … 周日=6（Calendar 中周日为 1，故先偏移）
            add(Calendar.DAY_OF_YEAR, -((get(Calendar.DAY_OF_WEEK) + 5) % 7))
        }
        val weekEnd = (weekStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 6) }

        val monthDayFormat = SimpleDateFormat("MM.dd", Locale.getDefault())
        val key = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(weekStart.time)

        // 周序号：以记录所在月的 1 号为基准，按周一对齐计算
        val firstOfMonth = Calendar.getInstance().apply { set(year, monthZero, 1) }
        val firstOffset = (firstOfMonth.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val weekNo = (day + firstOffset - 1) / 7 + 1

        val label = "第 $weekNo 周 · ${monthDayFormat.format(weekStart.time)} – ${monthDayFormat.format(weekEnd.time)}"
        return key to label
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

        binding.btnTeamSummary.setOnClickListener {
            if (settingsManager.getTeamAuth()?.isAdmin == true) {
                startActivity(Intent(this, TeamSummaryActivity::class.java))
            }
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
        // 用顶部半透明调试面板替代模态进度框：不阻塞界面，实时展示同步各阶段
        syncDebugLines.clear()
        appendSyncDebugLine("开始同步…")

        lifecycleScope.launch {
            val report = syncManager.performSync(direction, options)

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
            
            // 结果完整显示在顶部调试面板（不再弹结果框），2 秒后自动隐藏，点击面板可立即隐藏
            showSyncResult(message)

            // 旧加密数据首次迁移为信封格式时，新恢复码已生成，必须醒目提醒用户离线保存
            if (report.recoveryCodeGenerated) {
                AlertDialog.Builder(this@MainActivity)
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

    /**
     * 收集同步进度播报：同步期间实时追加到顶部半透明调试面板，
     * 收到 null（同步结束）时延时自动隐藏。手动/自动同步共用同一播报源。
     */
    private fun observeSyncProgress() {
        // 点击调试面板可手动隐藏
        binding.syncDebugPanel.setOnClickListener { hideSyncDebugNow() }
        lifecycleScope.launch {
            SyncManager.progressFlow.collect { msg ->
                if (msg != null) {
                    appendSyncDebugLine(msg)
                } else if (binding.syncDebugPanel.visibility == View.VISIBLE) {
                    scheduleSyncDebugHide()
                }
            }
        }
    }

    /** 顶部调试面板追加一行进度并显示进度条，最多保留最近 4 行（与布局 maxLines 一致） */
    private fun appendSyncDebugLine(msg: String) {
        syncDebugHideJob?.cancel()
        syncDebugLines.add(msg)
        while (syncDebugLines.size > 4) syncDebugLines.removeAt(0)
        binding.tvSyncDebug.text = syncDebugLines.joinToString("\n")
        binding.syncDebugProgress.visibility = View.VISIBLE
        binding.syncDebugPanel.visibility = View.VISIBLE
    }

    /** 同步完成：停掉进度条，仅保留最终结果消息，2 秒后自动隐藏 */
    private fun showSyncResult(msg: String) {
        syncDebugHideJob?.cancel()
        syncDebugLines.clear()
        syncDebugLines.add(msg)
        binding.tvSyncDebug.text = msg
        binding.syncDebugProgress.visibility = View.GONE
        binding.syncDebugPanel.visibility = View.VISIBLE
        scheduleSyncDebugHide()
    }

    /** 同步结束后延时 2 秒隐藏调试面板 */
    private fun scheduleSyncDebugHide() {
        syncDebugHideJob?.cancel()
        syncDebugHideJob = lifecycleScope.launch {
            delay(2000)
            hideSyncDebugNow()
        }
    }

    /** 立即隐藏调试面板（手动点击或延时到期） */
    private fun hideSyncDebugNow() {
        syncDebugHideJob?.cancel()
        binding.syncDebugPanel.visibility = View.GONE
        syncDebugLines.clear()
    }

    private fun observeViewModel() {
        viewModel.allAttendance.observe(this) { attendanceList ->
            // 只过滤一次，供记录列表/空状态/本月统计复用，避免对每条记录重复解析日期
            val records = getCurrentMonthRecords(attendanceList)
            monthRecords = records
            updateCalendarData()
            updateMonthlyStats(records)
            applyRecordFilter()

            // 数据变化后刷新小部件与常驻通知
            QuickRecordWidget.refresh(this)
            QuickEntryNotification.sync(this)

            // 小部件/通知的快捷登记：数据就绪后再弹出当日登记框
            if (pendingQuickAdd) {
                pendingQuickAdd = false
                openQuickAddDialog()
            }
        }
        
        viewModel.errorMessage.observe(this) { errorMsg ->
            if (!errorMsg.isNullOrEmpty()) {
                Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }
    }
    
    private fun updateEmptyState(visibleRecords: List<OvertimeRecord>) {
        if (visibleRecords.isEmpty()) {
            // 当月有记录但被筛选条件全部过滤掉时，提示"无匹配"而不是"暂无考勤记录"
            val filteredOut = monthRecords.isNotEmpty()
            binding.tvEmptyTitle.text =
                if (filteredOut) "当前筛选无记录" else getString(R.string.no_attendance)
            binding.tvEmptyHint.text =
                if (filteredOut) "试试切换到「全部」" else getString(R.string.no_attendance_hint)
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
        var dayShiftHours = 0.0
        var nightShiftHours = 0.0

        monthRecords.forEach { attendance ->
            val result = OvertimeCalculator.calculateOvertime(attendance, currentSettings)
            val hours = result.overtimeHours + result.extraHours
            totalHours += hours
            totalOvertimePay += result.estimatedPay
            // 按班次拆分时长（工资计算口径不变，仅展示用）
            if (attendance.shiftType == ShiftType.NIGHT.name) {
                nightShiftHours += hours
            } else {
                dayShiftHours += hours
            }
        }

        val performanceBonus = currentSettings.baseSalary * (currentSettings.performancePercent / 100.0)
        val totalPay = currentSettings.baseSalary + performanceBonus + totalOvertimePay

        binding.tvTotalOvertime.text = Formatter.formatHours(totalHours)
        binding.tvTotalPay.text = Formatter.formatMoney(totalPay)

        // 仅当月存在夜班记录时展示班次拆分，避免默认白班场景的噪音
        if (nightShiftHours > 0) {
            binding.tvShiftBreakdown.text =
                "白班 ${Formatter.formatHoursShort(dayShiftHours)} · 夜班 ${Formatter.formatHoursShort(nightShiftHours)}"
            binding.tvShiftBreakdown.visibility = View.VISIBLE
        } else {
            binding.tvShiftBreakdown.visibility = View.GONE
        }
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
     * @param records 已按当前所选月份过滤好的记录
     */
    private fun refreshMonthRecords(records: List<OvertimeRecord>) {
        monthRecords = records
        adapter.clearExpandedState()
        applyRecordFilter()
    }

    // ========== 试用/激活 温和提醒 ==========

    /**
     * 温和提醒（不阻断核心功能）：
     *   - 已过期 → 每次启动都提醒，"继续使用"+"输入激活码"
     *   - 剩余 ≤3 天 → 每天只提醒一次（避免烦扰）
     *   - NO_LICENSE / 剩余充足 → 不弹窗
     */
    private fun checkTrialReminder() {
        val manager = TrialLicenseManager(this)
        val state = manager.checkLocalStatus()
        val daysLeft = manager.remainingDays()

        when {
            // 任何形式的授权已过期 → 每次启动都提醒
            (state is LocalLicenseState.Active && daysLeft != null && daysLeft <= 0) ||
                (state is LocalLicenseState.FreeTrial && daysLeft != null && daysLeft <= 0) -> {
                AlertDialog.Builder(this)
                    .setTitle("试用已结束")
                    .setMessage("您的试用期已结束。输入激活码可以继续使用，未激活期间核心功能不受影响。")
                    .setPositiveButton("输入激活码") { _, _ ->
                        startActivity(Intent(this, SettingsActivity::class.java))
                    }
                    .setNegativeButton("继续使用", null)
                    .setCancelable(true)
                    .show()
            }
            // 有激活（任何来源）且剩余 ≤3 天 → 每天只提醒一次
            (state is LocalLicenseState.Active && daysLeft != null && daysLeft in 1..3) ||
                (state is LocalLicenseState.FreeTrial && daysLeft != null && daysLeft in 1..3) -> {
                if (shouldRemindToday()) {
                    AlertDialog.Builder(this)
                        .setTitle("试用即将结束")
                        .setMessage("您的试用还剩 $daysLeft 天，请在过期前激活以获得完整功能体验。")
                        .setPositiveButton("输入激活码") { _, _ ->
                            startActivity(Intent(this, SettingsActivity::class.java))
                        }
                        .setNegativeButton("稍后再说", null)
                        .setCancelable(true)
                        .show()
                }
            }
        }
    }

    /** 当天是否还没提醒过（用日历日期对比，0 点后自动重置） */
    private fun shouldRemindToday(): Boolean {
        val prefs = getSharedPreferences(TRIAL_PREFS, MODE_PRIVATE)
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val lastRemind = prefs.getLong(KEY_LAST_REMIND_DAY, 0L)
        if (lastRemind >= today) return false
        prefs.edit().putLong(KEY_LAST_REMIND_DAY, today).apply()
        return true
    }
}
