#!/bin/bash
# ESP system deployment script
# 
# Prerequisites:
#   - Android device with USB debugging enabled
#   - adb in PATH
#   - Root access (su) required for reading game memory
#   - Game APK installed on device
#   - esp_overlay.apk built (run build_esp.py first)
#
# Usage:
#   bash deploy_esp.sh [--pkg GAME_PKG] [--port PORT] [--skip-apk] [--skip-push]

set -e

GAME_PKG="com.tencent.tmgp.sgame"
PORT="47291"
SKIP_APK=0
SKIP_PUSH=0
READER_PATH=""

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/build"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --pkg) GAME_PKG="$2"; shift 2 ;;
        --port) PORT="$2"; shift 2 ;;
        --skip-apk) SKIP_APK=1; shift ;;
        --skip-push) SKIP_PUSH=1; shift ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

ADB="$(which adb 2>/dev/null || echo '${ANDROID_HOME:-/opt/android-sdk}/platform-tools/adb')"
if [ ! -x "$ADB" ]; then
    echo "[!] adb not found"
    exit 1
fi

# Check for native reader binary
if [ -f "${BUILD_DIR}/tv_reader" ]; then
    READER_PATH="${BUILD_DIR}/tv_reader"
elif [ -f "${SCRIPT_DIR}/tv_reader" ]; then
    READER_PATH="${SCRIPT_DIR}/tv_reader"
else
    echo "[!] tv_reader binary not found. Run build_esp.py first."
    exit 1
fi

# Check for APK
APK_PATH="${BUILD_DIR}/esp_overlay.apk"
if [ ! -f "$APK_PATH" ] && [ "$SKIP_APK" -eq 0 ]; then
    echo "[!] esp_overlay.apk not found. Run build_esp.py first."
    exit 1
fi

echo "[*] Checking ADB connection..."
$ADB get-state 2>/dev/null || { echo "[!] No ADB device connected"; exit 1; }
DEVICE=$($ADB get-serialno 2>/dev/null || echo "device")

echo "[*] Target game: $GAME_PKG"
echo "[*] Port: $PORT"

# Check root
if ! $ADB shell "su -c 'id'" 2>/dev/null | grep -q uid=0; then
    echo "[!] Root access not available. Need su to read game memory."
    echo "[!] On rooted device, grant root to adb shell or use Magisk."
    exit 1
fi

if [ "$SKIP_PUSH" -eq 0 ]; then
    echo "[*] Pushing tv_reader to device..."
    $ADB push "$READER_PATH" /data/local/tmp/tv_reader
    $ADB shell "chmod 755 /data/local/tmp/tv_reader"
    echo "  [✓] tv_reader pushed"

    if [ "$SKIP_APK" -eq 0 ]; then
        echo "[*] Installing esp_overlay.apk..."
        $ADB install -r "$APK_PATH"
        echo "  [✓] APK installed"
    fi
else
    echo "[*] Skipping push (--skip-push)"
fi

# Kill any existing reader
echo "[*] Stopping previous reader..."
$ADB shell "su -c 'killall tv_reader 2>/dev/null || true'"
sleep 0.5

# Start reader in background
echo "[*] Starting tv_reader..."
READER_CMD="su -c '/data/local/tmp/tv_reader --game-pkg $GAME_PKG --port $PORT'"
$ADB shell "nohup $READER_CMD > /data/local/tmp/tv_reader.log 2>&1 &"

sleep 1
# Verify reader started
if $ADB shell "su -c 'ps -A | grep tv_reader'" 2>/dev/null | grep -q tv_reader; then
    echo "  [✓] tv_reader running"
else
    echo "  [!] tv_reader may have failed. Check log:"
    $ADB shell "su -c 'tail -20 /data/local/tmp/tv_reader.log'"
fi

# Launch overlay APK
echo "[*] Launching ESP overlay..."
$ADB shell "am start -n com.esp/.MainActivity" 2>/dev/null || \
$ADB shell "am start -n com.esp/.OverlayService" 2>/dev/null || true

echo ""
echo "=============================================="
echo " ESP system deployed!"
echo "=============================================="
echo ""
echo " Steps:"
echo "  1. Open esp_overlay app on device"
echo "  2. Grant 'Draw over other apps' permission"
echo "  3. Click 'Start ESP Overlay'"
echo "  4. Launch Honor of Kings / 私服"
echo "  5. ESP minimap will appear on screen"
echo ""
echo " Debug commands:"
echo "  $ADB shell 'su -c \"tail -f /data/local/tmp/tv_reader.log\"'"
echo "  $ADB shell 'dumpsys activity service com.esp/.OverlayService'"
echo "  $ADB shell 'su -c \"netstat -tlnp\"' | grep $PORT"
echo ""
echo " Stop: $ADB shell 'su -c \"killall tv_reader\"'"
echo ""
