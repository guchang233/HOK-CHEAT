#!/usr/bin/env python3
"""
ESP System - Complete Build & Deploy Script
=============================================

Builds the entire ESP system:
  1. tv_reader (C, native binary via NDK) - reads game memory
  2. esp_overlay.apk (Kotlin/Android) - floating window display

Architecture:
  +------------+   TCP (127.0.0.1:47291)   +------------------+
  | tv_reader  |  -----------------------> | esp_overlay.apk  |
  | (C native) |                           | (Kotlin Service)  |
  +------------+                           +------------------+
  Reads game memory                        Draws ESP boxes
  via /proc/pid/mem                        on Android display
  (anti-cheat evasion)

Anti-cheat design:
  - /proc/pid/mem instead of ptrace (no PTRACE_TRACEME)
  - Benign range filtering (private rw, non-device mappings only)
  - Read throttling (1200 reads/sec max)
  - Heuristic actor validation (type range, team ID, position bounds)
  - Single-syscall reads (pread) - hard to detect
  - Low traffic: only actors with valid positions
  - No ptrace syscalls, no /proc/pid/status inspection for TracerPid

Requirements:
  - Android NDK (for native binary)
  - Android SDK / gradle (for overlay APK)
  - adb (for deployment)
  - Root / Magisk (for game memory reading)
  - Game APK installed (com.tencent.tmgp.sgame or private server pkg)
"""

import argparse
import os
import subprocess
import sys
import shutil
import struct
import time
from pathlib import Path
from typing import Optional, Tuple

ROOT = Path(__file__).resolve().parent
BUILD_DIR = ROOT / "build"
ANDROID_DIR = ROOT / "android"
APK_OUT = BUILD_DIR / "esp_overlay.apk"
NATIVE_OUT = BUILD_DIR / "tv_reader"

NEON = '\033[0m'
RED  = '\033[91m'
GRN  = '\033[92m'
YLW  = '\033[93m'
CYN  = '\033[96m'
BOLD = '\033[1m'

def cprint(color: str, msg: str):
    print(f"{color}{msg}{NEON}", flush=True)

def find_ndk() -> Optional[Path]:
    candidates = []
    if os.environ.get("NDK_HOME"):
        candidates.append(Path(os.environ["NDK_HOME"]))
    if os.environ.get("ANDROID_HOME"):
        sdk = Path(os.environ["ANDROID_HOME"])
        ndk_root = sdk / "ndk"
        if ndk_root.exists():
            for p in sorted(ndk_root.iterdir(), key=lambda x: x.stat().st_mtime, reverse=True):
                if p.is_dir():
                    candidates.append(p)
                    break
    for sdk_p in [Path("/opt/android-sdk"), Path(os.path.expanduser("~/Android/Sdk")),
                   Path("/usr/local/android-sdk")]:
        if sdk_p.exists():
            ndk_root = sdk_p / "ndk"
            if ndk_root.exists():
                for p in sorted(ndk_root.iterdir(), key=lambda x: x.stat().st_mtime, reverse=True):
                    if p.is_dir():
                        candidates.append(p)
                        break
    for ndk_p in [Path("/opt/android-ndk"), Path("/android-ndk")]:
        if ndk_p.exists():
            candidates.append(ndk_p)
    for c in candidates:
        if (c / "build" / "cmake" / "android.toolchain.cmake").exists():
            return c
    return None

def find_sdk() -> Optional[Path]:
    if os.environ.get("ANDROID_HOME"):
        p = Path(os.environ["ANDROID_HOME"])
        if p.exists(): return p
    for sdk_p in [Path("/opt/android-sdk"), Path(os.path.expanduser("~/Android/Sdk")),
                   Path("/usr/local/android-sdk")]:
        if sdk_p.exists(): return sdk_p
    return None

def find_adb() -> Optional[Path]:
    if os.environ.get("ADB"):
        p = Path(os.environ["ADB"])
        if p.exists(): return p
    sdk = find_sdk()
    if sdk:
        p = sdk / "platform-tools" / "adb"
        if p.exists(): return p
    p = shutil.which("adb")
    if p: return Path(p)
    return None

def run(cmd: list, cwd: Path = None, env: dict = None, capture: bool = False) -> subprocess.CompletedProcess:
    merged_env = os.environ.copy()
    if env: merged_env.update(env)
    return subprocess.run(cmd, cwd=str(cwd) if cwd else None, env=merged_env,
                          capture_output=capture, text=True, stdin=subprocess.DEVNULL)

