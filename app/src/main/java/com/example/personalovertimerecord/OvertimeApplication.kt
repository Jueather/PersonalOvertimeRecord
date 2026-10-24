package com.example.personalovertimerecord

import android.app.Application
import androidx.work.WorkManager
import com.example.personalovertimerecord.data.db.AppDatabase
import com.example.personalovertimerecord.repository.AttendanceRepository
import com.example.personalovertimerecord.repository.RoomAttendanceRepository
import com.example.personalovertimerecord.utils.AppLogger
import com.example.personalovertimerecord.utils.GlobalExceptionHandler
import com.example.personalovertimerecord.utils.HolidayStore
import com.example.personalovertimerecord.utils.QuickEntryNotification
import com.example.personalovertimerecord.utils.ThemeManager

/**
 * 应用全局Application类
 * 管理数据库单例、仓储单例和全局异常处理
 */
class OvertimeApplication : Application() {
    
    companion object {
        private const val TAG = "OvertimeApp"
        private lateinit var instance: OvertimeApplication
        
        fun getInstance(): OvertimeApplication = instance
        
        // 提供便捷访问方法
        fun getDatabase(): AppDatabase = instance.database
        fun getAttendanceRepository(): AttendanceRepository = instance.attendanceRepository
    }
    
    // 数据库单例
    val database: AppDatabase by lazy {
        AppDatabase.getDatabase(this)
    }
    
    // 考勤记录仓储单例
    val attendanceRepository: AttendanceRepository by lazy {
        RoomAttendanceRepository(database)
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // 初始化全局异常处理器（必须放在最前面）
        GlobalExceptionHandler.init(this)
        
        // 注册自定义异常回调（记录到文件等）
        GlobalExceptionHandler.registerExceptionCallback { thread, throwable ->
            AppLogger.e("Unhandled exception in thread: ${thread.name}", throwable)
        }
        
        AppLogger.i(TAG, "Application starting...")
        
        // 初始化主题管理器并应用保存的主题
        ThemeManager.init(this)
        ThemeManager.applyTheme()

        // 加载已缓存的在线节假日数据（不联网；失败时继续使用内置数据）
        HolidayStore.loadCached(this)

        // 按设置同步常驻快捷登记通知（关闭开关时自动移除）
        QuickEntryNotification.sync(this)

        // 打卡提醒功能已移除：清理历史版本遗留的 WorkManager 周期提醒任务，防止升级后继续弹通知
        WorkManager.getInstance(this).cancelUniqueWork("work_reminder_work")
        WorkManager.getInstance(this).cancelUniqueWork("off_work_reminder_work")
        
        AppLogger.i(TAG, "Application initialized successfully")
    }
    
    override fun onTerminate() {
        super.onTerminate()
        AppLogger.i(TAG, "Application terminating...")
    }
    
    override fun onLowMemory() {
        super.onLowMemory()
        AppLogger.w(TAG, "Low memory warning!")
        // 可以在这里清理缓存
    }
    
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        AppLogger.d(TAG, "Trim memory level: $level")
        when (level) {
            TRIM_MEMORY_RUNNING_LOW,
            TRIM_MEMORY_RUNNING_CRITICAL -> {
                // 应用正在运行但系统内存不足，清理不必要的资源
                AppLogger.w(TAG, "Memory pressure, cleaning resources")
            }
        }
    }
}
