package com.example.personalovertimerecord.utils

import android.content.Context
import android.util.Base64
import com.example.personalovertimerecord.data.OvertimeSettings
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
 * 同步结果枚举
 */
enum class SyncResult {
    SUCCESS,
    NO_CONFIG,
    NO_NETWORK,
    CONNECTION_FAILED,
    UPLOAD_FAILED,
    DOWNLOAD_FAILED,
    RESTORE_FAILED,
    NO_CHANGES,
    CONFLICT,
    /** 云端数据已加密，但本机同步加密密码错误或未配置 */
    ENCRYPTION_MISMATCH
}

/**
 * 同步报告：在 [SyncResult] 之外携带本次同步的统计细节，便于日志与 UI 展示。
 *
 * 计数字段语义：
 * - localRecordCount：同步前本地记录数（含软删除墓碑）；
 * - cloudRecordCount：云端备份中的记录数；
 * - uploadedCount：本次上传到云端的记录数（全量上传时等于本地记录数）；
 * - downloadedCount：本次从云端下载并恢复的记录数；
 * - mergedCount：双向合并后的总记录数。
 */
data class SyncReport(
    val result: SyncResult,
    val direction: SyncDirection? = null,
    val localRecordCount: Int = 0,
    val cloudRecordCount: Int = 0,
    val uploadedCount: Int = 0,
    val downloadedCount: Int = 0,
    val mergedCount: Int = 0,
    val syncTime: Long = System.currentTimeMillis(),
    /**
     * 本次上传是否新生成了恢复码（旧格式数据首次迁移为信封加密时为 true）。
     * UI 应提醒用户立即到设置页查看并离线保存恢复码；恢复码本身不进日志/报告。
     */
    val recoveryCodeGenerated: Boolean = false
) {
    /** 是否同步成功（含"无变更"） */
    val isSuccess: Boolean get() = result == SyncResult.SUCCESS || result == SyncResult.NO_CHANGES

    /** 人类可读的摘要信息，用于日志/通知 */
    fun toSummaryString(): String {
        val parts = mutableListOf<String>()
        parts.add("结果=$result")
        if (direction != null) parts.add("方向=$direction")
        if (localRecordCount > 0) parts.add("本地=$localRecordCount")
        if (cloudRecordCount > 0) parts.add("云端=$cloudRecordCount")
        if (uploadedCount > 0) parts.add("上传=$uploadedCount")
        if (downloadedCount > 0) parts.add("下载=$downloadedCount")
        if (mergedCount > 0) parts.add("合并=$mergedCount")
        return parts.joinToString("，")
    }
}

/**
 * 同步管理器
 * 负责 WebDAV 数据同步的核心逻辑，支持增量同步、删除同步和多种冲突解决策略
 */