def build_native(ndk: Path, sdk: Path, abi: str = "arm64-v8a") -> Optional[Path]:
    """Build tv_reader.c as Android native binary via NDK CMake."""
    abi_cmake = abi.replace("-", "_")
    build_dir = BUILD_DIR / f"cmake_{abi_cmake}"
    build_dir.mkdir(parents=True, exist_ok=True)

    toolchain = ndk / "build" / "cmake" / "android.toolchain.cmake"
    cmake_bin = ndk / "cmake" / "bin" / "cmake"
    ninja_bin = ndk / "cmake" / "bin" / "ninja"
    if not toolchain.exists():
        cprint(RED + "  [-] NDK toolchain not found", )
        return None
    if not cmake_bin.exists():
        cprint(RED + "  [-] cmake not found in NDK", )
        return None

    cmake_cmd = [
        str(cmake_bin),
        "-S", str(ROOT),
        "-B", str(build_dir),
        f"-DANDROID_STL=c++_static",
        f"-DANDROID_ABI={abi_cmake}",
        f"-DANDROID_PLATFORM=android-24",
        f"-DCMAKE_TOOLCHAIN_FILE={toolchain}",
        f"-DANDROID_NDK={ndk}",
        f"-DCMAKE_MAKE_PROGRAM={ninja_bin}",
        "-DCMAKE_BUILD_TYPE=Release",
    ]
    env = {
        "ANDROID_SDK_ROOT": str(sdk),
        "ANDROID_NDK_HOME": str(ndk),
    }
    cprint(CYN + f"  $ {' '.join(cmake_cmd)}")
    r = run(cmake_cmd, cwd=build_dir, env=env, capture=True)
    if r.returncode != 0:
        cprint(RED + f"  [-] cmake configure failed")
        if r.stderr: print(r.stderr[-500:])
        return None

    cprint(CYN + f"  $ {cmake_bin} --build {build_dir}")
    r = run([str(cmake_bin), "--build", str(build_dir)], cwd=build_dir, env=env, capture=True)
    if r.returncode != 0:
        cprint(RED + f"  [-] cmake build failed")
        if r.stderr: print(r.stderr[-500:])
        return None

    # Find the binary
    for f in build_dir.rglob("tv_reader"):
        if f.is_file():
            out = NATIVE_OUT
            out.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(f, out)
            cprint(GRN + f"  [✓] Native binary: {out}")
            return out
    cprint(RED + "  [-] tv_reader not found after build")
    return None

def build_apk(sdk: Path) -> Optional[Path]:
    """Build esp_overlay.apk via Android gradle."""
    wrapper = ANDROID_DIR / "gradlew_bootstrap.py"
    if not wrapper.exists():
        cprint(RED + "  [-] gradlew_bootstrap.py not found")
        return None

    env = {
        "ANDROID_HOME": str(sdk),
        "ANDROID_SDK_ROOT": str(sdk),
        "JAVA_HOME": os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-17-openjdk-amd64"),
    }

    cprint(CYN + f"  $ python3 {wrapper} assembleDebug")
    r = run(["python3", str(wrapper), "assembleDebug"],
            cwd=ANDROID_DIR, env=env, capture=True)
    if r.returncode != 0:
        cprint(RED + f"  [-] APK build failed")
        if r.stderr: print(r.stderr[-1000:])
        if r.stdout: print(r.stdout[-500:])
        return None

    for p in ANDROID_DIR.rglob("*.apk"):
        out = APK_OUT
        out.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(p, out)
        cprint(GRN + f"  [✓] APK: {out}")
        return out
    cprint(RED + "  [-] No APK produced")
    return None

def verify_tvef_frame(bin_file: Path) -> Tuple[bool, str]:
    """Verify TVEF protocol by parsing the binary header."""
    if not bin_file.exists():
        return False, "file not found"
    data = bin_file.read_bytes()
    if len(data) < 4:
        return False, "file too small"
    magic = data[:4]
    return magic == b"TVEF", f"magic={magic.hex()}"

