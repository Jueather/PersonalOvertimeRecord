<#
.SYNOPSIS
    PersonalOvertimeRecord 信封加密/恢复码 —— 真机端到端验收脚本（半自动化）

.DESCRIPTION
    对应 docs/信封加密恢复码方案实施总结.md 第 12 节的 7 组用例。

    自动化部分（adb）：
      - 全新安装 / pm clear 模拟重装换机
      - uiautomator 查找控件并点击、填写密码/恢复码、切换开关
      - logcat 判定同步成功 / 密码不匹配
      - 直接 HTTP 拉取 WebDAV 上的 overtime_backup.json：
          * 校验 PORE_ENV1: 前缀（信封）或明文 JSON
          * 计算"恢复槽"（二进制偏移 81..156，共 76 字节）的 SHA256：
            重装接入 / 恢复码找回 / 改密码后该哈希必须不变（= 原恢复码仍有效）；
            主动更换恢复码后该哈希必须变化（= 旧恢复码已失效）。
      - 全程截图 + Markdown 报告（含每条用例的自动证据）

    人工部分（脚本会暂停等待）：
      - 生物识别认证（任何 adb 命令都无法代替指纹/面容）
      - 新增加班记录、飞行模式开关等系统级操作

    PowerShell 5.1 / 7+ 均可运行；需要 adb 在 PATH 中且设备已 USB 调试授权。

.EXAMPLE
    # 最小运行：交互询问 WebDAV 信息（也可在设备上手动配置后直接回车跳过）
    .\Run-DeviceAcceptance.ps1

.EXAMPLE
    # 完整运行（自动构建 APK、自动填写 WebDAV 配置）
    .\Run-DeviceAcceptance.ps1 -Build `
        -WebDavBaseUrl "https://dav.example.com" -WebDavPath "/overtime_record/" `
        -WebDavUser "me@x.com" -WebDavPass "app-passwd"

.EXAMPLE
    # 只回归第三组（重装接入，原恢复码不得失效）
    .\Run-DeviceAcceptance.ps1 -Groups 3 -WebDavBaseUrl "https://dav.example.com" `
        -WebDavUser "me" -WebDavPass "pw"

.NOTES
    自定义 WebDAV 账号密码仅用于脚本直接 GET/PUT 备份文件做校验，不会写入设备以外的地方。
#>
[CmdletBinding()]
param(
    # adb 设备序列号（多设备时用 adb devices 查看；单设备可省略）
    [string]$DeviceId,

    # 运行前先执行 assembleDebug
    [switch]$Build,

    # 指定 APK；缺省使用 app/build/outputs/apk/debug/app-debug.apk
    [string]$ApkPath,

    # 全新安装（先卸载）为默认行为；加 -KeepInstall 可复用已装应用（不清数据）
    [switch]$KeepInstall,

    # 要执行的用例组，默认 1..7
    [int[]]$Groups = @(1, 2, 3, 4, 5, 6, 7),

    [string]$WebDavBaseUrl = "",
    [string]$WebDavPath = "/overtime_record/",
    [string]$WebDavUser = "",
    [string]$WebDavPass = "",

    # 第六组用：本地旧版备份文件（明文 JSON 或旧版密码密文），脚本会 PUT 到云端触发迁移
    [string]$LegacyFile = "",

    # 跳过 HTTPS 证书校验（自签证书的 NAS 场景）
    [switch]$InsecureSkipCert,

    [string]$Password1 = "P@ss1",
    [string]$Password2 = "P@ss2",
    [string]$WrongPassword = "Wrong-Pass-9",

    # 单次同步等待日志结果的超时秒数
    [int]$SyncTimeoutSec = 120,

    # 报告输出目录
    [string]$ReportDir = ""
)

$ErrorActionPreference = "Stop"
$Package = "com.example.personalovertimerecord"
$BackupFileName = "overtime_backup.json"
$WirePrefix = "PORE_ENV1:"

# ---------------------------------------------------------------- 基础工具

if ($InsecureSkipCert) {
    [System.Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }
}

if (-not $ReportDir) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $ReportDir = Join-Path $PSScriptRoot "reports\$stamp"
}
New-Item -ItemType Directory -Force -Path $ReportDir | Out-Null
$CodesFile = Join-Path $ReportDir "recovery-codes.txt"
"# 验收过程中捕获的恢复码（明文，验收后请删除本文件）" | Out-File -FilePath $CodesFile -Encoding utf8

$script:Results = New-Object System.Collections.Generic.List[object]
$script:ShotIndex = 0
$script:SlotHashH1 = $null   # 首次信封上传后的恢复槽哈希（R1 谱系）
$script:R1 = ""
$script:R2 = ""

function Write-Step([string]$msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg)   { Write-Host "    [OK] $msg" -ForegroundColor Green }
function Write-Warn2([string]$msg){ Write-Host "    [!]  $msg" -ForegroundColor Yellow }

function Adb {
    if ($DeviceId) { & adb -s $DeviceId @args }
    else { & adb @args }
}

function Record-Case([string]$Id, [string]$Expect, [string]$Status, [string]$Evidence = "") {
    $script:Results.Add([pscustomobject]@{
        用例 = $Id; 预期 = $Expect; 结果 = $Status; 证据 = $evidence; 时间 = (Get-Date -Format "HH:mm:ss")
    }) | Out-Null
    $color = if ($Status -eq "通过") { "Green" } elseif ($Status -eq "失败") { "Red" } else { "Yellow" }
    Write-Host "    [$Status] $Id" -ForegroundColor $color
}

function Take-Screenshot([string]$tag) {
    $script:ShotIndex++
    $n = "{0:D2}" -f $script:ShotIndex
    $remote = "/sdcard/shot_$n.png"
    $local = Join-Path $ReportDir ("{0}_{1}.png" -f $n, ($tag -replace '[\\/:*?""<>| ]', '_'))
    Adb shell screencap -p $remote | Out-Null
    Adb pull $remote $local | Out-Null
    Adb shell rm $remote | Out-Null
    return $local
}

# 人工检查点：脚本说明操作 -> 测试员在设备确认
function Checkpoint-Manual([string]$Id, [string]$Expect, [string]$Instruction) {
    Take-Screenshot $Id | Out-Null
    Write-Host "`n    ┌─ 人工确认 [$Id]" -ForegroundColor Magenta
    Write-Host "    │ 预期：$Expect" -ForegroundColor Magenta
    Write-Host "    │ 请操作：$Instruction" -ForegroundColor Magenta
    do { $a = Read-Host "    └ 结果 [y]通过 [n]失败 [s]跳过（默认 y）" } while ($a -and $a -notin @("y","n","s",""))
    switch ($a) {
        "n" { Record-Case $Id $Expect "失败" "人工确认不通过；见截图" }
        "s" { Record-Case $Id $Expect "跳过" "人工跳过；见截图" }
        default { Record-Case $Id $Expect "通过" "人工确认；见截图" }
    }
}

