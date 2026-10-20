package com.example.personalovertimerecord.utils

import android.content.Context
import com.example.personalovertimerecord.data.OvertimeSettings
import com.example.personalovertimerecord.utils.CloudBackupResolver.CloudResolve
import com.example.personalovertimerecord.utils.CloudBackupResolver.CloudSnapshot
import com.example.personalovertimerecord.data.SettingsManager
import com.example.personalovertimerecord.data.db.AppDatabase
import com.example.personalovertimerecord.data.db.AttendanceDao
import com.example.personalovertimerecord.data.db.AttendanceEntity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 同步管理器
 * 负责 WebDAV 数据同步的流程编排：全局互斥锁、按方向分派上传/下载/双向合并。
 * 云端备份格式解析与快照缓存见 [CloudBackupResolver]；同步加密密钥材料管理见 [SyncKeyManager]。
 */
class SyncManager(
    private val context: Context,
    private val settingsManager: SettingsManager,
    database: AppDatabase
) {

    companion object {
        private const val SYNC_DATA_VERSION = 3

        /**
         * 全局同步互斥锁：手动同步与自动同步并发触发时串行执行，避免相互覆盖
         */
        private val syncMutex = Mutex()
    }

    private val webDAVManager = WebDAVManager(context)
    private val attendanceDao: AttendanceDao = database.attendanceDao()
    private val dataExporter = DataExporter(context, database)
    private val gson = Gson()
    private val cloudResolver = CloudBackupResolver(webDAVManager, settingsManager)
    private val keyManager = SyncKeyManager(context, settingsManager, webDAVManager, cloudResolver)

    /**
     * 获取加密密码（如果启用了加密）
     */
    private fun getEncryptPassword(settings: OvertimeSettings): String? {
        return if (settings.syncEncryptionEnabled && settings.syncPassword.isNotBlank()) {
            settings.syncPassword
        } else null
    }


    // ================== 密钥材料与恢复码（实现见 [SyncKeyManager]） ==================

    /**
     * 遗忘同步密码时的恢复码找回：校验恢复码能解开云端信封后重置同步密码。
     * 不获取全局同步互斥锁（只下载校验与写本地材料，不上传）；
     * 成功后紧接着的 performSync 会复用同一实例内 [cloudResolver] 暂存的已验证云端快照。
     *
     * @return [SyncResult.SUCCESS] 表示新密码已生效；其余为失败原因
     */
    suspend fun validateAndApplyRecovery(recoveryCode: String, newPassword: String): SyncResult =
        keyManager.validateAndApplyRecovery(recoveryCode, newPassword)

    /**
     * 开启同步加密且本机无密钥材料时的云端探测与接入（重装/换机密钥谱系保护）。
     */
    suspend fun bootstrapKeyMaterialOnEnable(password: String): KeyBootstrapResult =
        keyManager.bootstrapKeyMaterialOnEnable(password)

    /**
     * 执行同步操作，返回同步报告（含结果与统计细节）。
     */
    suspend fun performSync(
        direction: SyncDirection = SyncDirection.BIDIRECTIONAL,
        options: SyncOptions = SyncOptions()
    ): SyncReport = withContext(Dispatchers.IO) {
        // SyncGate 为团队同步/云同步共享外层闸门（锁顺序：SyncGate 外层、syncMutex 内层）
        SyncGate.lock {
            syncMutex.withLock {
                // 检查配置
                val config = settingsManager.getWebDAVConfig()
                    ?: return@withLock SyncReport(SyncResult.NO_CONFIG, direction)

                // 检查网络
                if (!NetworkUtils.isNetworkAvailable(context)) {
                    return@withLock SyncReport(SyncResult.NO_NETWORK, direction)
                }

                // 测试连接
                val connectionOk = webDAVManager.testConnection(config)
                if (!connectionOk) {
                    return@withLock SyncReport(SyncResult.CONNECTION_FAILED, direction)
                }

                // 根据同步方向执行相应操作
                val report = when (direction) {
                    SyncDirection.UPLOAD_ONLY -> uploadBackup(config, options)
                    SyncDirection.DOWNLOAD_ONLY -> downloadAndRestore(config, options)
                    SyncDirection.BIDIRECTIONAL -> performBidirectionalSync(config, options)
                }
                // 补齐方向字段（子方法构造时未必带方向）
                report.copy(direction = direction)
            }
        }
    }

    /**
     * 上传备份到 WebDAV
     * 安全约束：仅当确认云端没有数据（404）或能成功解析云端数据时才上传，
     * 下载失败/解密失败一律中止上传，避免本地数据覆盖式冲掉云端备份。
     */
    private suspend fun uploadBackup(config: WebDAVConfig, options: SyncOptions): SyncReport {
        return try {
            // 获取加密设置
            val settings = settingsManager.getSettings()
            val encryptPassword = getEncryptPassword(settings)

            // 同步前本地记录数（含软删除墓碑），用于同步报告
            val localCount = attendanceDao.getAllRecordsIncludingDeletedSync().size

            // 读取云端现状（统一解析 明文/信封/旧版密文）
            val cloudResolve = cloudResolver.resolveCloud(config, encryptPassword)

            var recordsToUpload: List<AttendanceEntityBackup>
            var cloudRecords: List<AttendanceEntityBackup> = emptyList()
            var cloudSettings: OvertimeSettings? = null
            var cloudSnapshot: CloudSnapshot? = null

            // 如果云端有数据，进行增量对比
            when (cloudResolve) {
                is CloudResolve.Available -> {
                    cloudSnapshot = cloudResolve.snapshot
                    // 云端 JSON 无法解析为备份数据（损坏/格式不符/被外部改写）时中止，
                    // 防止“解析失败 → 当云端为空 → 全量上传”把本地数据整包冲掉云端备份
                    val cloudBackup = try {
                        val type = object : TypeToken<BackupData>() {}.type
                        (gson.fromJson(cloudResolve.snapshot.json, type) as? BackupData)?.sanitized()
                    } catch (e: Exception) {
                        AppLogger.e("解析云端数据失败，已中止上传以防覆盖云端备份", e)
                        null
                    }
                    if (cloudBackup == null) {
                        AppLogger.e("云端数据格式无法识别，已中止上传以防覆盖云端备份")
                        return SyncReport(SyncResult.RESTORE_FAILED, localRecordCount = localCount)
                    }
                    cloudRecords = cloudBackup.attendanceRecords
                    cloudSettings = cloudBackup.settings
                }
                CloudResolve.NotFound -> {
                    // 云端确认无文件（404）：允许全量上传
                }
                CloudResolve.DownloadFailed -> {
                    AppLogger.e("上传前无法读取云端数据（HTTP ${WebDAVManager.lastResponseCode}），已中止上传")
                    return SyncReport(SyncResult.DOWNLOAD_FAILED, localRecordCount = localCount)
                }
                CloudResolve.EncryptionMismatch -> {
                    AppLogger.e("云端数据已加密但本机密码无法解密（或已被其它密码加密），已中止上传以避免覆盖")
                    return SyncReport(SyncResult.ENCRYPTION_MISMATCH, localRecordCount = localCount)
                }
            }

            // 根据策略获取需要上传的记录
            if (cloudRecords.isNotEmpty() && options.mode == SyncMode.INCREMENTAL_MERGE) {
                // 增量模式：只上传有变化的记录（含删除标记记录）
                val changedRecords = dataExporter.getRecordsToUpload(cloudRecords, options.conflictStrategy)

                if (changedRecords.isEmpty()) {
                    AppLogger.d("没有需要上传的记录")
                    settingsManager.saveLastSyncTime(System.currentTimeMillis())
                    return SyncReport(
                        SyncResult.NO_CHANGES,
                        localRecordCount = localCount,
                        cloudRecordCount = cloudRecords.size
                    )
                }

                // 关键修复：增量记录必须与云端现有记录合并成完整集合再上传，
                // 否则云端文件会被"仅变更记录"替换，导致跨设备/重装后其余数据丢失
                val mergedByDate = cloudRecords.associateBy { it.date }.toMutableMap()
                changedRecords.forEach { mergedByDate[it.date] = it }
                recordsToUpload = mergedByDate.values.toList()
            } else {
                // 全量模式：上传所有本地记录（含软删除记录，保证删除操作不丢失）
                val localRecords = attendanceDao.getAllRecordsIncludingDeletedSync()
                recordsToUpload = localRecords.map { it.toBackup() }
            }

            // syncSettings=false 时保留云端已有设置，避免本地设置覆盖云端
            val syncSettings = if (options.syncSettings) {
                settings
            } else {
                cloudSettings ?: settings
            }

            // 上传时剥离密码字段（统一口径见 [buildBackupData]）：避免同步加密未开启时，
            // 导出/同步加密密码以明文形式暴露在 WebDAV 服务器上
            val backupData = buildBackupData(syncSettings, recordsToUpload)

            val content = gson.toJson(backupData)
            // 启用加密时包装为信封（密码包裹 DEK + 恢复码包裹 DEK）；未启用则明文上传
            val (wire, recoveryGenerated) = if (encryptPassword != null) {
                keyManager.buildUploadWire(content, encryptPassword, cloudSnapshot)
            } else {
                content to false
            }
            val success = webDAVManager.uploadRawFile(config, wire)

            if (success) {
                settingsManager.saveLastSyncTime(System.currentTimeMillis())
                AppLogger.d("上传成功，共 ${recordsToUpload.size} 条记录" + if (encryptPassword != null) " (信封加密)" else " (未加密)")
                SyncReport(
                    SyncResult.SUCCESS,
                    localRecordCount = localCount,
                    cloudRecordCount = cloudRecords.size,
                    uploadedCount = recordsToUpload.size,
                    recoveryCodeGenerated = recoveryGenerated
                )
            } else {
                SyncReport(
                    SyncResult.UPLOAD_FAILED,
                    localRecordCount = localCount,
                    cloudRecordCount = cloudRecords.size
                )
            }
        } catch (e: Exception) {
            AppLogger.e("上传备份失败", e)
            SyncReport(SyncResult.UPLOAD_FAILED)
        }
    }

    /**
     * 从 WebDAV 下载并恢复数据
     * 统一兼容：信封加密、旧版密码直加密、历史未加密三种云端格式
     */
    private suspend fun downloadAndRestore(config: WebDAVConfig, options: SyncOptions): SyncReport {
        return try {
            // 获取加密设置
            val settings = settingsManager.getSettings()
            val decryptPassword = getEncryptPassword(settings)

            // 同步前本地记录数（含软删除墓碑），用于同步报告
            val localCount = attendanceDao.getAllRecordsIncludingDeletedSync().size

            // 统一解析云端内容（明文 JSON / 信封 / 旧版密文）
            val content = when (val resolve = cloudResolver.resolveCloud(config, decryptPassword)) {
                is CloudResolve.Available -> resolve.snapshot.json
                CloudResolve.NotFound, CloudResolve.DownloadFailed ->
                    return SyncReport(SyncResult.DOWNLOAD_FAILED, localRecordCount = localCount)
                CloudResolve.EncryptionMismatch -> {
                    AppLogger.e("云端数据已加密但解密失败：请检查同步加密密码，或使用恢复码找回")
                    return SyncReport(SyncResult.ENCRYPTION_MISMATCH, localRecordCount = localCount)
                }
            }

            val type = object : TypeToken<BackupData>() {}.type
            val backupData: BackupData = (gson.fromJson(content, type) as? BackupData)
                ?.sanitized() ?: return SyncReport(SyncResult.RESTORE_FAILED, localRecordCount = localCount)

            // v3 完整性校验：云端备份携带 checksum 时比对，失败说明文件损坏/被篡改，拒绝恢复
            if (!backupData.verifyChecksum(gson)) {
                AppLogger.e("云端备份校验失败（checksum 不匹配），文件可能已损坏或被篡改，已中止恢复")
                return SyncReport(SyncResult.RESTORE_FAILED, localRecordCount = localCount)
            }

            val cloudCount = backupData.attendanceRecords.size

            when (options.mode) {
                SyncMode.FULL_REPLACE, SyncMode.CLOUD_PRIORITY -> {
                    // 全量替换模式
                    dataExporter.restoreDataFull(backupData)
                }
                SyncMode.INCREMENTAL_MERGE, SyncMode.LOCAL_PRIORITY -> {
                    // 增量合并模式（含删除同步）
                    dataExporter.restoreDataIncremental(backupData, options.conflictStrategy)
                }
            }

            if (options.syncSettings) {
                // 云端设置不含密码字段（上传时已剥离）；恢复时保留本地已配置的密码，
                // 避免云端空密码覆盖本机的导出/同步加密密码
                val localSettings = settingsManager.getSettings()
                settingsManager.saveSettings(
                    backupData.settings.copy(
                        exportPassword = localSettings.exportPassword,
                        syncPassword = localSettings.syncPassword
                    )
                )
            }

            settingsManager.saveLastSyncTime(System.currentTimeMillis())
            AppLogger.d("下载恢复成功，共 $cloudCount 条记录" + if (decryptPassword != null && content != null) " (已解密)" else " (未加密)")
            SyncReport(
                SyncResult.SUCCESS,
                localRecordCount = localCount,
                cloudRecordCount = cloudCount,
                downloadedCount = cloudCount
            )
        } catch (e: Exception) {
            AppLogger.e("下载并恢复失败", e)
            SyncReport(SyncResult.RESTORE_FAILED)
        }
    }

    /**
     * 执行双向同步
     * 策略：先获取云端数据，然后进行智能合并
     */
    private suspend fun performBidirectionalSync(config: WebDAVConfig, options: SyncOptions): SyncReport {
        // 获取加密设置
        val settings = settingsManager.getSettings()
        val encryptPassword = getEncryptPassword(settings)

        val lastSyncTime = settingsManager.getLastSyncTime()
        val remoteModifiedTime = webDAVManager.getFileModifiedTime(config)

        AppLogger.d("双向同步开始 - 本地最后同步时间: $lastSyncTime, 云端修改时间: $remoteModifiedTime")

        // 获取云端数据（统一解析 明文/信封/旧版密文）
        val cloudResolve = cloudResolver.resolveCloud(config, encryptPassword)

        if (cloudResolve is CloudResolve.EncryptionMismatch) {
            AppLogger.e("云端数据已加密但本机无法解密：请检查同步密码，或使用恢复码找回")
            return SyncReport(SyncResult.ENCRYPTION_MISMATCH)
        }
        if (cloudResolve is CloudResolve.DownloadFailed) {
            return SyncReport(SyncResult.DOWNLOAD_FAILED)
        }

        return when {
            // 情况1：云端没有数据，直接上传本地数据
            cloudResolve is CloudResolve.NotFound -> {
                AppLogger.d("云端无数据，执行上传")
                uploadBackup(config, options)
            }

            // 情况2：本地从未同步过，下载云端数据
            lastSyncTime == 0L -> {
                AppLogger.d("本地从未同步过，执行下载")
                downloadAndRestore(config, options)
            }

            // 情况3：云端在本地上次同步之后没有更新，直接上传
            remoteModifiedTime != null && remoteModifiedTime <= lastSyncTime -> {
                AppLogger.d("云端没有更新，执行上传")
                uploadBackup(config, options)
            }

            // 情况4：云端在本地上次同步之后有更新，需要合并
            else -> {
                AppLogger.d("云端有更新，执行智能合并")
                performSmartMerge(config, options)
            }
        }
    }

    /**
     * 执行智能合并
     * 1. 下载云端数据
     * 2. 与本地数据按策略合并（含删除标记处理）
     * 3. 上传合并结果，并把合并结果写回本地数据库
     */
    private suspend fun performSmartMerge(config: WebDAVConfig, options: SyncOptions): SyncReport {
        return try {
            // 获取加密设置
            val settings = settingsManager.getSettings()
            val encryptPassword = getEncryptPassword(settings)

            // 同步前本地记录数（含软删除墓碑），用于同步报告
            val localCount = attendanceDao.getAllRecordsIncludingDeletedSync().size

            // 下载云端数据（统一解析 明文/信封/旧版密文）
            val cloudResolve = cloudResolver.resolveCloud(config, encryptPassword)
            val cloudSnapshot: CloudSnapshot = when (cloudResolve) {
                is CloudResolve.Available -> cloudResolve.snapshot
                CloudResolve.NotFound, CloudResolve.DownloadFailed ->
                    return SyncReport(SyncResult.DOWNLOAD_FAILED, localRecordCount = localCount)
                CloudResolve.EncryptionMismatch -> {
                    AppLogger.e("云端数据已加密但解密失败：请检查同步加密密码，或使用恢复码找回")
                    return SyncReport(SyncResult.ENCRYPTION_MISMATCH, localRecordCount = localCount)
                }
            }

            val type = object : TypeToken<BackupData>() {}.type
            val cloudBackup: BackupData = (gson.fromJson(cloudSnapshot.json, type) as? BackupData)
                ?.sanitized() ?: return SyncReport(SyncResult.RESTORE_FAILED, localRecordCount = localCount)

            // v3 完整性校验：云端备份携带 checksum 时比对，失败说明文件损坏/被篡改，拒绝合并
            if (!cloudBackup.verifyChecksum(gson)) {
                AppLogger.e("云端备份校验失败（checksum 不匹配），文件可能已损坏或被篡改，已中止合并")
                return SyncReport(SyncResult.RESTORE_FAILED, localRecordCount = localCount)
            }

            val cloudCount = cloudBackup.attendanceRecords.size

            // 获取本地完整状态（活记录 + 软删除记录，保证删除操作参与合并）。
            // 统一先转成备份记录，再交给纯算法 mergeAttendanceBackups 做合并，
            // 时间戳兜底口径与 toBackup() 一致（modifiedAt 缺失用 createdAt）
            val localRecords = attendanceDao.getAllRecordsIncludingDeletedSync()
            val localBackups = localRecords.map { it.toBackup() }
            val mergedRecords = mergeAttendanceBackups(
                local = localBackups,
                cloud = cloudBackup.attendanceRecords,
                strategy = options.conflictStrategy
            )

            // 合并结果上传：settings 按策略决定来源，
            // 避免本地设置的修改在双向同步中永远无法回传云端
            val mergedSettings = if (options.syncSettings) settings else cloudBackup.settings

            // 上传合并结果。settings 中的密码字段（@Transient）本就不参与序列化，
            // 这里再显式剥离一层，与 uploadBackup 的“双保险”口径保持一致
            val mergedBackup = buildBackupData(mergedSettings, mergedRecords)

            val content = gson.toJson(mergedBackup)
            // 启用加密时包装为信封；恢复槽沿用云端信封中的槽，恢复码持续有效
            val (wire, recoveryGenerated) = if (encryptPassword != null) {
                keyManager.buildUploadWire(content, encryptPassword, cloudSnapshot)
            } else {
                content to false
            }
            val success = webDAVManager.uploadRawFile(config, wire)

            if (success) {
                // 把合并结果写回本地数据库，确保本地与云端一致
                dataExporter.restoreDataIncremental(mergedBackup, ConflictStrategy.NEWER_WINS)

                // 双向合并后同步设置（与 uploadBackup/downloadAndRestore 行为一致）
                if (options.syncSettings) {
                    settingsManager.saveSettings(mergedSettings)
                }

                settingsManager.saveLastSyncTime(System.currentTimeMillis())
                AppLogger.d("智能合并成功，共 ${mergedRecords.size} 条记录" + if (encryptPassword != null) " (信封加密)" else " (未加密)")
                SyncReport(
                    SyncResult.SUCCESS,
                    localRecordCount = localCount,
                    cloudRecordCount = cloudCount,
                    mergedCount = mergedRecords.size,
                    uploadedCount = mergedRecords.size,
                    recoveryCodeGenerated = recoveryGenerated
                )
            } else {
                SyncReport(
                    SyncResult.UPLOAD_FAILED,
                    localRecordCount = localCount,
                    cloudRecordCount = cloudCount
                )
            }
        } catch (e: Exception) {
            AppLogger.e("智能合并失败", e)
            SyncReport(SyncResult.UPLOAD_FAILED)
        }
    }

    /**
     * 构建上传/合并用的备份数据（统一口径）：
     * 剥离密码字段避免明文暴露在服务器上；记录 checksum 供恢复端完整性校验；
     * 附带当前版本号与设备名。
     */
    private fun buildBackupData(
        settings: OvertimeSettings,
        records: List<AttendanceEntityBackup>
    ): BackupData {
        return BackupData(
            version = SYNC_DATA_VERSION,
            exportTime = System.currentTimeMillis(),
            settings = settings.copy(exportPassword = "", syncPassword = ""),
            attendanceRecords = records,
            appVersionName = com.example.personalovertimerecord.BuildConfig.VERSION_NAME,
            appVersionCode = com.example.personalovertimerecord.BuildConfig.VERSION_CODE,
            recordCount = records.size,
            deviceName = BackupData.currentDeviceName(),
            checksum = BackupData.computeRecordsChecksum(records, gson),
            lastSyncTime = settingsManager.getLastSyncTime()
        )
    }

    private fun AttendanceEntity.toBackup(): AttendanceEntityBackup {
        return AttendanceEntityBackup(
            date = this.date,
            checkInTime = this.checkInTime,
            checkOutTime = this.checkOutTime,
            checkInTimestamp = this.checkInTimestamp,
            checkOutTimestamp = this.checkOutTimestamp,
            note = this.note,
            manualOvertimeHours = this.manualOvertimeHours,
            manualExtraHours = this.manualExtraHours,
            createdAt = this.createdAt,
            modifiedAt = this.modifiedAt ?: this.createdAt,
            isLeave = this.isLeave,
            leaveType = this.leaveType,
            leaveHours = this.leaveHours,
            isDeleted = this.isDeleted,
            lastModifiedBy = BackupData.currentDeviceName()
        )
    }
}