def deploy(adb: Path, native: Path = None, apk: Path = None, 
           game_pkg: str = "com.tencent.tmgp.sgame", port: int = 47291):
    """Deploy ESP to device."""
    cprint(CYN + f"[*] Deploying via {adb}...")

    # Check device
    r = run([str(adb), "get-state"], capture=True)
    if r.returncode != 0 or "device" not in r.stdout:
        cprint(RED + "  [!] No ADB device connected")
        return False

    if native:
        cprint(CYN + "  $ adb push tv_reader")
        run([str(adb), "push", str(native), "/data/local/tmp/tv_reader"], capture=True)
        run([str(adb), "shell", "chmod", "755", "/data/local/tmp/tv_reader"], capture=True)
        cprint(GRN + "  [✓] tv_reader pushed")

    if apk:
        cprint(CYN + "  $ adb install esp_overlay.apk")
        r = run([str(adb), "install", "-r", str(apk)], capture=True)
        if r.returncode == 0:
            cprint(GRN + "  [✓] APK installed")
        else:
            cprint(RED + f"  [!] APK install: {r.stdout.strip()}")

    # Kill existing reader
    run([str(adb), "shell", "su -c 'killall tv_reader 2>/dev/null || true'"], capture=True)
    time.sleep(0.5)

    # Start reader
    cmd = f"su -c '/data/local/tmp/tv_reader --game-pkg {game_pkg} --port {port}'"
    cprint(CYN + f"  $ adb shell 'nohup {cmd} &'")
    run([str(adb), "shell", f"nohup {cmd} > /data/local/tmp/tv_reader.log 2>&1 &"], capture=True)
    time.sleep(1)
    
    r = run([str(adb), "shell", "su -c 'ps -A | grep tv_reader || echo NOT_RUNNING'"], capture=True)
    if "tv_reader" in r.stdout:
        cprint(GRN + "  [✓] tv_reader running")
    else:
        cprint(YLW + "  [!] tv_reader not running, check /data/local/tmp/tv_reader.log")
        r2 = run([str(adb), "shell", "su -c 'tail -20 /data/local/tmp/tv_reader.log'"], capture=True)
        print(r2.stdout[:500])

    cprint(CYN + "  $ Launching overlay...")
    run([str(adb), "shell", "am start -n com.esp/.MainActivity"], capture=True)
    
    cprint(GRN + "\n  [✓] ESP deployed! Open esp_overlay on your device.")
    return True

def main():
    ap = argparse.ArgumentParser(
        description="ESP System Builder - Build & deploy ESP overlay for Honor of Kings",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  %(prog)s                                    # Build everything
  %(prog)s --deploy                           # Build + deploy to device
  %(prog)s --skip-ndk                         # Only build APK
  %(prog)s --skip-android                     # Only build native binary
  %(prog)s --deploy --pkg com.mypkg.server    # Deploy with custom game package
        """
    )
    ap.add_argument("--skip-ndk", action="store_true", help="Skip native (tv_reader) build")
    ap.add_argument("--skip-android", action="store_true", help="Skip APK build")
    ap.add_argument("--deploy", action="store_true", help="Deploy to connected ADB device")
    ap.add_argument("--apk", type=str, help="Use existing APK path")
    ap.add_argument("--pkg", default="com.tencent.tmgp.sgame", help="Game package name")
    ap.add_argument("--port", type=int, default=47291, help="Reader TCP port")
    ap.add_argument("--sdk", type=str, help="Android SDK path override")
    ap.add_argument("--ndk", type=str, help="Android NDK path override")
    args = ap.parse_args()

    if args.sdk: os.environ["ANDROID_HOME"] = args.sdk
    if args.ndk: os.environ["NDK_HOME"] = args.ndk

    cprint(BOLD + "╔══════════════════════════════════════════╗")
    cprint(BOLD + "║   ESP System Builder  -  Honor of Kings  ║")
    cprint(BOLD + "╚══════════════════════════════════════════╝")
    print()

    sdk = find_sdk()
    ndk = find_ndk()
    adb = find_adb()

    BUILD_DIR.mkdir(parents=True, exist_ok=True)

    cprint(CYN + f"  SDK: {sdk or '(not found)'}")
    cprint(CYN + f"  NDK: {ndk or '(not found)'}")
    cprint(CYN + f"  ADB: {adb or '(not found)'}")
    print()

    if not sdk:
        cprint(RED + "  [!] Android SDK not found. Set ANDROID_HOME.")
        sys.exit(1)

    native_bin = None
    apk_path = Path(args.apk) if args.apk else None

    if not args.skip_ndk:
        cprint(BOLD + "\n  ── Building tv_reader (native C binary) ──")
        if not ndk:
            cprint(RED + "  [!] NDK not found. Set NDK_HOME or install NDK via SDK Manager.")
            sys.exit(1)
        native_bin = build_native(ndk, sdk)

    if not args.skip_android and not apk_path:
        cprint(BOLD + "\n  ── Building esp_overlay APK ──")
        apk_path = build_apk(sdk)

    cprint(BOLD + "\n  ── Build Summary ──")
    if native_bin: cprint(GRN + f"  Native:  {native_bin}")
    if apk_path:   cprint(GRN + f"  APK:     {apk_path}")
    print()

    if args.deploy:
        if not adb:
            cprint(RED + "  [!] adb not found")
            sys.exit(1)
        deploy(adb, native=native_bin, apk=apk_path, 
               game_pkg=args.pkg, port=args.port)
    else:
        cprint(YLW + "  Tip: Use --deploy to push to device and start ESP")

if __name__ == "__main__":
    main()
