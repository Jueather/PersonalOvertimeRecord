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
     然后浏览器打开 http://127.0.0.1:8518 （页面每 60 秒局部刷新，明细可筛选/排序，支持导出 CSV）。

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
import csv
import gzip
import hashlib
import html
import io
import json
import os
import re
import socket
import subprocess
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
GZIP_WIRE_PREFIX = "PORE_GZ1:"             # BackupCompression.WIRE_PREFIX（未加密压缩备份）
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
    # 月度目标（0 = 不显示进度卡）
    "month_target_hours": 0,
    "month_target_pay": 0,
    # 拉取失败时弹 Windows 通知（默认开启）
    "notify_on_error": True,
}

WEEKDAY_NAMES = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]

# 日期类型（与 App HolidayManager.getDayType / OvertimeCalculator.effectiveDayType 对齐）
DAY_HOLIDAY = "holiday"     # 法定节假日（3 倍）
DAY_WEEKEND = "weekend"     # 周末（2 倍）
DAY_WORKDAY = "workday"     # 工作日 / 调休补班日（1.5 倍）

DAY_TYPE_NAMES = {
    DAY_HOLIDAY: "法定节假日",
    DAY_WEEKEND: "周末",
    DAY_WORKDAY: "工作日",
}

# 法定节假日（逐条对齐 App HolidayManager.kt，2024-2028）
_HOLIDAY_RANGES = [
    ("元旦", "2024-01-01", "2024-01-01"),
    ("春节", "2024-02-10", "2024-02-17"),
    ("清明节", "2024-04-04", "2024-04-06"),
    ("劳动节", "2024-05-01", "2024-05-05"),
    ("端午节", "2024-06-10", "2024-06-10"),
    ("中秋节", "2024-09-15", "2024-09-17"),
    ("国庆节", "2024-10-01", "2024-10-07"),
    ("元旦", "2025-01-01", "2025-01-01"),
    ("春节", "2025-01-28", "2025-02-04"),
    ("清明节", "2025-04-04", "2025-04-06"),
    ("劳动节", "2025-05-01", "2025-05-05"),
    ("端午节", "2025-05-31", "2025-06-02"),
    ("中秋节", "2025-09-29", "2025-09-30"),
    ("国庆节", "2025-10-01", "2025-10-08"),
    ("元旦", "2026-01-01", "2026-01-03"),
    ("春节", "2026-02-15", "2026-02-23"),
    ("清明节", "2026-04-04", "2026-04-06"),
    ("劳动节", "2026-05-01", "2026-05-05"),
    ("端午节", "2026-06-19", "2026-06-21"),
    ("中秋节", "2026-09-25", "2026-09-27"),
    ("国庆节", "2026-10-01", "2026-10-07"),
    ("元旦", "2027-01-01", "2027-01-01"),
    ("春节", "2027-02-05", "2027-02-12"),
    ("清明节", "2027-04-03", "2027-04-06"),
    ("劳动节", "2027-05-01", "2027-05-05"),
    ("端午节", "2027-06-10", "2027-06-12"),
    ("中秋节", "2027-09-15", "2027-09-17"),
    ("国庆节", "2027-10-01", "2027-10-07"),
    ("元旦", "2028-01-01", "2028-01-01"),
    ("春节", "2028-01-25", "2028-02-01"),
    ("清明节", "2028-04-04", "2028-04-07"),
    ("劳动节", "2028-05-01", "2028-05-05"),
    ("端午节", "2028-05-29", "2028-05-31"),
    ("国庆节", "2028-10-01", "2028-10-08"),
]

# 调休补班日（仅用于日历类型标记：该日按工作日展示；登记加班时仍按 App 口径以周末倍率计）
MAKEUP_WORKDAYS = frozenset({
    "2024-02-04", "2024-02-18", "2024-04-07", "2024-04-28",
    "2024-05-11", "2024-09-14", "2024-09-29", "2024-10-12",
    "2025-01-26", "2025-02-08", "2025-04-27", "2025-09-28", "2025-10-11",
    "2026-01-04", "2026-02-14", "2026-02-28",
    "2026-05-09", "2026-09-20", "2026-10-10",
})

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


def _aesgcm_encrypt(key, iv, data):
    _require_crypto()
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    return AESGCM(key).encrypt(iv, data, None)


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


def open_envelope(wire, password=None, recovery_code=None, want_ctx=False):
    """解开信封，返回 (plaintext_bytes, source)；want_ctx=True 时额外返回重封上下文
    （保留原密码槽/恢复码槽，只换 payload IV 与正文，避免恢复码失效）"""
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
    if want_ctx:
        return plaintext, source, {"kind": "envelope", "dek": dek,
                                   "header": blob[:5 + SLOT_SIZE * 2]}
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


def _maybe_gunzip(data):
    """新版本信封正文是 gzip(JSON)，旧版本是原始 JSON；按 magic 兼容两种格式"""
    return gzip.decompress(data) if data[:2] == b"\x1f\x8b" else data


def parse_wire(raw, password=None, recovery_code=None, want_ctx=False):
    """
    按 App resolveCloud 的顺序解析云端内容。
    返回 (backup_dict, source_meta[, seal_ctx])；无法识别/密钥不符时抛异常。
    seal_ctx 仅用于把修改后的数据按原格式加密写回云端。
    """
    trimmed = raw.lstrip()
    if trimmed.startswith(GZIP_WIRE_PREFIX):
        try:
            raw = gzip.decompress(_b64_decode_flexible(trimmed[len(GZIP_WIRE_PREFIX):])).decode("utf-8")
        except Exception:
            raise NeedKeyError("压缩备份解包失败（文件可能已损坏）")
        trimmed = raw.lstrip()
    if trimmed.startswith("{") or trimmed.startswith("["):
        backup = json.loads(raw)
        meta = {"format": "plain", "decrypted": False, "key_source": None}
        return (backup, meta, {"kind": "plain"} if want_ctx else None) if want_ctx else (backup, meta)

    if trimmed.startswith(WIRE_PREFIX):
        if want_ctx:
            plaintext, source, ctx = open_envelope(trimmed, password, recovery_code, want_ctx=True)
            backup = json.loads(_maybe_gunzip(plaintext).decode("utf-8"))
            return backup, {"format": "envelope", "decrypted": True, "key_source": source}, ctx
        plaintext, source = open_envelope(trimmed, password, recovery_code)
        backup = json.loads(_maybe_gunzip(plaintext).decode("utf-8"))
        return backup, {"format": "envelope", "decrypted": True, "key_source": source}

    if not password:
        raise NeedKeyError("云端数据是加密的旧版格式，但未配置 sync_password")
    plaintext, fmt = decrypt_legacy(trimmed, password)
    backup = json.loads(plaintext.decode("utf-8"))
    meta = {"format": fmt, "decrypted": True, "key_source": "sync_password"}
    if want_ctx:
        ctx = {"kind": "legacy_gcm" if fmt.startswith("legacy_G") else "legacy_cbc",
               "password": password}
        return backup, meta, ctx
    return backup, meta


def seal_wire(ctx, backup, password=None):
    """把修改后的备份按原格式加密，返回可直接 PUT 的文本"""
    kind = ctx.get("kind")
    payload = json.dumps(backup, ensure_ascii=False).encode("utf-8")
    if kind == "plain":
        return json.dumps(backup, ensure_ascii=False)
    if kind == "envelope":
        iv = os.urandom(GCM_IV_SIZE)
        # 与 App 一致：正文先 gzip 再加密
        blob = ctx["header"] + iv + _aesgcm_encrypt(ctx["dek"], iv, gzip.compress(payload))
        return WIRE_PREFIX + base64.b64encode(blob).decode("ascii")
    if kind == "legacy_gcm":
        secret = password or ctx.get("password") or ""
        if not secret:
            raise ValueError("旧版加密备份写回需要同步密码")
        salt = os.urandom(SLOT_SALT_SIZE)
        iv = os.urandom(GCM_IV_SIZE)
        key = derive_key(secret, salt, PBKDF2_ITERATIONS)
        return base64.b64encode(GCM_MAGIC + salt + iv + _aesgcm_encrypt(key, iv, payload)).decode("ascii")
    raise ValueError("旧版 AES-CBC 备份不支持从桌面端写入，请先在 App 中同步升级为信封加密")


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


def fetch_backup_snapshot(cfg):
    """下载云端备份：返回 (raw_text, last_modified, etag, url)"""
    url = build_url(cfg["server_url"], cfg.get("remote_path") or "/", BACKUP_FILENAME)
    status, headers, content = webdav_request("GET", url, cfg.get("username"), cfg.get("password"))
    if status < 200 or status >= 300:
        if status == 404:
            raise IOError("云端没有备份文件（404）：%s" % url)
        if status == 401:
            raise IOError("WebDAV 认证失败（401），请检查账号/密码")
        raise IOError("下载失败，HTTP %d：%s" % (status, url))
    last_modified = headers.get("Last-Modified") or headers.get("last-modified") or ""
    etag = headers.get("ETag") or headers.get("etag") or ""
    return content.decode("utf-8"), last_modified, etag, url


def fetch_backup_raw(cfg):
    raw, last_modified, _etag, url = fetch_backup_snapshot(cfg)
    return raw, last_modified, url


class WriteConflictError(RuntimeError):
    """云端文件在读取后被其他设备更新（ETag/If-Unmodified-Since 不匹配）"""


def upload_backup_raw(cfg, wire_text, etag="", last_modified=""):
    """条件写入云端备份：带 If-Match（或 If-Unmodified-Since）避免覆盖并发修改"""
    url = build_url(cfg["server_url"], cfg.get("remote_path") or "/", BACKUP_FILENAME)
    headers = {"Content-Type": "application/octet-stream; charset=utf-8"}
    if etag:
        headers["If-Match"] = etag
    elif last_modified:
        headers["If-Unmodified-Since"] = last_modified
    status, resp_headers, content = webdav_request(
        "PUT", url, cfg.get("username"), cfg.get("password"),
        body=wire_text.encode("utf-8"), headers=headers)
    if status == 412:
        raise WriteConflictError("云端备份在保存期间被其他设备更新，请重试")
    if status not in (200, 201, 204):
        detail = content[:200].decode("utf-8", "ignore")
        raise IOError("上传失败，HTTP %d：%s" % (status, detail or url))
    return resp_headers


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


def _build_holidays():
    out = {}
    for name, start, end in _HOLIDAY_RANGES:
        d0, d1 = _parse_date(start), _parse_date(end)
        while d0 is not None and d1 is not None and d0 <= d1:
            out[d0.strftime("%Y-%m-%d")] = name
            d0 += timedelta(days=1)
    return out


HOLIDAYS = _build_holidays()


def get_holiday_name(date_s):
    """法定节假日名称，非节假日返回 None"""
    return HOLIDAYS.get(date_s)


def is_makeup_workday(date_s):
    """是否调休补班日（周末上班）"""
    return date_s in MAKEUP_WORKDAYS


def calendar_day_type(date_s, d=None):
    """日历日期类型：法定节假日 > 调休补班(工作日) > 周末 > 工作日"""
    d = d or _parse_date(date_s)
    if d is None:
        return DAY_WORKDAY
    if date_s in HOLIDAYS:
        return DAY_HOLIDAY
    if date_s in MAKEUP_WORKDAYS:
        return DAY_WORKDAY
    return DAY_WEEKEND if d.weekday() >= 5 else DAY_WORKDAY