class SyncManager(
    private val context: Context,
    private val settingsManager: SettingsManager,
    database: AppDatabase
) {

    companion object {
        private const val SYNC_DATA_VERSION = 3

        /** 恢复码找回后云端快照缓存的有效期（毫秒），过期即丢弃 */
        private const val RECOVERED_CACHE_TTL_MS = 60_000L

        /**
         * 全局同步互斥锁：手动同步与自动同步并发触发时串行执行，避免相互覆盖
         */
        private val syncMutex = Mutex()
    }

    private val webDAVManager = WebDAVManager(context)
    private val attendanceDao: AttendanceDao = database.attendanceDao()
    private val dataExporter = DataExporter(context, database)
    private val gson = Gson()

    /**
     * 恢复码找回流程刚下载并验证通过的云端原文（[validateAndApplyRecovery] 写入）。
     * 找回成功后设置页会立刻触发一次正常同步拉取数据，复用此快照可省一次 HTTP GET。
     * 单次消费 + 短时过期（[RECOVERED_CACHE_TTL_MS]）：同步未及时发生或失败后，
     * 陈旧快照会被丢弃，绝不参与后续正常同步。
     */
    @Volatile
    private var recoveredCloudCache: Pair<String, Long>? = null

    private fun consumeRecoveredCloudCache(): String? {
        val cached = recoveredCloudCache ?: return null
        recoveredCloudCache = null
        val age = android.os.SystemClock.elapsedRealtime() - cached.second
        return cached.first.takeIf { age in 0..RECOVERED_CACHE_TTL_MS }
    }

    /**
     * 获取加密密码（如果启用了加密）
     */
    private fun getEncryptPassword(settings: OvertimeSettings): String? {
        return if (settings.syncEncryptionEnabled && settings.syncPassword.isNotBlank()) {
            settings.syncPassword
        } else null
    }

    // ================== 信封加密（密码 + 恢复码双钥匙） ==================

    /** 已解密的云端数据快照 */
    private class CloudSnapshot(
        val json: String,
        /** 信封格式解出的 DEK；明文 JSON / 旧版密文为 null */
        val dek: ByteArray?,
        /** 信封中的恢复槽（Base64）；用于新设备接入后继续沿用原恢复码 */
        val recoverySlotB64: String?,
        /** 信封中的密码槽（Base64）；接入时缓存复用可省一次 PBKDF2 派生 */
        val pwdSlotB64: String? = null
    )

    private sealed class CloudResolve {
        object NotFound : CloudResolve()
        object DownloadFailed : CloudResolve()
        object EncryptionMismatch : CloudResolve()
        data class Available(val snapshot: CloudSnapshot) : CloudResolve()
    }

    /**
     * 下载云端文件并按三种格式解析：
     * 明文 JSON（历史未加密）/ 信封（PORE_ENV1，密码或恢复码包裹 DEK）/ 旧版裸 Base64 密文。
     * 信封格式密码正确时，顺带为本机补齐 DEK 与恢复槽（新设备接入引导）。
     */
    private suspend fun resolveCloud(config: WebDAVConfig, password: String?): CloudResolve {
        // 恢复码找回刚下载验证过的快照可直接复用（单次消费，60 秒过期），省一次 HTTP GET
        val raw = consumeRecoveredCloudCache()?.also {
            AppLogger.d("复用恢复码找回时已验证的云端快照，跳过本次下载")
        } ?: webDAVManager.downloadRawFile(config)
        if (raw == null) {
            return if (WebDAVManager.lastResponseCode == 404) {
                CloudResolve.NotFound
            } else {
                CloudResolve.DownloadFailed
            }
        }

        if (isJsonLike(raw)) {
            return CloudResolve.Available(CloudSnapshot(raw, null, null))
        }

        if (EnvelopeCrypto.isEnvelope(raw)) {
            if (password.isNullOrBlank()) return CloudResolve.EncryptionMismatch
            // 一次解析同时得到明文/DEK/双槽，避免对同一密码重复 PBKDF2 派生
            val opened = EnvelopeCrypto.openWithPassword(raw, password)
                ?: return CloudResolve.EncryptionMismatch
            val dek = opened.dek
            // String 构造会按 UTF-8 拷贝字节，明文原数组此后不再使用，立即清零
            val json = String(opened.plaintext, Charsets.UTF_8).also {
                java.util.Arrays.fill(opened.plaintext, 0)
            }
            val dekB64 = Base64.encodeToString(dek, Base64.NO_WRAP)
            val slotB64 = Base64.encodeToString(opened.recSlot.toBytes(), Base64.NO_WRAP)
            val pwdSlotB64 = Base64.encodeToString(opened.pwdSlot.toBytes(), Base64.NO_WRAP)
            // 接入云端密钥谱系：本地无材料（新设备），或本地 DEK 与云端不一致
            // （重装后曾误生成新 DEK）时，一律以云端为准，并清掉失效的本地恢复码明文
            val local = settingsManager.getSyncKeyMaterial()
            if (local == null || local.dek != dekB64) {
                settingsManager.adoptCloudKeyMaterial(dekB64, slotB64, pwdSlotB64, password)
            } else if (local.pwdSlotB64.isBlank() || local.pwdSlotPassword != password) {
                // DEK 相同但本机缺密码槽缓存（旧版本升级 / 改过密码），顺手补上
                settingsManager.savePwdSlot(pwdSlotB64, password)
            }
            return CloudResolve.Available(CloudSnapshot(json, dek, slotB64, pwdSlotB64))
        }

        // 旧版裸 Base64 密文（密码直加密，无 magic 前缀）：用当前密码尝试解密
        if (!password.isNullOrBlank()) {
            val json = runCatching { EncryptionUtils.decryptString(raw, password) }.getOrNull()
            if (json != null && isJsonLike(json)) {
                return CloudResolve.Available(CloudSnapshot(json, null, null))
            }
        }
        return CloudResolve.EncryptionMismatch
    }

    /**
     * 把备份 JSON 包装为上传内容。启用加密时统一输出信封格式：
     * DEK 来源优先级 = 本地密钥材料 → 云端信封解出的 DEK → 新生成（同时产生新恢复码）。
     * 恢复槽始终原样复用，保证更换同步密码/更换设备后恢复码继续有效。
     *
     * @return (线路内容, 是否新生成恢复码)
     */
    private fun buildUploadWire(json: String, password: String, cloud: CloudSnapshot?): Pair<String, Boolean> {
        val localMaterial = settingsManager.getSyncKeyMaterial()
        val dek: ByteArray
        val slotB64: String
        var cachedPwdSlotB64 = ""
        var cachedPwdSlotPassword = ""
        var generated = false

        when {
            localMaterial != null -> {
                dek = Base64.decode(localMaterial.dek, Base64.NO_WRAP)
                slotB64 = localMaterial.recoverySlotB64
                cachedPwdSlotB64 = localMaterial.pwdSlotB64
                cachedPwdSlotPassword = localMaterial.pwdSlotPassword
            }
            cloud?.dek != null && !cloud.recoverySlotB64.isNullOrBlank() -> {
                // 从云端信封接入的 DEK（理论上 resolveCloud 已落盘，这里再兜底一次）
                dek = cloud.dek
                slotB64 = cloud.recoverySlotB64
                settingsManager.adoptCloudKeyMaterial(
                    Base64.encodeToString(dek, Base64.NO_WRAP),
                    slotB64,
                    cloud.pwdSlotB64 ?: "",
                    password
                )
                cachedPwdSlotB64 = cloud.pwdSlotB64 ?: ""
                cachedPwdSlotPassword = password
            }
            else -> {
                // 首次启用加密 / 旧格式数据迁移：生成全新 DEK + 恢复码（同时预包裹密码槽）
                val fresh = settingsManager.generateAndSaveSyncKeyMaterial(password)
                dek = Base64.decode(fresh.dek, Base64.NO_WRAP)
                slotB64 = fresh.recoverySlotB64
                cachedPwdSlotB64 = fresh.pwdSlotB64
                cachedPwdSlotPassword = password
                generated = true
                settingsManager.setRecoveryCodePending(true)
            }
        }

        val recoverySlot = EnvelopeCrypto.WrappedSlot.fromStandaloneBytes(
            Base64.decode(slotB64, Base64.NO_WRAP)
        )
        // 密码未变且已缓存密码槽 → 直接复用（省一次 PBKDF2 派生）；否则重新包裹并更新缓存
        val passwordSlot: EnvelopeCrypto.WrappedSlot =
            if (cachedPwdSlotB64.isNotBlank() && cachedPwdSlotPassword == password) {
                EnvelopeCrypto.WrappedSlot.fromStandaloneBytes(Base64.decode(cachedPwdSlotB64, Base64.NO_WRAP))
            } else {
                val freshSlot = EnvelopeCrypto.wrapDek(dek, password)
                settingsManager.savePwdSlot(
                    Base64.encodeToString(freshSlot.toBytes(), Base64.NO_WRAP),
                    password
                )
                freshSlot
            }
        val wire = EnvelopeCrypto.buildEnvelopeWithSlots(
            dek = dek,
            pwdSlot = passwordSlot,
            existingRecSlot = recoverySlot,
            plaintext = json.toByteArray(Charsets.UTF_8)
        )
        return wire to generated
    }

    /**
     * 遗忘同步密码时的恢复码找回（不获取 [syncMutex]：只下载校验与写本地材料，不上传）。
     * 校验恢复码能解开云端信封后，保存新同步密码与 DEK；调用方随后应执行一次正常同步拉取数据。
     *
     * @return [SyncResult.SUCCESS] 表示新密码已生效；其余为失败原因
     */
    suspend fun validateAndApplyRecovery(recoveryCode: String, newPassword: String): SyncResult =
        withContext(Dispatchers.IO) {
            val config = settingsManager.getWebDAVConfig()
                ?: return@withContext SyncResult.NO_CONFIG
            if (!NetworkUtils.isNetworkAvailable(context)) {
                return@withContext SyncResult.NO_NETWORK
            }

            val raw = webDAVManager.downloadRawFile(config)
                ?: return@withContext SyncResult.DOWNLOAD_FAILED // 云端无备份或不可读，无法凭恢复码找回

            if (!EnvelopeCrypto.isEnvelope(raw)) {
                // 云端仍是旧格式（密码直加密），信封中没有恢复槽，恢复码无法使用
                return@withContext SyncResult.ENCRYPTION_MISMATCH
            }

            val extracted = EnvelopeCrypto.extractDekAndRecoverySlotWithRecoveryCode(raw, recoveryCode)
                ?: return@withContext SyncResult.ENCRYPTION_MISMATCH
            val (dek, slot) = extracted

            // 恢复码验证通过：保存新密码（保留其他设置），落盘 DEK/恢复槽
            val current = settingsManager.getSettings()
            settingsManager.saveSettings(
                current.copy(syncEncryptionEnabled = true, syncPassword = newPassword)
            )
            settingsManager.adoptCloudKeyMaterial(
                Base64.encodeToString(dek, Base64.NO_WRAP),
                Base64.encodeToString(slot.toBytes(), Base64.NO_WRAP)
            )
            settingsManager.setRecoveryCodePending(false)
            // DEK 局部副本已写入加密 prefs，不再使用，立即清零
            java.util.Arrays.fill(dek, 0)
            // 缓存已验证的云端原文，供紧接着的 performSync 复用（单次、60 秒过期）
            recoveredCloudCache = raw to android.os.SystemClock.elapsedRealtime()
            AppLogger.d("恢复码验证成功，已重置同步密码")
            SyncResult.SUCCESS
        }

    /** 开启同步加密时云端探测结果（见 [bootstrapKeyMaterialOnEnable]） */
    sealed class KeyBootstrapResult {
        /** 已接入云端既有信封：原恢复码继续有效，本机不保存其明文 */
        object Adopted : KeyBootstrapResult()
        /** 云端无信封（无备份/明文/旧版密文）：已生成全新 DEK 与恢复码，请引导用户离线保存 */
        data class Generated(val recoveryCode: String) : KeyBootstrapResult()
        /** 云端是加密备份但当前密码无法解开（信封密码错，或旧密文密码错） */
        object WrongPassword : KeyBootstrapResult()
        /** 未配置 WebDAV */
        object NoConfig : KeyBootstrapResult()
        /** 无网络或云端暂时不可读：不生成新材料，推迟到下次同步时自动接入/生成 */
        object Unavailable : KeyBootstrapResult()
    }

    /**
     * 开启同步加密且本机无密钥材料时调用（重装/换机场景的关键保护）：
     * 必须先读云端再决定，不能直接生成新 DEK——否则首次同步会用新 DEK 覆盖云端信封，
     * 导致用户离线保存的原恢复码永久失效。
     *
     * - 云端是信封且密码正确：接入其 DEK 与恢复槽（恢复码明文本机不可见，但继续有效）；
     * - 云端无文件 / 明文 JSON / 旧版密码密文（密码可解）：不存在信封谱系，生成全新材料；
     * - 密码解不开：[KeyBootstrapResult.WrongPassword]，不生成任何材料；
     * - 网络等暂时失败：[KeyBootstrapResult.Unavailable]，交由后续同步流程兜底处理。
     */
    suspend fun bootstrapKeyMaterialOnEnable(password: String): KeyBootstrapResult =
        withContext(Dispatchers.IO) {
            val config = settingsManager.getWebDAVConfig()
                ?: return@withContext KeyBootstrapResult.NoConfig
            if (!NetworkUtils.isNetworkAvailable(context)) {
                return@withContext KeyBootstrapResult.Unavailable
            }

            val raw = try {
                webDAVManager.downloadRawFile(config)
            } catch (e: Exception) {
                AppLogger.e("开启加密时探测云端失败", e)
                return@withContext KeyBootstrapResult.Unavailable
            }

            // 云端无备份：全新启用，生成新材料
            if (raw == null) {
                return@withContext if (WebDAVManager.lastResponseCode == 404) {
                    generateFreshMaterial(password)
                } else {
                    KeyBootstrapResult.Unavailable
                }
            }

            // 明文备份：首次加密，生成新材料（下次同步完成迁移）
            if (isJsonLike(raw)) {
                return@withContext generateFreshMaterial(password)
            }

            // 云端已是信封：只有密码正确才允许接入，绝不能另起 DEK 谱系
            if (EnvelopeCrypto.isEnvelope(raw)) {
                val slots = EnvelopeCrypto.extractDekAndSlotsWithPassword(raw, password)
                    ?: return@withContext KeyBootstrapResult.WrongPassword
                val (dek, pwdSlot, recSlot) = slots
                settingsManager.adoptCloudKeyMaterial(
                    Base64.encodeToString(dek, Base64.NO_WRAP),
                    Base64.encodeToString(recSlot.toBytes(), Base64.NO_WRAP),
                    Base64.encodeToString(pwdSlot.toBytes(), Base64.NO_WRAP),
                    password
                )
                // DEK 已写入加密 prefs，局部副本清零
                java.util.Arrays.fill(dek, 0)
                AppLogger.d("检测到云端加密备份，已接入现有恢复码谱系")
                return@withContext KeyBootstrapResult.Adopted
            }

            // 旧版裸密文：密码能解开才生成新材料（信封迁移）；解不开按密码错误处理
            val legacyJson = runCatching { EncryptionUtils.decryptString(raw, password) }.getOrNull()
            if (legacyJson != null && isJsonLike(legacyJson)) {
                generateFreshMaterial(password)
            } else {
                KeyBootstrapResult.WrongPassword
            }
        }

    private fun generateFreshMaterial(password: String): KeyBootstrapResult {
        val material = settingsManager.generateAndSaveSyncKeyMaterial(password)
        settingsManager.setRecoveryCodePending(true)
        return KeyBootstrapResult.Generated(material.recoveryCode)
    }

    /**
     * 执行同步操作，返回同步报告（含结果与统计细节）。
     */
    suspend fun performSync(
        direction: SyncDirection = SyncDirection.BIDIRECTIONAL,
        options: SyncOptions = SyncOptions()
    ): SyncReport = withContext(Dispatchers.IO) {
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
            val cloudResolve = resolveCloud(config, encryptPassword)

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

            val backupData = BackupData(
                version = SYNC_DATA_VERSION,
                exportTime = System.currentTimeMillis(),
                // 上传时剥离密码字段：避免同步加密未开启时，
                // 导出/同步加密密码以明文形式暴露在 WebDAV 服务器上
                settings = syncSettings.copy(exportPassword = "", syncPassword = ""),
                attendanceRecords = recordsToUpload,
                appVersionName = com.example.personalovertimerecord.BuildConfig.VERSION_NAME,
                appVersionCode = com.example.personalovertimerecord.BuildConfig.VERSION_CODE,
                recordCount = recordsToUpload.size,
                deviceName = BackupData.currentDeviceName(),
                checksum = BackupData.computeRecordsChecksum(recordsToUpload, gson),
                lastSyncTime = settingsManager.getLastSyncTime()
            )

            val content = gson.toJson(backupData)
            // 启用加密时包装为信封（密码包裹 DEK + 恢复码包裹 DEK）；未启用则明文上传
            val (wire, recoveryGenerated) = if (encryptPassword != null) {
                buildUploadWire(content, encryptPassword, cloudSnapshot)
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
            val content = when (val resolve = resolveCloud(config, decryptPassword)) {
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
        val cloudResolve = resolveCloud(config, encryptPassword)

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
            val cloudResolve = resolveCloud(config, encryptPassword)
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
            val mergedBackup = BackupData(
                version = SYNC_DATA_VERSION,
                exportTime = System.currentTimeMillis(),
                settings = mergedSettings.copy(exportPassword = "", syncPassword = ""),
                attendanceRecords = mergedRecords,
                appVersionName = com.example.personalovertimerecord.BuildConfig.VERSION_NAME,
                appVersionCode = com.example.personalovertimerecord.BuildConfig.VERSION_CODE,
                recordCount = mergedRecords.size,
                deviceName = BackupData.currentDeviceName(),
                checksum = BackupData.computeRecordsChecksum(mergedRecords, gson),
                lastSyncTime = settingsManager.getLastSyncTime()
            )

            val content = gson.toJson(mergedBackup)
            // 启用加密时包装为信封；恢复槽沿用云端信封中的槽，恢复码持续有效
            val (wire, recoveryGenerated) = if (encryptPassword != null) {
                buildUploadWire(content, encryptPassword, cloudSnapshot)
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
     * 判断下载内容是否为 JSON 格式
     * 加密备份内容为 Base64 文本（非 JSON），据此可区分"未加密数据"与"密码错误的加密数据"
     */
    private fun isJsonLike(content: String): Boolean {
        val trimmed = content.trimStart()
        return trimmed.startsWith("{") || trimmed.startsWith("[")
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

    /**
     * 测试 WebDAV 连接
     */
    suspend fun testConnection(): Boolean {
        val config = settingsManager.getWebDAVConfig() ?: return false

        if (!NetworkUtils.isNetworkAvailable(context)) {
            return false
        }

        return webDAVManager.testConnection(config)
    }
}
