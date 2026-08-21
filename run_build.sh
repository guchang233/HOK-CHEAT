#!/usr/bin/env bash
# ============================================================
# 王者荣耀 直装APK + ESP 一键构建器 - 启动脚本
# ============================================================
#
# 使用方法:
#   ./run_build.sh                    # 打开 GUI 界面
#   ./run_build.sh <apk> <server>     # 命令行模式
#
# 示例:
#   ./run_build.sh
#   ./run_build.sh hok.apk 192.168.1.100:6645
#   ./run_build.sh /path/to/hok.apk 10.0.0.1:6645 --skip-esp
#

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

PYTHON="${PYTHON:-python3}"

if [ $# -lt 2 ]; then
    echo "🎮 启动 GUI 构建器..."
    exec "$PYTHON" build_gui.py "$@"
else
    APK="$1"
    SERVER="$2"
    shift 2
    echo "🚀 命令行模式"
    echo "   APK:    $APK"
    echo "   Server: $SERVER"
    echo ""
    exec "$PYTHON" ultimate_builder.py "$APK" --server "$SERVER" "$@"
fi