def effective_day_type(date_s, ot, extra, d=None):
    """有效日期类型（与 App OvertimeCalculator.effectiveDayType 完全一致）：
    登记了加班 → 节假日按节假日，其余一律按周末；
    只登记了加点 → 按工作日；两者都未登记 → 按日历。
    """
    cal = calendar_day_type(date_s, d)
    if ot > 0:
        return DAY_HOLIDAY if cal == DAY_HOLIDAY else DAY_WEEKEND
    if extra > 0:
        return DAY_WORKDAY
    return cal


def compute_record_pay(date_s, ot, extra, hourly, rates, custom_rate=None, d=None):
    """单条记录预估加班费（对齐 OvertimeCalculator.calculateOvertimePay）。

    rates = (工作日倍率, 周末倍率, 节假日倍率)；customRate >= 0 时整条按自定义倍率。
    返回 (pay, day_type, rate_text)。
    """
    rate_normal, rate_weekend, rate_holiday = rates
    if custom_rate is not None and custom_rate >= 0:
        pay = (ot + extra) * hourly * custom_rate
        return pay, effective_day_type(date_s, ot, extra, d), "×%g（自定义）" % custom_rate

    day_type = effective_day_type(date_s, ot, extra, d)
    type_rate = {
        DAY_HOLIDAY: rate_holiday,
        DAY_WEEKEND: rate_weekend,
        DAY_WORKDAY: rate_normal,
    }[day_type]
    pay = ot * hourly * type_rate + extra * hourly * rate_normal
    if ot > 0 and extra > 0:
        rate_text = "%g× + %g×" % (type_rate, rate_normal)
    elif ot > 0:
        rate_text = "%g×（%s）" % (type_rate, DAY_TYPE_NAMES[day_type])
    elif extra > 0:
        rate_text = "%g×（工作日加点）" % rate_normal
    else:
        rate_text = "—"
    return pay, day_type, rate_text


def _record_view(r, hourly, rates):
    """单条记录的展示视图（明细表与 CSV 导出共用同一口径）"""
    date_s = r.get("date") or ""
    d = _parse_date(date_s)
    ot = max(0.0, float(r.get("manualOvertimeHours") or 0))
    extra = max(0.0, float(r.get("manualExtraHours") or 0))
    is_leave = bool(r.get("isLeave"))
    shift = (r.get("shiftType") or "DAY")
    is_night = (shift == "NIGHT") and not is_leave
    labels, cls = [], "normal"
    if is_leave:
        labels.append("请假")
        cls = "leave"
    if ot > 0:
        labels.append("加班")
        if cls == "normal":
            cls = "ot"
    if extra > 0:
        labels.append("加点")
        if cls == "normal":
            cls = "extra"
    if is_night:
        labels.append("夜班")
    custom = r.get("customRate")
    custom_rate = float(custom) if custom is not None else None
    pay, day_type, rate_text = compute_record_pay(
        date_s, ot, extra, hourly, rates, custom_rate, d)
    ci, co = r.get("checkInTime"), r.get("checkOutTime")
    if not ci and not co:
        time_range = "未登记"
    else:
        time_range = "%s - %s" % (ci or "--:--", co or "--:--")
    return {
        "date": date_s,
        "weekday": WEEKDAY_NAMES[d.weekday()] if d else "",
        "day_type": day_type,
        "day_type_name": DAY_TYPE_NAMES[day_type],
        "calendar_type": calendar_day_type(date_s, d) if d else DAY_WORKDAY,
        "holiday": get_holiday_name(date_s) if d is not None else None,
        "makeup": bool(d is not None and is_makeup_workday(date_s)),
        "labels": labels or ["—"],
        "cls": cls,
        "time": time_range,
        "ot": ot if ot > 0 else None,
        "extra": extra if extra > 0 else None,
        "total": round(ot + extra, 2),
        "leave_hours": float(r.get("leaveHours") or 0) if is_leave else None,
        "leave_type": r.get("leaveType") or "",
        "pay": round(pay, 2) if (ot + extra) > 0 else None,
        "rate_text": rate_text,
        "custom_rate": ("×%g" % custom_rate) if (custom_rate is not None and custom_rate >= 0) else "默认",
        "custom_raw": custom_rate if (custom_rate is not None and custom_rate >= 0) else None,
        "note": r.get("note") or "",
        "device": r.get("lastModifiedBy") or "",
        "shift": shift,
        "is_night": is_night,
    }


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