# ---------------------------------------------------------------- UI 自动化

function Get-ScreenSize {
    $line = (Adb shell wm size) -join ""
    if ($line -match "(\d+)x(\d+)") { return @([int]$Matches[1], [int]$Matches[2]) }
    return @(1080, 2400)
}

function Get-UiXml {
    $tmp = Join-Path $env:TEMP ("uidump_{0}.xml" -f ([guid]::NewGuid().ToString("N")))
    Adb shell uiautomator dump -q /sdcard/uidump.xml | Out-Null
    Adb pull /sdcard/uidump.xml $tmp | Out-Null
    if (-not (Test-Path $tmp)) { return $null }
    try {
        $raw = [System.IO.File]::ReadAllText($tmp)
        return [xml]$raw
    } finally {
        Remove-Item $tmp -ErrorAction SilentlyContinue
    }
}

function Find-Nodes($xml, [string]$byId = "", [string]$byText = "", [string]$cls = "") {
    if (-not $xml) { return @() }
    $out = @()
    foreach ($n in $xml.SelectNodes("//node")) {
        $rid = [string]$n.GetAttribute("resource-id")
        $txt = [string]$n.GetAttribute("text")
        if ($byId -and $rid -notmatch (":id/" + [regex]::Escape($byId) + "$")) { continue }
        if ($byText -and -not ($txt -like "*$byText*")) { continue }
        if ($cls -and [string]$n.GetAttribute("class") -ne $cls) { continue }
        $b = [string]$n.GetAttribute("bounds")
        if ($b -notmatch "\[(\-?\d+),(\-?\d+)\]\[(\-?\d+),(\-?\d+)\]") { continue }
        $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]; $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
        $out += [pscustomobject]@{
            Node = $n; Text = $txt; Rid = $rid
            CX = [int](($x1 + $x2) / 2); CY = [int](($y1 + $y2) / 2); X1 = $x1; Y1 = $y1; X2 = $x2; Y2 = $y2
            Checked = ([string]$n.GetAttribute("checked") -eq "true")
            Displayed = ([string]$n.GetAttribute("displayed") -ne "false")
        }
    }
    return $out
}

# 反复尝试让某 resource-id 节点出现在屏幕内并返回（自动上滑）
function Ensure-NodeVisible([string]$id, [int]$tries = 8) {
    $dims = Get-ScreenSize
    for ($i = 0; $i -lt $tries; $i++) {
        $xml = Get-UiXml
        $nodes = @(Find-Nodes $xml -byId $id) | Where-Object { $_.Displayed -and $_.Y2 -lt ($dims[1] - 100) -and $_.Y1 -gt 60 }
        if ($nodes.Count -gt 0) { return $nodes[0] }
        # 上滑
        Adb shell input swipe ([int]($dims[0]/2)) ([int]($dims[1]*0.7)) ([int]($dims[0]/2)) ([int]($dims[1]*0.3)) 300 | Out-Null
        Start-Sleep -Milliseconds 700
    }
    return $null
}

function Tap-Node($node) {
    Adb shell input tap $node.CX $node.CY | Out-Null
    Start-Sleep -Milliseconds 600
}

function Tap-ById([string]$id, [int]$tries = 8) {
    $n = Ensure-NodeVisible $id $tries
    if (-not $n) { Write-Warn2 "未找到控件 $id"; return $false }
    Tap-Node $n
    return $true
}

# AlertDialog 按钮无 resource-id，按文字点
function Tap-ByText([string]$text, [int]$timeoutSec = 3) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $xml = Get-UiXml
        $nodes = @(Find-Nodes $xml -byText $text) | Where-Object { $_.Displayed }
        if ($nodes.Count -gt 0) { Tap-Node $nodes[0]; return $true }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Wait-Text([string]$text, [int]$timeoutSec = 30) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $xml = Get-UiXml
        if (-not $xml) { Start-Sleep -Milliseconds 800; continue }
        $nodes = @(Find-Nodes $xml -byText $text) | Where-Object { $_.Displayed }
        if ($nodes.Count -gt 0) { return $true }
        Start-Sleep -Milliseconds 800
    }
    return $false
}

