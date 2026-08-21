#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build_inject_dex.py — 将 ESP 注入源码 (Java) 编译为 DEX
=========================================================
产物: esp_system/inject/build/classes.dex
  → 注入官方 APK 时作为 classesN.dex 加入

工具链 (自动探测):
  javac        JDK 自带
  d8           /opt/android-tools/android-14/d8 或 ANDROID_HOME/build-tools/*/d8
  android.jar  /opt/android-tools/android-34/android.jar 或 $ANDROID_HOME/platforms/*

用法:
  python3 build_inject_dex.py [--min-api 24]
"""

import argparse
import glob
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SRC = HERE / "src"
BUILD = HERE / "build"

# ---- 工具探测 ----

def find_tool_candidates(rel: str) -> list:
    cands = []
    # 1. 环境变量
    for env in ("ANDROID_BUILD_TOOLS",):
        v = Path(env) if (env in __import__('os').environ) else None
    # 2. 已知位置
    for base in ("/opt/android-tools", "/opt/android-sdk", "/opt/Android/Sdk"):
        cands += sorted(glob.glob(f"{base}/*/"))
    # 3. PATH
    w = shutil.which(rel)
    if w:
        cands.append(str(Path(w).parent))
    return cands


def find_d8() -> list:
    """返回 d8 调用命令 (列表)。优先新版 r8.jar (兼容 JDK 25 生成的匿名类)。"""
    # 1. 环境变量指定
    import os
    if os.environ.get("R8_JAR"):
        return ["java", "-cp", os.environ["R8_JAR"], "com.android.tools.r8.D8"]
    # 2. 已下载的新版 r8
    for p in ("/opt/r8/r8.jar", "/usr/local/share/r8/r8.jar"):
        if Path(p).exists():
            return ["java", "-cp", p, "com.android.tools.r8.D8"]
    # 3. PATH 中的 d8
    w = shutil.which("d8")
    if w:
        return [w]
    # 4. build-tools 自带 d8 (旧版, 可能不兼容新 JDK 编译产物)
    pats = [str(Path.home() / "Android/Sdk/build-tools/*/d8")]
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        v = os.environ.get(env)
        if v:
            pats.append(f"{v}/build-tools/*/d8")
    pats += ["/opt/android-tools/*/d8", "/opt/android-sdk/build-tools/*/d8"]
    for pat in pats:
        for p in sorted(glob.glob(pat)):
            return [p]
    for pat in ("/opt/android-tools/*/lib/d8.jar",):
        for p in sorted(glob.glob(pat)):
            return ["java", "-cp", p, "com.android.tools.r8.D8"]
    raise SystemExit("[!] 未找到 d8 (需要 Android build-tools 或 r8.jar)")


def find_android_jar() -> str:
    import os
    pats = [str(Path.home() / "Android/Sdk/platforms/*/android.jar")]
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        v = os.environ.get(env)
        if v:
            pats.append(f"{v}/platforms/*/android.jar")
    pats += ["/opt/android-tools/android-*/android.jar",
             "/opt/android-sdk/platforms/*/android.jar"]
    for pat in pats:
        for p in sorted(glob.glob(pat)):
            return p
    raise SystemExit("[!] 未找到 android.jar (需要 Android platform)")


def run(cmd, **kw):
    print("  $", " ".join(str(c) for c in cmd), flush=True)
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--min-api", type=int, default=24)
    args = ap.parse_args()

    srcs = sorted(str(p) for p in SRC.rglob("*.java"))
    if not srcs:
        raise SystemExit(f"[!] 无 Java 源码: {SRC}")
    print(f"[*] 源码文件 ({len(srcs)}):")
    for s in srcs:
        print(f"    {Path(s).name}")

    classes_dir = BUILD / "classes"
    if classes_dir.exists():
        shutil.rmtree(classes_dir)
    classes_dir.mkdir(parents=True)

    jar = find_android_jar()
    print(f"[*] android.jar: {jar}")

    print("[*] javac 编译 (release 8)...")
    r = run(["javac", "--release", "8", "-nowarn",
             "-cp", jar, "-d", str(classes_dir)] + srcs)
    if r.returncode != 0:
        print(r.stdout)
        print(r.stderr)
        raise SystemExit("[!] javac 编译失败")
    n_classes = len(list(classes_dir.rglob("*.class")))
    print(f"[✓] 编译 {n_classes} 个 class")

    dex_dir = BUILD / "dex"
    if dex_dir.exists():
        shutil.rmtree(dex_dir)
    dex_dir.mkdir(parents=True)

    d8 = find_d8()
    print(f"[*] d8: {' '.join(d8)}")
    class_files = [str(p) for p in sorted(classes_dir.rglob("*.class"))]
    r = run(d8 + ["--release", "--lib", jar,
                  f"--min-api", str(args.min_api),
                  "--output", str(dex_dir)] + class_files)
    if r.returncode != 0:
        print(r.stdout)
        print(r.stderr)
        raise SystemExit("[!] d8 转换失败")

    dex = dex_dir / "classes.dex"
    if not dex.exists():
        raise SystemExit("[!] d8 未产出 classes.dex")
    size = dex.stat().st_size
    print(f"[✓] DEX 产出: {dex} ({size:,} bytes)")

    # 校验 magic
    magic = dex.read_bytes()[:8]
    assert magic.startswith(b"dex\n"), f"DEX magic 错误: {magic!r}"
    print(f"[*] DEX magic: {magic!r}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