def compute_stats(backup, now=None, targets=None):
    now = now or datetime.now()
    settings = backup.get("settings") or {}
    records = backup.get("attendanceRecords") or []
    targets = targets or {}

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
    rate_holiday = float(settings["overtimeRateHoliday"]) if settings.get("overtimeRateHoliday") is not None else 3.0
    rates = (rate_normal, rate_weekend, rate_holiday)

    # 月度目标（config.json 配置，0 表示不启用）
    try:
        target_hours = float(targets.get("month_target_hours") or 0)
    except (TypeError, ValueError):
        target_hours = 0.0
    try:
        target_pay = float(targets.get("month_target_pay") or 0)
    except (TypeError, ValueError):
        target_pay = 0.0

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
    month_day_hours = month_night_hours = 0.0   # 本月时长按班次拆分（白班/夜班）
    year_hours = year_pay = 0.0
    year_ot = year_extra = 0.0            # 本年加班/加点拆分
    year_weekday_hours = year_weekend_hours = year_holiday_hours = 0.0
    total_hours = 0.0
    month_count = 0                    # 本月有加班/加点登记的天数
    month_late_nights = 0              # 本月 22:00 后下班次数
    month_latest_checkout = None       # 本月最晚下班 "HH:MM"
    month_leave_by_type = {}           # 请假类型 -> {days, hours}
    holiday_ot_hours = 0.0             # 法定节假日登记加班时长
    holiday_days = set()               # 法定节假日有加班的日期
    makeup_ot_hours = 0.0              # 调休补班日登记加班时长（按工作日 1.5 倍）
    makeup_days = set()
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
        # 估算口径与 App 完全一致（OvertimeCalculator）：
        # 登记"加班"按有效日期类型倍率（法定节假日 = overtimeRateHoliday，默认 3 倍；
        # 其余按周末倍率），"加点"始终按工作日倍率；customRate >= 0 时整条覆盖。
        pay, day_type, _rate_text = compute_record_pay(
            date_s, ot, extra, hourly, rates, custom_rate, d)

        if ot > 0 and d is not None:
            if get_holiday_name(date_s):
                holiday_ot_hours += ot
                holiday_days.add(date_s)
            elif is_makeup_workday(date_s):
                makeup_ot_hours += ot
                makeup_days.add(date_s)

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
            if (r.get("shiftType") or "DAY") == "NIGHT" and not is_leave:
                month_night_hours += hours
            else:
                month_day_hours += hours
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
            year_ot += ot
            year_extra += extra
            if day_type == DAY_HOLIDAY:
                year_holiday_hours += hours
            elif day_type == DAY_WEEKEND:
                year_weekend_hours += hours
            else:
                year_weekday_hours += hours

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

    # 同比：去年同月
    prev_year_key = "%04d-%02d" % (now.year - 1, now.month)
    prev_year_agg = monthly_map.get(prev_year_key, {})
    prev_year_hours = round(prev_year_agg.get("ot", 0.0) + prev_year_agg.get("extra", 0.0), 2)
    yoy_pct = round((cur_hours - prev_year_hours) / prev_year_hours * 100.0) if prev_year_hours > 0 else None

    # 本年加班/加点拆分与工作日/周末分布
    year_hours_r = round(year_ot + year_extra, 2)
    year_weekend_share = round(year_weekend_hours / year_hours_r * 100.0) if year_hours_r > 0 else None

    # 季度汇总：近 8 个季度（含当前季度），从 monthly_map 聚合
    quarter_map = {}
    for ym, agg in monthly_map.items():
        try:
            y, mth = int(ym[:4]), int(ym[5:7])
        except (ValueError, IndexError):
            continue
        qk = "%dQ%d" % (y, (mth - 1) // 3 + 1)
        qa = quarter_map.setdefault(qk, {"ot": 0.0, "extra": 0.0})
        qa["ot"] += agg["ot"]
        qa["extra"] += agg["extra"]
    quarterly = []
    q_year, q_num = now.year, (now.month - 1) // 3 + 1
    for _ in range(8):
        qk = "%dQ%d" % (q_year, q_num)
        qa = quarter_map.get(qk, {"ot": 0.0, "extra": 0.0})
        quarterly.append({
            "label": qk,
            "ot": round(qa["ot"], 2),
            "extra": round(qa["extra"], 2),
            "hours": round(qa["ot"] + qa["extra"], 2),
        })
        q_num -= 1
        if q_num == 0:
            q_num, q_year = 4, q_year - 1
    quarterly.reverse()
    # 去掉开头的空季度，避免图表前段全是空柱（至少保留 1 个季度）
    while len(quarterly) > 1 and quarterly[0]["hours"] == 0:
        quarterly.pop(0)

    # 峰值日：本年单日合计最高的一天
    year_daily = [(ds, a) for ds, a in daily_map.items()
                  if ds.startswith(year_key) and (a["ot"] + a["extra"]) > 0]
    peak_day = None
    if year_daily:
        ds, a = max(year_daily, key=lambda x: x[1]["ot"] + x[1]["extra"])
        pd_dt = _parse_date(ds)
        peak_day = {
            "date": ds,
            "weekday": WEEKDAY_NAMES[pd_dt.weekday()] if pd_dt else "",
            "hours": round(a["ot"] + a["extra"], 2),
            "ot": round(a["ot"], 2),
            "extra": round(a["extra"], 2),
        }

    # 加班集中度：加班最多的前 10% 天数（向上取整，至少 1 天）占总时长比例
    day_totals = sorted((a["ot"] + a["extra"] for a in daily_map.values() if (a["ot"] + a["extra"]) > 0),
                        reverse=True)
    top_days = max(1, (len(day_totals) + 9) // 10) if day_totals else 0
    top_hours = round(sum(day_totals[:top_days]), 2) if day_totals else 0.0
    top_share = round(top_hours / total_hours * 100.0) if total_hours > 0 else None

    # 近 3 个月趋势：最近 N 个完整月 vs 再往前 N 个完整月（不含本月）
    # 完整月不足 6 个时退回 2 个月窗口，仍然不足则显示"数据不足"
    complete_keys = sorted(k for k in monthly_map if k < month_key)
    trend_pct, trend_text, trend_window = None, "数据不足", 0
    for window in (3, 2):
        if len(complete_keys) < window * 2:
            continue

        def sum_hours(keys):
            return sum(monthly_map[k]["ot"] + monthly_map[k]["extra"] for k in keys) / float(len(keys))

        recent = sum_hours(complete_keys[-window:])
        prev = sum_hours(complete_keys[-window * 2:-window])
        trend_window = window
        if prev > 0:
            trend_pct = round((recent - prev) / prev * 100.0)
            trend_text = "上升" if trend_pct > 3 else ("下降" if trend_pct < -3 else "基本持平")
        else:
            trend_text = "上升" if recent > 0 else "基本持平"
            trend_pct = None
        break

    # 明细（全量，按日期倒序；前端支持筛选/排序）
    recent = sorted(
        (_record_view(r, hourly, rates) for r in active if r.get("date")),
        key=lambda v: v["date"], reverse=True,
    )

    # 月度目标进度（目标未配置时为 None）
    month_progress_hours = (round((month_ot + month_extra) / target_hours * 100)
                            if target_hours > 0 else None)
    month_progress_pay = (round(month_pay / target_pay * 100)
                          if target_pay > 0 else None)

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
        "month_day_hours": round(month_day_hours, 2),
        "month_night_hours": round(month_night_hours, 2),
        "month_target_hours": target_hours,
        "month_target_pay": target_pay,
        "month_progress_hours": month_progress_hours,
        "month_progress_pay": month_progress_pay,
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
        "prev_year_hours": prev_year_hours,
        "yoy_pct": yoy_pct,
        "year_ot": round(year_ot, 2),
        "year_extra": round(year_extra, 2),
        "year_weekend_hours": round(year_weekend_hours, 2),
        "year_weekday_hours": round(year_weekday_hours, 2),
        "year_holiday_hours": round(year_holiday_hours, 2),
        "year_weekend_share": year_weekend_share,
        "holiday_ot_hours": round(holiday_ot_hours, 2),
        "holiday_days": len(holiday_days),
        "makeup_ot_hours": round(makeup_ot_hours, 2),
        "makeup_days": len(makeup_days),
        "peak_day": peak_day,
        "top_days": top_days,
        "top_hours": top_hours,
        "top_share": top_share,
        "day_count_with_hours": len(day_totals),
        "trend_pct": trend_pct,
        "trend_text": trend_text,
        "trend_window": trend_window,
        "quarterly": quarterly,
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

def _fmt_bar_val(v):
    """柱顶数值的紧凑写法：≥10 取整，小于 10 保留 1 位小数"""
    return ("%d" % round(v)) if v >= 10 else ("%g" % round(v, 1))


def svg_daily_chart(daily30):
    """近 30 天分组并排柱状图：加班（蓝）、加点（青）、请假（橙）各占一根独立柱子"""
    w, h = 860, 220
    pad_l, pad_b, pad_t = 40, 28, 16
    plot_w = w - pad_l - 8
    plot_h = h - pad_b - pad_t
    if not daily30:
        return ""
    segments = [("ot", "加班", "#4c6ef5"), ("extra", "加点", "#22b8cf"), ("leave", "请假", "#f59e0b")]
    n = len(daily30)
    slot = plot_w / n
    gap = 1.6
    group_w = min(slot * 0.84, 27.0)
    bar_w = max(2.4, (group_w - gap * (len(segments) - 1)) / len(segments))
    group_w = bar_w * len(segments) + gap * (len(segments) - 1)
    # 分组显示时按"单根柱子"的最大值定标尺，而不是当天合计
    max_v = max((max(d.get(k, 0.0) for k, _label, _color in segments) for d in daily30), default=0) or 1.0
    max_v = max(max_v, 1.0)
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
        gx = pad_l + idx * slot + (slot - group_w) / 2
        total = sum(d.get(k, 0.0) for k, _label, _color in segments)
        if total > 0:
            tip_lines = ["%s（%s）" % (d["date"], wd)]
            for k, label, _color in segments:
                v = d.get(k, 0.0)
                if v > 0:
                    tip_lines.append("%s %.2f 小时" % (label, v))
            tip_lines.append("合计 %.2f 小时" % total)
            tip = "\n".join(tip_lines)
            # 每个分类各自独立成柱，并排摆放
            for si, (k, label, color) in enumerate(segments):
                v = d.get(k, 0.0)
                if v <= 0:
                    continue
                bh = plot_h * (v / max_v)
                x = gx + si * (bar_w + gap)
                parts.append('<rect x="%.1f" y="%.1f" width="%.1f" height="%.1f" rx="1" fill="%s">'
                             '<title>%s\n%s %.2f 小时</title></rect>'
                             % (x, pad_t + plot_h - bh, bar_w, bh, color, d["date"], label, v))
                parts.append('<text x="%.1f" y="%.1f" font-size="7" fill="#4b5563" '
                             'text-anchor="middle">%s</text>'
                             % (x + bar_w / 2, pad_t + plot_h - bh - 2.2, _fmt_bar_val(v)))
        else:
            parts.append(
                '<rect x="%.1f" y="%.1f" width="%.1f" height="2" fill="#e5e7eb">'
                '<title>%s（%s）：无记录</title></rect>'
                % (gx, pad_t + plot_h - 2, group_w, d["date"], wd)
            )
        if idx % 5 == 0 or idx == n - 1:
            parts.append('<text x="%.1f" y="%d" font-size="10" fill="#6b7280" text-anchor="middle">%s</text>'
                         % (gx + group_w / 2, h - 8, d["date"][5:]))
    return ('<svg viewBox="0 0 %d %d" width="100%%" height="%d" role="img">%s</svg>'
            % (w, h, h, "".join(parts)))


def svg_monthly_chart(monthly12):
    """近 12 个月分组并排柱状图：加班（蓝）与加点（青）各占一根独立柱子"""
    w, h = 860, 220
    pad_l, pad_b, pad_t = 40, 28, 16
    plot_w = w - pad_l - 8
    plot_h = h - pad_b - pad_t
    if not monthly12:
        return ""
    segments = [("ot", "加班", "#4c6ef5"), ("extra", "加点", "#22b8cf")]
    n = len(monthly12)
    slot = plot_w / n
    gap = 3.0
    group_w = min(slot * 0.6, 48.0)
    bar_w = max(4.0, (group_w - gap * (len(segments) - 1)) / len(segments))
    group_w = bar_w * len(segments) + gap * (len(segments) - 1)
    # 分组显示时按"单根柱子"的最大值定标尺
    max_v = max((max(m.get(k, 0.0) for k, _label, _color in segments) for m in monthly12), default=0) or 1.0
    max_v = max(max_v, 1.0)
    parts = []
    for i in range(1, 4):
        y = pad_t + plot_h - plot_h * i / 4.0
        parts.append('<line x1="%d" y1="%.1f" x2="%d" y2="%.1f" stroke="#e5e7eb" stroke-width="1"/>'
                     % (pad_l, y, w - 8, y))
        parts.append('<text x="%d" y="%.1f" font-size="10" fill="#9ca3af" text-anchor="end">%g</text>'
                     % (pad_l - 6, y + 3, max_v * i / 4.0))
    for idx, m in enumerate(monthly12):
        total = m.get("ot", 0.0) + m.get("extra", 0.0)
        gx = pad_l + idx * slot + (slot - group_w) / 2
        if total > 0:
            tip_lines = [m["month"]]
            for k, label, _color in segments:
                v = m.get(k, 0.0)
                if v > 0:
                    tip_lines.append("%s %.2f 小时" % (label, v))
            tip_lines.append("合计 %.2f 小时，预估 ¥%.2f" % (total, m.get("pay", 0.0)))
            tip = "\n".join(tip_lines)
            # 加班与加点各自独立成柱，并排摆放
            for si, (k, label, color) in enumerate(segments):
                v = m.get(k, 0.0)
                if v <= 0:
                    continue
                bh = plot_h * (v / max_v)
                x = gx + si * (bar_w + gap)
                parts.append('<rect x="%.1f" y="%.1f" width="%.1f" height="%.1f" rx="2" fill="%s">'
                             '<title>%s\n%s %.2f 小时</title></rect>'
                             % (x, pad_t + plot_h - bh, bar_w, bh, color, m["month"], label, v))
                parts.append('<text x="%.1f" y="%.1f" font-size="8.5" fill="#4b5563" '
                             'text-anchor="middle">%s</text>'
                             % (x + bar_w / 2, pad_t + plot_h - bh - 3, _fmt_bar_val(v)))
        else:
            parts.append(
                '<rect x="%.1f" y="%.1f" width="%.1f" height="2" fill="#e5e7eb"><title>%s：无记录</title></rect>'
                % (gx, pad_t + plot_h - 2, group_w, m["month"])
            )
        parts.append('<text x="%.1f" y="%d" font-size="10" fill="#6b7280" text-anchor="middle">%s</text>'
                     % (gx + group_w / 2, h - 8, m["month"][2:]))
    return ('<svg viewBox="0 0 %d %d" width="100%%" height="%d" role="img">%s</svg>'
            % (w, h, h, "".join(parts)))


def svg_quarter_chart(quarterly):
    """近 8 个季度分组并排柱状图：加班（蓝）与加点（青）各占一根独立柱子"""
    w, h = 860, 220
    pad_l, pad_b, pad_t = 40, 28, 16
    plot_w = w - pad_l - 8
    plot_h = h - pad_b - pad_t
    if not quarterly:
        return ""
    segments = [("ot", "加班", "#4c6ef5"), ("extra", "加点", "#22b8cf")]
    n = len(quarterly)
    slot = plot_w / n
    gap = 4.0
    group_w = min(slot * 0.5, 52.0)
    bar_w = max(4.0, (group_w - gap * (len(segments) - 1)) / len(segments))
    group_w = bar_w * len(segments) + gap * (len(segments) - 1)
    max_v = max((max(q.get(k, 0.0) for k, _label, _color in segments) for q in quarterly), default=0) or 1.0
    max_v = max(max_v, 1.0)
    parts = []
    for i in range(1, 4):
        y = pad_t + plot_h - plot_h * i / 4.0
        parts.append('<line x1="%d" y1="%.1f" x2="%d" y2="%.1f" stroke="#e5e7eb" stroke-width="1"/>'
                     % (pad_l, y, w - 8, y))
        parts.append('<text x="%d" y="%.1f" font-size="10" fill="#9ca3af" text-anchor="end">%g</text>'
                     % (pad_l - 6, y + 3, max_v * i / 4.0))
    for idx, q in enumerate(quarterly):
        total = q.get("ot", 0.0) + q.get("extra", 0.0)
        gx = pad_l + idx * slot + (slot - group_w) / 2
        if total > 0:
            for si, (k, label, color) in enumerate(segments):
                v = q.get(k, 0.0)
                if v <= 0:
                    continue
                bh = plot_h * (v / max_v)
                x = gx + si * (bar_w + gap)
                parts.append('<rect x="%.1f" y="%.1f" width="%.1f" height="%.1f" rx="2" fill="%s">'
                             '<title>%s\n%s %.2f 小时\n季度合计 %.2f 小时</title></rect>'
                             % (x, pad_t + plot_h - bh, bar_w, bh, color, q["label"], label, v, total))
                parts.append('<text x="%.1f" y="%.1f" font-size="9" fill="#4b5563" '
                             'text-anchor="middle">%s</text>'
                             % (x + bar_w / 2, pad_t + plot_h - bh - 3, _fmt_bar_val(v)))
        else:
            parts.append('<rect x="%.1f" y="%.1f" width="%.1f" height="2" fill="#e5e7eb">'
                         '<title>%s：无记录</title></rect>'
                         % (gx, pad_t + plot_h - 2, group_w, q["label"]))
        parts.append('<text x="%.1f" y="%d" font-size="10" fill="#6b7280" text-anchor="middle">%s</text>'
                     % (gx + group_w / 2, h - 8, q["label"]))
    return ('<svg viewBox="0 0 %d %d" width="100%%" height="%d" role="img">%s</svg>'
            % (w, h, h, "".join(parts)))


def svg_late_chart(daily30, std_end):
    """近 30 天下班晚点柱状图：柱高 = 打卡下班时间相对标准下班时间的晚点小时数"""
    w, h = 860, 220
    pad_l, pad_b, pad_t = 40, 28, 16
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
            parts.append('<text x="%.1f" y="%.1f" font-size="7" fill="#4b5563" '
                         'text-anchor="middle">%s</text>'
                         % (x + bar_w / 2, y - 2.2, _fmt_bar_val(late)))
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
    pad_l, pad_b, pad_t = 40, 28, 16
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
        if total > 0:
            # 堆叠柱在柱顶标注当天合计值
            parts.append('<text x="%.1f" y="%.1f" font-size="9" fill="#4b5563" '
                         'text-anchor="middle">%s</text>'
                         % (x + bar_w / 2, pad_t + plot_h - plot_h * (total / max_v) - 3,
                            _fmt_bar_val(total)))
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
.badge.holiday{background:#fee2e2;color:#b91c1c}
.badge.makeup{background:#ede9fe;color:#6d28d9}
.badge.daytype{background:#f3f4f6;color:#6b7280}
.badge.night{background:#424242;color:#fff}
.check-ok{color:#059669}.check-bad{color:#dc2626}
.note{font-size:11px;color:#9ca3af;margin-top:10px;line-height:1.8}
form.btn{display:inline-block}
button{background:#4c6ef5;color:#fff;border:0;border-radius:6px;padding:5px 14px;font-size:12px;cursor:pointer}
button:hover{background:#4263eb}
button:disabled{background:#93a4f7;cursor:default}
.empty{text-align:center;color:#9ca3af;padding:40px 0}
.card .bar{height:6px;background:#eef2f7;border-radius:99px;margin:8px 0 4px;overflow:hidden}
.card .bar i{display:block;height:100%;background:#4c6ef5;border-radius:99px}
.card .bar.done i{background:#10b981}
.card .tiny{font-size:11px;color:#9ca3af}
.filters{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin-bottom:10px}
.filters select,.filters input{border:1px solid #e5e7eb;border-radius:6px;padding:4px 8px;font-size:12px;color:#374151;background:#fff}
.filters input{min-width:180px}
.filters .count{font-size:11px;color:#6b7280;margin-left:auto}
.export{float:right;font-size:12px;color:#4c6ef5;text-decoration:none;font-weight:400;margin-left:10px}
.export:hover{text-decoration:underline}
.livebar{max-width:960px;margin:0 auto;color:#9ca3af;font-size:11px;padding:0 0 24px;line-height:1.8}
.livebar a{color:#6b7280}
.row-btn{background:none;border:0;color:#4c6ef5;cursor:pointer;font-size:12px;padding:0 3px}
.row-btn:hover{text-decoration:underline}
.row-btn.del{color:#ef4444}
.mask{position:fixed;inset:0;background:rgba(15,23,42,.45);display:none;align-items:center;justify-content:center;z-index:50}
.mask.show{display:flex}
.dialog{background:#fff;border-radius:12px;padding:18px 20px;width:380px;max-width:92vw;box-shadow:0 18px 48px rgba(0,0,0,.25);text-align:left}
.dialog h3{margin:0 0 6px;font-size:16px;color:#111827}
.dialog .hint{font-size:11px;color:#b45309;background:#fffbeb;border:1px solid #fde68a;border-radius:6px;padding:6px 8px;margin:8px 0}
.dialog label{font-size:12px;color:#6b7280;display:block;margin:10px 0 4px}
.dialog input{width:100%;box-sizing:border-box;border:1px solid #e5e7eb;border-radius:6px;padding:6px 8px;font-size:13px;color:#111827}
.dialog .err{color:#dc2626;font-size:12px;margin-top:8px}
.dialog .actions{display:flex;gap:8px;justify-content:flex-end;margin-top:16px}
.dialog button.ghost{background:#f3f4f6;color:#374151}
.dialog button.danger{background:#ef4444}
"""

_PAGE_SKELETON = """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>加班记录 · PC 监控</title>
<style>__CSS__</style></head>
<body><div class="wrap" id="dashboard" data-refresh="__REFRESH__">__BODY__</div>
<div class="livebar">每 __REFRESH__ 秒自动局部刷新 · <span id="countdown">--</span> · <span id="last-sync">等待首次自动刷新</span></div>
<div class="mask" id="edit-mask">
  <div class="dialog">
    <h3>编辑 <span id="edit-date">--</span></h3>
    <div class="hint">保存会直接写回云端备份（加密后上传），手机端下次同步即生效。写入前会在本机 backup_history/ 保留一份原始快照。</div>
    <label>加班时长（小时，留空 = 未登记）</label>
    <input id="edit-ot" type="number" step="0.5" min="0" max="24" placeholder="例如 4">
    <label>加点时长（小时，留空 = 未登记）</label>
    <input id="edit-extra" type="number" step="0.5" min="0" max="24" placeholder="例如 1.5">
    <label>自定义倍率（1.0~4.0，留空 = 按默认）</label>
    <input id="edit-rate" type="number" step="0.1" min="1" max="4" placeholder="留空">
    <label>备注</label>
    <input id="edit-note" type="text" maxlength="500" placeholder="可选">
    <div class="err" id="edit-msg" style="display:none"></div>
    <div class="actions">
      <button class="ghost" id="edit-cancel">取消</button>
      <button class="danger" id="edit-delete">删除记录</button>
      <button id="edit-save">保存到云端</button>
    </div>
  </div>
</div>
<script>__JS__</script>
</body></html>"""

# 前端脚本：局部刷新（fetch /fragment）+ 明细筛选/排序（刷新后保留筛选状态）
_JS = """
(function () {
  var state = { month: '', type: '', kw: '', sort: 'date_desc' };
  var timer = null, left = 60;
  var currentEdit = null;

  function byId(id) { return document.getElementById(id); }

  function saveState() {
    var m = byId('f-month'); if (m) state.month = m.value;
    var t = byId('f-type'); if (t) state.type = t.value;
    var k = byId('f-kw'); if (k) state.kw = k.value;
    var s = byId('f-sort'); if (s) state.sort = s.value;
  }

  function applyFilters() {
    var body = byId('records-body');
    if (!body) return;
    var rows = Array.prototype.slice.call(body.querySelectorAll('tr.rec'));
    var kw = (state.kw || '').toLowerCase();
    var shown = 0;
    rows.forEach(function (tr) {
      var types = (tr.getAttribute('data-type') || '').split(' ');
      var ok = true;
      if (state.month && tr.getAttribute('data-month') !== state.month) ok = false;
      if (ok && state.type && types.indexOf(state.type) < 0) ok = false;
      if (ok && kw && (tr.textContent || '').toLowerCase().indexOf(kw) < 0) ok = false;
      tr.style.display = ok ? '' : 'none';
      if (ok) shown++;
    });
    rows.sort(function (a, b) {
      var da = a.getAttribute('data-date') || '', db = b.getAttribute('data-date') || '';
      var ha = parseFloat(a.getAttribute('data-hours') || '0');
      var hb = parseFloat(b.getAttribute('data-hours') || '0');
      if (state.sort === 'date_asc') return da < db ? -1 : (da > db ? 1 : 0);
      if (state.sort === 'hours_desc') return hb - ha || (da < db ? 1 : -1);
      if (state.sort === 'hours_asc') return ha - hb || (da < db ? 1 : -1);
      return da > db ? -1 : (da < db ? 1 : 0);
    });
    rows.forEach(function (tr) { body.appendChild(tr); });
    var c = byId('f-count');
    if (c) c.textContent = '显示 ' + shown + ' / ' + rows.length + ' 条';
  }

  function restoreControls() {
    var map = { 'f-month': state.month, 'f-type': state.type, 'f-kw': state.kw, 'f-sort': state.sort };
    Object.keys(map).forEach(function (id) {
      var el = byId(id);
      if (el) el.value = map[id];
    });
    applyFilters();
  }

  function schedule() {
    if (timer) clearInterval(timer);
    var d = byId('dashboard');
    left = parseInt((d && d.getAttribute('data-refresh')) || '60', 10) || 60;
    tick();
    timer = setInterval(function () {
      left -= 1;
      if (left <= 0) { clearInterval(timer); timer = null; loadFragment(true); return; }
      tick();
    }, 1000);
  }

  function tick() {
    var e = byId('countdown');
    if (e) e.textContent = left + ' 秒后自动刷新';
  }

  function swap(htmlText) {
    var doc = new DOMParser().parseFromString(htmlText, 'text/html');
    var fresh = doc.getElementById('dashboard');
    var old = byId('dashboard');
    if (!fresh || !old) return;
    old.parentNode.replaceChild(document.importNode(fresh, true), old);
    restoreControls();
    schedule();
  }

  function loadFragment(isAuto) {
    saveState();
    fetch('/fragment', { cache: 'no-store' }).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.text();
    }).then(function (txt) {
      swap(txt);
      var e = byId('last-sync');
      if (e) e.textContent = (isAuto ? '自动刷新' : '手动刷新') + '成功 · ' + new Date().toLocaleTimeString();
    }).catch(function () {
      var e = byId('last-sync');
      if (e) e.textContent = '局部刷新失败，稍后自动重试 · ' + new Date().toLocaleTimeString();
      schedule();
    });
  }

  function openEdit(btn) {
    var row = btn.closest ? btn.closest('tr') : null;
    if (!row) return;
    currentEdit = row.getAttribute('data-date');
    byId('edit-date').textContent = currentEdit;
    var ot = row.getAttribute('data-ot') || '-1';
    var extra = row.getAttribute('data-extra') || '-1';
    var rate = row.getAttribute('data-rate') || '';
    byId('edit-ot').value = (ot === '-1' || ot === '') ? '' : ot;
    byId('edit-extra').value = (extra === '-1' || extra === '') ? '' : extra;
    byId('edit-rate').value = rate;
    byId('edit-note').value = row.getAttribute('data-note') || '';
    byId('edit-msg').style.display = 'none';
    byId('edit-mask').classList.add('show');
  }

  function closeEdit() {
    byId('edit-mask').classList.remove('show');
    currentEdit = null;
  }

  function saveEdit(payload) {
    var msg = byId('edit-msg');
    if (msg) msg.style.display = 'none';
    fetch('/api/record', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    }).then(function (r) { return r.json(); }).then(function (j) {
      if (!j.ok) throw new Error(j.error || '保存失败');
      closeEdit();
      loadFragment(false);
      var el = byId('last-sync');
      if (el) {
        el.textContent = (payload.action === 'delete' ? '已删除并写回云端' : '已保存到云端')
          + ' · ' + new Date().toLocaleTimeString();
      }
    }).catch(function (err) {
      if (msg) {
        msg.textContent = err.message;
        msg.style.display = 'block';
      }
    });
  }

  document.addEventListener('change', function (e) {
    var id = e.target && e.target.id;
    if (id === 'f-month' || id === 'f-type' || id === 'f-sort') {
      state[id.slice(2)] = e.target.value;
      applyFilters();
    }
  });

  document.addEventListener('input', function (e) {
    if (e.target && e.target.id === 'f-kw') {
      state.kw = e.target.value;
      applyFilters();
    }
  });

  document.addEventListener('click', function (e) {
    var t = e.target;
    if (!t) return;

    if (t.id === 'edit-cancel' || t.id === 'edit-mask') {
      if (t.id !== 'edit-cancel' && t.id !== 'edit-mask') return;
      if (t.id === 'edit-mask' && e.target !== t) return;
      closeEdit();
      return;
    }
    if (t.id === 'edit-save') {
      if (!currentEdit) return;
      var ot = byId('edit-ot').value.trim();
      var extra = byId('edit-extra').value.trim();
      var rate = byId('edit-rate').value.trim();
      saveEdit({
        date: currentEdit,
        action: 'update',
        manualOvertimeHours: ot === '' ? -1 : parseFloat(ot),
        manualExtraHours: extra === '' ? -1 : parseFloat(extra),
        customRate: rate === '' ? null : parseFloat(rate),
        note: byId('edit-note').value
      });
      return;
    }
    if (t.id === 'edit-delete') {
      if (!currentEdit) return;
      if (!confirm('确定删除 ' + currentEdit + ' 这条记录吗？删除会软删除并同步到手机端。')) return;
      saveEdit({ date: currentEdit, action: 'delete' });
      return;
    }
    if (t.getAttribute && t.getAttribute('data-edit')) {
      openEdit(t);
      return;
    }
    if (t.getAttribute && t.getAttribute('data-del')) {
      var row = t.closest ? t.closest('tr') : null;
      var date = row ? row.getAttribute('data-date') : '';
      if (date && confirm('确定删除 ' + date + ' 这条记录吗？删除会软删除并同步到手机端。')) {
        saveEdit({ date: date, action: 'delete' });
      }
      return;
    }
    if (t.id !== 'btn-refresh') return;
    e.preventDefault();
    t.disabled = true;
    t.textContent = '拉取中…';
    fetch('/api/refresh', { method: 'POST', headers: { 'Accept': 'application/json' } })
      .then(function (r) { return r.json(); })
      .then(function () { loadFragment(false); })
      .catch(function () {
        t.disabled = false;
        t.textContent = '立即拉取云端';
        var el = byId('last-sync');
        if (el) el.textContent = '拉取失败 · ' + new Date().toLocaleTimeString();
      });
  });

  restoreControls();
  schedule();
})();
"""



def _fmt_ts(ms):
    try:
        return datetime.fromtimestamp(int(ms) / 1000.0).strftime("%Y-%m-%d %H:%M:%S")
    except (TypeError, ValueError, OSError):
        return "未知"


def render_body(snapshot, status):
    """仪表盘正文（首页与 /fragment 局部刷新共用）"""
    if snapshot is None:
        err = status.get("last_error")
        body = '<h1>加班记录 · PC 监控</h1>'
        if err:
            body += '<div class="status err">%s</div>' % html.escape(str(err))
        body += '<div class="panel empty">正在拉取云端数据，请稍候…（5 秒后自动重试）</div>'
        return body

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
        status_html = ('<div class="status ok">已连接 WebDAV，每 %d 分钟自动拉取；'
                       '页面每 60 秒局部刷新，筛选与排序状态会保留。</div>'
                       % status.get("interval_min", 5))

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
        _card("本月合计", "%g" % stats["month_hours"],
              ("白班 %gh · 夜班 %gh" % (stats["month_day_hours"], stats["month_night_hours"]))
              if stats["month_night_hours"] > 0 else "加班+加点 小时"),
        _card("本月预估加班费", "¥%.2f" % stats["month_pay"], "时薪 ¥%g" % stats["hourly_wage"]),
        _target_cards(stats),
        _card("本年累计", "%g" % stats["year_hours"], "小时 · 预估 ¥%.2f" % stats["year_pay"]),
        _card("本月请假", "%d 天" % stats["month_leave_days"], "%.2f 小时" % stats["month_leave_hours"]),
        _card("本月加班次数", "%d 次" % stats["month_count"], "平均每次 %g 小时" % stats["month_avg"]),
        _mom_card(stats),
        _card("当前连续加班", "%d 天" % stats["streak_days"], "截至最近一个登记日"),
        _card("本月 22 点后下班", "%d 次" % stats["late_nights"],
              ("最晚 %s" % stats["latest_checkout"]) if stats["latest_checkout"] else "未登记上下班时间"),
        _card("近 12 月月均", "%g" % stats["month_avg_12"], "小时/月"),
        _yoy_card(stats),
        _card("本年加班 / 加点", "%g / %g" % (stats["year_ot"], stats["year_extra"]), "小时"),
        _weekend_share_card(stats),
        _holiday_card(stats),
        _peak_card(stats),
        _concentration_card(stats),
        _trend_card(stats),
    ])

    body = "".join([
        '<h1>加班记录 · PC 监控'
        '<a class="export" href="/export.csv">导出 CSV</a>'
        '<button id="btn-refresh" style="float:right">立即拉取云端</button></h1>',
        '<div class="sub">%s</div>' % meta_line,
        status_html,
        '<div class="cards">%s</div>' % cards,
        _panel("近 30 天每日时长（加班 / 加点 / 请假 并排显示）", svg_daily_chart(stats["daily30"]),
               '<div class="legend">加班<i style="background:#4c6ef5"></i>加点<i style="background:#22b8cf"></i>'
               '请假<i style="background:#f59e0b"></i>每天三根独立柱并排，悬停查看分类明细</div>'),
        _panel("近 12 个月汇总（加班 / 加点 并排显示）", svg_monthly_chart(stats["monthly12"]),
               '<div class="legend">加班<i style="background:#4c6ef5"></i>加点<i style="background:#22b8cf"></i>'
               '每月两根独立柱并排，悬停查看分类明细与预估加班费</div>'),
        _panel("近 8 个季度汇总（加班 / 加点 并排显示）", svg_quarter_chart(stats["quarterly"]),
               '<div class="legend">加班<i style="background:#4c6ef5"></i>加点<i style="background:#22b8cf"></i>'
               '每季度两根独立柱并排，悬停查看分类明细</div>'),
        _panel("近 30 天下班晚点（标准下班 %s）" % std_end, svg_late_chart(stats["daily30"], std_end),
               '<div class="legend">晚点时长<i style="background:#ff922b"></i>'
               '灰条=有加班但无打卡，浅灰=无记录，虚线=标准下班时间'
               + ('<br>提示：当前备份未包含上下班时间数据，暂无法统计晚点——在 App 中登记上下班时间并同步后自动可用。</div>'
                  if stats["latest_checkout"] is None else '</div>')),
        _panel("加班星期分布（全部记录）", svg_weekday_chart(stats["weekday_dist"]),
               '<div class="legend">加班<i style="background:#4c6ef5"></i>加点<i style="background:#22b8cf"></i>'
               '悬停查看分类明细</div>'),
        _leave_panel(stats["month_leave_by_type"]),
        _records_table(stats["recent"]),
        '<div class="note">'
        '口径说明：加班时长 = 登记加班 + 登记加点（与 App 一致，未登记记 0）；预估加班费与 App 完全一致：'
        '登记"加班"时，法定节假日按节假日倍率（默认 3 倍）、其余按周末倍率（默认 2 倍）；'
        '只登记"加点"或两者都登记时，加点部分按工作日倍率（默认 1.5 倍）；自定义倍率记录整条覆盖。'
        '法定节假日与调休补班日逐年内置（2024-2028）；与 App 一致，登记"加班"时除法定节假日外一律按周末倍率计，'
        '调休补班日仅用于日历类型标记（不改变倍率）。'
        '次数 = 有加班/加点登记的天数；连续加班 = 截至最近一个登记日的连续天数；'
        '"连续"指相邻自然日，遇到无登记日即中断。'
        '下班晚点 = 打卡下班时间 - 标准下班时间（仅统计有打卡且登记了加班/加点的记录），时间取自本机时区。'
        '同比 = 本月 vs 去年同月；周末占比 = 周末时长 ÷ 本年总时长；'
        '单日峰值 = 本年单日加班+加点最高的一天；集中度 = 时长最高的前 10%% 天数（向上取整）占总时长比例；'
        '趋势 = 最近 3 个完整月月均 vs 再往前 3 个月月均（不足 6 个月时改用 2 个月窗口），'
        '变化在 ±3%% 内记为"基本持平"。'
        '月度目标可在 config.json 中通过 month_target_hours / month_target_pay 配置（0 表示不显示）。'
        '数据仅读自 WebDAV 云端备份，本工具不会上传/修改任何数据。'
        '</div>',
    ])

    return body


def render_fragment(snapshot, status):
    """仅返回仪表盘容器（供前端局部刷新替换）"""
    refresh = 5 if snapshot is None else 60
    return ('<div class="wrap" id="dashboard" data-refresh="%d">%s</div>'
            % (refresh, render_body(snapshot, status)))


def render_dashboard(snapshot, status):
    """完整页面（首屏加载用；之后由前端局部刷新）"""
    refresh = 5 if snapshot is None else 60
    return (_PAGE_SKELETON
            .replace("__REFRESH__", str(refresh))
            .replace("__CSS__", _CSS)
            .replace("__BODY__", render_body(snapshot, status))
            .replace("__JS__", _JS))


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


def _yoy_card(stats):
    """同比去年同月卡片"""
    yoy = stats["yoy_pct"]
    if yoy is None:
        return _card("同比去年同月", "—", "去年同月无加班记录")
    return _card("同比去年同月", ("+%d%%" if yoy > 0 else "%d%%") % yoy,
                 "去年同月 %g 小时" % stats["prev_year_hours"])


def _weekend_share_card(stats):
    """本年周末加班占比卡片"""
    share = stats["year_weekend_share"]
    if share is None:
        return _card("本年周末加班占比", "—", "本年暂无加班记录")
    return _card("本年周末加班占比", "%d%%" % share,
                 "周末 %g 小时 · 工作日 %g 小时 · 节假日 %g 小时"
                 % (stats["year_weekend_hours"], stats["year_weekday_hours"],
                    stats["year_holiday_hours"]))


def _holiday_card(stats):
    """节假日口径校验卡片：法定节假日与调休补班日的加班时长"""
    holiday_h = stats["holiday_ot_hours"]
    makeup_h = stats["makeup_ot_hours"]
    if holiday_h <= 0 and makeup_h <= 0:
        return _card("节假日加班", "—", "未登记法定节假日/调休补班加班")
    return _card("节假日加班（法定）", "%g" % holiday_h,
                 "%d 天 · 调休补班 %g 小时（%d 天）"
                 % (stats["holiday_days"], makeup_h, stats["makeup_days"]))


def _target_cards(stats):
    """月度目标进度卡片（config.json 未配置目标时不显示）"""
    parts = []
    th = stats["month_target_hours"]
    if th > 0:
        pct = stats["month_progress_hours"]
        done_h = stats["month_hours"]
        remain = max(0.0, th - done_h)
        parts.append(_progress_card(
            "本月时长目标", "%g / %g 小时" % (done_h, th), pct,
            ("已完成 %d%% · 还差 %g 小时" % (pct, remain)) if pct < 100
            else ("已完成 %d%% · 已达标" % pct)))
    tp = stats["month_target_pay"]
    if tp > 0:
        pct = stats["month_progress_pay"]
        done_p = stats["month_pay"]
        remain = max(0.0, tp - done_p)
        parts.append(_progress_card(
            "本月加班费目标", "¥%.2f / ¥%.2f" % (done_p, tp), pct,
            ("已完成 %d%% · 还差 ¥%.2f" % (pct, remain)) if pct < 100
            else ("已完成 %d%% · 已达标" % pct)))
    return "".join(parts)


def _progress_card(label, value, percent, small):
    pct = max(0, min(100, int(percent)))
    return ('<div class="card"><div class="label">%s</div>'
            '<div class="value" style="font-size:16px">%s</div>'
            '<div class="bar%s"><i style="width:%d%%"></i></div>'
            '<div class="tiny">%s</div></div>'
            % (html.escape(label), html.escape(value),
               " done" if pct >= 100 else "", pct, html.escape(small)))


def _peak_card(stats):
    """本年峰值日卡片"""
    peak = stats["peak_day"]
    if not peak:
        return _card("本年单日峰值", "—", "本年暂无加班记录")
    return _card("本年单日峰值", "%g" % peak["hours"],
                 "%s %s · 加班 %g / 加点 %g 小时" % (peak["date"], peak["weekday"],
                                                    peak["ot"], peak["extra"]))


def _concentration_card(stats):
    """加班集中度卡片：前 10% 天数贡献的时长占比"""
    share = stats["top_share"]
    if share is None:
        return _card("加班集中度", "—", "暂无加班记录")
    return _card("加班集中度", "%d%%" % share,
                 "前 %d 天（共 %d 个加班日）贡献 %g 小时"
                 % (stats["top_days"], stats["day_count_with_hours"], stats["top_hours"]))


def _trend_card(stats):
    """近 N 个月趋势卡片（默认 3 个月，数据不足时退回 2 个月）"""
    window = stats.get("trend_window") or 0
    pct = stats["trend_pct"]
    if pct is None:
        return _card("近 %d 月趋势" % window if window else "短期趋势",
                     stats["trend_text"], "完整月份不足 4 个月")
    return _card("近 %d 月趋势" % window,
                 "%s %s" % (stats["trend_text"], ("+%d%%" if pct > 0 else "%d%%") % pct),
                 "近 %d 月月均 vs 前 %d 月" % (window, window))


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


def _records_table(recent):
    """记录明细表：带日期类型标记、客户端筛选与排序（全量记录）"""
    if not recent:
        return '<div class="panel"><h2>记录明细</h2><div class="empty">暂无记录</div></div>'
    months = sorted({r["date"][:7] for r in recent if len(r["date"]) >= 7}, reverse=True)
    month_opts = '<option value="">全部月份</option>' + "".join(
        '<option value="%s">%s</option>' % (html.escape(m), html.escape(m)) for m in months
    )
    rows = []
    for r in recent:
        badges = "".join(
            '<span class="badge %s">%s</span>'
            % (("night" if label == "夜班" else r["cls"]), html.escape(label))
            for label in r["labels"]
        )
        if r["holiday"]:
            day_badge = '<span class="badge holiday">%s</span>' % html.escape(r["holiday"])
        elif r["makeup"]:
            day_badge = '<span class="badge makeup">调休补班</span>'
        else:
            day_badge = ('<span class="badge daytype">%s</span>'
                         % html.escape(DAY_TYPE_NAMES[r["calendar_type"]]))
        cells = [
            "<b>%s</b> %s" % (html.escape(r["date"]), html.escape(r["weekday"])),
            day_badge,
            badges,
            html.escape(r["time"]),
            ("%g 小时" % r["ot"]) if r["ot"] else "—",
            ("%g 小时" % r["extra"]) if r["extra"] else "—",
            ("%.2f 小时 %s" % (r["leave_hours"], html.escape(r["leave_type"]))) if r["leave_hours"] is not None else "—",
            ("¥%.2f" % r["pay"]) if r["pay"] is not None else "—",
            html.escape(r["rate_text"]),
            html.escape(r["note"]),
            html.escape(r["device"]),
            ('<button class="row-btn" data-edit="1">编辑</button>'
             '<button class="row-btn del" data-del="1">删除</button>'),
        ]
        type_keys = []
        if r["ot"]:
            type_keys.append("ot")
        if r["extra"]:
            type_keys.append("extra")
        if r["leave_hours"] is not None:
            type_keys.append("leave")
        rows.append(
            '<tr class="rec" data-date="%s" data-month="%s" data-hours="%g" data-type="%s"'
            ' data-ot="%s" data-extra="%s" data-rate="%s" data-note="%s">%s</tr>'
            % (html.escape(r["date"]), html.escape(r["date"][:7]), r["total"],
               " ".join(type_keys),
               ("%g" % r["ot"]) if r["ot"] is not None else "-1",
               ("%g" % r["extra"]) if r["extra"] is not None else "-1",
               ("%g" % r["custom_raw"]) if r["custom_raw"] is not None else "",
               html.escape(r["note"]),
               "".join("<td>%s</td>" % c for c in cells))
        )
    head = "".join("<th>%s</th>" % h for h in
                   ["日期", "日期类型", "类型", "上下班", "加班", "加点", "请假",
                    "预估加班费", "计费倍率", "备注", "来源设备", "操作"])
    return ('<div class="panel"><h2>记录明细（共 %d 条）'
            '<a class="export" href="/export.csv">下载全部 CSV</a></h2>'
            '<div class="filters">'
            '<select id="f-month">%s</select>'
            '<select id="f-type">'
            '<option value="">全部类型</option>'
            '<option value="ot">仅加班</option>'
            '<option value="extra">仅加点</option>'
            '<option value="leave">仅请假</option>'
            '</select>'
            '<input id="f-kw" type="search" placeholder="关键字：备注 / 设备 / 日期">'
            '<select id="f-sort">'
            '<option value="date_desc">日期 新→旧</option>'
            '<option value="date_asc">日期 旧→新</option>'
            '<option value="hours_desc">时长 多→少</option>'
            '<option value="hours_asc">时长 少→多</option>'
            '</select>'
            '<span class="count" id="f-count">按当前条件筛选</span>'
            '</div>'
            '<div style="overflow-x:auto"><table><thead><tr>%s</tr></thead>'
            '<tbody id="records-body">%s</tbody></table></div></div>'
            % (len(recent), month_opts, head, "".join(rows)))


CSV_HEADERS = ["日期", "星期", "日期类型", "法定节假日", "加班(小时)", "加点(小时)", "合计(小时)",
               "计费倍率", "预估加班费(元)", "请假类型", "请假(小时)", "上下班", "备注", "来源设备"]


def build_csv(stats):
    """全量记录导出为 CSV（UTF-8 BOM，Excel 直接打开不乱码）"""
    buf = io.StringIO()
    writer = csv.writer(buf, lineterminator="\r\n")
    writer.writerow(CSV_HEADERS)
    for r in stats["recent"]:
        if r["holiday"]:
            day_type_text = "法定节假日"
        elif r["makeup"]:
            day_type_text = "调休补班"
        else:
            day_type_text = DAY_TYPE_NAMES[r["calendar_type"]]
        writer.writerow([
            r["date"],
            r["weekday"],
            day_type_text,
            r["holiday"] or "",
            ("%g" % r["ot"]) if r["ot"] else "",
            ("%g" % r["extra"]) if r["extra"] else "",
            "%g" % r["total"] if r["total"] else "",
            r["rate_text"],
            ("%.2f" % r["pay"]) if r["pay"] is not None else "",
            r["leave_type"],
            ("%g" % r["leave_hours"]) if r["leave_hours"] is not None else "",
            r["time"],
            r["note"],
            r["device"],
        ])
    return "\ufeff" + buf.getvalue()


# ---------------------------------------------------------------------------
# 轮询与状态
# ---------------------------------------------------------------------------

def _show_windows_notification(title, message):
    """弹出 Windows 气泡通知（失败时退化为系统提示音，不影响主流程）"""
    if sys.platform != "win32":
        print("[通知] %s：%s" % (title, message))
        return
    env = os.environ.copy()
    env["PORE_TOAST_TITLE"] = title
    env["PORE_TOAST_BODY"] = message
    script = (
        "Add-Type -AssemblyName System.Windows.Forms;"
        "Add-Type -AssemblyName System.Drawing;"
        "$n=New-Object System.Windows.Forms.NotifyIcon;"
        "$n.Icon=[System.Drawing.SystemIcons]::Warning;"
        "$n.Visible=$true;"
        "$n.ShowBalloonTip(8000,$env:PORE_TOAST_TITLE,$env:PORE_TOAST_BODY,"
        "[System.Windows.Forms.ToolTipIcon]::Warning);"
        "Start-Sleep -Milliseconds 6500;"
        "$n.Dispose()"
    )
    try:
        subprocess.run(
            ["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
            env=env, timeout=25, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception:
        try:
            import winsound
            winsound.MessageBeep(0x00000030)  # MB_ICONWARNING
        except Exception:
            pass


DEVICE_NAME_PC = "PC 监控"
EDIT_HOUR_MAX = 24.0
EDIT_RATE_RANGE = (1.0, 4.0)


def _edit_hours(value, label):
    """校验加班/加点时长：空或 -1 表示未登记，否则 0~24"""
    if value is None or value == "":
        return -1.0
    v = float(value)
    if v == -1.0:
        return -1.0
    if v < 0 or v > EDIT_HOUR_MAX:
        raise ValueError("%s 需为 0~24 小时（留空表示未登记）" % label)
    return round(v, 2)


def apply_record_edit(backup, edit, now_ms=None):
    """修改/软删除指定日期的已有记录（MVP 不新增日期）。

    原地修改 backup，返回 {date, action, updated, values}。
    与 App 的 NEWER_WINS 保持一致：写入 modifiedAt=当前时间 + lastModifiedBy。
    """
    now_ms = now_ms or int(time.time() * 1000)
    date_s = str(edit.get("date") or "").strip()
    if _parse_date(date_s) is None:
        raise ValueError("日期格式应为 yyyy-MM-dd")
    action = edit.get("action") or "update"
    if action not in ("update", "delete"):
        raise ValueError("不支持的 action：%s" % action)

    records = backup.get("attendanceRecords") or []
    targets = [r for r in records if r.get("date") == date_s and not r.get("isDeleted")]
    if not targets:
        raise ValueError("%s 没有可编辑的活跃记录（MVP 仅支持修改已有记录）" % date_s)

    if action == "delete":
        for r in targets:
            r["isDeleted"] = True
            r["modifiedAt"] = now_ms
            r["lastModifiedBy"] = DEVICE_NAME_PC
        return {"date": date_s, "action": action, "updated": len(targets), "values": None}

    values = {}
    if "manualOvertimeHours" in edit:
        values["manualOvertimeHours"] = _edit_hours(edit.get("manualOvertimeHours"), "加班时长")
    if "manualExtraHours" in edit:
        values["manualExtraHours"] = _edit_hours(edit.get("manualExtraHours"), "加点时长")
    if "customRate" in edit:
        raw_rate = edit.get("customRate")
        if raw_rate is None or raw_rate == "":
            values["customRate"] = None
        else:
            rate = round(float(raw_rate), 3)
            low, high = EDIT_RATE_RANGE
            if rate < low or rate > high:
                raise ValueError("自定义倍率需在 %.1f~%.1f 之间（留空按默认倍率）" % (low, high))
            values["customRate"] = rate
    if "note" in edit:
        note = str(edit.get("note") or "").strip()
        if len(note) > 500:
            raise ValueError("备注最多 500 字")
        values["note"] = note or None

    for r in targets:
        for k, v in values.items():
            if v is None:
                r.pop(k, None)
            else:
                r[k] = v
        r["modifiedAt"] = now_ms
        r["lastModifiedBy"] = DEVICE_NAME_PC
    return {"date": date_s, "action": action, "updated": len(targets), "values": values}


def _verify_edit_applied(backup, result):
    """回读校验：确认云端已包含本次修改"""
    recs = [r for r in backup.get("attendanceRecords") or [] if r.get("date") == result["date"]]
    if not recs:
        return False
    if result["action"] == "delete":
        return all(bool(r.get("isDeleted")) for r in recs)

    active = [r for r in recs if not r.get("isDeleted")]
    if len(active) != result["updated"]:
        return False
    for key, expected in (result.get("values") or {}).items():
        for r in active:
            actual = r.get(key)
            if key in ("manualOvertimeHours", "manualExtraHours"):
                if abs(float(actual if actual is not None else -1) - expected) > 1e-6:
                    return False
            elif key == "customRate":
                if expected is None:
                    if actual not in (None, -1):
                        return False
                elif abs(float(actual or 0) - expected) > 1e-9:
                    return False
            else:
                if (actual or None) != expected:
                    return False
    return True


def archive_cloud_backup(raw_text, keep=50):
    """写回前把云端原始内容留一份本地快照（pc-monitor/backup_history/）"""
    base = os.path.dirname(os.path.abspath(__file__))
    folder = os.path.join(base, "backup_history")
    os.makedirs(folder, exist_ok=True)
    # 用微秒精度命名：连续两次保存若落在同一毫秒会互相覆盖快照
    path = os.path.join(folder, "overtime_backup_%s.json" % datetime.now().strftime("%Y%m%d_%H%M%S_%f"))
    with open(path, "w", encoding="utf-8") as f:
        f.write(raw_text)
    # 只保留最近 keep 份，避免无限增长
    files = sorted((os.path.join(folder, n) for n in os.listdir(folder)
                    if n.startswith("overtime_backup_")), reverse=True)
    for old in files[keep:]:
        try:
            os.remove(old)
        except OSError:
            pass
    return path


class MonitorState:
    def __init__(self, cfg):
        self.cfg = cfg
        self.lock = threading.Lock()
        self.write_lock = threading.Lock()
        self.snapshot = None
        self.last_error = None
        self.last_attempt_ms = None
        self.last_ok_ms = None
        self.last_notify_ms = None
        self.last_write_ms = None
        self.version = 0
        self.refresh_event = threading.Event()
        self.stop_event = threading.Event()

    def _notify_failure(self, message):
        """拉取失败通知：同一次故障只提示一次，10 分钟内不重复打扰"""
        if not self.cfg.get("notify_on_error", True):
            return
        now_ms = time.time() * 1000
        if self.last_notify_ms and now_ms - self.last_notify_ms < 600_000:
            return
        self.last_notify_ms = now_ms
        threading.Thread(
            target=_show_windows_notification,
            args=("加班记录监控：云端拉取失败", str(message)[:180]),
            daemon=True,
        ).start()

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
                "stats": compute_stats(backup, targets=self.cfg),
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
                is_new_failure = self.last_error != str(e)
                self.last_error = str(e)
                self.version += 1
            print("[%s] 拉取失败：%s" % (datetime.now().strftime("%H:%M:%S"), e))
            if is_new_failure:
                self._notify_failure(str(e))

    def save_record(self, edit):
        """修改/软删除云端已有记录：重新拉取最新云端 → 修改 → 条件写回 → 回读校验。

        写入策略（避免覆盖并发修改）：
        1. 每次保存都重新 GET 云端，只在最新内容上改一条记录；
        2. PUT 带 If-Match/If-Unmodified-Since，冲突则重试一次；
        3. 写回前本地归档原始密文，写回后重新下载并解密校验。
        """
        pw = (self.cfg.get("sync_password") or "").strip() or None
        rc = (self.cfg.get("recovery_code") or "").strip() or None
        with self.write_lock:
            last_error = None
            for attempt in (1, 2):
                raw, last_modified, etag, _url = fetch_backup_snapshot(self.cfg)
                backup, _meta, ctx = parse_wire(raw, pw, rc, want_ctx=True)
                if not isinstance(backup, dict) or "attendanceRecords" not in backup:
                    raise ValueError("云端内容不是有效的备份数据（缺少 attendanceRecords）")
                if backup.get("checksum") and verify_checksum(backup) != "ok":
                    raise ValueError(
                        "云端 checksum 与本机序列化复刻不一致，为避免写坏数据已拒绝保存")
                result = apply_record_edit(backup, edit)
                if backup.get("checksum"):
                    backup["checksum"] = compute_records_checksum(
                        backup.get("attendanceRecords") or [])
                wire_text = seal_wire(ctx, backup, pw)
                archive_path = archive_cloud_backup(raw)
                try:
                    upload_backup_raw(self.cfg, wire_text, etag, last_modified)
                except WriteConflictError as e:
                    last_error = e
                    if attempt == 1:
                        continue
                    raise
                # 回读校验：确认云端已是本次修改后的内容
                raw2, _lm2, _e2, _u2 = fetch_backup_snapshot(self.cfg)
                verified, _vmeta = parse_wire(raw2, pw, rc)
                if not _verify_edit_applied(verified, result):
                    raise IOError("回读校验失败：云端内容与本次修改不一致，已保留本地快照 %s" % archive_path)
                self.do_poll()
                with self.lock:
                    self.last_write_ms = time.time()
                return {
                    "date": result["date"],
                    "action": result["action"],
                    "updated": result["updated"],
                    "archive": os.path.basename(archive_path),
                }
            raise last_error or IOError("保存失败")

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

        def _send(self, code, content_type, body_bytes, extra_headers=None):
            self.send_response(code)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body_bytes)))
            self.send_header("Cache-Control", "no-store")
            for k, v in (extra_headers or {}).items():
                self.send_header(k, v)
            self.end_headers()
            self.wfile.write(body_bytes)

        def do_GET(self):
            if self.path in ("/", "/index.html"):
                status = state.get_status()
                with state.lock:
                    snap = state.snapshot
                page = render_dashboard(snap, status)
                self._send(200, "text/html; charset=utf-8", page.encode("utf-8"))
            elif self.path == "/fragment":
                # 前端局部刷新：只返回仪表盘容器，不重新加载整页
                status = state.get_status()
                with state.lock:
                    snap = state.snapshot
                frag = render_fragment(snap, status).encode("utf-8")
                self._send(200, "text/html; charset=utf-8", frag)
            elif self.path.split("?")[0] == "/export.csv":
                with state.lock:
                    snap = state.snapshot
                if snap is None:
                    self._send(503, "text/plain; charset=utf-8",
                               "暂无数据，请等待云端拉取完成".encode("utf-8"))
                else:
                    csv_text = build_csv(snap["stats"])
                    filename = "overtime_%s.csv" % datetime.now().strftime("%Y%m%d")
                    self._send(200, "text/csv; charset=utf-8", csv_text.encode("utf-8"),
                               {"Content-Disposition": 'attachment; filename="%s"' % filename})
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

        def _local_request(self):
            """只接受本机来源的写入请求（防 DNS rebinding / 恶意网页跨站写云端）"""
            host = (self.headers.get("Host") or "").lower()
            if not (host.startswith("127.0.0.1") or host.startswith("localhost")
                    or host.startswith("[::1]")):
                return False
            origin = (self.headers.get("Origin") or "").lower()
            if origin and not (origin.startswith("http://127.0.0.1")
                               or origin.startswith("http://localhost")):
                return False
            return True

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
                payload = json.dumps(
                    {"ok": state.get_status()["last_error"] is None,
                     "status": state.get_status()},
                    ensure_ascii=False).encode("utf-8")
                self._send(200, "application/json; charset=utf-8", payload)
            elif self.path == "/api/record":
                if not self._local_request():
                    self._send(403, "application/json; charset=utf-8",
                               '{"ok":false,"error":"仅允许本机页面写入"}'.encode("utf-8"))
                    return
                try:
                    length = int(self.headers.get("Content-Length") or 0)
                    if length <= 0 or length > 64 * 1024:
                        raise ValueError("请求体大小非法")
                    edit = json.loads(self.rfile.read(length).decode("utf-8"))
                    if not isinstance(edit, dict):
                        raise ValueError("请求体必须是 JSON 对象")
                    body = {"ok": True, "result": state.save_record(edit)}
                    code = 200
                except Exception as e:
                    body = {"ok": False, "error": str(e)}
                    code = 400
                self._send(code, "application/json; charset=utf-8",
                           json.dumps(body, ensure_ascii=False).encode("utf-8"))
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
        "customRate": None, "shiftType": "DAY", "isDeleted": False, "lastModifiedBy": "Xiaomi 14",
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
        assert '"shiftType":"DAY"' in s, s
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

    def t_envelope_reseal():
        """重封信封必须保留原 header（两个密码槽），否则恢复码会失效"""
        pt = json.dumps(sample_backup, ensure_ascii=False).encode("utf-8")
        wire = make_envelope("p@ss1234", "ABCD-EFGH-IJKL-MNOP", pt)
        backup, _meta, ctx = parse_wire(wire, password="p@ss1234", want_ctx=True)
        header_len = 5 + SLOT_SIZE * 2
        blob = base64.b64decode(wire[len(WIRE_PREFIX):])
        assert ctx["header"] == blob[:header_len], "seal_ctx 未保留原始 header"
        backup["attendanceRecords"][0]["manualOvertimeHours"] = 6.0
        wire2 = seal_wire(ctx, backup, "p@ss1234")
        blob2 = base64.b64decode(wire2[len(WIRE_PREFIX):])
        assert blob2[:header_len] == blob[:header_len], "重封改动了密码槽，恢复码将失效"
        assert blob2[header_len:header_len + GCM_IV_SIZE] != blob[header_len:header_len + GCM_IV_SIZE]
        # 恢复码（归一化写法）与同步密码都必须仍能解开新密文
        out, meta = parse_wire(wire2, recovery_code="abcd efgh ijkl mnop")
        assert meta["key_source"] == "recovery_code"
        assert out["attendanceRecords"][0]["manualOvertimeHours"] == 6.0
        out2, _ = parse_wire(wire2, password="p@ss1234")
        assert out2["attendanceRecords"][0]["manualOvertimeHours"] == 6.0

    check("信封重封（保留密码槽/恢复码可用）", t_envelope_reseal)

    def t_edit_update():
        backup = json.loads(json.dumps(sample_backup, ensure_ascii=False))
        res = apply_record_edit(
            backup,
            {"date": "2026-10-17", "manualOvertimeHours": 6,
             "manualExtraHours": "", "customRate": 2.5, "note": "  改备注  "},
            now_ms=1700000000000)
        rec = backup["attendanceRecords"][0]
        assert res["action"] == "update" and res["updated"] == 1, res
        assert rec["manualOvertimeHours"] == 6.0 and rec["manualExtraHours"] == -1.0, rec
        assert rec["customRate"] == 2.5 and rec["note"] == "改备注", rec
        assert rec["modifiedAt"] == 1700000000000 and rec["lastModifiedBy"] == DEVICE_NAME_PC
        # 未提交的字段保持原值
        assert rec["checkInTime"] == "09:00" and rec["date"] == "2026-10-17"
        # checksum 必须跟着更新，否则 App 恢复时会因校验失败中止
        backup["checksum"] = compute_records_checksum(backup["attendanceRecords"])
        assert verify_checksum(backup) == "ok"
        assert _verify_edit_applied(backup, res)

    check("记录编辑（部分字段/checksum 同步）", t_edit_update)

    def t_edit_clear_and_delete():
        backup = json.loads(json.dumps(sample_backup, ensure_ascii=False))
        rec = backup["attendanceRecords"][0]
        rec["customRate"] = 3.0
        rec["note"] = "旧备注"
        res = apply_record_edit(backup, {"date": "2026-10-17", "customRate": None, "note": ""})
        assert "customRate" not in rec and "note" not in rec, rec
        assert _verify_edit_applied(backup, res)
        # 软删除：置 isDeleted 而非移除（跨设备同步删除依赖该标记）
        res_del = apply_record_edit(backup, {"date": "2026-10-17", "action": "delete"})
        assert rec["isDeleted"] is True and len(backup["attendanceRecords"]) == 1, rec
        assert res_del["updated"] == 1 and _verify_edit_applied(backup, res_del)
        # 已删除记录不可再编辑（MVP 不支持新增/复活）
        try:
            apply_record_edit(backup, {"date": "2026-10-17", "manualOvertimeHours": 1})
            raise AssertionError("已软删除记录竟可编辑")
        except ValueError:
            pass

    check("记录编辑（清空字段/软删除）", t_edit_clear_and_delete)

    def t_edit_invalid():
        cases = [
            {"date": "2026-10-17", "manualOvertimeHours": 25},      # 超范围
            {"date": "2026-10-17", "manualExtraHours": -2},          # 非法负值
            {"date": "2026-10-17", "customRate": 5},                 # 倍率超范围
            {"date": "2026-10-17", "action": "create"},              # MVP 不支持新增
            {"date": "2026-13-01", "manualOvertimeHours": 1},        # 非法日期
            {"date": "2026-10-16", "manualOvertimeHours": 1},        # 无活跃记录
        ]
        for case in cases:
            backup = json.loads(json.dumps(sample_backup, ensure_ascii=False))
            try:
                apply_record_edit(backup, case)
                raise AssertionError("非法编辑未被拒绝：%s" % case)
            except ValueError:
                pass

    check("记录编辑（非法输入拒绝）", t_edit_invalid)

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
        # 本年拆分：加班 4h / 加点 3.5h；周末 5.5h（10-17 周六）、工作日 2h（10-16 周五）
        assert st["year_ot"] == 4.0 and st["year_extra"] == 3.5, (st["year_ot"], st["year_extra"])
        assert st["year_weekend_hours"] == 5.5 and st["year_weekday_hours"] == 2.0
        assert st["year_weekend_share"] == 73, st["year_weekend_share"]
        # 峰值日：2026-10-17 共 5.5h
        assert st["peak_day"]["date"] == "2026-10-17" and st["peak_day"]["hours"] == 5.5, st["peak_day"]
        assert st["peak_day"]["weekday"] == "周六", st["peak_day"]
        # 集中度：2 个加班日中前 1 天贡献 5.5/7.5 ≈ 73%
        assert st["top_days"] == 1 and st["top_share"] == 73, (st["top_days"], st["top_share"])
        # 同比无去年数据；趋势完整月不足
        assert st["yoy_pct"] is None and st["prev_year_hours"] == 0.0
        assert st["trend_text"] == "数据不足"
        # 季度：仅 2026Q4 有数据，开头的空季度已被裁剪
        assert st["quarterly"][-1]["label"] == "2026Q4", st["quarterly"]
        assert st["quarterly"][-1]["hours"] == 7.5, st["quarterly"][-1]
        assert len(st["quarterly"]) == 1, st["quarterly"]

    check("统计口径（时长/请假/加班费/删除）", t_stats)

    def t_shift():
        """班次字段：Gson 键序/null 省略、夜班标签、月度按班次拆分、非法值兜底"""
        night = dict(sample_record, date="2026-10-18", shiftType="NIGHT")
        s = gson_like_record(night)
        assert '"shiftType":"NIGHT"' in s, s
        # 键序与 Android 声明序一致：shiftType 在 customRate 之后、isDeleted 之前
        assert s.index('"leaveHours"') < s.index('"shiftType"') < s.index('"isDeleted"'), s
        # null 省略：shiftType=None 与缺失该字段的 checksum 相同（旧备份兼容）
        no_shift = dict(night)
        del no_shift["shiftType"]
        assert compute_records_checksum([dict(night, shiftType=None)]) == compute_records_checksum([no_shift])
        # 视图：夜班标签；白班/请假不贴
        rv = _record_view(night, 46.0, (1.5, 2.0, 3.0))
        assert rv["shift"] == "NIGHT" and rv["is_night"] and "夜班" in rv["labels"], rv["labels"]
        assert "夜班" not in _record_view(sample_record, 46.0, (1.5, 2.0, 3.0))["labels"]
        rv_leave = _record_view(dict(night, isLeave=True, manualOvertimeHours=-1.0,
                                     manualExtraHours=-1.0), 46.0, (1.5, 2.0, 3.0))
        assert not rv_leave["is_night"] and "夜班" not in rv_leave["labels"]
        # 月度统计按班次拆分（口径 = 加班+加点，与 App 一致）
        now = datetime(2026, 10, 20, 12, 0, 0)
        backup = json.loads(json.dumps(sample_backup, ensure_ascii=False))
        backup["attendanceRecords"] = [sample_record, night]
        st = compute_stats(backup, now=now)
        assert st["month_day_hours"] == 5.5 and st["month_night_hours"] == 5.5, st
        assert st["month_hours"] == 11.0, st["month_hours"]
        # 非法班次值兜底为白班，不丢记录
        backup["attendanceRecords"] = [dict(sample_record, shiftType="XXX")]
        st2 = compute_stats(backup, now=now)
        assert st2["month_day_hours"] == 5.5 and st2["month_night_hours"] == 0.0, st2

    check("班次字段（shiftType 序列化/标签/月度拆分）", t_shift)

    def t_stats_trend():
        backup = json.loads(json.dumps(sample_backup, ensure_ascii=False))
        recs = []
        for ym, h in [("2026-04", 4.0), ("2026-05", 4.0), ("2026-06", 4.0),
                      ("2026-07", 8.0), ("2026-08", 8.0), ("2026-09", 8.0)]:
            recs.append(dict(sample_record, date=ym + "-10", manualOvertimeHours=h,
                             manualExtraHours=-1.0, checkOutTime="21:00",
                             checkInTimestamp=None, checkOutTimestamp=None))
        recs.append(dict(sample_record, date="2025-10-15", manualOvertimeHours=2.0,
                         manualExtraHours=-1.0))
        backup["attendanceRecords"] = recs
        now = datetime(2026, 10, 20, 12, 0, 0)
        st = compute_stats(backup, now=now)
        # 同比：本月 0h vs 去年同月 2h → -100%
        assert st["prev_year_hours"] == 2.0 and st["yoy_pct"] == -100, (st["prev_year_hours"], st["yoy_pct"])
        # 趋势：前 3 个完整月（4/5/6 月）均 4h，最近 3 个完整月（7/8/9 月）均 8h → +100% 上升
        assert st["trend_text"] == "上升" and st["trend_pct"] == 100, (st["trend_text"], st["trend_pct"])
        assert st["trend_window"] == 3, st["trend_window"]
        # 本年拆分只统计 2026 年（不含 2025 年那条）
        assert st["year_ot"] == 36.0, st["year_ot"]
        # 季度聚合：2026Q2 = 12h，2026Q3 = 24h
        q = {x["label"]: x["hours"] for x in st["quarterly"]}
        assert q.get("2026Q2") == 12.0 and q.get("2026Q3") == 24.0, q
        assert st["peak_day"]["hours"] == 8.0, st["peak_day"]

    check("统计口径（同比/趋势/季度）", t_stats_trend)

    def t_stats_custom_rate():
        backup = json.loads(json.dumps(sample_backup, ensure_ascii=False))
        backup["attendanceRecords"] = [dict(sample_record, customRate=3.0)]
        now = datetime(2026, 10, 20, 12, 0, 0)
        st = compute_stats(backup, now=now)
        hourly = 8000.0 / (21.75 * 8.0)
        expect = 5.5 * hourly * 3.0
        assert abs(st["month_pay"] - expect) < 0.01, (st["month_pay"], expect)

    check("统计口径（自定义倍率覆盖）", t_stats_custom_rate)

    def t_holiday_rules():
        # 日历类型：法定节假日 > 调休补班(工作日) > 周末 > 工作日
        assert calendar_day_type("2026-10-01") == DAY_HOLIDAY
        assert get_holiday_name("2026-10-01") == "国庆节"
        assert calendar_day_type("2026-09-20") == DAY_WORKDAY      # 周日调休补班
        assert is_makeup_workday("2026-10-10")
        assert calendar_day_type("2026-10-10") == DAY_WORKDAY      # 周六调休补班
        assert calendar_day_type("2026-10-17") == DAY_WEEKEND
        assert calendar_day_type("2026-10-16") == DAY_WORKDAY
        # 登记即分类（与 App OvertimeCalculator.effectiveDayType 一致）
        assert effective_day_type("2026-10-01", 4.0, 0) == DAY_HOLIDAY
        assert effective_day_type("2026-10-17", 4.0, 0) == DAY_WEEKEND
        assert effective_day_type("2026-10-16", 4.0, 0) == DAY_WEEKEND
        assert effective_day_type("2026-10-01", 0, 2.0) == DAY_WORKDAY

        backup = json.loads(json.dumps(sample_backup, ensure_ascii=False))
        backup["attendanceRecords"] = [
            dict(sample_record, date="2026-10-01", manualOvertimeHours=4.0, manualExtraHours=-1.0),
            dict(sample_record, date="2026-09-20", manualOvertimeHours=2.0, manualExtraHours=-1.0),
            dict(sample_record, date="2026-10-10", manualOvertimeHours=1.0, manualExtraHours=-1.0),
        ]
        now = datetime(2026, 10, 20, 12, 0, 0)
        st = compute_stats(backup, now=now)
        hourly = 8000.0 / (21.75 * 8.0)
        # 本月仅 10-01/10-10：法定节假日 3 倍；调休补班日登记加班仍按 App 口径以周末倍率计
        expect = 4.0 * hourly * 3.0 + 1.0 * hourly * 2.0
        assert abs(st["month_pay"] - expect) < 0.01, (st["month_pay"], expect)
        assert st["holiday_ot_hours"] == 4.0 and st["holiday_days"] == 1, st["holiday_ot_hours"]
        assert st["makeup_ot_hours"] == 3.0 and st["makeup_days"] == 2, st["makeup_ot_hours"]
        assert st["year_holiday_hours"] == 4.0, st["year_holiday_hours"]
        # 备份内设置可覆盖节假日倍率
        backup["settings"] = {"baseSalary": 8000.0, "dailyWorkHours": 8.0,
                              "overtimeRateHoliday": 4.0}
        st2 = compute_stats(backup, now=now)
        expect2 = 4.0 * hourly * 4.0 + 1.0 * hourly * 2.0
        assert abs(st2["month_pay"] - expect2) < 0.01, (st2["month_pay"], expect2)

    check("节假日/调休口径与倍率", t_holiday_rules)

    def t_targets():
        backup = json.loads(json.dumps(sample_backup, ensure_ascii=False))
        backup["attendanceRecords"] = [
            dict(sample_record, manualOvertimeHours=4.0, manualExtraHours=1.5)]
        now = datetime(2026, 10, 20, 12, 0, 0)
        base = compute_stats(backup, now=now)
        assert base["month_progress_hours"] is None and base["month_target_hours"] == 0.0
        st = compute_stats(backup, now=now, targets={"month_target_hours": 11})
        assert st["month_target_hours"] == 11.0 and st["month_progress_hours"] == 50, st["month_progress_hours"]
        st2 = compute_stats(backup, now=now, targets={"month_target_pay": base["month_pay"] * 2})
        assert st2["month_progress_pay"] == 50, st2["month_progress_pay"]

    check("月度目标进度", t_targets)

    def t_csv():
        backup = json.loads(json.dumps(sample_backup, ensure_ascii=False))
        backup["attendanceRecords"] = [
            dict(sample_record, date="2026-10-01", manualOvertimeHours=4.0,
                 manualExtraHours=-1.0, note='含,逗号"引号'),
            dict(sample_record, date="2026-10-18", isLeave=True, leaveType="年假",
                 leaveHours=8.0, manualOvertimeHours=-1.0, manualExtraHours=-1.0, note=""),
        ]
        st = compute_stats(backup, now=datetime(2026, 10, 20, 12, 0, 0))
        text = build_csv(st)
        assert text.startswith("\ufeff"), "缺少 UTF-8 BOM"
        lines = text.lstrip("\ufeff").split("\r\n")
        assert lines[0] == ",".join(CSV_HEADERS), lines[0]
        holiday_row = next(ln for ln in lines if ln.startswith("2026-10-01"))
        assert "法定节假日,国庆节" in holiday_row, holiday_row
        assert "3×（法定节假日）" in holiday_row, holiday_row
        assert '"含,逗号""引号"' in text, text

    check("CSV 导出（表头/BOM/转义）", t_csv)

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
