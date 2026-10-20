# 方案 B：多用户团队版实施计划（Ktor 自建后端）

## Context

将个人加班记录 App 升级为小团队工具：用户账号登录、各自只看/同步自己的记录，管理员查看全员加班汇总。当前 App 是单机单用户（Room v7 单表 `attendance_records`，无账号概念），唯一云通道是 WebDAV 整库备份。本次新增"团队同步"通道，**不改动本地 Room schema、不删除 WebDAV 功能**。

已确认的决策：

| 决策项    | 结论                                                                     |
| ------ | ---------------------------------------------------------------------- |
| 后端技术栈  | Ktor 3.x (Netty) + SQLite（裸 SQL + 手写 DAO，不用 Exposed）                   |
| 部署     | fat jar（shadowJar）+ systemd 部署到已有 Linux 服务器                            |
| 账号开通   | 管理员创建成员账号；服务端首启自动生成 admin（随机密码打印控制台）                                   |
| WebDAV | 保留不动，与团队同步并存                                                           |
| 网络     | 服务器有域名，Nginx/Caddy HTTPS 反代；App 端不改 network\_security\_config（保持全局禁明文） |
| 同步协议   | 全量同步（个人记录量级小），服务端 NEWER\_WINS 合并，规避时钟偏移的增量游标问题                         |

已核实的可复用资产：

* [RecordMerge.kt](../../app/src/main/java/com/example/personalovertimerecord/utils/RecordMerge.kt)：无 Android 依赖的纯合并函数（date 为键、NEWER\_WINS 用 modifiedAt/createdAt 兜底、软删除墓碑）——服务端 MergeService 与客户端 TeamSyncManager 共用同一语义

* [DataExporter.kt](../../app/src/main/java/com/example/personalovertimerecord/utils/DataExporter.kt) L97-119 的 `AttendanceEntityBackup`：同步 wire format 的事实标准

* [SettingsManager.kt](../../app/src/main/java/com/example/personalovertimerecord/data/SettingsManager.kt)：`saveWebDAVConfig/getWebDAVConfig` + 加密 SharedPreferences 模式，AuthConfig 照此仿写

* [AttendanceDao.kt](../../app/src/main/java/com/example/personalovertimerecord/data/db/AttendanceDao.kt)：已有 `getAllRecordsIncludingDeletedSync/getRecordByDate/insertOrUpdate/deleteAll/getCount`，切账号与合并落库所需方法齐全

* [SyncManager.kt](../../app/src/main/java/com/example/personalovertimerecord/utils/SyncManager.kt)：同步编排 + Mutex 模式仿照源（其 `syncMutex` 为 private，需另建共享闸门）

* [OvertimeCalculator.kt](../../app/src/main/java/com/example/personalovertimerecord/utils/OvertimeCalculator.kt)：加班工时计算（服务端无法算工时——班次/倍率设置不上云，由客户端同步时算好随记录上传）

* 无 values-en，strings.xml 仅中文一份

***

## 一、服务端（新建 `server/` 目录，独立 Gradle 工程，不加入 Android 根工程 include）

### 1.1 目录结构

```
server/
├── settings.gradle.kts / build.gradle.kts / gradlew   # Kotlin JVM + Ktor + shadow，JDK 17+
├── systemd/overtime-server.service                    # systemd 部署单元
├── README.md                                          # 部署文档
└── src/main/kotlin/com/example/overtimeserver/
    ├── Application.kt            # EngineMain 入口，装配各模块
    ├── config/AppConfig.kt       # HOCON：port/dbPath/jwtSecretFile/adminUsername
    ├── db/Database.kt            # SQLite：WAL + busy_timeout=5000 + foreign_keys=ON，事务助手
    ├── db/Schema.kt              # 建表（users / records）
    ├── db/UserDao.kt             # count/create/findByUsername/listAll/updatePassword/setActive
    ├── db/RecordDao.kt           # listByUser/upsertBatch（事务内按 (user_id,date) 合并）/聚合查询
    ├── auth/PasswordHasher.kt    # bcrypt（at.favre.lib:bcrypt）
    ├── auth/JwtService.kt        # HMAC256 签发/校验（ktor-server-auth-jwt）
    ├── auth/AuthSetup.kt         # Ktor Authentication 配置 + requireRole("admin")
    ├── sync/MergeService.kt      # NEWER_WINS 合并（RecordMerge.kt 的 JVM 移植，语义一致）
    ├── api/Models.kt             # DTO（camelCase，与客户端 Gson 默认对齐）
    ├── api/AuthRoutes.kt         # login / change-password
    ├── api/SyncRoutes.kt         # POST /api/sync
    ├── api/AdminRoutes.kt        # 用户管理 + summary 聚合
    ├── api/HealthRoutes.kt       # GET /api/health（无认证探活）
    ├── bootstrap/AdminBootstrap.kt  # 首启生成 admin + SecureRandom 密码，横幅打印到控制台
    └── util/MonthKey.kt          # date 字符串 → yyyyMM 月键
```

