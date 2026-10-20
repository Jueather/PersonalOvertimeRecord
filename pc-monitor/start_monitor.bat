@echo off
chcp 65001 >nul
title PersonalOvertimeRecord PC Monitor
cd /d "%~dp0"

where python >nul 2>nul
if errorlevel 1 (
    echo [错误] 未检测到 Python，请先安装 Python 3.8+：https://www.python.org/downloads/
    pause
    exit /b 1
)

python -c "import cryptography" >nul 2>nul
if errorlevel 1 (
    echo [提示] 正在安装解密依赖 cryptography（仅首次需要，明文备份可跳过）...
    python -m pip install -q cryptography
)

python overtime_monitor.py
pause
