# 加班记录团队服务端（overtime-server）

Ktor 3 + SQLite 的小团队加班记录同步服务端。配套 Android 客户端的「团队同步」与「管理员汇总」功能。

- 协议：HTTPS 反代 + JWT Bearer；全量同步、NEWER_WINS 冲突合并（与客户端 `RecordMerge.kt` 语义一致）
- 存储：单文件 SQLite（WAL），墓碑软删除永久保留
- 不上云：薪资、班次/倍率设置、WebDAV 配置均仅存客户端本地；加班工时由客户端算好后随记录上传（`overtimeHours`），服务端只做聚合

## 1. 构建与产物

要求 JDK 17+（构建机与服务器均可为 21）。

```bash
cd server
./gradlew shadowJar        # 产物：build/libs/overtime-server-all.jar（fat jar）
./gradlew test             # 单元/集成测试（testApplication 内存起服，不落生产库）
```

## 2. 服务器部署（systemd）

```bash
# 1) 上传 jar
scp build/libs/overtime-server-all.jar user@server:/tmp/

# 2) 服务器上：专用低权用户与目录
sudo useradd -r -s /usr/sbin/nologin overtime
sudo mkdir -p /opt/overtime-server /etc/overtime-server /var/lib/overtime-server
sudo mv /tmp/overtime-server-all.jar /opt/overtime-server/
sudo chown -R overtime:overtime /var/lib/overtime-server

# 3) 外置配置（端口/数据库路径等；密钥文件与管理员用户名也可在此改）
sudo tee /etc/overtime-server/application.conf >/dev/null <<'EOF'
ktor {
  deployment { port = 8080 }
  application { modules = [ com.example.overtimeserver.ApplicationKt.module ] }
}
overtime {
  dbPath = "/var/lib/overtime-server/overtime.db"
  jwtSecretFile = "/var/lib/overtime-server/jwt-secret.key"
  adminUsername = "admin"
  jwt { issuer = "overtime-server", audience = "overtime-app", expiryDays = 30 }
}
EOF
sudo chown -R overtime:overtime /etc/overtime-server
sudo chmod 750 /etc/overtime-server

# 4) systemd（unit 文件在本仓库 server/systemd/ 下）
sudo cp systemd/overtime-server.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now overtime-server
```

## 3. 首次启动与获取管理员密码

- `users` 表为空时自动创建管理员，随机密码**只在启动日志打印一次**：

```bash
sudo journalctl -u overtime-server -n 100 --no-pager | grep -A4 "首次启动"
```

- 想指定初始密码可先设环境变量（unit 中 `Environment=ADMIN_INITIAL_PASSWORD=...` 或 `sudo systemctl edit overtime-server`），登录后立即修改。
- 登录后第一件事：在 App 设置页用「修改密码」改掉初始密码。

## 4. HTTPS 反向代理（必需）

App 端保持全局禁明文（无 `usesCleartextTraffic`），必须走 HTTPS。

**Caddy**（自动证书，推荐）：

```
overtime.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

**Nginx + Let's Encrypt**：

```nginx
server {
    listen 443 ssl;
    server_name overtime.example.com;
    ssl_certificate     /etc/letsencrypt/live/overtime.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/overtime.example.com/privkey.pem;
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

建议防火墙只放行 443，8080 不对外。

## 5. 数据备份（每日 cron）

```bash
sudo -u overtime sh -c 'echo "VACUUM INTO '"'"'/var/lib/overtime-server/backup/overtime-$(date +\%F).db'"'"'" | sqlite3 /var/lib/overtime-server/overtime.db'
```

或用 `.backup` 命令（注意 WAL 模式下直接拷贝 .db 文件不安全）。保留最近 30 天：

```bash
find /var/lib/overtime-server/backup -name "*.db" -mtime +30 -delete
```

## 6. 升级流程

1. 本地 `./gradlew shadowJar`，scp 新 jar 覆盖 `/opt/overtime-server/overtime-server-all.jar`
2. `sudo systemctl restart overtime-server`
3. Schema 变更只允许向后兼容（`CREATE TABLE IF NOT EXISTS` / 加列）；破坏性变更需先备份并写迁移脚本

## 7. API 一览（JWT Bearer，除 login/health）

| 方法路径 | 权限 | 说明 |
| --- | --- | --- |
| POST `/api/auth/login` | 匿名 | `{username,password}` → `{token, expiresIn, user}` |
| POST `/api/auth/change-password` | 登录用户 | 自改密码 |
| POST `/api/sync` | 登录用户 | `{records, deviceName}` → `{serverTime, records(服务端全量)}`，事务内 NEWER_WINS |
| GET `/api/health` | 匿名 | 探活 |
| GET `/api/admin/users` | admin | 成员列表（含 recordCount） |
| POST `/api/admin/users` | admin | 建号，密码缺省生成，**仅此一次返回** |
| POST `/api/admin/users/{id}/reset-password` | admin | 重置密码，仅此一次返回 |
| POST `/api/admin/users/{id}/set-active` | admin | 停用/启用（禁止登录、保留数据） |
| GET `/api/admin/summary?from=202610&to=202612` | admin | 按人按月聚合（yyyyMM，含端点）；不含工资 |

错误统一 `{"error": "..."}`；管理员守卫实时查库校验 role + is_active，不信任 token 内角色。

## 8. 故障排查

```bash
sudo journalctl -u overtime-server -f          # 实时日志
curl -s http://127.0.0.1:8080/api/health       # 本机探活
ls -l /var/lib/overtime-server                 # db 与 jwt-secret.key 应属于 overtime 用户
```

- 登录 401：密码错 / 账号被停用（管理员可在 App 中重置密码）
- 同步 500：查 journalctl；多为 SQLite 磁盘满或权限问题
- token 全员失效：`jwt-secret.key` 被删除后重启会重新生成，历史上文 token 全部作废（数据无损，重新登录即可）