function Get-AllText {
    $xml = Get-UiXml
    if (-not $xml) { return "" }
    return (($xml.SelectNodes("//node") | ForEach-Object { [string]$_.GetAttribute("text") }) -join "`n")
}

# 抓取界面上出现的恢复码 XXXX-XXXX-XXXX-XXXX
function Find-RecoveryCodeOnScreen {
    $all = Get-AllText
    if ($all -match "([0-9A-HJ-NP-TV-Z]{4}(?:-[0-9A-HJ-NP-TV-Z]{4}){3})") { return $Matches[1] }
    return ""
}

# 清空输入框并填写（input text 对空格用 %s；本脚本密码无空格）
function Set-Field([string]$id, [string]$value) {
    if (-not (Tap-ById $id)) { return $false }
    Start-Sleep -Milliseconds 300
    1..60 | ForEach-Object { Adb shell input keyevent 67 | Out-Null }  # DEL 清空
    $safe = $value -replace " ", "%s"
    Adb shell input text $safe | Out-Null
    Start-Sleep -Milliseconds 300
    return $true
}

# 对话框内的裸 EditText（按出现顺序，0 起）
function Set-DialogField([int]$index, [string]$value) {
    $xml = Get-UiXml
    $edits = @(Find-Nodes $xml -cls "android.widget.EditText") | Where-Object { $_.Displayed }
    if ($edits.Count -le $index) { Write-Warn2 "对话框内未找到第 $index 个输入框"; return $false }
    Tap-Node $edits[$index]
    1..40 | ForEach-Object { Adb shell input keyevent 67 | Out-Null }
    Adb shell input text ($value -replace " ", "%s") | Out-Null
    Start-Sleep -Milliseconds 300
    return $true
}

# 按预期状态拨动 switch
function Set-Switch([string]$id, [bool]$on) {
    $n = Ensure-NodeVisible $id
    if (-not $n) { Write-Warn2 "未找到开关 $id"; return $false }
    if ($n.Checked -ne $on) { Tap-Node $n; Start-Sleep -Milliseconds 500 }
    return $true
}

# ---------------------------------------------------------------- App 控制

function Start-App {
    Adb shell monkey -p $Package -c android.intent.category.LAUNCHER 1 | Out-Null
    Start-Sleep -Seconds 3
}

function Open-Settings {
    # SettingsActivity 非 exported；大多数设备 adb shell 可直接启动，失败则人工导航
    Adb shell am start -n "$Package/.SettingsActivity" | Out-Null
    Start-Sleep -Seconds 2
    $xml = Get-UiXml
    if ((@(Find-Nodes $xml -byId "switchSyncEncryption")).Count -eq 0) {
        Checkpoint-Manual "导航" "进入设置页" "请手动打开 App 的【设置】页面，完成后回到本窗口"
    }
}

# 触发设置页"立即同步"并按 logcat 判定结果：success / mismatch / other
function Invoke-Sync([string]$caseTag) {
    Adb logcat -c
    if (-not (Tap-ById "btnSyncNow" 6)) { return "no-button" }
    $deadline = (Get-Date).AddSeconds($SyncTimeoutSec)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $logs = (Adb logcat -d) -join "`n"
        $logFile = Join-Path $ReportDir "logcat_$caseTag.txt"
        ($logs -split "`n" | Where-Object { $_ -match "OvertimeApp" }) | Out-File -FilePath $logFile -Encoding utf8
        if ($logs -match "上传成功，共|下载恢复成功，共|智能合并成功，共|没有需要上传的记录|云端无数据，执行上传") { return "success" }
        if ($logs -match "已中止上传以避免覆盖|无法解密|解密失败：请检查同步加密密码") { return "mismatch" }
    }
    return "timeout"
}

# ---------------------------------------------------------------- WebDAV 云端校验

function Get-CloudUrl {
    if (-not $WebDavBaseUrl) { return $null }
    $p = $WebDavPath.Trim()
    if (-not $p.EndsWith("/")) { $p += "/" }
    return ($WebDavBaseUrl.TrimEnd('/') + $p + $BackupFileName)
}

function Get-CloudSnapshot([string]$label) {
    $url = Get-CloudUrl
    if (-not $url) {
        return [pscustomobject]@{ Exists = $false; Skipped = $true; IsEnvelope = $false; IsPlain = $false; SlotHash = ""; Length = 0 }
    }
    $outFile = Join-Path $ReportDir ("cloud_{0}.txt" -f ($label -replace '[^\w-]', '_'))
    $headers = @{}
    if ($WebDavUser) {
        $pair = "{0}:{1}" -f $WebDavUser, $WebDavPass
        $token = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair))
        $headers["Authorization"] = "Basic $token"
    }
    try {
        Invoke-WebRequest -Uri $url -Headers $headers -OutFile $outFile -TimeoutSec 30 -UseBasicParsing | Out-Null
    } catch {
        $code = $null
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
        return [pscustomobject]@{ Exists = $false; Skipped = $false; HttpCode = $code; IsEnvelope = $false; IsPlain = $false; SlotHash = ""; Length = 0 }
    }
    $content = [System.IO.File]::ReadAllText($outFile)
    $isEnv = $content.StartsWith($WirePrefix)
    $isPlain = (-not $isEnv) -and ($content.TrimStart().StartsWith("{") -or $content.TrimStart().StartsWith("["))
    $slotHash = ""
    if ($isEnv) {
        try {
            $bytes = [Convert]::FromBase64String($content.Substring($WirePrefix.Length).Trim())
            # 布局：magic4 + version1 + pwdSlot76 + recSlot76；recSlot 偏移 81，长度 76
            $rec = New-Object byte[] 76
            [Array]::Copy($bytes, 81, $rec, 0, 76)
            $sha = [System.Security.Cryptography.SHA256]::Create().ComputeHash($rec)
            $slotHash = ([BitConverter]::ToString($sha) -replace "-", "").ToLower()
        } catch { $slotHash = "PARSE_ERROR" }
    }
    return [pscustomobject]@{
        Exists = $true; Skipped = $false; IsEnvelope = $isEnv; IsPlain = $isPlain
        SlotHash = $slotHash; Length = $content.Length; File = $outFile
    }
}

