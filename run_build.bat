@echo off
REM ============================================================
REM 王者荣耀 直装APK + ESP 一键构建器 - Windows 启动脚本
REM ============================================================
REM
REM 使用方法:
REM   run_build.bat                     REM 打开 GUI 界面
REM   run_build.bat <apk> <server>      REM 命令行模式
REM
REM 示例:
REM   run_build.bat
REM   run_build.bat hok.apk 192.168.1.100:6645
REM   run_build.bat hok.apk 10.0.0.1:6645 --skip-esp
REM

set SCRIPT_DIR=%~dp0
cd /d "%SCRIPT_DIR%"

if "%~2"=="" (
    echo Starting GUI builder...
    python build_gui.py %*
) else (
    set APK=%1
    set SERVER=%2
    shift
    shift
    echo CLI mode: APK=%APK%, Server=%SERVER%
    python ultimate_builder.py "%APK%" --server "%SERVER%" %*
)