依赖：`ktor-server-core/netty/content-negotiation/serialization-kotlinx-json/auth/auth-jwt`、`org.xerial:sqlite-jdbc`、`at.favre.lib:bcrypt:0.10.2`、logback。打包：shadowJar → `overtime-server-all.jar`。

### 1.2 SQLite 表结构

```sql
CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT NOT NULL UNIQUE COLLATE NOCASE,
  password_hash TEXT NOT NULL,
  display_name TEXT NOT NULL DEFAULT '',
  role TEXT NOT NULL DEFAULT 'member' CHECK (role IN ('admin','member')),
  created_at INTEGER NOT NULL,
  is_active INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS records (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  date TEXT NOT NULL,                      -- 不透明字符串键，与客户端对齐
  check_in_time TEXT, check_out_time TEXT,
  check_in_timestamp INTEGER, check_out_timestamp INTEGER,
  note TEXT,
  manual_overtime_hours REAL NOT NULL DEFAULT -1.0,
  manual_extra_hours REAL NOT NULL DEFAULT -1.0,
  overtime_hours REAL,                     -- 客户端算好的加班工时，聚合用
  created_at INTEGER NOT NULL, modified_at INTEGER NOT NULL,
  is_leave INTEGER NOT NULL DEFAULT 0,
  leave_type TEXT, leave_hours REAL NOT NULL DEFAULT 0.0,
  custom_rate REAL,
  is_deleted INTEGER NOT NULL DEFAULT 0,   -- 墓碑永久保留
  last_modified_by TEXT NOT NULL DEFAULT '',
  synced_at INTEGER NOT NULL,              -- 服务端接收时间，仅排查用
  UNIQUE (user_id, date)
);
```

字段与 `AttendanceEntityBackup` 一一镜像；(user\_id, date) 唯一键与客户端 RecordMerge 的 date 键语义对齐，同账号多设备天然收敛。

### 1.3 REST API（JWT Bearer，除 login/health）

| 方法路径                                           | 权限    | 说明                                                                                                                                 |
| ---------------------------------------------- | ----- | ---------------------------------------------------------------------------------------------------------------------------------- |
| POST `/api/auth/login`                         | 匿名    | `{username,password}` → `{token, expiresIn, user}`；校验 bcrypt + is\_active                                                          |
| POST `/api/auth/change-password`               | 登录用户  | 自改密码                                                                                                                               |
| POST `/api/sync`                               | 登录用户  | `{records:[RecordDto], deviceName}` → `{serverTime, records:[该用户服务端全量]}`；单事务内按 (user\_id,date) NEWER\_WINS 合并；另按 userId 加 Mutex 兜底 |
| GET `/api/health`                              | 匿名    | 探活                                                                                                                                 |
| GET `/api/admin/users`                         | admin | 成员列表（含 recordCount）                                                                                                                |
| POST `/api/admin/users`                        | admin | 建号；密码缺省服务端生成，**仅此一次返回**                                                                                                            |
| POST `/api/admin/users/{id}/reset-password`    | admin | 重置密码，仅此一次返回                                                                                                                        |
| POST `/api/admin/users/{id}/set-active`        | admin | 停用/启用（禁止登录、保留数据，不物理删除）                                                                                                             |
| GET `/api/admin/summary?from=202610&to=202612` | admin | 按人按月聚合：加班工时/请假时长/记录次数/请假次数。**不含工资**（薪资不上云）                                                                                         |

### 1.4 Bootstrap 与安全要点