function Put-CloudFile([string]$localFile) {
    $url = Get-CloudUrl
    if (-not $url) { throw "未提供 -WebDavBaseUrl，无法 PUT 旧版文件" }
    $headers = @{}
    if ($WebDavUser) {
        $pair = "{0}:{1}" -f $WebDavUser, $WebDavPass
        $headers["Authorization"] = "Basic " + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair))
    }
    Invoke-WebRequest -Uri $url -Method Put -Headers $headers -InFile $localFile -TimeoutSec 30 -UseBasicParsing | Out-Null
}

# 设备上清空后重新填写 WebDAV 表单（pm clear 之后用）
function Fill-WebDavConfig {
    if (-not $WebDavBaseUrl) {
        Checkpoint-Manual "WebDAV配置" "WebDAV 四项配置已填写并保存" "请手动填写服务器地址/账号/密码/路径并点【保存】"
        return
    }
    Set-Field "etWebDAVServer" $WebDavBaseUrl  | Out-Null
    Set-Field "etWebDAVUsername" $WebDavUser    | Out-Null
    Set-Field "etWebDAVPassword" $WebDavPass    | Out-Null
    Set-Field "etWebDAVPath" $WebDavPath        | Out-Null
    Tap-ById "btnSaveWebDAV" | Out-Null
    Start-Sleep -Seconds 1
    Write-Ok "WebDAV 配置已自动填入并保存"
}

# 设置页：配置同步加密开关与密码并保存
function Save-SyncEncryption([bool]$on, [string]$pwd) {
    Open-Settings
    Set-Switch "switchSyncEncryption" $on | Out-Null
    if ($on) { Set-Field "etSyncPassword" $pwd | Out-Null }
    Tap-ById "btnSave" | Out-Null
    Start-Sleep -Seconds 1
}

# ================================================================ 用例组

function Group-1 {
    Write-Step "第一组：首次开启加密（云端无备份）"
    $pre = Get-CloudSnapshot "1_pre"
    if ($pre.Exists) {
        Write-Warn2 "云端已存在备份文件：首次开启用例建议从空目录开始（当前文件已另存 cloud_1_pre.txt）"
        Checkpoint-Manual "1.0" "云端目录为空", "如要严格走首次流程，请先清空 WebDAV 目录后继续"
    }

    Write-Step "1.1 开启加密 → 应弹恢复码 R1"
    Start-App
    Open-Settings
    Set-Switch "switchSyncEncryption" $true | Out-Null
    Set-Field "etSyncPassword" $Password1 | Out-Null
    Tap-ById "btnSave" | Out-Null
    $hasDialog = Wait-Text "你的同步恢复码" 40
    $shot = Take-Screenshot "1.1"
    if ($hasDialog) {
        $code = Find-RecoveryCodeOnScreen
        if ($code) {
            $script:R1 = $code
            "R1 = $code（首次开启）" | Out-File -FilePath $CodesFile -Append -Encoding utf8
            Record-Case "1.1" "弹不可取消恢复码对话框，得到 R1" "通过" "捕获 R1=$code；截图 $shot"
        } else {
            Record-Case "1.1" "弹恢复码对话框" "失败" "对话框出现但未解析出恢复码；截图 $shot"
        }
    } else {
        Record-Case "1.1" "弹不可取消恢复码对话框" "失败" "未出现对话框；截图 $shot"
    }
    Tap-ByText "我已离线保存" 5 | Out-Null
    Start-Sleep -Seconds 2

    Write-Step "1.2 同步 → 成功且云端为 PORE_ENV1 信封"
    Open-Settings
    $r = Invoke-Sync "1.2"
    $snap = Get-CloudSnapshot "1.2_post"
    if ($snap.Skipped) {
        if ($r -eq "success") { Record-Case "1.2" "同步成功" "通过" "logcat 判定成功（未提供 WebDAV 参数，未校验云端文件）" }
        else { Record-Case "1.2" "同步成功且云端为信封" "失败" "同步判定=$r" }
    } else {
        if ($r -eq "success" -and $snap.IsEnvelope -and $snap.SlotHash) {
            $script:SlotHashH1 = $snap.SlotHash
            "H1(R1恢复槽) = $($snap.SlotHash)" | Out-File -FilePath $CodesFile -Append -Encoding utf8
            Record-Case "1.2" "同步成功；云端以 PORE_ENV1: 开头" "通过" "信封确认；H1=$($snap.SlotHash)"
        } else {
            Record-Case "1.2" "同步成功；云端为信封格式" "失败" "同步判定=$r；信封=$($snap.IsEnvelope)"
        }
    }
    Checkpoint-Manual "1.2b" "红底待保存提醒已消失" "查看设置页同步加密区域是否还有红色提醒"

    Write-Step "1.3 生物识别查看密码/恢复码"
    Checkpoint-Manual "1.3a" "指纹/面容通过后显示同步密码 $Password1" "点【查看同步密码】并完成生物识别"
    Checkpoint-Manual "1.3b" "指纹/面容通过后显示恢复码 $($script:R1)" "点【查看恢复码】并完成生物识别"
}

