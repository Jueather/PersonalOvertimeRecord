# 拆分 SyncManager 实施计划

## Context

`utils/SyncManager.kt`（853 行）同时承担三重职责：云端备份格式解析、信封密钥材料管理、同步流程编排，是全项目最大的类且为数据安全核心路径。目标：按职责拆分为 3 个协作类 + 1 个瘦编排器，**公开 API 与行为完全不变**，为后续维护和测试铺路。

约束：

* 不引入 DI，保持 `SyncManager(context, settingsManager, database)` 构造签名不变（4 个调用方：MainActivity、SettingsActivity、AutoSyncManager、AutoSyncWorker）

* 新文件放 `utils/` 平铺包（项目无分包先例）；同包移动 → 调用方 import 基本不变

* 验证只用 `:app:compileDebugKotlin` / `:app:testDebugUnitTest`，**不用 assembleDebug**（build.gradle.kts L26-31 会对 assemble/bundle/install/package 任务递增版本号）

## 拆分结构

| 文件                                 | 职责                                                                                                                                      | 来源                         |
| ---------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- | -------------------------- |
| `utils/SyncModels.kt`（新建）          | 顶层 `SyncResult` 枚举 + `SyncReport` data class                                                                                            | SyncManager.kt L20-74      |
| `utils/CloudBackupResolver.kt`（新建） | 下载云端文件、识别三种格式（明文 JSON / 信封 PORE\_ENV1 / 旧版 Base64 密文）、密钥谱系接入、恢复码快照缓存                                                                    | L103-117、L130-203、L813-816 |
| `utils/SyncKeyManager.kt`（新建）      | 密钥材料生命周期：DEK 生成/接入、buildUploadWire 信封封装、恢复码找回、开启加密时云端探测                                                                                 | L205-403                   |
| `utils/SyncManager.kt`（瘦身至约 460 行） | 同步编排：performSync（互斥锁+分派）、uploadBackup、downloadAndRestore、performBidirectionalSync、performSmartMerge；删除死 API `testConnection()`（已确认零调用方） | 其余                         |

## 实施步骤（每步后编译验证）

**Step 1 — SyncModels.kt**：剪切顶层 `SyncResult`、`SyncReport`（含 KDoc）到新文件。同包，调用方零改动。

**Step 2 — CloudBackupResolver.kt**：

```kotlin
internal class CloudBackupResolver(
    private val webDAVManager: WebDAVManager,
    private val settingsManager: SettingsManager
) {
    internal class CloudSnapshot(json, dek, recoverySlotB64, pwdSlotB64 = null)
    internal sealed class CloudResolve { NotFound / DownloadFailed / EncryptionMismatch / Available(snapshot) }
    @Volatile private var recoveredCloudCache: Pair<String, Long>? = null
    internal fun stashRecoveredSnapshot(raw: String)          // 新增：SyncKeyManager 写缓存入口
    private fun consumeRecoveredCloudCache(): String?
    internal suspend fun resolveCloud(config, password): CloudResolve
    internal fun isJsonLike(content: String): Boolean
}
```

* `CloudSnapshot`/`CloudResolve` 作为**嵌套 internal 类型**（与 KeyBootstrapResult 嵌套先例一致，避免 utils 顶层再加类型）；SyncManager import 后 when 分支零改动

* `RECOVERED_CACHE_TTL_MS` 随缓存移入 resolver

* SyncManager 增加成员 `private val cloudResolver = CloudBackupResolver(webDAVManager, settingsManager)`（声明顺序在 webDAVManager 之后）

**Step 3 — SyncKeyManager.kt**：

```kotlin
class SyncKeyManager(
    private val context: Context,
    private val settingsManager: SettingsManager,
    private val webDAVManager: WebDAVManager,
    private val cloudResolver: CloudBackupResolver
) {
    sealed class KeyBootstrapResult { Adopted / Generated(recoveryCode) / WrongPassword / NoConfig / Unavailable }
    internal fun buildUploadWire(json, password, cloud: CloudBackupResolver.CloudSnapshot?): Pair<String, Boolean>
    suspend fun validateAndApplyRecovery(recoveryCode, newPassword): SyncResult   // 不取 syncMutex，KDoc 注明
    suspend fun bootstrapKeyMaterialOnEnable(password): KeyBootstrapResult
    private fun generateFreshMaterial(password): KeyBootstrapResult
}
```

* `validateAndApplyRecovery` 写缓存改为 `cloudResolver.stashRecoveredSnapshot(raw)`；`isJsonLike` 经 resolver 调用

* `buildUploadWire` 返回 Pair 保留（仅 2 个调用点，不值得新类型）

* SyncManager 保留**公开委托**（保持实例级缓存语义：SettingsActivity 在同一 syncManager 实例上先 validateAndApplyRecovery 后 performSync，复用 60 秒快照）：

  * `suspend fun validateAndApplyRecovery(...) = keyManager.validateAndApplyRecovery(...)`

  * `suspend fun bootstrapKeyMaterialOnEnable(...) = keyManager.bootstrapKeyMaterialOnEnable(...)`

* SyncManager 组合：`private val keyManager = SyncKeyManager(context, settingsManager, webDAVManager, cloudResolver)`

**Step 4 — SettingsActivity.kt**：L363/369/383/392 的 `SyncManager.KeyBootstrapResult.X` → `SyncKeyManager.KeyBootstrapResult.X`，新增 import。L359、L765 调用行不动。

**Step 5 — SyncManager 瘦身**：

* 删除死 API `testConnection()`（L841-849，零外部调用方，SettingsActivity L554 直接用 WebDAVManager）

* 新增私有 `buildBackupData(settings, records): BackupData` 统一 uploadBackup(L527-540) 与 performSmartMerge(L755-766) 的重复构造（剥离密码 + checksum + BuildConfig 版本号口径不变）

* `syncMutex` 保持 companion 作用域（进程级并发保护，多实例共用）；`SYNC_DATA_VERSION` 留 companion

* 清理不再使用的 import（Base64、Arrays、EnvelopeCrypto、EncryptionUtils 等，以实际为准）

## 验证

1. 每步：`.\gradlew.bat :app:compileDebugKotlin`（不触发版本号自增）
2. 全部完成：`.\gradlew.bat :app:testDebugUnitTest`（9 个既有测试，含加密/合并逻辑）
3. `git diff` 复查：确认公开 API（performSync/validateAndApplyRecovery/bootstrapKeyMaterialOnEnable/构造签名）行为不变、SettingsActivity 仅类型前缀改动

