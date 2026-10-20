#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PersonalOvertimeRecord —— PC 端监控（方案 A：WebDAV + PC 读取器）

原理：手机 App 的"云同步"会把全量备份 overtime_backup.json 上传到你的 WebDAV
服务器（远程目录默认 /overtime_record/）。本工具用同一套 WebDAV 凭据拉取该
备份，完全兼容 App 的三种云端格式并本地解密，然后在浏览器里以仪表盘展示
加班统计（实时性取决于 App 的同步频率与本地轮询间隔）。

支持的三种云端格式（与 App SyncManager/EnvelopeCrypto/EncryptionUtils 逐字节对齐）：
  1. 明文 JSON（历史未开启同步加密）
  2. 信封加密  "PORE_ENV1:" + Base64（当前格式，需要同步密码或恢复码）
  3. 旧版裸 Base64 密文（G2/GM AES-GCM 与更早的 AES-CBC，需要同步密码）

使用步骤：
  1. 把 config.example.json 复制为 config.json，填入 WebDAV 地址/账号/密码；
     若 App 开启了"同步加密"，再填 sync_password（或 recovery_code）。
  2. 安装依赖（仅加密备份需要；明文备份无需任何第三方库）：
         pip install cryptography
  3. 运行：python overtime_monitor.py
     然后浏览器打开 http://127.0.0.1:8518 （页面每 60 秒自动刷新）。

其他：
  python overtime_monitor.py --selftest      # 本地自测解密/统计逻辑
  python overtime_monitor.py --no-browser    # 不自动打开浏览器
  python overtime_monitor.py --port 9000     # 临时指定端口

安全说明：
  - 仪表盘只绑定 127.0.0.1，不向局域网暴露数据；
  - config.json 内含 WebDAV 明文凭据，请勿提交到仓库/外发（已在 .gitignore 忽略）；
  - 与 App 一致：Basic 凭据只发给同注册域主机，拒绝 https→http 降级跳转。