function Group-2 {
    Write-Step "第二组：日常加解密"
    Checkpoint-Manual "2.1" "新增 1 条加班记录并同步成功" "在首页新增一条记录，然后到设置页点【立即同步】（脚本会自动判定结果）"
    $before = Get-CloudSnapshot "2.1_pre"
    Open-Settings
    $r = Invoke-Sync "2.1"
    $after = Get-CloudSnapshot "2.1_post"
    if ($r -eq "success") {
        $changed = $after.Skipped -or ($before.Length -ne $after.Length)
        Record-Case "2.1" "同步成功且云端内容更新" (& { if ($changed) {"通过"} else {"失败"} }) "判定=$r；云文件长度 $($before.Length)→$($after.Length)"
    } else {
        Record-Case "2.1" "同步成功" "失败" "判定=$r"
    }

    Write-Step "2.2 错误密码 → 中止上传且云端不被覆盖"
    Save-SyncEncryption $true $WrongPassword
    Open-Settings
    $r = Invoke-Sync "2.2"
    $after2 = Get-CloudSnapshot "2.2_post"
    if ($r -eq "mismatch") {
        $unchanged = $after2.Skipped -or ($after2.Length -eq $after.Length -and $after2.SlotHash -eq $after.SlotHash)
        Record-Case "2.2a" "提示密码不匹配并中止" "通过" "logcat 判定 mismatch"
        Record-Case "2.2b" "云端文件未被覆盖" (& { if ($unchanged) {"通过"} else {"失败"} }) "长度 $($after.Length)→$($after2.Length)；槽哈希 $($after.SlotHash)→$($after2.SlotHash)"
    } else {
        Record-Case "2.2" "密码错误时中止上传" "失败" "判定=$r（期望 mismatch）"
    }
    # 恢复正确密码
    Save-SyncEncryption $true $Password1
}

function Group-3 {
    Write-Step "第三组：重装/换机用密码接入（核心回归：R1 不得失效）"

    Write-Step "3.1 pm clear 后重新配置 → 应'接入现有备份'且不弹新恢复码"
    Adb shell pm clear $Package | Out-Null
    Start-Sleep -Seconds 2
    Start-App
    Open-Settings
    Fill-WebDavConfig
    Set-Switch "switchSyncEncryption" $true | Out-Null
    Set-Field "etSyncPassword" $Password1 | Out-Null
    Adb logcat -c
    Tap-ById "btnSave" | Out-Null
    $adopted = Wait-Text "已接入现有加密备份" 45
    $shot = Take-Screenshot "3.1"
    $newCodeDialog = Wait-Text "你的同步恢复码" 2
    if ($adopted -and -not $newCodeDialog) {
        Record-Case "3.1" "提示接入现有备份，不弹新恢复码" "通过" "截图 $shot"
        Tap-ByText "知道了" 5 | Out-Null
    } elseif ($newCodeDialog) {
        Record-Case "3.1" "必须接入而非生成新恢复码" "失败" "出现了新恢复码对话框（缺陷回归）；截图 $shot"
        Tap-ByText "我已离线保存" 3 | Out-Null
    } else {
        Record-Case "3.1" "提示接入现有备份" "失败" "两种预期对话框均未出现；截图 $shot"
    }
    Start-Sleep -Seconds 2

    Write-Step "3.2 立即同步 → 数据恢复"
    Open-Settings
    $r = Invoke-Sync "3.2"
    Record-Case "3.2" "同步成功，云端记录完整恢复" (& { if ($r -eq "success") {"通过"} else {"失败"} }) "判定=$r"
    Checkpoint-Manual "3.2b" "首页可看到第一/二组新增的记录" "在首页核对记录条数"

    Write-Step "3.3 查看恢复码 → 本机未保存明文"
    Checkpoint-Manual "3.3" "生物识别后提示'本机未保存恢复码'，可重新生成" "点【查看恢复码】并完成生物识别"

    Write-Step "3.4 再上传后云端恢复槽哈希必须仍为 H1（R1 仍有效）"
    Checkpoint-Manual "3.4a" "已在本机新增 1 条记录" "首页新增一条记录后回到本窗口"
    Open-Settings
    $r = Invoke-Sync "3.4"
    $snap = Get-CloudSnapshot "3.4_post"
    if ($r -ne "success") {
        Record-Case "3.4" "同步成功且 R1 恢复槽不变" "失败" "同步判定=$r"
    } elseif ($snap.Skipped) {
        Record-Case "3.4" "同步成功（未提供 WebDAV 参数，槽哈希未校验）" "通过" "判定=success"
    } elseif ($snap.SlotHash -eq $script:SlotHashH1) {
        Record-Case "3.4" "恢复槽哈希不变 → R1 仍有效（核心回归）" "通过" "H1=$($script:SlotHashH1)"
    } else {
        Record-Case "3.4" "恢复槽被替换 → R1 已失效（缺陷回归）" "失败" "$($script:SlotHashH1) → $($snap.SlotHash)"
    }

    Write-Step "3.5 错误密码不得覆盖云端，随后恢复正确密码"
    Save-SyncEncryption $true $WrongPassword
    Open-Settings
    $r = Invoke-Sync "3.5"
    $snap2 = Get-CloudSnapshot "3.5_post"
    $ok = ($r -eq "mismatch") -and ($snap2.Skipped -or $snap2.SlotHash -eq $script:SlotHashH1)
    Record-Case "3.5" "密码不匹配时中止，云端恢复槽不变" (& { if ($ok) {"通过"} else {"失败"} }) "判定=$r；槽=$($snap2.SlotHash)"
    Save-SyncEncryption $true $Password1
}