* 首启 `users` 表为空时生成 admin（用户名可配，默认 `admin`），密码 SecureRandom 16 位无歧义字符集，横幅打印控制台；支持 `ADMIN_INITIAL_PASSWORD` 环境变量覆盖；幂等（有用户即跳过）

* JWT secret 留空时首启生成并持久化到 `jwt-secret.key`（权限 600），此后读文件——保证重启不踢线

* HTTPS 由 Nginx/Caddy 反代 + Let's Encrypt 承担，App 只配 `https://域名`

### 1.5 部署

* systemd unit：专用低权用户 `overtime`，`-config=/etc/overtime-server/application.conf` 外置配置，WorkingDirectory=/var/lib/overtime-server，Restart=always，加固项（NoNewPrivileges/ProtectSystem）

* README 含：shadowJar 构建 → scp 上服务器 → openjdk-21-jre-headless → 反代配置 → 每日 `sqlite3 .backup` cron → 升级流程（替换 jar 重启，schema 只允许向后兼容变更）

***

## 二、Android 端改造

### 2.1 新增文件

| 文件                                                        | 职责                                                                                                                                                                                                        |
| --------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `data/model/TeamApiModels.kt`                             | DTO：AuthSession、LoginRequest/Response、RecordDto（镜像 AttendanceEntityBackup + overtimeHours）、TeamSyncRequest/Response、AdminUserDto、TeamSummaryDto                                                           |
| `utils/TeamApiClient.kt`                                  | HttpURLConnection + Gson（仿 WebDAVManager 风格：60s 超时、Bearer 头、401 → UNAUTHORIZED 统一映射）                                                                                                                      |
| `utils/TeamSyncManager.kt`                                | 同步编排（仿 SyncManager 的 Mutex 模式）：本地含墓碑全量读取 → OvertimeCalculator 补算 overtimeHours（仅上传不落库）→ POST /api/sync → 响应记录与本地 `mergeAttendanceBackups(local, cloud, NEWER_WINS)` → 差异逐条 `insertOrUpdate` 落库（保留本地自增 id） |
| `utils/SyncGate.kt`                                       | 共享锁：团队同步与 WebDAV 同步互斥（二者写同一 Room 库，SyncManager 的锁是 private）                                                                                                                                               |
| `ui/LoginActivity.kt` + `activity_login.xml`              | 服务器地址/用户名/密码表单（仿 SettingsActivity 表单模式），成功后存会话 finish()                                                                                                                                                   |
| `ui/TeamSummaryActivity.kt` + `activity_team_summary.xml` | 管理员汇总页：RecyclerView 按人分组展示月度工时/请假/次数，默认最近 3 个月，下拉刷新                                                                                                                                                       |
| `adapter/TeamSummaryAdapter.kt` + item 布局                 | 汇总列表渲染                                                                                                                                                                                                    |

### 2.2 修改文件

| 文件                                                         | 改动                                                                                                                                                                                                                                                             |
| ---------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `data/SettingsManager.kt`                                  | 新增 `saveAuthConfig/getAuthSession/clearAuthConfig` + 团队自动同步开关（token 过期则视为未登录）；key：TEAM\_SERVER\_URL/AUTH\_TOKEN/AUTH\_TOKEN\_EXPIRES\_AT/AUTH\_USER\_ID/AUTH\_USERNAME/AUTH\_DISPLAY\_NAME/AUTH\_ROLE/TEAM\_AUTO\_SYNC\_ENABLED                                |
| `res/layout/activity_settings.xml` + `SettingsActivity.kt` | WebDAV 区块后追加"团队同步"卡片：登录状态、登录/切换账号、退出登录、立即同步、自动同步开关；仿 setupButtons/loadWebDAVConfig 模式                                                                                                                                                                          |
| `MainActivity.kt` + `activity_main.xml`                    | 管理员可见的"团队汇总"按钮（按 role 控制 visibility，onResume 刷新）；同步入口加团队同步项                                                                                                                                                                                                    |
| `AndroidManifest.xml`                                      | 注册 LoginActivity、TeamSummaryActivity（exported=false）                                                                                                                                                                                                           |
| `proguard-rules.pro`                                       | 为 `data.model.**` DTO 追加 Gson keep 规则                                                                                                                                                                                                                          |
| `res/values/strings.xml`                                   | 新增团队同步/登录/管理员条目（team\_sync\_section\_title、team\_server\_address、team\_login、team\_logout、team\_sync\_now、team\_sync\_failed、team\_clear\_local\_message、login\_title、team\_summary\_title、admin\_create\_user、admin\_reset\_password、admin\_password\_once 等） |