"""

import argparse
import base64
import hashlib
import html
import json
import os
import re
import socket
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from datetime import datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# ---------------------------------------------------------------------------
# 常量（与 App 源码对齐）
# ---------------------------------------------------------------------------

WIRE_PREFIX = "PORE_ENV1:"                 # EnvelopeCrypto.WIRE_PREFIX
ENVELOPE_MAGIC = b"PEV1"                   # EnvelopeCrypto.MAGIC
ENVELOPE_VERSION = 1
SLOT_SALT_SIZE = 16                        # EncryptionUtils.INTERNAL_SALT_SIZE
GCM_IV_SIZE = 12                           # EncryptionUtils.INTERNAL_GCM_IV_LENGTH
WRAPPED_DEK_SIZE = 32 + 16                 # DEK(32) + GCM tag(16)
SLOT_SIZE = SLOT_SALT_SIZE + GCM_IV_SIZE + WRAPPED_DEK_SIZE          # 76
PBKDF2_ITERATIONS = 600_000                # 当前强度
PBKDF2_ITERATIONS_LEGACY = 65_536          # 历史 GM/CBC 强度
BACKUP_FILENAME = "overtime_backup.json"   # WebDAVManager.DEFAULT_BACKUP_FILENAME
MAX_DOWNLOAD_BYTES = 64 * 1024 * 1024      # 与 App 相同的下载上限

GCM_MAGIC = b"G2"                          # EncryptionUtils 当前 GCM
GCM_MAGIC_LEGACY = b"GM"                   # 历史 GCM
LEGACY_CBC_IV_SIZE = 16

RETRYABLE_STATUS_CODES = {408, 429, 502, 503, 504}
REDIRECT_STATUS_CODES = {301, 302, 303, 307, 308}
MAX_REDIRECTS = 5
MAX_RETRIES = 2
RETRY_BACKOFF_BASE_MS = 1000

# 常见两段式公共后缀（与 WebDAVManager.TWO_LEVEL_PUBLIC_SUFFIXES 一致）
TWO_LEVEL_PUBLIC_SUFFIXES = {
    "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "ac.cn",
    "com.hk", "com.tw", "com.jp", "co.jp", "com.sg", "com.au", "com.br",
    "co.uk", "org.uk", "net.au",
}

DEFAULT_CONFIG = {
    "server_url": "",
    "username": "",
    "password": "",
    "remote_path": "/overtime_record/",
    "sync_password": "",
    "recovery_code": "",
    "poll_interval_minutes": 5,
    "port": 8518,
}

WEEKDAY_NAMES = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]

# 参与加班费估算的 Double 字段（Gson 序列化复刻用）
DOUBLE_FIELDS = {"manualOvertimeHours", "manualExtraHours", "leaveHours", "customRate"}


class CryptoUnavailable(RuntimeError):
    """云端数据已加密但本机未安装 cryptography"""


class NeedKeyError(RuntimeError):
    """云端数据已加密但未配置同步密码/恢复码，或密钥错误"""


# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------

def script_dir():
    return os.path.dirname(os.path.abspath(__file__))


def load_config(path):
    if not os.path.exists(path):
        example = os.path.join(script_dir(), "config.example.json")
        try:
            if os.path.exists(example):
                with open(example, "r", encoding="utf-8") as f:
                    tpl = f.read()
                with open(path, "w", encoding="utf-8") as f:
                    f.write(tpl)
        except OSError:
            pass
        raise SystemExit(
            "未找到 config.json，已尝试从模板生成。\n"
            "请编辑 %s 填入 WebDAV 配置后重新运行。" % path
        )
    with open(path, "r", encoding="utf-8") as f:
        user = json.load(f)
    cfg = dict(DEFAULT_CONFIG)
    cfg.update({k: v for k, v in user.items() if k in DEFAULT_CONFIG})
    if not str(cfg.get("server_url", "")).strip():
        raise SystemExit("config.json 缺少 server_url（WebDAV 服务器地址）")
    try:
        cfg["poll_interval_minutes"] = max(1, int(cfg.get("poll_interval_minutes") or 5))
    except (TypeError, ValueError):
        cfg["poll_interval_minutes"] = 5
    try:
        cfg["port"] = int(cfg.get("port") or 8518)
    except (TypeError, ValueError):
        cfg["port"] = 8518
    return cfg


# ---------------------------------------------------------------------------
# 加密/解密（与 App 逐字节对齐）
# ---------------------------------------------------------------------------

def _b64_decode_flexible(text):
    """兼容 Android Base64.DEFAULT（可含换行）的宽容解码"""
    cleaned = re.sub(r"\s+", "", text)
    pad = (-len(cleaned)) % 4
    return base64.b64decode(cleaned + "=" * pad)


def _require_crypto():
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM  # noqa: F401
    except ImportError:
        raise CryptoUnavailable(
            "云端备份已加密，需要 cryptography 库解密：pip install cryptography"
        )


def derive_key(secret, salt, iterations=PBKDF2_ITERATIONS):
    """PBKDF2-HmacSHA256，256 位输出（EncryptionUtils.deriveKey）"""
    return hashlib.pbkdf2_hmac("sha256", secret.encode("utf-8"), salt, iterations, dklen=32)


def normalize_recovery_code(code):
    """RecoveryCodeManager.normalize：大写、去 -/空格、O→0、I/L→1"""
    cleaned = code.strip().upper().replace("-", "").replace(" ", "")
    return "".join({"O": "0", "I": "1", "L": "1"}.get(c, c) for c in cleaned)


def _aesgcm_decrypt(key, iv, data):
    _require_crypto()
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    return AESGCM(key).decrypt(iv, data, None)


def unwrap_slot(slot_bytes, secret):
    """EnvelopeCrypto.unwrapDek：成功返回 32B DEK，失败返回 None"""
    if len(slot_bytes) != SLOT_SIZE:
        return None
    salt = slot_bytes[:SLOT_SALT_SIZE]
    iv = slot_bytes[SLOT_SALT_SIZE:SLOT_SALT_SIZE + GCM_IV_SIZE]
    wrapped = slot_bytes[SLOT_SALT_SIZE + GCM_IV_SIZE:]
    try:
        kek = derive_key(secret, salt)
        dek = _aesgcm_decrypt(kek, iv, wrapped)
        return dek if len(dek) == 32 else None
    except Exception:
        return None


def open_envelope(wire, password=None, recovery_code=None):
    """解开信封，返回 (plaintext_bytes, source)；失败抛 NeedKeyError"""
    blob = _b64_decode_flexible(wire[len(WIRE_PREFIX):])
    if blob[:4] != ENVELOPE_MAGIC:
        raise NeedKeyError("信封 magic 不匹配（文件可能已损坏）")
    if blob[4] != ENVELOPE_VERSION:
        raise NeedKeyError("不支持的信封版本: %d" % blob[4])
    off = 5
    pwd_slot = blob[off:off + SLOT_SIZE]; off += SLOT_SIZE
    rec_slot = blob[off:off + SLOT_SIZE]; off += SLOT_SIZE
    payload_iv = blob[off:off + GCM_IV_SIZE]; off += GCM_IV_SIZE
    payload = blob[off:]
    if len(payload) < 16:
        raise NeedKeyError("信封正文缺失 GCM tag")

    dek = None
    source = None
    if password:
        dek = unwrap_slot(pwd_slot, password)
        if dek is not None:
            source = "sync_password"
    if dek is None and recovery_code:
        dek = unwrap_slot(rec_slot, normalize_recovery_code(recovery_code))
        if dek is not None:
            source = "recovery_code"
    if dek is None:
        raise NeedKeyError("同步密码/恢复码无法解开云端信封（密钥错误？）")
    try:
        plaintext = _aesgcm_decrypt(dek, payload_iv, payload)
    except Exception:
        raise NeedKeyError("正文解密失败（数据可能被篡改）")
    return plaintext, source


def _unpad_pkcs7(data):
    if not data or len(data) % 16 != 0:
        raise ValueError("CBC 数据长度非法")
    pad = data[-1]
    if pad < 1 or pad > 16 or data[-pad:] != bytes([pad]) * pad:
        raise ValueError("PKCS7 填充校验失败（密码错误？）")
    return data[:-pad]


def decrypt_legacy(raw_b64, password):
    """旧版裸 Base64 密文：G2/GM AES-GCM 与无 magic 的 AES-CBC-PKCS7"""
    data = _b64_decode_flexible(raw_b64)
    if data[:2] == GCM_MAGIC or data[:2] == GCM_MAGIC_LEGACY:
        iterations = PBKDF2_ITERATIONS if data[:2] == GCM_MAGIC else PBKDF2_ITERATIONS_LEGACY
        off = 2
        salt = data[off:off + SLOT_SALT_SIZE]; off += SLOT_SALT_SIZE
        iv = data[off:off + GCM_IV_SIZE]; off += GCM_IV_SIZE
        body = data[off:]
        key = derive_key(password, salt, iterations)
        return _aesgcm_decrypt(key, iv, body), "legacy_%s" % data[:2].decode()
    # 旧版 CBC：salt(16) + iv(16) + 密文，PBKDF2 65536
    if len(data) <= SLOT_SALT_SIZE + LEGACY_CBC_IV_SIZE:
        raise NeedKeyError("旧版密文长度非法")
    salt = data[:SLOT_SALT_SIZE]
    iv = data[SLOT_SALT_SIZE:SLOT_SALT_SIZE + LEGACY_CBC_IV_SIZE]
    body = data[SLOT_SALT_SIZE + LEGACY_CBC_IV_SIZE:]
    key = derive_key(password, salt, PBKDF2_ITERATIONS_LEGACY)
    _require_crypto()
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    decryptor = Cipher(algorithms.AES(key), modes.CBC(iv)).decryptor()
    padded = decryptor.update(body) + decryptor.finalize()
    return _unpad_pkcs7(padded), "legacy_cbc"


def parse_wire(raw, password=None, recovery_code=None):
    """
    按 App resolveCloud 的顺序解析云端内容。
    返回 (backup_dict, source_meta)；无法识别/密钥不符时抛异常。
    """
    trimmed = raw.lstrip()
    if trimmed.startswith("{") or trimmed.startswith("["):
        return json.loads(raw), {"format": "plain", "decrypted": False, "key_source": None}

    if trimmed.startswith(WIRE_PREFIX):
        plaintext, source = open_envelope(trimmed, password, recovery_code)
        backup = json.loads(plaintext.decode("utf-8"))
        return backup, {"format": "envelope", "decrypted": True, "key_source": source}

    if not password:
        raise NeedKeyError("云端数据是加密的旧版格式，但未配置 sync_password")
    plaintext, fmt = decrypt_legacy(trimmed, password)
    backup = json.loads(plaintext.decode("utf-8"))
    return backup, {"format": fmt, "decrypted": True, "key_source": "sync_password"}


# ---------------------------------------------------------------------------
# checksum 校验（best-effort 复刻 Gson 序列化；不一致仅提示，不阻断展示）
# ---------------------------------------------------------------------------

def _java_double(v):
    f = float(v)
    if f == int(f) and abs(f) < 1e16:
        return "%.1f" % f
    return repr(f)


def _json_string(s):
    out = json.dumps(s, ensure_ascii=False)
    # Gson 默认开启 HTML 转义（JsonWriter HTML-safe）：这 5 个字符以 \uXXXX 输出，
    # BackupData.computeRecordsChecksum 用的正是默认 Gson()，复刻时必须一致
    for ch, esc in (("<", "\\u003c"), (">", "\\u003e"), ("&", "\\u0026"),
                    ("=", "\\u003d"), ("'", "\\u0027")):
        out = out.replace(ch, esc)
    return out


def gson_like_record(record):
    """按 Gson 默认行为序列化单条记录：省略 null、HTML 转义、Double 用 Java 格式。

    字段顺序沿用云端 JSON 中该记录自身的键序（json.loads 保持插入顺序）：
    写入端计算 checksum 的字段顺序与它写出 JSON 的顺序一致，且实测云端备份
    按字母序输出（与 Kotlin 声明序不同），因此不能硬编码声明序。
    """
    parts = []
    for key in record.keys():
        v = record.get(key)
        if v is None:
            continue
        if isinstance(v, bool):
            s = "true" if v else "false"
        elif key in DOUBLE_FIELDS:
            s = _java_double(v)
        elif isinstance(v, (int, float)):
            s = str(int(v)) if isinstance(v, int) else _java_double(v)
        else:
            s = _json_string(str(v))
        parts.append("%s:%s" % (_json_string(key), s))
    return "{" + ",".join(parts) + "}"


def compute_records_checksum(records):
    json_text = "[" + ",".join(gson_like_record(r) for r in records) + "]"
    return hashlib.sha256(json_text.encode("utf-8")).hexdigest()


def verify_checksum(backup):
    """返回 'ok' / 'mismatch' / None（云端未携带 checksum 或解析失败）"""
    expected = backup.get("checksum")
    if not expected:
        return None
    try:
        actual = compute_records_checksum(backup.get("attendanceRecords") or [])
        return "ok" if expected.lower() == actual else "mismatch"
    except Exception:
        return None


# ---------------------------------------------------------------------------
# WebDAV 客户端（同注册域才带凭据 + 手动重定向 + 瞬态重试）
# ---------------------------------------------------------------------------

class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


_OPENER = urllib.request.build_opener(_NoRedirect)


def registered_domain(host):
    labels = (host or "").lower().split(".")
    if len(labels) <= 2:
        return ".".join(labels)
    last_two = ".".join(labels[-2:])
    return ".".join(labels[-3:]) if last_two in TWO_LEVEL_PUBLIC_SUFFIXES else last_two


def effective_port(parts):
    return parts.port or (443 if parts.scheme == "https" else 80 if parts.scheme == "http" else None)


def same_trusted_origin(a, b):
    if a.scheme != "https" or b.scheme != "https":
        return False
    if effective_port(a) != effective_port(b):
        return False
    return registered_domain(a.hostname) == registered_domain(b.hostname)


def build_url(base_url, *path_parts):
    result = base_url.strip().rstrip("/")
    for part in path_parts:
        clean = part.strip("/")
        if clean:
            result += "/" + clean
    return result


def webdav_request(method, url, username, password, body=None, headers=None):
    """返回 (status, headers, content_bytes)；重定向策略与 App 一致"""
    original = urllib.parse.urlsplit(url)
    current = url
    redirect_count = 0
    retry_count = 0
    while True:
        try:
            req = urllib.request.Request(current, data=body, method=method)
            target = urllib.parse.urlsplit(current)
            # Basic 凭据只发给同注册域主机（与 WebDAVManager 一致）
            if username and same_trusted_origin(original, target):
                token = base64.b64encode(
                    ("%s:%s" % (username, password)).encode("utf-8")
                ).decode("ascii")
                req.add_header("Authorization", "Basic " + token)
            req.add_header("User-Agent", "PORE-PC-Monitor/1.0")
            for k, v in (headers or {}).items():
                req.add_header(k, v)
            with _OPENER.open(req, timeout=60) as resp:
                content = resp.read(MAX_DOWNLOAD_BYTES + 1)
                if len(content) > MAX_DOWNLOAD_BYTES:
                    raise IOError("云端备份超过大小上限（64 MB），已中止下载")
                return resp.status, dict(resp.headers), content
        except urllib.error.HTTPError as e:
            if e.code in REDIRECT_STATUS_CODES and redirect_count < MAX_REDIRECTS:
                location = e.headers.get("Location")
                if location:
                    nxt = urllib.parse.urljoin(current, location)
                    nxt_parts = urllib.parse.urlsplit(nxt)
                    # 仅跟随 https 跳转，拒绝降级（与 App 一致）
                    if nxt_parts.scheme == "https":
                        redirect_count += 1
                        current = nxt
                        continue
            if e.code in RETRYABLE_STATUS_CODES and retry_count < MAX_RETRIES:
                retry_count += 1
                time.sleep(RETRY_BACKOFF_BASE_MS * retry_count / 1000.0)
                continue
            content = b""
            try:
                content = e.read(MAX_DOWNLOAD_BYTES + 1)
            except Exception:
                pass
            return e.code, dict(e.headers), content
        except (urllib.error.URLError, socket.timeout, ConnectionError, OSError) as e:
            if retry_count < MAX_RETRIES:
                retry_count += 1
                time.sleep(RETRY_BACKOFF_BASE_MS * retry_count / 1000.0)
                continue
            raise IOError("网络请求失败: %s" % e)


def fetch_backup_raw(cfg):
    url = build_url(cfg["server_url"], cfg.get("remote_path") or "/", BACKUP_FILENAME)
    status, headers, content = webdav_request("GET", url, cfg.get("username"), cfg.get("password"))
    if status < 200 or status >= 300:
        if status == 404:
            raise IOError("云端没有备份文件（404）：%s" % url)
        if status == 401:
            raise IOError("WebDAV 认证失败（401），请检查账号/密码")
        raise IOError("下载失败，HTTP %d：%s" % (status, url))
    last_modified = headers.get("Last-Modified") or headers.get("last-modified") or ""
    return content.decode("utf-8"), last_modified, url


# ---------------------------------------------------------------------------
# 统计（口径对齐 OvertimeCalculator）
# ---------------------------------------------------------------------------

def _fmt_hours(v):
    v = round(float(v), 2)
    return ("%g" % v)


def _parse_date(s):
    try:
        return datetime.strptime(s, "%Y-%m-%d").date()
    except (TypeError, ValueError):
        return None


def _resolve_checkout_dt(r, d):
    """解析记录的实际下班时间：优先打卡时间戳，回退到 checkOutTime 字符串（日期取记录日期）"""
    if d is None or r.get("isLeave"):
        return None
    co_ts = r.get("checkOutTimestamp")
    if co_ts:
        try:
            return datetime.fromtimestamp(int(co_ts) / 1000.0)
        except (TypeError, ValueError, OSError, OverflowError):
            pass
    co_str = str(r.get("checkOutTime") or "").strip()
    if re.match(r"^\d{1,2}:\d{2}$", co_str):
        try:
            h, m = (int(x) for x in co_str.split(":"))
            return datetime(d.year, d.month, d.day, h, m)
        except ValueError:
            return None
    return None


def compute_stats(backup, now=None):
    now = now or datetime.now()
    settings = backup.get("settings") or {}
    records = backup.get("attendanceRecords") or []

    # 缺失字段回退到 App OvertimeSettings 默认值
    base = float(settings["baseSalary"]) if settings.get("baseSalary") is not None else 5000.0
    perf = float(settings["performancePercent"]) if settings.get("performancePercent") is not None else 0.0
    month_days = float(settings["monthlyWorkDays"]) if settings.get("monthlyWorkDays") is not None else 21.75
    day_hours = float(settings["dailyWorkHours"]) if settings.get("dailyWorkHours") is not None else 8.0
    total_monthly = base * (1 + perf / 100.0)
    monthly_hours = month_days * day_hours
    hourly = total_monthly / monthly_hours if monthly_hours > 0 else 0.0
    rate_normal = float(settings["overtimeRateNormal"]) if settings.get("overtimeRateNormal") is not None else 1.5
    rate_weekend = float(settings["overtimeRateWeekend"]) if settings.get("overtimeRateWeekend") is not None else 2.0

    # 标准下班时间（下班晚点分析用），缺省 "17:00"（与 App OvertimeSettings 默认一致）
    std_end_str = str(settings.get("workEndTime") or "17:00").strip()
    if not re.match(r"^\d{1,2}:\d{2}$", std_end_str):
        std_end_str = "17:00"
    std_end_h, std_end_m = (int(x) for x in std_end_str.split(":"))

    active = [r for r in records if not r.get("isDeleted")]
    deleted_count = len(records) - len(active)

    month_key = now.strftime("%Y-%m")
    year_key = now.strftime("%Y")

    month_ot = month_extra = month_pay = 0.0
    month_leave_days = month_leave_hours = 0
    year_hours = year_pay = 0.0
    total_hours = 0.0
    month_count = 0                    # 本月有加班/加点登记的天数
    month_late_nights = 0              # 本月 22:00 后下班次数
    month_latest_checkout = None       # 本月最晚下班 "HH:MM"
    month_leave_by_type = {}           # 请假类型 -> {days, hours}
    daily_map = {}                     # date -> {ot, extra, leave, late, checkout}
    monthly_map = {}                   # YYYY-MM -> {ot, extra, pay}
    weekday_map = {i: {"ot": 0.0, "extra": 0.0} for i in range(7)}

    for r in active:
        date_s = r.get("date") or ""
        d = _parse_date(date_s)
        ot = float(r.get("manualOvertimeHours") or 0)
        ot = ot if ot > 0 else 0.0
        extra = float(r.get("manualExtraHours") or 0)
        extra = extra if extra > 0 else 0.0
        leave_hours = float(r.get("leaveHours") or 0)
        is_leave = bool(r.get("isLeave"))
        hours = ot + extra
        total_hours += hours

        custom = r.get("customRate")
        custom_rate = float(custom) if custom is not None else None
        # 估算口径：登记"加班"按周末倍率（法定节假日 3 倍场景以 App 为准），
        # 登记"加点"按工作日倍率；customRate >= 0 时整条覆盖
        if custom_rate is not None and custom_rate >= 0:
            pay = (ot + extra) * hourly * custom_rate
        else:
            pay = ot * hourly * rate_weekend + extra * hourly * rate_normal

        if is_leave:
            month_leave_days += 1 if date_s.startswith(month_key) else 0
            if date_s.startswith(month_key):
                month_leave_hours += leave_hours
                lt = str(r.get("leaveType") or "未分类")
                agg_lt = month_leave_by_type.setdefault(lt, {"days": 0, "hours": 0.0})
                agg_lt["days"] += 1
                agg_lt["hours"] += leave_hours

        month_hit = date_s.startswith(month_key)
        year_hit = d is not None and d.strftime("%Y") == year_key
        co_dt = _resolve_checkout_dt(r, d) if hours > 0 else None
        if month_hit:
            month_ot += ot
            month_extra += extra
            month_pay += pay
            if hours > 0:
                month_count += 1
            if co_dt is not None:
                if co_dt.hour >= 22:
                    month_late_nights += 1
                hhmm = co_dt.strftime("%H:%M")
                if month_latest_checkout is None or hhmm > month_latest_checkout:
                    month_latest_checkout = hhmm
        if year_hit:
            year_hours += hours
            year_pay += pay

        if d is not None:
            agg_d = daily_map.setdefault(date_s, {"ot": 0.0, "extra": 0.0, "leave": 0.0})
            agg_d["ot"] += ot
            agg_d["extra"] += extra
            if is_leave:
                agg_d["leave"] += leave_hours
            wk = weekday_map[d.weekday()]
            wk["ot"] += ot
            wk["extra"] += extra
            # 下班晚点：实际下班时间 - 标准下班时间（有加班/加点且能解析出下班时间）
            if co_dt is not None:
                try:
                    end_dt = datetime(d.year, d.month, d.day, std_end_h, std_end_m)
                    late_h = (co_dt.timestamp() - end_dt.timestamp()) / 3600.0
                    late_h = max(0.0, min(late_h, 24.0))
                    agg_d["late"] = max(agg_d.get("late", 0.0), round(late_h, 2))
                    agg_d["checkout"] = co_dt.strftime("%H:%M")
                except (TypeError, ValueError, OSError, OverflowError):
                    pass
            mk = d.strftime("%Y-%m")
            agg = monthly_map.setdefault(mk, {"ot": 0.0, "extra": 0.0, "pay": 0.0})
            agg["ot"] += ot
            agg["extra"] += extra
            agg["pay"] += pay

    # 近 30 天（含无记录日）：加班/加点/请假分开统计，供堆叠柱状图使用
    daily30 = []
    for i in range(29, -1, -1):
        d = (now - timedelta(days=i)).date()
        ds = d.strftime("%Y-%m-%d")
        agg = daily_map.get(ds, {})
        daily30.append({
            "date": ds,
            "weekday": d.weekday(),
            "ot": round(agg.get("ot", 0.0), 2),
            "extra": round(agg.get("extra", 0.0), 2),
            "leave": round(agg.get("leave", 0.0), 2),
            "late": agg.get("late"),          # 下班晚点小时（无打卡/无加班为 None）
            "checkout": agg.get("checkout"),  # 实际下班 "HH:MM"
        })

    # 近 12 个月：同样区分加班/加点
    monthly12 = []
    for i in range(11, -1, -1):
        first = (now.replace(day=1) - timedelta(days=30 * i)).strftime("%Y-%m")
        agg = monthly_map.get(first, {"ot": 0.0, "extra": 0.0, "pay": 0.0})
        monthly12.append({
            "month": first,
            "ot": round(agg["ot"], 2),
            "extra": round(agg["extra"], 2),
            "hours": round(agg["ot"] + agg["extra"], 2),
            "pay": round(agg["pay"], 2),
        })

    # 当前连续加班天数：从最近一个有加班/加点登记的日子往前连续计数
    ot_date_set = {ds for ds, a in daily_map.items() if a["ot"] > 0 or a["extra"] > 0}
    streak_days = 0
    if ot_date_set:
        probe = _parse_date(max(ot_date_set))
        while probe is not None and probe.isoformat() in ot_date_set:
            streak_days += 1
            probe -= timedelta(days=1)

    # 环比上月
    prev_key = (now.replace(day=1) - timedelta(days=1)).strftime("%Y-%m")
    prev_hours = round(monthly_map.get(prev_key, {}).get("ot", 0.0)
                       + monthly_map.get(prev_key, {}).get("extra", 0.0), 2)
    cur_hours = round(month_ot + month_extra, 2)
    mom_pct = round((cur_hours - prev_hours) / prev_hours * 100.0) if prev_hours > 0 else None

    month_avg_12 = round(sum(mo["hours"] for mo in monthly12) / 12.0, 2)
    month_avg = round(cur_hours / month_count, 2) if month_count > 0 else 0.0

    # 明细（倒序，最多 60 条）
    def view(r):
        date_s = r.get("date") or ""
        d = _parse_date(date_s)
        ot = max(0.0, float(r.get("manualOvertimeHours") or 0))
        extra = max(0.0, float(r.get("manualExtraHours") or 0))
        is_leave = bool(r.get("isLeave"))
        labels, cls = [], "normal"
        if is_leave:
            labels.append("请假"); cls = "leave"
        if ot > 0:
            labels.append("加班")
            cls = "ot" if cls == "normal" else cls
        if extra > 0:
            labels.append("加点")
            if cls == "normal":
                cls = "extra"
        custom = r.get("customRate")
        custom_rate = float(custom) if custom is not None else None
        if custom_rate is not None and custom_rate >= 0:
            pay = (ot + extra) * hourly * custom_rate
        else:
            pay = ot * hourly * rate_weekend + extra * hourly * rate_normal
        ci, co = r.get("checkInTime"), r.get("checkOutTime")
        if not ci and not co:
            time_range = "未登记"
        else:
            time_range = "%s - %s" % (ci or "--:--", co or "--:--")
        return {
            "date": date_s,
            "weekday": WEEKDAY_NAMES[d.weekday()] if d else "",
            "labels": labels or ["—"],
            "cls": cls,
            "time": time_range,
            "ot": ot if ot > 0 else None,
            "extra": extra if extra > 0 else None,
            "leave_hours": float(r.get("leaveHours") or 0) if is_leave else None,
            "leave_type": r.get("leaveType") or "",
            "pay": round(pay, 2) if (ot + extra) > 0 else None,
            "custom_rate": ("×%g" % custom_rate) if (custom_rate is not None and custom_rate >= 0) else "默认",
            "note": r.get("note") or "",
            "device": r.get("lastModifiedBy") or "",
        }

    recent = sorted(
        (view(r) for r in active if r.get("date")),
        key=lambda v: v["date"], reverse=True,
    )[:60]

    return {
        "record_count_total": len(records),
        "record_count_active": len(active),
        "deleted_count": deleted_count,
        "hourly_wage": round(hourly, 2),
        "month_key": month_key,
        "month_ot": round(month_ot, 2),
        "month_extra": round(month_extra, 2),
        "month_hours": round(month_ot + month_extra, 2),
        "month_pay": round(month_pay, 2),
        "month_leave_days": month_leave_days,
        "month_leave_hours": round(month_leave_hours, 2),
        "month_count": month_count,
        "month_avg": month_avg,
        "prev_month_hours": prev_hours,
        "mom_pct": mom_pct,
        "streak_days": streak_days,
        "late_nights": month_late_nights,
        "latest_checkout": month_latest_checkout,
        "month_avg_12": month_avg_12,
        "weekday_dist": weekday_map,
        "month_leave_by_type": month_leave_by_type,
        "year_hours": round(year_hours, 2),
        "year_pay": round(year_pay, 2),
        "total_hours": round(total_hours, 2),
        "daily30": daily30,
        "monthly12": monthly12,
        "recent": recent,
    }


# ---------------------------------------------------------------------------
# SVG 图表
# ---------------------------------------------------------------------------

def svg_daily_chart(daily30):
    """近 30 天堆叠柱状图：加班（蓝）/ 加点（青）/ 请假（橙）分段显示"""
    w, h = 860, 220
    pad_l, pad_b, pad_t = 40, 28, 12
    plot_w = w - pad_l - 8
    plot_h = h - pad_b - pad_t
    if not daily30:
        return ""
    segments = [("ot", "加班", "#4c6ef5"), ("extra", "加点", "#22b8cf"), ("leave", "请假", "#f59e0b")]

    def day_total(d):
        return sum(d.get(k, 0.0) for k, _label, _color in segments)

    max_v = max((day_total(d) for d in daily30), default=0) or 1.0
    max_v = max(max_v, 1.0)
    n = len(daily30)
    slot = plot_w / n
    bar_w = max(6.0, slot * 0.62)
    parts = []
    # 网格与刻度
    for i in range(1, 4):
        y = pad_t + plot_h - plot_h * i / 4.0
        parts.append('<line x1="%d" y1="%.1f" x2="%d" y2="%.1f" stroke="#e5e7eb" stroke-width="1"/>'
                     % (pad_l, y, w - 8, y))
        parts.append('<text x="%d" y="%.1f" font-size="10" fill="#9ca3af" text-anchor="end">%g</text>'
                     % (pad_l - 6, y + 3, max_v * i / 4.0))
    for idx, d in enumerate(daily30):
        wd = WEEKDAY_NAMES[d["weekday"]]
        x = pad_l + idx * slot + (slot - bar_w) / 2
        total = day_total(d)
        if total > 0:
            tip_lines = ["%s（%s）" % (d["date"], wd)]
            for k, label, _color in segments:
                v = d.get(k, 0.0)
                if v > 0:
                    tip_lines.append("%s %.2f 小时" % (label, v))
            tip_lines.append("合计 %.2f 小时" % total)
            tip = "\n".join(tip_lines)
            # 自下而上堆叠：加班 → 加点 → 请假
            y_cursor = pad_t + plot_h
            for k, _label, color in segments:
                v = d.get(k, 0.0)
                if v <= 0:
                    continue
                seg_h = plot_h * (v / max_v)
                y_cursor -= seg_h
                parts.append('<rect x="%.1f" y="%.1f" width="%.1f" height="%.1f" fill="%s">'
                             '<title>%s</title></rect>'
                             % (x, y_cursor, bar_w, seg_h, color, tip))
        else:
            parts.append(
                '<rect x="%.1f" y="%.1f" width="%.1f" height="2" fill="#e5e7eb">'
                '<title>%s（%s）：无记录</title></rect>'
                % (x, pad_t + plot_h - 2, bar_w, d["date"], wd)
            )
        if idx % 5 == 0 or idx == n - 1:
            parts.append('<text x="%.1f" y="%d" font-size="10" fill="#6b7280" text-anchor="middle">%s</text>'
                         % (x + bar_w / 2, h - 8, d["date"][5:]))
    return ('<svg viewBox="0 0 %d %d" width="100%%" height="%d" role="img">%s</svg>'
            % (w, h, h, "".join(parts)))


def svg_monthly_chart(monthly12):
    """近 12 个月堆叠柱状图：加班（蓝）/ 加点（青）分段显示"""
    w, h = 860, 220
    pad_l, pad_b, pad_t = 40, 28, 12
    plot_w = w - pad_l - 8
    plot_h = h - pad_b - pad_t
    if not monthly12:
        return ""
    segments = [("ot", "加班", "#4c6ef5"), ("extra", "加点", "#22b8cf")]

    def month_total(m):
        return m.get("ot", 0.0) + m.get("extra", 0.0)

    max_v = max((month_total(m) for m in monthly12), default=0) or 1.0
    max_v = max(max_v, 1.0)
    n = len(monthly12)
    slot = plot_w / n
    bar_w = max(10.0, slot * 0.55)
    parts = []
    for i in range(1, 4):
        y = pad_t + plot_h - plot_h * i / 4.0
        parts.append('<line x1="%d" y1="%.1f" x2="%d" y2="%.1f" stroke="#e5e7eb" stroke-width="1"/>'
                     % (pad_l, y, w - 8, y))
        parts.append('<text x="%d" y="%.1f" font-size="10" fill="#9ca3af" text-anchor="end">%g</text>'
                     % (pad_l - 6, y + 3, max_v * i / 4.0))
    for idx, m in enumerate(monthly12):
        total = month_total(m)
        x = pad_l + idx * slot + (slot - bar_w) / 2
        if total > 0:
            tip_lines = [m["month"]]
            for k, label, _color in segments:
                v = m.get(k, 0.0)
                if v > 0:
                    tip_lines.append("%s %.2f 小时" % (label, v))
            tip_lines.append("合计 %.2f 小时，预估 ¥%.2f" % (total, m.get("pay", 0.0)))
            tip = "\n".join(tip_lines)
            y_cursor = pad_t + plot_h
            for k, _label, color in segments:
                v = m.get(k, 0.0)
                if v <= 0:
                    continue
                seg_h = plot_h * (v / max_v)
                y_cursor -= seg_h
                parts.append('<rect x="%.1f" y="%.1f" width="%.1f" height="%.1f" fill="%s">'
                             '<title>%s</title></rect>'
                             % (x, y_cursor, bar_w, seg_h, color, tip))
        else:
            parts.append(
                '<rect x="%.1f" y="%.1f" width="%.1f" height="2" fill="#e5e7eb"><title>%s：无记录</title></rect>'
                % (x, pad_t + plot_h - 2, bar_w, m["month"])
            )
        parts.append('<text x="%.1f" y="%d" font-size="10" fill="#6b7280" text-anchor="middle">%s</text>'
                     % (x + bar_w / 2, h - 8, m["month"][2:]))
    return ('<svg viewBox="0 0 %d %d" width="100%%" height="%d" role="img">%s</svg>'
            % (w, h, h, "".join(parts)))


def svg_late_chart(daily30, std_end):
    """近 30 天下班晚点柱状图：柱高 = 打卡下班时间相对标准下班时间的晚点小时数"""
    w, h = 860, 220
    pad_l, pad_b, pad_t = 40, 28, 12
    plot_w = w - pad_l - 8
    plot_h = h - pad_b - pad_t
    if not daily30:
        return ""
    max_v = max((d["late"] or 0.0 for d in daily30), default=0) or 1.0
    max_v = max(max_v, 1.0)
    n = len(daily30)
    slot = plot_w / n
    bar_w = max(6.0, slot * 0.62)
    parts = []
    for i in range(1, 4):
        y = pad_t + plot_h - plot_h * i / 4.0
        parts.append('<line x1="%d" y1="%.1f" x2="%d" y2="%.1f" stroke="#e5e7eb" stroke-width="1"/>'
                     % (pad_l, y, w - 8, y))
        parts.append('<text x="%d" y="%.1f" font-size="10" fill="#9ca3af" text-anchor="end">%g</text>'
                     % (pad_l - 6, y + 3, max_v * i / 4.0))
    # 0 点基线（= 标准下班时间）
    base_y = pad_t + plot_h
    parts.append('<line x1="%d" y1="%.1f" x2="%d" y2="%.1f" stroke="#adb5bd" stroke-width="1" stroke-dasharray="4 3"/>'
                 % (pad_l, base_y, w - 8, base_y))
    for idx, d in enumerate(daily30):
        wd = WEEKDAY_NAMES[d["weekday"]]
        x = pad_l + idx * slot + (slot - bar_w) / 2
        late = d.get("late")
        if late is not None and late > 0:
            bh = plot_h * (late / max_v)
            y = base_y - bh
            tip = "%s（%s）\n实际下班 %s\n晚点 %.2f 小时（相对标准下班 %s）" % (
                d["date"], wd, d.get("checkout") or "--:--", late, std_end)
            parts.append('<rect x="%.1f" y="%.1f" width="%.1f" height="%.1f" fill="#ff922b">'
                         '<title>%s</title></rect>' % (x, y, bar_w, bh, tip))
        elif d.get("ot", 0) > 0 or d.get("extra", 0) > 0:
            # 有加班登记但无有效打卡
            parts.append('<rect x="%.1f" y="%.1f" width="%.1f" height="2" fill="#ced4da">'
                         '<title>%s（%s）：有加班登记，无下班打卡数据</title></rect>'
                         % (x, base_y - 2, bar_w, d["date"], wd))
        else:
            parts.append('<rect x="%.1f" y="%.1f" width="%.1f" height="2" fill="#e5e7eb">'
                         '<title>%s（%s）：无加班记录</title></rect>'
                         % (x, base_y - 2, bar_w, d["date"], wd))
        if idx % 5 == 0 or idx == n - 1:
            parts.append('<text x="%.1f" y="%d" font-size="10" fill="#6b7280" text-anchor="middle">%s</text>'
                         % (x + bar_w / 2, h - 8, d["date"][5:]))
    return ('<svg viewBox="0 0 %d %d" width="100%%" height="%d" role="img">%s</svg>'
            % (w, h, h, "".join(parts)))


def svg_weekday_chart(weekday_dist):
    """星期分布堆叠柱状图：周一~周日，加班（蓝）/ 加点（青）分段"""
    w, h = 860, 220
    pad_l, pad_b, pad_t = 40, 28, 12
    plot_w = w - pad_l - 8
    plot_h = h - pad_b - pad_t
    if not weekday_dist:
        return ""
    if isinstance(weekday_dist, dict):
        # stats["weekday_dist"] 为 {0..6: {...}}，按星期索引转为有序列表
        weekday_dist = [weekday_dist.get(i, {"ot": 0.0, "extra": 0.0}) for i in range(7)]
    segments = [("ot", "加班", "#4c6ef5"), ("extra", "加点", "#22b8cf")]

    def day_total(d):
        return sum(d.get(k, 0.0) for k, _label, _color in segments)

    max_v = max((day_total(d) for d in weekday_dist), default=0) or 1.0
    max_v = max(max_v, 1.0)
    n = len(weekday_dist)
    slot = plot_w / n
    bar_w = max(16.0, slot * 0.45)
    parts = []
    for i in range(1, 4):
        y = pad_t + plot_h - plot_h * i / 4.0
        parts.append('<line x1="%d" y1="%.1f" x2="%d" y2="%.1f" stroke="#e5e7eb" stroke-width="1"/>'
                     % (pad_l, y, w - 8, y))
        parts.append('<text x="%d" y="%.1f" font-size="10" fill="#9ca3af" text-anchor="end">%g</text>'
                     % (pad_l - 6, y + 3, max_v * i / 4.0))
    for idx, d in enumerate(weekday_dist):
        total = day_total(d)
        x = pad_l + idx * slot + (slot - bar_w) / 2
        if total > 0:
            tip_lines = [WEEKDAY_NAMES[idx]]
            for k, label, _color in segments:
                v = d.get(k, 0.0)
                if v > 0:
                    tip_lines.append("%s %.2f 小时" % (label, v))
            tip_lines.append("合计 %.2f 小时" % total)
            tip = "\n".join(tip_lines)
            y_cursor = pad_t + plot_h
            for k, _label, color in segments:
                v = d.get(k, 0.0)
                if v <= 0:
                    continue
                seg_h = plot_h * (v / max_v)
                y_cursor -= seg_h
                parts.append('<rect x="%.1f" y="%.1f" width="%.1f" height="%.1f" fill="%s">'
                             '<title>%s</title></rect>'
                             % (x, y_cursor, bar_w, seg_h, color, tip))
        else:
            parts.append('<rect x="%.1f" y="%.1f" width="%.1f" height="2" fill="#e5e7eb">'
                         '<title>%s：无记录</title></rect>'
                         % (x, pad_t + plot_h - 2, bar_w, WEEKDAY_NAMES[idx]))
        parts.append('<text x="%.1f" y="%d" font-size="10" fill="#6b7280" text-anchor="middle">%s</text>'
                     % (x + bar_w / 2, h - 8, WEEKDAY_NAMES[idx]))
    return ('<svg viewBox="0 0 %d %d" width="100%%" height="%d" role="img">%s</svg>'
            % (w, h, h, "".join(parts)))


# ---------------------------------------------------------------------------
# 仪表盘 HTML
# ---------------------------------------------------------------------------

_CSS = """
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:"Segoe UI","Microsoft YaHei",system-ui,sans-serif;background:#f3f4f6;color:#111827;padding:24px}
.wrap{max-width:960px;margin:0 auto}
h1{font-size:20px;margin-bottom:4px}
.sub{color:#6b7280;font-size:12px;margin-bottom:16px;line-height:1.8}
.status{font-size:12px;padding:6px 10px;border-radius:6px;margin-bottom:16px;line-height:1.7}
.status.ok{background:#ecfdf5;color:#047857}
.status.err{background:#fef2f2;color:#b91c1c}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:12px;margin-bottom:20px}
.card{background:#fff;border-radius:10px;padding:14px 16px;box-shadow:0 1px 2px rgba(0,0,0,.06)}
.card .label{font-size:12px;color:#6b7280}
.card .value{font-size:24px;font-weight:700;margin-top:4px}
.card .value small{font-size:12px;font-weight:400;color:#9ca3af}
.panel{background:#fff;border-radius:10px;padding:16px;box-shadow:0 1px 2px rgba(0,0,0,.06);margin-bottom:20px}
.panel h2{font-size:14px;margin-bottom:12px;color:#374151}
.legend{font-size:11px;color:#6b7280;margin-top:6px}
.legend i{display:inline-block;width:10px;height:10px;border-radius:2px;margin:0 4px 0 10px;vertical-align:-1px}
table{width:100%;border-collapse:collapse;font-size:12.5px}
th{color:#6b7280;font-weight:600;text-align:left;padding:6px 8px;border-bottom:1px solid #e5e7eb;white-space:nowrap}
td{padding:6px 8px;border-bottom:1px solid #f3f4f6;white-space:nowrap}
tr:hover td{background:#f9fafb}
.badge{display:inline-block;font-size:11px;padding:1px 8px;border-radius:99px;margin-right:4px}
.badge.ot{background:#eef2ff;color:#4c6ef5}
.badge.extra{background:#e0f7fa;color:#0e7490}
.badge.leave{background:#fef3c7;color:#b45309}
.badge.normal{background:#f3f4f6;color:#9ca3af}
.check-ok{color:#059669}.check-bad{color:#dc2626}
.note{font-size:11px;color:#9ca3af;margin-top:10px;line-height:1.8}
form.btn{display:inline-block}
button{background:#4c6ef5;color:#fff;border:0;border-radius:6px;padding:5px 14px;font-size:12px;cursor:pointer}
button:hover{background:#4263eb}
.empty{text-align:center;color:#9ca3af;padding:40px 0}
"""

_PAGE_SKELETON = """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta http-equiv="refresh" content="__REFRESH__">
<title>加班记录 · PC 监控</title>
<style>__CSS__</style></head>
<body><div class="wrap">__BODY__</div></body></html>"""


def _fmt_ts(ms):
    try:
        return datetime.fromtimestamp(int(ms) / 1000.0).strftime("%Y-%m-%d %H:%M:%S")
    except (TypeError, ValueError, OSError):
        return "未知"


def render_dashboard(snapshot, status):
    if snapshot is None:
        err = status.get("last_error")
        body = '<h1>加班记录 · PC 监控</h1>'
        if err:
            body += '<div class="status err">%s</div>' % html.escape(str(err))
        body += '<div class="panel empty">正在拉取云端数据，请稍候…（页面自动刷新）</div>'
        return (_PAGE_SKELETON
                .replace("__REFRESH__", "5")
                .replace("__CSS__", _CSS)
                .replace("__BODY__", body))

    stats = snapshot["stats"]
    meta = snapshot["meta"]
    backup = snapshot["backup"]

    # 标准下班时间（下班晚点图标题用）
    std_end = str((backup.get("settings") or {}).get("workEndTime") or "17:00").strip()
    if not re.match(r"^\d{1,2}:\d{2}$", std_end):
        std_end = "17:00"

    # 状态条
    if status.get("last_error"):
        status_html = '<div class="status err">最近一次拉取失败（%s）：%s<br>仍在展示上一次成功的数据。</div>' % (
            _fmt_ts(status.get("last_attempt_ms", 0) * 1000) if status.get("last_attempt_ms") else "未知时间",
            html.escape(str(status["last_error"])),
        )
    else:
        status_html = '<div class="status ok">已连接 WebDAV，每 %d 分钟自动拉取；页面每 60 秒自动刷新。</div>' % status.get("interval_min", 5)

    # 元信息
    checksum = snapshot.get("checksum")
    if checksum == "ok":
        check_html = '<span class="check-ok">完整性校验通过</span>'
    elif checksum == "mismatch":
        check_html = '<span class="check-bad">checksum 不匹配（文件可能被篡改/损坏，仅提示）</span>'
    else:
        check_html = "无校验值"
    meta_line = (
        "备份生成时间：<b>%s</b> ｜ 来源设备：%s ｜ App 版本：%s ｜ 记录：%d 条（含 %d 条已删除） ｜ %s ｜ 云端格式：%s ｜ 云端文件修改时间：%s"
        % (
            _fmt_ts(backup.get("exportTime")),
            html.escape(str(backup.get("deviceName") or "未知")),
            html.escape(str(backup.get("appVersionName") or "未知")),
            stats["record_count_total"], stats["deleted_count"],
            check_html,
            meta.get("format", "?"),
            html.escape(snapshot.get("cloud_last_modified") or "未知"),
        )
    )

    cards = "".join([
        _card("本月加班（%s）" % stats["month_key"], "%g" % stats["month_ot"], "小时"),
        _card("本月加点", "%g" % stats["month_extra"], "小时"),
        _card("本月合计", "%g" % stats["month_hours"], "加班+加点 小时"),
        _card("本月预估加班费", "¥%.2f" % stats["month_pay"], "时薪 ¥%g" % stats["hourly_wage"]),
        _card("本年累计", "%g" % stats["year_hours"], "小时 · 预估 ¥%.2f" % stats["year_pay"]),
        _card("本月请假", "%d 天" % stats["month_leave_days"], "%.2f 小时" % stats["month_leave_hours"]),
        _card("本月加班次数", "%d 次" % stats["month_count"], "平均每次 %g 小时" % stats["month_avg"]),
        _mom_card(stats),
        _card("当前连续加班", "%d 天" % stats["streak_days"], "截至最近一个登记日"),
        _card("本月 22 点后下班", "%d 次" % stats["late_nights"],
              ("最晚 %s" % stats["latest_checkout"]) if stats["latest_checkout"] else "未登记上下班时间"),
        _card("近 12 月月均", "%g" % stats["month_avg_12"], "小时/月"),
    ])

    refresh_form = (
        '<form class="btn" method="post" action="/api/refresh">'
        '<button type="submit">立即拉取云端</button></form>'
    )

    body = "".join([
        '<h1>加班记录 · PC 监控 <form class="btn" style="float:right" method="post" action="/api/refresh"><button>%s</button></form></h1>' % "立即拉取云端",
        '<div class="sub">%s</div>' % meta_line,
        status_html,
        '<div class="cards">%s</div>' % cards,
        _panel("近 30 天每日时长（加班 / 加点 / 请假 分开堆叠）", svg_daily_chart(stats["daily30"]),
               '<div class="legend">加班<i style="background:#4c6ef5"></i>加点<i style="background:#22b8cf"></i>'
               '请假<i style="background:#f59e0b"></i>悬停柱体查看分类明细</div>'),
        _panel("近 12 个月汇总（加班 / 加点 分开堆叠）", svg_monthly_chart(stats["monthly12"]),
               '<div class="legend">加班<i style="background:#4c6ef5"></i>加点<i style="background:#22b8cf"></i>'
               '悬停查看分类明细与预估加班费</div>'),
        _panel("近 30 天下班晚点（标准下班 %s）" % std_end, svg_late_chart(stats["daily30"], std_end),
               '<div class="legend">晚点时长<i style="background:#ff922b"></i>'
               '灰条=有加班但无打卡，浅灰=无记录，虚线=标准下班时间'
               + ('<br>提示：当前备份未包含上下班时间数据，暂无法统计晚点——在 App 中登记上下班时间并同步后自动可用。</div>'
                  if stats["latest_checkout"] is None else '</div>')),
        _panel("加班星期分布（全部记录）", svg_weekday_chart(stats["weekday_dist"]),
               '<div class="legend">加班<i style="background:#4c6ef5"></i>加点<i style="background:#22b8cf"></i>'
               '悬停查看分类明细</div>'),
        _leave_panel(stats["month_leave_by_type"]),
        _recent_table(stats["recent"]),
        '<div class="note">'
        '口径说明：加班时长 = 登记加班 + 登记加点（与 App 一致，未登记记 0）；预估加班费按备份内设置的倍率计算，'
        '登记"加班"按周末 %.1f 倍估算（法定节假日 3 倍场景以 App 为准），"加点"按 %.1f 倍，自定义倍率记录单独覆盖。'
        '次数 = 有加班/加点登记的天数；连续加班 = 截至最近一个登记日的连续天数；'
        '下班晚点 = 打卡下班时间 - 标准下班时间（仅统计有打卡且登记了加班/加点的记录），时间取自本机时区。'
        '数据仅读自 WebDAV 云端备份，本工具不会上传/修改任何数据。'
        '</div>' % (2.0, 1.5),
    ])

    return (_PAGE_SKELETON
            .replace("__REFRESH__", "60")
            .replace("__CSS__", _CSS)
            .replace("__BODY__", body))


def _card(label, value, small):
    return ('<div class="card"><div class="label">%s</div>'
            '<div class="value">%s <small>%s</small></div></div>'
            % (html.escape(label), value, html.escape(small)))


def _mom_card(stats):
    """环比上月卡片：加班时长变化百分比"""
    mom_pct = stats["mom_pct"]
    if mom_pct is None:
        value = "持平" if stats["month_hours"] == 0 else "—"
        small = "上月无加班记录"
    elif mom_pct == 0:
        value = "持平"
        small = "上月 %g 小时" % stats["prev_month_hours"]
    else:
        value = ("+%d%%" if mom_pct > 0 else "%d%%") % mom_pct
        small = "上月 %g 小时" % stats["prev_month_hours"]
    return _card("时长环比上月", value, small)


def _leave_panel(leave_by_type):
    """本月请假类型分布面板"""
    if not leave_by_type:
        return '<div class="panel"><h2>本月请假明细（按类型）</h2><div class="empty">本月无请假记录</div></div>'
    rows = "".join(
        "<tr><td>%s</td><td>%d 次</td><td>%.2f 小时</td></tr>"
        % (html.escape(t), v["days"], v["hours"])
        for t, v in sorted(leave_by_type.items(), key=lambda kv: -kv[1]["hours"])
    )
    return ('<div class="panel"><h2>本月请假明细（按类型）</h2>'
            '<table><thead><tr><th>类型</th><th>次数</th><th>时长</th></tr></thead>'
            '<tbody>%s</tbody></table></div>' % rows)


def _panel(title, svg, legend=""):
    inner = svg or '<div class="empty">暂无数据</div>'
    return '<div class="panel"><h2>%s</h2>%s%s</div>' % (html.escape(title), inner, legend)


def _recent_table(recent):
    if not recent:
        return '<div class="panel"><h2>最近记录</h2><div class="empty">暂无记录</div></div>'
    rows = []
    for r in recent:
        badges = "".join(
            '<span class="badge %s">%s</span>' % (r["cls"], html.escape(label))
            for label in r["labels"]
        )
        cells = [
            "<b>%s</b> %s" % (html.escape(r["date"]), html.escape(r["weekday"])),
            badges,
            html.escape(r["time"]),
            ("%g 小时" % r["ot"]) if r["ot"] else "—",
            ("%g 小时" % r["extra"]) if r["extra"] else "—",
            ("%.2f 小时 %s" % (r["leave_hours"], html.escape(r["leave_type"]))) if r["leave_hours"] is not None else "—",
            ("¥%.2f" % r["pay"]) if r["pay"] is not None else "—",
            html.escape(r["custom_rate"]),
            html.escape(r["note"]),
            html.escape(r["device"]),
        ]
        rows.append("<tr>%s</tr>" % "".join("<td>%s</td>" % c for c in cells))
    head = "".join("<th>%s</th>" % h for h in
                   ["日期", "类型", "上下班", "加班", "加点", "请假", "预估加班费", "倍率", "备注", "来源设备"])
    return ('<div class="panel"><h2>最近记录（最多 60 条，按日期倒序）</h2>'
            '<div style="overflow-x:auto"><table><thead><tr>%s</tr></thead><tbody>%s</tbody></table></div></div>'
            % (head, "".join(rows)))


# ---------------------------------------------------------------------------
# 轮询与状态
# ---------------------------------------------------------------------------

class MonitorState:
    def __init__(self, cfg):
        self.cfg = cfg
        self.lock = threading.Lock()
        self.snapshot = None
        self.last_error = None
        self.last_attempt_ms = None
        self.last_ok_ms = None
        self.version = 0
        self.refresh_event = threading.Event()
        self.stop_event = threading.Event()

    def do_poll(self):
        with self.lock:
            self.last_attempt_ms = time.time()
        try:
            raw, last_modified, _url = fetch_backup_raw(self.cfg)
            backup, meta = parse_wire(
                raw,
                password=(self.cfg.get("sync_password") or "").strip() or None,
                recovery_code=(self.cfg.get("recovery_code") or "").strip() or None,
            )
            if not isinstance(backup, dict) or "attendanceRecords" not in backup:
                raise ValueError("云端内容不是有效的备份数据（缺少 attendanceRecords）")
            snap = {
                "fetched_at_ms": time.time() * 1000,
                "backup": backup,
                "meta": meta,
                "stats": compute_stats(backup),
                "checksum": verify_checksum(backup),
                "cloud_last_modified": last_modified,
            }
            with self.lock:
                self.snapshot = snap
                self.last_error = None
                self.last_ok_ms = time.time()
                self.version += 1
            print("[%s] 拉取成功：%d 条记录（格式 %s）"
                  % (datetime.now().strftime("%H:%M:%S"),
                     len(backup.get("attendanceRecords") or []), meta.get("format")))
        except Exception as e:
            with self.lock:
                self.last_error = str(e)
                self.version += 1
            print("[%s] 拉取失败：%s" % (datetime.now().strftime("%H:%M:%S"), e))

    def poll_loop(self):
        interval = self.cfg["poll_interval_minutes"] * 60
        self.do_poll()  # 启动立即拉取
        while not self.stop_event.is_set():
            triggered = self.refresh_event.wait(timeout=interval)
            self.refresh_event.clear()
            if self.stop_event.is_set():
                break
            if triggered:
                # 短时间内的连续点击只处理一次
                time.sleep(0.5)
            self.do_poll()

    def get_status(self):
        with self.lock:
            return {
                "interval_min": self.cfg["poll_interval_minutes"],
                "last_error": self.last_error,
                "last_attempt_ms": self.last_attempt_ms,
                "last_ok_ms": self.last_ok_ms,
                "version": self.version,
            }


def make_handler(state):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            pass  # 静默访问日志

        def _send(self, code, content_type, body_bytes):
            self.send_response(code)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body_bytes)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body_bytes)

        def do_GET(self):
            if self.path in ("/", "/index.html"):
                status = state.get_status()
                with state.lock:
                    snap = state.snapshot
                page = render_dashboard(snap, status)
                self._send(200, "text/html; charset=utf-8", page.encode("utf-8"))
            elif self.path == "/api/data":
                status = state.get_status()
                with state.lock:
                    snap = state.snapshot
                if snap is None:
                    payload = {"ok": False, "status": status}
                else:
                    payload = {
                        "ok": True,
                        "status": status,
                        "fetched_at_ms": snap["fetched_at_ms"],
                        "meta": snap["meta"],
                        "checksum": snap["checksum"],
                        "backup": {
                            "version": snap["backup"].get("version"),
                            "exportTime": snap["backup"].get("exportTime"),
                            "deviceName": snap["backup"].get("deviceName"),
                            "appVersionName": snap["backup"].get("appVersionName"),
                            "recordCount": snap["backup"].get("recordCount"),
                        },
                        "stats": snap["stats"],
                    }
                self._send(200, "application/json; charset=utf-8",
                           json.dumps(payload, ensure_ascii=False).encode("utf-8"))
            else:
                self._send(404, "text/plain; charset=utf-8", b"not found")

        def do_POST(self):
            if self.path == "/api/refresh":
                before = state.get_status()["version"]
                state.refresh_event.set()
                deadline = time.time() + 30
                while time.time() < deadline:
                    if state.get_status()["version"] > before and state.get_status()["last_error"] is None:
                        break
                    if state.get_status()["version"] > before + 1:
                        break
                    time.sleep(0.3)
                self.send_response(302)
                self.send_header("Location", "/")
                self.send_header("Content-Length", "0")
                self.end_headers()
            else:
                self._send(404, "text/plain; charset=utf-8", b"not found")

    return Handler


# ---------------------------------------------------------------------------
# 自测：验证解密与统计逻辑（python overtime_monitor.py --selftest）
# ---------------------------------------------------------------------------

def _selftest():
    import tempfile
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    results = []

    def check(name, fn):
        try:
            fn()
            results.append((name, True, ""))
        except Exception as e:
            results.append((name, False, str(e)))

    ts_in = int(datetime(2026, 10, 17, 9, 0).timestamp() * 1000)
    ts_out = int(datetime(2026, 10, 17, 21, 30).timestamp() * 1000)
    sample_record = {
        "date": "2026-10-17",
        "checkInTime": "09:00", "checkOutTime": "21:30",
        "checkInTimestamp": ts_in, "checkOutTimestamp": ts_out,
        "note": "版本发布",
        "manualOvertimeHours": 4.0, "manualExtraHours": 1.5,
        "createdAt": ts_in, "modifiedAt": ts_out,
        "isLeave": False, "leaveType": None, "leaveHours": 0.0,
        "customRate": None, "isDeleted": False, "lastModifiedBy": "Xiaomi 14",
    }
    sample_backup = {
        "version": 3, "exportTime": 1760713800000,
        "settings": {"baseSalary": 8000.0, "dailyWorkHours": 8.0},
        "attendanceRecords": [sample_record],
        "recordCount": 1, "deviceName": "Xiaomi 14",
        "checksum": hashlib.sha256(
            ("[" + gson_like_record(sample_record) + "]").encode("utf-8")
        ).hexdigest(),
    }

    def t_plain():
        raw = json.dumps(sample_backup, ensure_ascii=False)
        backup, meta = parse_wire(raw)
        assert meta["format"] == "plain" and backup["recordCount"] == 1

    def t_gson_shape():
        s = gson_like_record(sample_record)
        assert s.startswith('{"date":"2026-10-17","checkInTime":"09:00"'), s[:60]
        assert '"manualOvertimeHours":4.0' in s, s
        assert '"customRate"' not in s and '"leaveType"' not in s, s
        assert '"isDeleted":false' in s, s
        h = _json_string("a<b&c=d'e>f")
        assert h == '"a\\u003cb\\u0026c\\u003dd\\u0027e\\u003ef"', h

    def t_checksum():
        assert verify_checksum(sample_backup) == "ok"
        bad = dict(sample_backup); bad["checksum"] = "00" * 32
        assert verify_checksum(bad) == "mismatch"

    def make_envelope(password, code, plaintext):
        dek = os.urandom(32)

        def wrap(secret):
            salt, iv = os.urandom(16), os.urandom(12)
            kek = hashlib.pbkdf2_hmac("sha256", secret.encode("utf-8"), salt, PBKDF2_ITERATIONS, dklen=32)
            return salt + iv + AESGCM(kek).encrypt(iv, dek, None)

        payload_iv = os.urandom(12)
        blob = (ENVELOPE_MAGIC + bytes([ENVELOPE_VERSION])
                + wrap(password) + wrap(normalize_recovery_code(code))
                + payload_iv + AESGCM(dek).encrypt(payload_iv, plaintext, None))
        return WIRE_PREFIX + base64.b64encode(blob).decode()

    def t_envelope_password():
        pt = json.dumps(sample_backup, ensure_ascii=False).encode("utf-8")
        wire = make_envelope("p@ss1234", "ABCD-EFGH-IJKL-MNOP", pt)
        backup, meta = parse_wire(wire, password="p@ss1234")
        assert meta["format"] == "envelope" and meta["key_source"] == "sync_password"
        assert backup["recordCount"] == 1

    def t_envelope_recovery():
        pt = json.dumps(sample_backup, ensure_ascii=False).encode("utf-8")
        wire = make_envelope("p@ss1234", "ABCD-EFGH-IJKL-MNOP", pt)
        # 故意用小写/带空格的写法，验证 normalize
        backup, meta = parse_wire(wire, recovery_code="abcd efgh ijkl mnop")
        assert meta["key_source"] == "recovery_code"
        assert backup["recordCount"] == 1

    def t_envelope_wrong_key():
        pt = json.dumps(sample_backup, ensure_ascii=False).encode("utf-8")
        wire = make_envelope("p@ss1234", "ABCD-EFGH-IJKL-MNOP", pt)
        try:
            parse_wire(wire, password="wrong")
            raise AssertionError("错误密码竟解密成功")
        except NeedKeyError:
            pass

    def _legacy(name, magic, iterations, cipher):
        salt = os.urandom(16)
        pt = json.dumps(sample_backup, ensure_ascii=False).encode("utf-8")
        if cipher == "gcm":
            iv = os.urandom(12)
            key = hashlib.pbkdf2_hmac("sha256", b"legacy-pwd", salt, iterations, dklen=32)
            body = AESGCM(key).encrypt(iv, pt, None)
            blob = magic + salt + iv + body
        else:
            iv = os.urandom(16)
            key = hashlib.pbkdf2_hmac("sha256", b"legacy-pwd", salt, iterations, dklen=32)
            pad = 16 - len(pt) % 16
            padded = pt + bytes([pad]) * pad
            enc = Cipher(algorithms.AES(key), modes.CBC(iv)).encryptor()
            body = enc.update(padded) + enc.finalize()
            blob = salt + iv + body
        wire = base64.b64encode(blob).decode()
        out, meta = parse_wire(wire, password="legacy-pwd")
        assert out["recordCount"] == 1 and meta["format"].startswith("legacy")

    check("明文 JSON 解析", t_plain)
    check("Gson 序列化复刻", t_gson_shape)
    check("checksum 校验", t_checksum)
    check("信封解密（同步密码）", t_envelope_password)
    check("信封解密（恢复码+归一化）", t_envelope_recovery)
    check("信封错误密码拒绝", t_envelope_wrong_key)
    check("旧版 G2 (AES-GCM 600k)", lambda: _legacy("g2", GCM_MAGIC, PBKDF2_ITERATIONS, "gcm"))
    check("旧版 GM (AES-GCM 65k)", lambda: _legacy("gm", GCM_MAGIC_LEGACY, PBKDF2_ITERATIONS_LEGACY, "gcm"))
    check("旧版 CBC (PKCS7 65k)", lambda: _legacy("cbc", b"", PBKDF2_ITERATIONS_LEGACY, "cbc"))

    def t_stats():
        backup = json.loads(json.dumps(sample_backup, ensure_ascii=False))
        backup["attendanceRecords"] = [
            sample_record,
            # 仅字符串下班时间（无打卡时间戳），覆盖 _resolve_checkout_dt 回退路径
            dict(sample_record, date="2026-10-16", checkInTime="09:00", checkOutTime="22:30",
                 checkInTimestamp=None, checkOutTimestamp=None,
                 manualOvertimeHours=-1.0, manualExtraHours=2.0, note=""),
            dict(sample_record, date="2026-10-18", isLeave=True, leaveType="年假",
                 leaveHours=8.0, manualOvertimeHours=-1.0, manualExtraHours=-1.0,
                 checkInTime=None, checkOutTime=None, checkInTimestamp=None, checkOutTimestamp=None, note=""),
            dict(sample_record, date="2026-10-19", isDeleted=True),
        ]
        now = datetime(2026, 10, 20, 12, 0, 0)
        st = compute_stats(backup, now=now)
        assert st["month_hours"] == 7.5, st["month_hours"]        # 4 + 1.5 + 2
        assert st["month_ot"] == 4.0 and st["month_extra"] == 3.5
        assert st["month_leave_days"] == 1 and st["month_leave_hours"] == 8.0
        assert st["deleted_count"] == 1 and st["record_count_active"] == 3
        hourly = 8000.0 / (21.75 * 8.0)
        expect_pay = 4.0 * hourly * 2.0 + 3.5 * hourly * 1.5
        assert abs(st["month_pay"] - expect_pay) < 0.01, (st["month_pay"], expect_pay)
        assert len(st["daily30"]) == 30 and len(st["monthly12"]) == 12
        # 新维度：次数/均值
        assert st["month_count"] == 2 and st["month_avg"] == 3.75, (st["month_count"], st["month_avg"])
        # 连续加班：10-16、10-17 连续 → 2 天
        assert st["streak_days"] == 2, st["streak_days"]
        # 22 点后下班：仅 10-16 22:30；最晚下班 22:30
        assert st["late_nights"] == 1 and st["latest_checkout"] == "22:30", (st["late_nights"], st["latest_checkout"])
        # 下班晚点：10-16 = 22:30-17:00 = 5.5；10-17 = 21:30-17:00 = 4.5
        d16 = next(d for d in st["daily30"] if d["date"] == "2026-10-16")
        d17 = next(d for d in st["daily30"] if d["date"] == "2026-10-17")
        assert abs(d16["late"] - 5.5) < 0.01 and d16["checkout"] == "22:30", d16
        assert abs(d17["late"] - 4.5) < 0.01, d17
        # 星期分布：2026-10-16 周五 extra 2h；2026-10-17 周六 ot 4h + extra 1.5h
        assert st["weekday_dist"][4]["extra"] == 2.0, st["weekday_dist"][4]
        assert st["weekday_dist"][5]["ot"] == 4.0 and st["weekday_dist"][5]["extra"] == 1.5
        # 请假类型分布
        assert st["month_leave_by_type"] == {"年假": {"days": 1, "hours": 8.0}}
        # 环比：上月无数据 → None；月均 7.5/12
        assert st["mom_pct"] is None and st["prev_month_hours"] == 0.0
        assert abs(st["month_avg_12"] - 7.5 / 12) < 0.01, st["month_avg_12"]

    check("统计口径（时长/请假/加班费/删除）", t_stats)

    def t_stats_custom_rate():
        backup = json.loads(json.dumps(sample_backup, ensure_ascii=False))
        backup["attendanceRecords"] = [dict(sample_record, customRate=3.0)]
        now = datetime(2026, 10, 20, 12, 0, 0)
        st = compute_stats(backup, now=now)
        hourly = 8000.0 / (21.75 * 8.0)
        expect = 5.5 * hourly * 3.0
        assert abs(st["month_pay"] - expect) < 0.01, (st["month_pay"], expect)

    check("统计口径（自定义倍率覆盖）", t_stats_custom_rate)

    ok = all(r[1] for r in results)
    for name, passed, err in results:
        print("  [%s] %s%s" % ("PASS" if passed else "FAIL", name, ("  -> " + err) if err else ""))
    print("\n自测结果：%s" % ("全部通过" if ok else "存在失败"))
    return 0 if ok else 1


# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------

def main():
    if sys.platform == "win32":
        try:
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass

    parser = argparse.ArgumentParser(description="PersonalOvertimeRecord PC 端监控")
    parser.add_argument("--config", default=os.path.join(script_dir(), "config.json"))
    parser.add_argument("--port", type=int, default=None)
    parser.add_argument("--no-browser", action="store_true")
    parser.add_argument("--selftest", action="store_true", help="运行本地解密/统计自测")
    args = parser.parse_args()

    if args.selftest:
        return _selftest()

    cfg = load_config(args.config)
    if args.port:
        cfg["port"] = args.port

    state = MonitorState(cfg)
    t = threading.Thread(target=state.poll_loop, daemon=True)
    t.start()

    server = ThreadingHTTPServer(("127.0.0.1", cfg["port"]), make_handler(state))
    url = "http://127.0.0.1:%d" % cfg["port"]
    print("PersonalOvertimeRecord PC 监控已启动：%s" % url)
    print("云端：%s（每 %d 分钟自动拉取）" % (build_url(cfg["server_url"], cfg.get("remote_path") or "/"), cfg["poll_interval_minutes"]))
    print("按 Ctrl+C 退出。")
    if not args.no_browser:
        threading.Timer(1.2, lambda: webbrowser.open(url)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已退出。")
        state.stop_event.set()
    return 0


if __name__ == "__main__":
    sys.exit(main())