function Group-4 {
    Write-Step "第四组：恢复码找回"

    Write-Step "4.1 用 R1 找回并设置新密码 $Password2"
    Open-Settings
    Tap-ById "btnRecoverWithCode" | Out-Null
    $entered = Set-DialogField 0 ($script:R1 -replace "-", "")  # 去连字符也应被接受
    Tap-ByText "下一步" 5 | Out-Null
    $askPwd = Wait-Text "设置新的同步密码" 8
    if (-not $askPwd) { Record-Case "4.1" "进入设置新密码对话框" "失败" "R1=$($script:R1)"; return }
    Set-DialogField 0 $Password2 | Out-Null
    Set-DialogField 1 $Password2 | Out-Null
    Tap-ByText "确认找回" 5 | Out-Null
    $done = Wait-Text "找回完成" 90
    $shot = Take-Screenshot "4.1"
    Record-Case "4.1" "R1 验证通过、新密码生效、提示找回完成" (& { if ($done) {"通过"} else {"失败"} }) "截图 $shot；R1 去连字符输入"
    Tap-ByText "知道了" 5 | Out-Null

    Write-Step "4.2/4.3 恢复后同步；恢复槽不变（R1 仍有效）"
    Open-Settings
    $r = Invoke-Sync "4.2"
    $snap = Get-CloudSnapshot "4.2_post"
    $sameLineage = $snap.Skipped -or $snap.SlotHash -eq $script:SlotHashH1
    Record-Case "4.2" "找回后同步成功" (& { if ($r -eq "success") {"通过"} else {"失败"} }) "判定=$r"
    Record-Case "4.3a" "$Password2 可正常同步；恢复槽不变 → R1 仍可解" (& {
        if ($r -eq "success" -and $sameLineage) {"通过"} else {"失败"} }) "槽=$($snap.SlotHash)，H1=$($script:SlotHashH1)"

    Write-Step "4.3b 旧密码 $Password1 必须失效"
    Save-SyncEncryption $true $Password1
    Open-Settings
    $r2 = Invoke-Sync "4.3b"
    Record-Case "4.3b" "旧密码同步被拒（mismatch）" (& { if ($r2 -eq "mismatch") {"通过"} else {"失败"} }) "判定=$r2"
    # 用 R1 再找回为 P@ss2，恢复后续用例的状态
    Open-Settings
    Tap-ById "btnRecoverWithCode" | Out-Null
    Set-DialogField 0 $script:R1 | Out-Null
    Tap-ByText "下一步" 5 | Out-Null
    if (Wait-Text "设置新的同步密码" 8) {
        Set-DialogField 0 $Password2 | Out-Null
        Set-DialogField 1 $Password2 | Out-Null
        Tap-ByText "确认找回" 5 | Out-Null
        Wait-Text "找回完成" 90 | Out-Null
        Tap-ByText "知道了" 5 | Out-Null
    }

    Write-Step "4.4 错误/非法恢复码"
    Checkpoint-Manual "4.4a" "格式不正确时提示'恢复码格式不正确'，不能进入下一步" "点【用恢复码找回】，输入 123，点下一步"
    Checkpoint-Manual "4.4b" "格式正确但错误的恢复码提示'恢复码错误'" "输入 0000-0000-0000-0000，下一步并设置一个临时新密码，确认找回"

    Write-Step "4.5 恢复码容错（小写/去连字符）"
    Open-Settings
    Tap-ById "btnRecoverWithCode" | Out-Null
    $variant = ($script:R1.ToLower())
    Set-DialogField 0 $variant | Out-Null
    Tap-ByText "下一步" 5 | Out-Null
    $ok = Wait-Text "设置新的同步密码" 8
    Record-Case "4.5" "小写变体被规范化接受" (& { if ($ok) {"通过"} else {"失败"} }) "输入=$variant"
    Tap-ByText "取消" 3 | Out-Null
}