### 2.3 登录/切账号/自动同步行为

* **自动登录**：`getAuthSession()` 存在且未过期即已登录；API 401 → 清会话 → Toast 引导重登（不清数据）

* **切换账号（严格序）**：确认对话框（明示将清空本机 N 条记录、WebDAV 备份谱系交叉污染风险）→ `deleteAll()` → `clearAuthConfig()` → LoginActivity → 登录成功立即 `performSync()`（本地空库 = 全量拉取服务端）

* **自动触发**：MainActivity onCreate 生物识别通过后 `lifecycleScope.launch { syncIfConfigured() }` 静默执行（失败才提示，成功不打扰）；可选增强：AutoSyncWorker 末尾追加团队同步

* **本地 Room 不动**：v7 零迁移，登录身份只存加密 SharedPreferences

***

## 三、实施顺序与里程碑

| 阶段           | 内容                                                             | 验证里程碑                                                                                 |
| ------------ | -------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| 0 骨架         | server/ 独立 Gradle 工程、Ktor 空壳、health 路由                         | `./gradlew shadowJar` 可运行，curl /api/health 200                                        |
| 1 服务端核心      | DB/DAO/bcrypt/JWT/三组路由/MergeService/Bootstrap + test-host 集成测试 | curl 全链路：首启打印 admin 密码 → login → 建号 → 双客户端互同步收敛 → summary 对账；**重启后旧 token 有效**；停用用户被拒 |
| 2 部署         | jar + 配置 + systemd 上服务器，HTTPS 反代                               | systemctl running；外网 https 域名 health 200；服务器重启自启且密码/token 不失效                         |
| 3 Android 登录 | DTO/ApiClient/SettingsManager/LoginActivity/Manifest/strings   | 真机登录成功；杀进程自动登录；服务端改密后 401 引导重登；未配置时团队功能不可见（老用户无感）                                     |
| 4 团队同步       | TeamSyncManager + SyncGate + 设置页区块                             | 双端同日互改收敛；删除墓碑收敛；A 账号数据不出现在 B；团队同步与 WebDAV 交替执行无错乱；断网不崩溃                               |
| 5 管理员        | TeamSummaryActivity + 建号/重置密码对话框                               | admin 可见汇总且数字与 SQL 对账一致；member 不可见入口且直连 admin API 返回 403                              |
| 6 收尾         | 启动静默自动同步、切账号全流程、混淆回归、server/README 定稿                          | 切账号后数据 = 新账号全量；release 混淆包回归通过                                                        |

## 四、风险与对策

| 风险                     | 对策                                                               |
| ---------------------- | ---------------------------------------------------------------- |
| 设备时钟偏移致 NEWER\_WINS 误判 | 全量同步保证最终一致；sync 响应带 serverTime，客户端登录时算偏差并提示；synced\_at 留服务器时间供对账 |
| token 过期/服务端重置密码       | JWT 30 天有效期；401 统一清会话引导重登；secret 持久化保证重启不踢人                      |
| 并发同步互踩                 | 服务端 userId Mutex + BEGIN IMMEDIATE；客户端 SyncGate 串行化两条通道          |
| 切账号与 WebDAV 备份谱系交叉     | 确认框明示；文档建议切账号前处理旧 WebDAV 备份或更换远程路径                               |
| R8 混淆破坏 Gson           | DTO 包 keep 规则 + 混淆包回归列入阶段 6                                      |
| SQLite 并发写             | WAL + busy\_timeout + 短事务                                        |

## 五、端到端验证方式

1. 服务端：`./gradlew test`（test-host 集成测试覆盖 login/sync 收敛/summary/权限矩阵）
2. 双模拟器/真机 + 同一服务器：账号 A、B 各自记录互不可见；A 双端互改同日记录收敛；管理员建号/重置密码/停用即时生效
3. 汇总对账：App 端显示数字与 `sqlite3` 直接 SQL 聚合结果一致
4. 回归：未登录状态下全 App 行为与现状完全一致（WebDAV、统计、设置不受影响）