function Group-5 {
    Write-Step "第五组：更换恢复码"
    Open-Settings
    Tap-ById "btnViewRecoveryCode" | Out-Null
    Checkpoint-Manual "5.0" "生物识别通过后出现恢复码对话框（当前码或'本机未保存'）" "完成生物识别，不要关对话框"

    Write-Step "5.1 重新签发 → R2；同步后槽哈希变化（R1 失效）"
    # 两种对话框分别是"更换恢复码"（有明文时）和"重新生成"（无明文时）
    if (-not (Tap-ByText "更换恢复码" 3)) { Tap-ByText "重新生成" 3 | Out-Null }
    Tap-ByText "确认更换" 5 | Out-Null
    $hasNew = Wait-Text "你的同步恢复码" 15
    $script:R2 = Find-RecoveryCodeOnScreen
    $shot = Take-Screenshot "5.1"
    if ($hasNew -and $script:R2 -and $script:R2 -ne $script:R1) {
        "R2 = $($script:R2)（更换后）" | Out-File -FilePath $CodesFile -Append -Encoding utf8
        # 先不点"我已离线保存"，直接 force-stop 以验证待保存提醒（5.2）
        Record-Case "5.1a" "生成新恢复码 R2 且与 R1 不同" "通过" "R1=$($script:R1) R2=$($script:R2)"
    } else {
        Record-Case "5.1a" "生成不同的新恢复码" "失败" "R1=$($script:R1) R2=$($script:R2)；截图 $shot"
    }

    Write-Step "5.2 未同步即杀进程 → 红底提醒保留；同步后消失，槽哈希变为 H2"
    Adb shell am force-stop $Package
    Start-Sleep -Seconds 2
    Open-Settings
    Set-Switch "switchSyncEncryption" $true | Out-Null  # 展开同步加密区域
    Start-Sleep -Milliseconds 800
    $xml = Get-UiXml
    $banner = @(Find-Nodes $xml -byId "tvRecoveryPending") | Where-Object { $_.Displayed }
    Record-Case "5.2a" "红底待保存提醒在重启后仍在" (& { if ($banner.Count -gt 0) {"通过"} else {"失败"} }) "tvRecoveryPending displayed=$($banner.Count -gt 0)"

    $r = Invoke-Sync "5.2"
    $snap = Get-CloudSnapshot "5.2_post"
    if ($r -eq "success" -and -not $snap.Skipped -and $snap.SlotHash -and $snap.SlotHash -ne $script:SlotHashH1) {
        Record-Case "5.1b/5.2b" "同步成功且恢复槽变化 → R2 生效、R1 失效" "通过" "H1=$($script:SlotHashH1) H2=$($snap.SlotHash)"
    } elseif ($r -eq "success" -and $snap.Skipped) {
        Record-Case "5.1b" "同步成功（未提供 WebDAV 参数，槽变化未校验）" "通过" "判定=success"
    } else {
        Record-Case "5.1b" "同步后恢复槽应变化" "失败" "判定=$r；H1=$($script:SlotHashH1) 现=$($snap.SlotHash)"
    }
    # 确认保存 R2（清除红条）
    if (Wait-Text "你的同步恢复码" 2) { Tap-ByText "我已离线保存" 3 | Out-Null }
    Open-Settings
    Start-Sleep -Milliseconds 500
    $xml2 = Get-UiXml
    $banner2 = @(Find-Nodes $xml2 -byId "tvRecoveryPending") | Where-Object { $_.Displayed }
    Record-Case "5.2c" "同步并确认后红底提醒消失" (& { if ($banner2.Count -eq 0) {"通过"} else {"失败"} }) ""
    # 后续用 R2 作为有效恢复码
    $script:R1 = $script:R2
    $script:SlotHashH1 = $snap.SlotHash
}

function Group-6 {
    Write-Step "第六组：旧版数据迁移（可选）"
    if (-not $LegacyFile -or -not (Test-Path $LegacyFile)) {
        Record-Case "6.1" "旧版备份自动迁移为信封" "跳过" "未提供 -LegacyFile"
        Record-Case "6.2" "迁移后数据无丢失/重复" "跳过" "同上"
        Write-Warn2 "跳过第六组：需要 -LegacyFile 指向旧版备份（明文 JSON 或旧版密码密文）"
        return
    }
    Put-CloudFile $LegacyFile
    Write-Ok "已将旧版备份 PUT 到云端"
    Adb shell pm clear $Package | Out-Null
    Start-Sleep -Seconds 2
    Start-App
    Open-Settings
    Fill-WebDavConfig
    Set-Switch "switchSyncEncryption" $true | Out-Null
    Set-Field "etSyncPassword" $Password1 | Out-Null
    Tap-ById "btnSave" | Out-Null
    # 旧密文密码正确 / 明文：生成新材料；旧密文密码错误：WrongPassword
    $generated = Wait-Text "你的同步恢复码" 45
    $mismatch = Wait-Text "同步密码不匹配" 3
    Record-Case "6.1a" "旧版数据可被当前密码读取并生成新恢复码" (& { if ($generated) {"通过"} elseif ($mismatch) {"失败"} else {"失败"} }) ""
    if ($generated) {
        $code = Find-RecoveryCodeOnScreen
        if ($code) { "R3(迁移) = $code" | Out-File -FilePath $CodesFile -Append -Encoding utf8 }
        Tap-ByText "我已离线保存" 5 | Out-Null
        Open-Settings
        $r = Invoke-Sync "6.1"
        $snap = Get-CloudSnapshot "6.1_post"
        Record-Case "6.1b" "首次同步后云端为信封格式" (& { if ($r -eq "success" -and ($snap.Skipped -or $snap.IsEnvelope)) {"通过"} else {"失败"} }) "判定=$r；信封=$($snap.IsEnvelope)"
        Checkpoint-Manual "6.2" "迁移后数据无丢失、无重复" "首页/报表核对记录数"
    }
}

function Group-7 {
    Write-Step "第七组：关闭加密与断网分支"

    Write-Step "7.1 关闭加密后上传应为明文"
    Save-SyncEncryption $false ""
    Open-Settings
    $r = Invoke-Sync "7.1"
    $snap = Get-CloudSnapshot "7.1_post"
    Record-Case "7.1" "关闭加密后同步成功且云端为明文 JSON" (& {
        if ($r -eq "success" -and ($snap.Skipped -or $snap.IsPlain)) {"通过"} else {"失败"} }) "判定=$r；明文=$($snap.IsPlain)"

    Write-Step "7.2 断网时开启加密：推迟到联网后首次同步处理"
    Checkpoint-Manual "7.2a" "设备已开启飞行模式（或断开所有网络）" "请打开飞行模式后回到本窗口"
    Open-Settings
    Set-Switch "switchSyncEncryption" $true | Out-Null
    Set-Field "etSyncPassword" $Password2 | Out-Null
    Tap-ById "btnSave" | Out-Null
    Start-Sleep -Seconds 3
    $shot = Take-Screenshot "7.2a"
    Checkpoint-Manual "7.2a2" "提示'暂时无法检查云端备份，将在下次联网同步时自动确认'" "观察屏幕 Toast；截图 $shot"

    Checkpoint-Manual "7.2b" "联网首次同步后出现保存恢复码提醒（弹窗或通知），且原谱系恢复码仍有效" "关闭飞行模式，点立即同步；脚本将校验恢复槽哈希"
    Open-Settings
    $r = Invoke-Sync "7.2"
    $snap = Get-CloudSnapshot "7.2_post"
    if ($snap.Skipped) {
        Record-Case "7.2b" "联网后同步成功" (& { if ($r -eq "success") {"通过"} else {"失败"} }) "判定=$r（未校验云端）"
    } else {
        # 明文 → 信封的首次迁移会产生新 DEK/新槽（原云端是 7.1 上传的明文，无旧槽可保留）
        Record-Case "7.2b" "联网后同步成功且云端为信封" (& { if ($r -eq "success" -and $snap.IsEnvelope) {"通过"} else {"失败"} }) "判定=$r；信封=$($snap.IsEnvelope)"
    }
}

# ================================================================ 主流程

function Assert-Preconditions {
    if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
        throw "未找到 adb，请确认 platform-tools 在 PATH 中"
    }
    $devices = @(Adb devices) -split "`n" | Where-Object { $_ -match "\bdevice$" }
    if ($devices.Count -eq 0) { throw "没有已授权的设备/模拟器（adb devices）" }
    if (-not $DeviceId -and $devices.Count -gt 1) {
        throw "检测到多台设备，请用 -DeviceId 指定"
    }
    Write-Ok "设备连接正常：$($devices[0].Trim())"

    if ($Build) {
        Write-Step "构建 debug APK"
        $gradle = Join-Path (Split-Path $PSScriptRoot -Parent | Split-Path -Parent) "gradlew.bat"
        & $gradle :app:assembleDebug --console=plain
        if ($LASTEXITCODE -ne 0) { throw "构建失败" }
    }
    if (-not $ApkPath) {
        $repo = Split-Path $PSScriptRoot -Parent | Split-Path -Parent
        $ApkPath = Join-Path $repo "app\build\outputs\apk\debug\app-debug.apk"
    }
    if (-not $KeepInstall) {
        Write-Step "全新安装（先卸载）"
        Adb uninstall $Package | Out-Null
        if (-not (Test-Path $ApkPath)) { throw "APK 不存在：$ApkPath（可加 -Build 自动构建）" }
        Adb install -r $ApkPath | Out-Null
        Write-Ok "已安装 $ApkPath"
    }
}

function Write-FinalReport {
    $report = Join-Path $ReportDir "验收报告.md"
    $lines = @()
    $lines += "# 真机端到端验收报告"
    $lines += ""
    $lines += "- 时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
    $lines += "- 设备：$((Adb shell getprop ro.product.model) -join '' -replace "`r?`n",'') / Android $((Adb shell getprop ro.build.version.release) -join '')"
    $lines += "- 用例组：$($Groups -join ',')"
    $pass = @($script:Results | Where-Object { $_.结果 -eq "通过" }).Count
    $fail = @($script:Results | Where-Object { $_.结果 -eq "失败" }).Count
    $skip = @($script:Results | Where-Object { $_.结果 -eq "跳过" }).Count
    $lines += "- 统计：通过 $pass / 失败 $fail / 跳过 $skip"
    $lines += ""
    $lines += "| 用例 | 预期 | 结果 | 证据 | 时间 |"
    $lines += "|---|---|---|---|---|"
    foreach ($r in $script:Results) {
        $lines += "| $($r.用例) | $($r.预期) | $($r.结果) | $($r.证据 -replace "`r?`n", ' ') | $($r.时间) |"
    }
    $lines += ""
    $lines += "## 关键谱系指纹"
    $lines += "- H1（R1 恢复槽 SHA256）：$($script:SlotHashH1)"
    $lines += "- R1：$($script:R1)"
    $lines += "- R2：$($script:R2)"
    $lines += ""
    $lines += "截图、云端快照、logcat 见本目录其余文件。"
    $lines | Out-File -FilePath $report -Encoding utf8
    Write-Host "`n================================================" -ForegroundColor White
    Write-Host "通过 $pass / 失败 $fail / 跳过 $skip" -ForegroundColor $(if ($fail -gt 0) {"Red"} else {"Green"})
    Write-Host "报告目录：$ReportDir" -ForegroundColor White
    if ($fail -gt 0) { exit 1 }
}

Assert-Preconditions
try {
    foreach ($g in ($Groups | Sort-Object -Unique)) {
        switch ($g) {
            1 { Group-1 }
            2 { Group-2 }
            3 { Group-3 }
            4 { Group-4 }
            5 { Group-5 }
            6 { Group-6 }
            7 { Group-7 }
            default { Write-Warn2 "未知用例组：$g" }
        }
    }
} finally {
    Write-FinalReport
}
