#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ultimate_builder.py — 王者荣耀一键直装 + ESP 集成构建器
======================================================

选官方 APK → 填私服 IP → 自动产出:
  ① 已注入私服 IP 的直装 APK
  ② ESP 全功能内置版 APK (单一直装包: 悬浮窗+tv_reader 随游戏自动运行)
  ③ (可选) 独立 ESP Overlay APK — 分体模式
  ④ ADB 一键部署脚本

用法:
  python3 ultimate_builder.py 官方.apk --server 127.0.0.1:6645

  # 完整参数:
  python3 ultimate_builder.py 官方.apk \\
      --server 127.0.0.1:6645 \\
      --output ./output/ \\
      --game-pkg com.tencent.tmgp.sgame \\
      --skip-esp \\
      --skip-verify

架构:
  ┌─────────────────┐    ┌──────────────────┐    ┌────────────────────┐
  │  官方 APK 输入    │ →  │  URL 扫描 + 替换  │ →  │  重打包 + v2 签名   │
  └─────────────────┘    └──────────────────┘    └────────────────────┘
                                                              │
                                                              ▼
  ┌─────────────────┐    ┌──────────────────┐    ┌────────────────────┐
  │  ESP 注入 dex     │ → │  内置注入:         │ → │  game_embedded.apk  │
  │  (Provider/服务/  │    │  classesN.dex +   │    │  单一直装包 (推荐)   │
  │   悬浮窗)         │    │  assets+manifest  │    │  apksigner v1+v2+v3 │
  └─────────────────┘    └──────────────────┘    └────────────────────┘
          ▲                        ▲
          │ javac + d8             │ tv_reader (NDK, arm64+v7a)
  ┌─────────────────┐    ┌──────────────────┐
  │  Java 源码       │    │  esp_system/      │
  └─────────────────┘    └──────────────────┘
                                                              │
                                                              ▼
                                                    部署包 (output/deploy/)
                                                    ├── game_embedded.apk  ← 内置版
                                                    ├── game_private.apk
                                                    ├── install.sh
                                                    └── README.txt
"""

import argparse
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import time
import zipfile
from collections import defaultdict
from pathlib import Path

# ---- 内部模块导入 ----
import hokstrap
import build_private_hok as bph
import apk_injector

ROOT = Path(__file__).resolve().parent
BUILD_DIR = ROOT / "build_ultimate"
ANDROID_DIR = ROOT / "esp_system" / "android"
ESP_NATIVE_SRC = ROOT / "esp_system" / "tv_reader.c"
INJECT_DIR = ROOT / "esp_system" / "inject"
INJECT_DEX = INJECT_DIR / "build" / "dex" / "classes.dex"
ULTIMATE_OUT = ROOT / "output"

# ---- 终端美化 ----
class C:
    N = '\033[0m'
    R = '\033[91m'
    G = '\033[92m'
    Y = '\033[93m'
    B = '\033[94m'
    M = '\033[95m'
    C = '\033[96m'
    D = '\033[2m'
    BOLD = '\033[1m'
    BG_R = '\033[41m'
    BG_G = '\033[42m'
    BG_B = '\033[44m'

def p(color=None, msg="", **kw):
    print(f"{color or ''}{msg}{C.N if color else ''}", flush=True, **kw)

def banner():
    p(C.BOLD + C.C, "╔══════════════════════════════════════════════════════════╗")
    p(C.BOLD + C.C, "║  ultimate_builder.py  -  王者荣耀一键直装 + ESP 集成构建器 ║")
    p(C.BOLD + C.C, "╚══════════════════════════════════════════════════════════╝")
    p()

def section(title):
    p()
    p(C.BOLD + C.M, f"  ── {title} ──")

def ok(msg): p(C.G, f"  [✓] {msg}")
def fail(msg): p(C.R, f"  [✗] {msg}")
def info(msg): p(C.C, f"  [·] {msg}")
def warn(msg): p(C.Y, f"  [!] {msg}")

def run(cmd, cwd=None, env=None, capture=False, timeout=None):
    merged = os.environ.copy()
    if env: merged.update(env)
    return subprocess.run(
        cmd, cwd=str(cwd) if cwd else None, env=merged,
        capture_output=capture, text=True, timeout=timeout,
        stdin=subprocess.DEVNULL
    )

# ================================================================
#  Step 1: 扫描 APK 中的服务器地址
# ================================================================
def step1_scan(apk_path: Path) -> dict:
    section("Step 1/6  扫描 APK 中的服务器地址")
    findings = bph.scan_apk_for_replacement(str(apk_path))
    total = sum(len(v) for v in findings.values())
    info(f"扫描命中 {len(findings)} 个文件，共 {total} 处")
    for f, fnds in list(findings.items())[:8]:
        types = set(fn['type'] for fn in fnds)
        info(f"  {f}  ({len(fnds)} 处, types: {', '.join(sorted(types))})")
    if total == 0:
        warn("未找到任何服务器地址，APK 可能不包含标准 URL 格式")
    return findings

# ================================================================
#  Step 2: 生成替换规则
# ================================================================
def step2_rules(findings: dict, server_addr: str, out_dir: Path) -> Path:
    section("Step 2/6  生成替换规则")
    rules = bph.build_replacement_rules(findings, server_addr)
    hokstrap_rules = {'replacements': [], 'file_overrides': {}}
    for r in rules:
        hokstrap_rules['replacements'].append({
            'path': r['file'],
            'find': r['find_original'],
            'replace': r['replace_with'],
            'binary': r['is_binary'],
        })
    rules_path = out_dir / "replacement_rules.json"
    with open(rules_path, 'w', encoding='utf-8') as f:
        json.dump(hokstrap_rules, f, ensure_ascii=False, indent=2)
    ok(f"生成 {len(hokstrap_rules['replacements'])} 条规则 → {rules_path}")
    for r in rules[:5]:
        show_find = r['find_original'][:60] + ('...' if len(r['find_original']) > 60 else '')
        show_rep = r['replace_with'][:60] + ('...' if len(r['replace_with']) > 60 else '')
        info(f"  [{ 'BIN' if r['is_binary'] else 'TXT' }] {r['file']}")
        info(f"         {show_find}  →  {show_rep}")
    return rules_path

# ================================================================
#  Step 3: 应用替换 + 重打包 + 签名
# ================================================================
def step3_patch_official(apk_path: Path, out_dir: Path, keys_dir: Path) -> Path:
    section("Step 3/6  直连官方 — 重打包 + v2签名 (无IP替换)")

    patched = out_dir / "game_patched.apk"
    signed  = out_dir / "game_signed.apk"

    info("剥离旧签名条目，重新打包 (原样拷贝 + 对齐 4/4096)...")
    hokstrap.rebuild_apk(str(apk_path), str(patched), {})
    ok(f"重打包完成 → {patched} ({os.path.getsize(patched):,} bytes)")

    info("签名 (apksigner v1+v2+v3)...")
    shutil.copyfile(patched, signed)
    if not apk_injector.sign_apk(Path(signed), Path(keys_dir)):
        fail("签名失败")
        sys.exit(1)
    ok(f"签名完成 → {signed} ({os.path.getsize(signed):,} bytes)")

    return signed

def step3_patch_and_sign(apk_path: Path, rules_path: Path, out_dir: Path, keys_dir: Path) -> Path:
    section("Step 3/6  应用替换 + ZIP级重打包 + v2签名")
    
    patched = out_dir / "game_patched.apk"
    signed  = out_dir / "game_signed.apk"
    
    # Patch
    class Args: pass
    a = Args()
    a.apk = str(apk_path)
    a.rules = str(rules_path)
    a.out = str(patched)
    a.allow_miss = False
    
    info("应用替换规则...")
    overrides = hokstrap.apply_replacements(a)
    hokstrap.rebuild_apk(str(apk_path), str(patched), overrides)
    ok(f"替换完成 → {patched} ({os.path.getsize(patched):,} bytes)")
    
    # Sign
    info("签名 (apksigner v1+v2+v3)...")
    shutil.copyfile(patched, signed)
    if not apk_injector.sign_apk(Path(signed), Path(keys_dir)):
        fail("签名失败")
        sys.exit(1)
    ok(f"签名完成 → {signed} ({os.path.getsize(signed):,} bytes)")

    return signed

def step4_verify(apk_path: Path):
    section("Step 4/6  校验 APK 结构与签名")
    # 1) 结构校验: zip 可读 / resources.arsc STORED / STORED 条目对齐
    #    (v2 摘要已覆盖全文件字节, 官方 apksigner verify 负责完整性;
    #     不再整包 inflate 校验 CRC, 2GB 级 APK 上又慢又吃内存)
    ok_all = True
    try:
        with zipfile.ZipFile(apk_path) as zf:
            names = zf.namelist()
            for n in hokstrap.FORCE_STORED:
                if n in names and zf.getinfo(n).compress_type != zipfile.ZIP_STORED:
                    fail(f"{n} 被压缩（API 30+ 要求 STORED）")
                    ok_all = False
    except Exception as e:
        fail(f"zip 无法打开: {e}")
        sys.exit(1)
    checked, problems = hokstrap.check_alignment(str(apk_path))
    if problems:
        for p in problems:
            fail(p)
        ok_all = False
    else:
        info(f"对齐检查: {checked} 个 STORED 条目通过 (.so=4096, 其余=4)")
    # 2) 官方签名校验 (v1+v2+v3)
    if not apk_injector.verify_with_apksigner(apk_path):
        fail("apksigner 签名校验失败")
        sys.exit(1)
    ok("APK 结构 + apksigner 签名校验通过")
    if not ok_all:
        sys.exit(1)

# ================================================================
#  Step 5: 构建 ESP 系统
# ================================================================
def find_ndk() -> Path | None:
    candidates = []
    for env in ("NDK_HOME", "ANDROID_NDK_HOME"):
        v = os.environ.get(env)
        if v and v != ".":
            candidates.append(Path(v))
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        v = os.environ.get(env)
        if v and v != ".":
            candidates.append(Path(v) / "ndk")
    candidates += [
        Path("/opt/android-tools/android-ndk-r26d"),
        Path("/opt/android-ndk"),
        Path("/opt/android-sdk/ndk"),
        Path.home() / "Android/Sdk/ndk",
    ]
    for p in candidates:
        if (p / "build" / "cmake" / "android.toolchain.cmake").exists():
            return p
        if p.is_dir():
            # Find the actual NDK version directory
            for sub in sorted(p.iterdir(), key=lambda x: x.stat().st_mtime, reverse=True):
                if (sub / "build" / "cmake" / "android.toolchain.cmake").exists():
                    return sub
    return None

def _find_in_sdk(rel_glob: str) -> Path | None:
    """在 $ANDROID_HOME 下按 glob 找文件 (SDK cmake 包等)"""
    import glob as _g
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        v = os.environ.get(env)
        if v:
            for p in sorted(_g.glob(f"{v}/{rel_glob}")):
                return Path(p)
    return None

def _cmake_bin(ndk: Path) -> Path | None:
    """NDK 自带 cmake 优先, 其次 SDK cmake 包, 最后系统 cmake"""
    inner = ndk / "cmake" / "bin" / "cmake"
    if inner.exists():
        return inner
    p = _find_in_sdk("cmake/*/bin/cmake")
    if p:
        return p
    w = shutil.which("cmake")
    return Path(w) if w else None

def _ninja_bin(ndk: Path) -> Path | None:
    """NDK 自带 ninja 优先, 其次 SDK cmake 包, 最后系统 ninja"""
    inner = ndk / "cmake" / "bin" / "ninja"
    if inner.exists():
        return inner
    p = _find_in_sdk("cmake/*/bin/ninja")
    if p:
        return p
    w = shutil.which("ninja")
    return Path(w) if w else None

def find_sdk() -> Path | None:
    for p in [
        Path(os.environ.get("ANDROID_HOME", "")),
        Path("/opt/android-tools"),
        Path("/opt/android-sdk"),
        Path.home() / "Android/Sdk",
        Path("/usr/local/android-sdk"),
    ]:
        # 注意: Path("") == Path(".") — 过滤空 ANDROID_HOME 误命中当前目录
        if p and str(p) != "." and p.is_dir():
            return p
    return None

def find_adb() -> Path | None:
    p = shutil.which("adb")
    if p: return Path(p)
    sdk = find_sdk()
    if sdk:
        p = sdk / "platform-tools" / "adb"
        if p.exists(): return p
    return None

def build_tv_reader_ndk(ndk: Path, sdk: Path, out_dir: Path) -> dict:
    """Compile tv_reader.c as armeabi-v7a + arm64-v8a native binaries."""
    section("Step 5a  编译 tv_reader 原生二进制 (NDK)")

    toolchain = ndk / "build" / "cmake" / "android.toolchain.cmake"
    cmake_bin = _cmake_bin(ndk)
    ninja_bin = _ninja_bin(ndk)

    if not toolchain.exists():
        fail(f"NDK toolchain not found: {toolchain}")
        return {}
    if not cmake_bin or not ninja_bin:
        fail("cmake/ninja 不可用 (NDK 内置或系统安装)")
        return {}

    abi_targets = {
        "arm64_v8a": "arm64-v8a",
        "armeabi_v7a": "armeabi-v7a",
        # 模拟器 (雷电/MuMu) 是 x86_64 + ARM 转译; root shell 下裸执行 ARM ELF
        # 不经过转译路径, 必须提供原生 x86_64 二进制
        "x86_64": "x86_64",
    }

    results = {}
    for abi_key, abi_cmake in abi_targets.items():
        build_dir = out_dir / f"cmake_{abi_key}"
        if build_dir.exists():
            shutil.rmtree(build_dir)
        build_dir.mkdir(parents=True, exist_ok=True)

        info(f"Building for {abi_cmake}...")
        cmake_cmd = [
            str(cmake_bin), str(ROOT / "esp_system"),
            "-B", str(build_dir),
            "-G", "Ninja",
            f"-DANDROID_ABI={abi_cmake}",
            f"-DANDROID_PLATFORM=android-24",
            f"-DCMAKE_TOOLCHAIN_FILE={toolchain}",
            f"-DANDROID_NDK={ndk}",
            f"-DCMAKE_MAKE_PROGRAM={ninja_bin}",
            "-DCMAKE_BUILD_TYPE=Release",
        ]
        env = {
            "ANDROID_SDK_ROOT": str(sdk) if sdk else "",
            "ANDROID_NDK_HOME": str(ndk),
        }
        r = run(cmake_cmd, cwd=build_dir, env=env, capture=True)
        if r.returncode != 0:
            fail(f"cmake configure failed for {abi_cmake}")
            if r.stderr: print(r.stderr[-400:])
            continue

        r = run([str(cmake_bin), "--build", str(build_dir)], cwd=build_dir, env=env, capture=True)
        if r.returncode != 0:
            fail(f"cmake build failed for {abi_cmake}")
            if r.stderr: print(r.stderr[-400:])
            continue

        for f in build_dir.rglob("tv_reader"):
            if f.is_file():
                out_bin = out_dir / f"tv_reader_{abi_key}"
                shutil.copy2(f, out_bin)
                results[abi_key] = out_bin
                ok(f"  {abi_cmake}: {out_bin} ({out_bin.stat().st_size:,} bytes)")
                break

    return results

def build_tv_reader_host(out_dir: Path) -> Path | None:
    """Fallback: compile tv_reader for host (for testing only)."""
    info("Building host fallback (for testing only)...")
    out_bin = out_dir / "tv_reader_host"
    r = run(["gcc", "-O2", "-o", str(out_bin), str(ESP_NATIVE_SRC), "-lm", "-lpthread"],
            capture=True)
    if r.returncode == 0:
        ok(f"Host binary: {out_bin}")
        return out_bin
    return None

# ---- ESP dex 混淆映射 (对抗 TP 启动期 dex 内容扫描) ----
# 探针实测: 注入原始 dex → 启动 1.2s 被 SIGKILL; 仅加 assets → 正常。
# 故 dex 内不得残留任何 cheat 特征字样 (类名/字符串/标识符)。
OBF_PROVIDER = "com.gs.GsProvider"
OBF_SERVICE = "com.gs.GsService"
OBF_ASSET_ARM64 = "assets/native/gsvc_arm64"
OBF_ASSET_V7A = "assets/native/gsvc_v7a"

# 顺序敏感: 先长串/具体, 后短串/泛化
_OBF_REPLACEMENTS = [
    # 游戏包名常量删除, 调用点改运行时获取 (dex 不留包名字符串)
    ('    public static final String GAME_PKG_DEFAULT = "com.tencent.tmgp.sgame";\n',
     ''),
    ('RootHelper.GAME_PKG_DEFAULT', 'getPackageName()'),
    # 运行时路径 / 二进制名 / 资产路径
    ('/data/adb/esp', '/data/local/tmp/.gs'),
    ('tv_reader', 'gsvc'),
    ('esp_native/', 'native/'),
    ('esp_overlay', 'gs_panel'),
    # 类与包名
    ('com.esp', 'com.gs'),
    ('com/esp', 'com/gs'),
    ('EspInjectProvider', 'GsProvider'),
    ('EspCanvasView', 'GsCanvasView'),
    ('EspFrame', 'GsFrame'),
    ('OverlayService', 'GsService'),
    ('RootHelper', 'GsOps'),
    ('ReaderClient', 'GsNet'),
    # 残留特征字样 (TAG / UI 文案 / 标识符)
    ('ESP-', 'GS-'),
    ('ESP', 'GS'),
    ('Esp', 'Gs'),
    ('esp', 'gs'),
    ('READER', 'SVC'),
    ('Reader', 'Svc'),
    ('reader', 'svc'),
    # inject 字样 (日志 TAG/文案) → 中性 init
    ('Inject', 'Init'),
    ('inject', 'init'),
]
_OBF_FILE_RENAMES = {
    'EspInjectProvider.java': 'GsProvider.java',
    'OverlayService.java': 'GsService.java',
    'EspCanvasView.java': 'GsCanvasView.java',
    'EspFrame.java': 'GsFrame.java',
    'RootHelper.java': 'GsOps.java',
    'ReaderClient.java': 'GsNet.java',
}
# 混淆后 dex 字节里禁止出现的特征串 (小写匹配由 MUTF-8 直接子串判定)
_FORBIDDEN_IN_DEX = [b'esp', b'Esp', b'ESP', b'tv_reader', b'/data/adb',
                     b'tmgp.sgame', b'cheat', b'hack', b'inject']


def _obfuscate_sources(src_dir: Path, out_dir: Path) -> list:
    """拷贝并混淆 Java 源码树 → out_dir; 返回 java 文件路径列表"""
    if out_dir.exists():
        shutil.rmtree(out_dir)
    java_files = []
    for p in sorted(src_dir.rglob("*.java")):
        txt = p.read_text(encoding="utf-8")
        for old, new in _OBF_REPLACEMENTS:
            txt = txt.replace(old, new)
        rel = p.relative_to(src_dir)                      # com/esp/Xxx.java
        rel = Path(*[('gs' if part == 'esp' else part) for part in rel.parts])
        new_name = _OBF_FILE_RENAMES.get(rel.name, rel.name)
        dst = out_dir / rel.parent / new_name
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_text(txt, encoding="utf-8")
        java_files.append(dst)
    return java_files


def _compile_dex(java_files: list, out_dir: Path, min_api: int = 24) -> Path:
    """javac + d8 → out_dir/classes.dex (复用 build_inject_dex 工具探测)"""
    sys.path.insert(0, str(INJECT_DIR))
    import build_inject_dex as bid
    jar = bid.find_android_jar()
    d8 = bid.find_d8()

    classes = out_dir / "classes"
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir(parents=True)
    # -parameters: JDK21+ javac 对匿名类 mandated 参数发无名 MethodParameters,
    # 旧 d8 (build-tools 34) 会 NPE; 显式 -parameters 让所有参数具名即可规避
    r = run(["javac", "--release", "8", "-nowarn", "-parameters", "-cp", jar,
             "-d", str(classes)] + [str(f) for f in java_files], capture=True)
    if r.returncode != 0:
        fail("javac 编译混淆源码失败")
        if r.stdout: print(r.stdout[-800:])
        if r.stderr: print(r.stderr[-800:])
        return None

    dex_dir = out_dir / "dex"
    if dex_dir.exists():
        shutil.rmtree(dex_dir)
    dex_dir.mkdir(parents=True)
    class_files = sorted(classes.rglob("*.class"))
    r = run(d8 + ["--release", "--lib", jar, "--min-api", str(min_api),
                  "--output", str(dex_dir)] + [str(f) for f in class_files],
            capture=True)
    if r.returncode != 0:
        fail("d8 转换失败")
        if r.stdout: print(r.stdout[-800:])
        if r.stderr: print(r.stderr[-800:])
        return None
    dex = dex_dir / "classes.dex"
    return dex if dex.exists() else None


def build_esp_inject_dex(out_dir: Path) -> Path | None:
    """构建混淆版 ESP 注入 dex (源码特征清洗 → javac + d8)"""
    section("Step 5b  构建 ESP 注入 dex (混淆: 类名/字符串/路径中性化)")

    src = INJECT_DIR / "src"
    if not src.exists():
        fail(f"注入源码缺失: {src}")
        return None

    obf_dir = out_dir / "obf_build"
    java_files = _obfuscate_sources(src, obf_dir / "src")
    if not java_files:
        fail("混淆源码为空")
        return None
    info(f"混淆源码: {len(java_files)} 个文件 → com.gs.*")

    dex = _compile_dex(java_files, obf_dir)
    if dex is None:
        return None

    # 特征串自检: dex 字节级扫描, 任何残留直接判失败
    data = dex.read_bytes()
    leaked = [s.decode() for s in _FORBIDDEN_IN_DEX if s in data]
    if leaked:
        fail(f"混淆后 dex 仍含特征串: {leaked}")
        return None
    ok(f"特征串自检通过 (esp/tv_reader/tmgp.sgame 等均未残留)")
    ok(f"注入 dex → {dex} ({dex.stat().st_size:,} bytes)")
    return dex


def embed_esp_into_apk(
    game_apk: Path, dex: Path, tv_bins: dict, out_dir: Path,
    keys_dir: Path, game_pkg: str,
) -> Path | None:
    """将 ESP 全套组件内置注入到游戏 APK — 单一直装包"""
    section("Step 5c  ESP 内置注入 (classesN.dex + assets + manifest)")

    out_apk = out_dir / "game_embedded_unsigned.apk"
    report = apk_injector.inject_esp_into_apk(
        game_apk, out_apk, dex,
        tv_reader_arm64=tv_bins.get("arm64_v8a"),
        tv_reader_v7a=tv_bins.get("armeabi_v7a"),
        keys_dir=keys_dir,
        provider_class=OBF_PROVIDER,
        service_class=OBF_SERVICE,
        asset_arm64_name=OBF_ASSET_ARM64,
        asset_v7a_name=OBF_ASSET_V7A,
        authorities=f"{game_pkg}.gs.c1",
    )
    if report is None:
        fail("注入失败")
        return None

    # 官方工具链校验
    if not apk_injector.verify_with_aapt2(out_apk, provider_name="GsProvider",
                                          service_name="GsService"):
        fail("aapt2 校验注入后的 manifest 失败")
        return None
    ok("aapt2 manifest 校验通过 (GsProvider + GsService + 权限)")

    if not apk_injector.verify_with_apksigner(out_apk):
        fail("apksigner 签名校验失败")
        return None
    ok("apksigner v1+v2+v3 签名校验通过")

    m = report.get("manifest", {})
    info(f"包名: {m.get('package')}  新增权限: {len(m.get('permissions', []))}")
    info(f"注入条目: {', '.join(report.get('added_entries', []))}")

    final = out_dir / "game_embedded.apk"
    shutil.move(str(out_apk), final)
    ok(f"内置版 APK → {final} ({final.stat().st_size:,} bytes)")
    return final


def _build_harmless_dex(out_dir: Path) -> Path | None:
    """构建无害探针 dex (一个纯 Java 工具类, 无任何 Android/悬浮窗/root API)"""
    tiny_src = out_dir / "tiny_build" / "src"
    pkg_dir = tiny_src / "com" / "gs" / "core"
    pkg_dir.mkdir(parents=True, exist_ok=True)
    (pkg_dir / "Holder.java").write_text(
        'package com.gs.core;\n'
        'public class Holder {\n'
        '    public static String v() { return "1.0.2"; }\n'
        '    public static int i(int a) { return a + 7; }\n'
        '}\n', encoding="utf-8")
    return _compile_dex(sorted(tiny_src.rglob("*.java")), out_dir / "tiny_build")


def build_probe_apks(game_apk: Path, dex: Path, tv_bins: dict,
                     out_dir: Path, keys_dir: Path, game_pkg: str = "") -> list:
    """TP 反作弊检测点探针 (上轮结论: assets 不检测, dex 被扫描):
      game_test_hdex.apk — 仅追加无害 classesN.dex, 不动 manifest/assets
                           → 判定 TP 检测的是 dex 文件存在性还是内容
      game_test_mfo.apk  — 仅改 manifest (权限 + service + 禁用 provider),
                           无 dex/assets → 判定 manifest 结构是否被检测
    判定: 能进游戏 = 不被检测; 启动白屏/被杀 = 检测该项。"""
    section("Step 5e  TP 检测探针变体 (harmless-dex / manifest-only)")

    probes = []

    # ---- 探针 1: 无害 dex ----
    info("构建探针: game_test_hdex.apk (仅追加无害 dex)...")
    hdex = _build_harmless_dex(out_dir)
    if hdex is None:
        warn("无害 dex 构建失败, 跳过 hdex 探针")
    else:
        out_apk = out_dir / "game_test_hdex_unsigned.apk"
        rep = apk_injector.inject_esp_into_apk(
            game_apk, out_apk, hdex,
            keys_dir=keys_dir,
            inject_manifest=False, include_dex=True, include_assets=False)
        if rep and apk_injector.verify_with_apksigner(out_apk):
            final = out_dir / "game_test_hdex.apk"
            shutil.move(str(out_apk), final)
            ok(f"探针 → {final} ({final.stat().st_size:,} bytes)")
            probes.append(final)
        else:
            warn("hdex 探针构建/签名失败")

    # ---- 探针 2: manifest-only (provider 禁用 → 不会被实例化, 无需 dex) ----
    info("构建探针: game_test_mfo.apk (仅改 manifest)...")
    out_apk = out_dir / "game_test_mfo_unsigned.apk"
    rep = apk_injector.inject_esp_into_apk(
        game_apk, out_apk, dex,
        keys_dir=keys_dir,
        inject_manifest=True, include_dex=False, include_assets=False,
        provider_enabled=False,
        provider_class=OBF_PROVIDER, service_class=OBF_SERVICE,
        authorities=f"{game_pkg or 'unknown.pkg'}.gs.c1",
    )
    if (rep and apk_injector.verify_with_aapt2(out_apk, provider_name="GsProvider",
                                               service_name="GsService")
            and apk_injector.verify_with_apksigner(out_apk)):
        final = out_dir / "game_test_mfo.apk"
        shutil.move(str(out_apk), final)
        ok(f"探针 → {final} ({final.stat().st_size:,} bytes)")
        probes.append(final)
    else:
        warn("mfo 探针构建/签名失败")

    return probes


def build_esp_overlay_apk(sdk: Path, tv_bins: dict, out_dir: Path,
                           game_pkg: str, port: int) -> Path | None:
    """Build the ESP overlay APK with embedded tv_reader binaries (分体模式, 可选).

    嵌入全部已构建 ABI (arm64 + x86_64): 真机用 arm64, 雷电/MuMu 等
    x86 模拟器 root shell 下无法执行 ARM ELF, 需原生 x86_64。
    """
    section("Step 5d  构建独立 ESP Overlay APK (分体模式, 可选)")

    # asset 名 → 构建产物键
    asset_map = {
        "arm64_v8a": "tv_reader_arm64",
        "x86_64": "tv_reader_x64",
    }

    # Copy tv_reader binaries into assets
    assets_dir = ANDROID_DIR / "app" / "src" / "main" / "assets" / "native"
    assets_dir.mkdir(parents=True, exist_ok=True)

    # Clean old assets
    for old in assets_dir.glob("tv_reader*"):
        old.unlink()

    embedded = []
    for abi_key, asset_name in asset_map.items():
        src = tv_bins.get(abi_key)
        if not src:
            continue
        target_asset = assets_dir / asset_name
        shutil.copy2(src, target_asset)
        target_asset.chmod(0o644)
        embedded.append(asset_name)
        info(f"嵌入 tv_reader {abi_key} ({src.stat().st_size:,} bytes) → assets/native/{asset_name}")

    if not embedded:
        fail("无可嵌入的 tv_reader 二进制")
        return None
    
    # Build using gradle bootstrap
    wrapper = ANDROID_DIR / "gradlew_bootstrap.py"
    if not wrapper.exists():
        fail("gradlew_bootstrap.py not found")
        return None
    
    env = {
        "ANDROID_HOME": str(sdk),
        "ANDROID_SDK_ROOT": str(sdk),
        "JAVA_HOME": os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-17-openjdk-amd64"),
    }
    
    info("Building ESP overlay APK...")
    r = run(["python3", str(wrapper), "assembleDebug"],
            cwd=ANDROID_DIR, env=env, capture=True)
    
    if r.returncode != 0:
        fail(f"APK build failed")
        if r.stderr: print(r.stderr[-600:])
        if r.stdout: print(r.stdout[-300:])
        return None
    
    # Find output APK (限定 outputs 目录, 避免 rglob 误中缓存 APK)
    apk_found = None
    for pattern in ("app/build/outputs/apk/debug/*.apk",
                    "app/build/outputs/apk/release/*.apk"):
        for p in ANDROID_DIR.glob(pattern):
            apk_found = p
            break
        if apk_found:
            break
    
    if not apk_found:
        fail("No APK produced")
        return None
    
    out_apk = out_dir / "esp_overlay.apk"
    shutil.copy2(apk_found, out_apk)
    ok(f"ESP Overlay APK: {out_apk} ({out_apk.stat().st_size:,} bytes)")
    
    # Verify tv_reader is embedded
    with zipfile.ZipFile(out_apk) as zf:
        embedded = [n for n in zf.namelist() if "tv_reader" in n]
        info(f"APK 内嵌文件: {embedded}")
        for e in embedded:
            data = zf.read(e)
            is_elf = data[:4] == b'\x7fELF'
            info(f"  {e}: {len(data):,} bytes {'✓ ELF' if is_elf else '✗ NOT ELF'}")
    
    return out_apk

# ================================================================
#  Step 6: 生成部署包
# ================================================================
def generate_deployment_bundle(
    game_apk: Path, esp_apk: Path, tv_reader_bin: Path,
    out_dir: Path, game_pkg: str, server_addr: str | None, port: int,
    is_official: bool = False,
    embedded_apk: Path | None = None,
    probe_apks: list = None,
):
    section("Step 6/6  生成部署包")

    deploy_dir = out_dir / "deploy"
    deploy_dir.mkdir(parents=True, exist_ok=True)

    # ---- 输出文件名: 官方模式用 game_official.apk ----
    out_game_name = "game_official.apk" if is_official else "game_private.apk"
    shutil.copy2(game_apk, deploy_dir / out_game_name)
    if esp_apk:
        shutil.copy2(esp_apk, deploy_dir / "esp_overlay.apk")

    # ---- TP 检测探针 (定位反作弊检测项, 手动安装测试) ----
    for probe in (probe_apks or []):
        shutil.copy2(probe, deploy_dir / probe.name)

    server_display = server_addr if server_addr else "直连官方"

    embedded_name = None
    if embedded_apk:
        embedded_name = "game_embedded.apk"
        shutil.copy2(embedded_apk, deploy_dir / embedded_name)

    embedded_block = f'''
if [ -f "{embedded_name}" ]; then
    echo "[*] 安装内置版 APK (ESP 已内置, 单包直装)..."
    adb install -r "{embedded_name}"
    echo "  [✓] 内置版已安装"
    adb shell "appops set {game_pkg} SYSTEM_ALERT_WINDOW allow" 2>/dev/null || true
    adb shell "am start -n {game_pkg}/{game_pkg}.SGameActivity"
    echo ""
    echo "=============================================="
    echo "  内置版部署完成！"
    echo "  ESP 随游戏自动启动 (游戏启动数秒后悬浮窗出现)"
    echo "  首次需在 Magisk/KernelSU 授权 (部署 tv_reader)"
    echo "=============================================="
    exit 0
fi
''' if embedded_name else ""

    # Generate install script
    install_sh = deploy_dir / "install.sh"
    install_sh.write_text(f'''#!/bin/bash
# ============================================================
#  一键部署脚本 — 王者荣耀{"官方版 + ESP" if is_official else "私服 + ESP"}
#  生成时间: {time.strftime('%Y-%m-%d %H:%M:%S')}
# ============================================================
set -e

GAME_APK="{out_game_name}"
ESP_APK="esp_overlay.apk"
GAME_PKG="{game_pkg}"
PORT="{port}"
SERVER="{server_display}"
{f'EMBEDDED_APK="{embedded_name}"' if embedded_name else 'EMBEDDED_APK=""'}
echo "[*] 检查 ADB 连接..."
adb wait-for-device
{embedded_block}
echo "[*] 检查 Root 权限..."
if ! adb shell "su -c 'id'" | grep -q uid=0; then
    echo "[!] 需要 Root 权限 (Magisk/KernelSU)。请先 root 设备。"
    exit 1
fi

echo "[*] 安装游戏 APK..."
adb install -r "$GAME_APK"
echo "  [✓] 游戏已安装"

if [ -f "$ESP_APK" ]; then
    echo "[*] 安装 ESP Overlay APK..."
    adb install -r "$ESP_APK"
    echo "  [✓] ESP 已安装"
fi

echo "[*] 授予悬浮窗权限..."
adb shell "appops set {game_pkg} SYSTEM_ALERT_WINDOW allow" 2>/dev/null || true
adb shell "appops set com.esp SYSTEM_ALERT_WINDOW allow" 2>/dev/null || true

echo "[*] 启动游戏..."
adb shell "am start -n {game_pkg}/{game_pkg}.SGameActivity"

if [ -f "$ESP_APK" ]; then
    echo "[*] 启动 ESP Overlay 应用..."
    sleep 3
    adb shell "am start -n com.esp/.MainActivity"
fi

echo ""
echo "=============================================="
echo "  部署完成！"
echo "  模式: {'直连官方' if is_official else '私服模式'} (分体: 游戏 + 独立 ESP)"
echo "=============================================="
echo ""
echo "  1. ESP 应用内点击启动 (首次会请求 Root 部署 tv_reader)"
echo "  2. 进入对局后 ESP 悬浮窗显示敌人位置"
echo ""
echo "  调试命令:"
echo "    adb shell 'su -c \"ps -A | grep tv_reader\"'"
echo "    adb shell 'su -c \"logcat -s ESP-Reader\"'"
echo ""
''', encoding='utf-8')
    install_sh.chmod(0o755)

    # Generate README
    readme = deploy_dir / "README.txt"
    mode_text = "直连官方 (无IP替换)" if is_official else f"私服模式 → {server_addr}"
    embedded_doc = f"""内置版 (推荐):
  game_embedded.apk    — ESP 全部功能内置的单一直装包
                        (启动游戏自动运行: 悬浮窗 + tv_reader 部署)
  安装: adb install -r game_embedded.apk
  首次启动游戏后, ESP Provider 自动拉起悬浮窗服务;
  悬浮窗权限: adb shell "appops set {game_pkg} SYSTEM_ALERT_WINDOW allow"

""" if embedded_name else ""
    readme.write_text(f'''王者荣耀{"官方版" if is_official else "私服"} + ESP 集成部署包
====================================

生成时间: {time.strftime('%Y-%m-%d %H:%M:%S')}
模式: {mode_text}
游戏包名: {game_pkg}
ESP 端口: {port}

文件说明:
  {out_game_name}      — {"官方版 APK (已重打包+签名, 不含任何注入)" if is_official else "已注入私服 IP 的直装 APK"}
  esp_overlay.apk       — ESP 悬浮窗服务 (自包含 tv_reader, 独立应用)
  install.sh            — 一键部署脚本
{embedded_doc}
分体模式 (推荐, TP 实测唯一可行方案):
  游戏包不加 dex/不改 manifest (TP 双重校验), ESP 独立应用 + root
  外部读取游戏内存, 悬浮窗绘制 — 游戏进程零改动:
    chmod +x install.sh
    ./install.sh
  或手动:
    adb install -r {out_game_name}
    adb install -r esp_overlay.apk
    adb shell "appops set com.esp SYSTEM_ALERT_WINDOW allow"
    打开 ESP 应用 → 启动 (首次授权 Root) → 进对局看悬浮窗

ESP 使用 (分体模式):
  1. 先启动游戏进入对局
  2. 打开 ESP Overlay 应用, 点击启动
  3. 首次运行会请求 Root (部署 tv_reader) — Magisk/KernelSU 授权
  4. 悬浮窗出现后即显示敌人位置/小地图

注意:
  - 需要 Root 权限 (Magisk / KernelSU)
  - 首次启动 ESP 时需要授予 Root 权限
  - 确保游戏已登录进入大厅后 ESP 才会有数据
  - 若 ESP 无显示，检查 reader 日志:
    adb shell "su -c 'tail -50 /data/local/tmp/.gs/gsvc.log'"

TP 反作弊检测结论 (探针实测):
  +assets 文件      → 不检测, 可自由添加
  +classesN.dex     → 检测文件数量 (无害 dex 也被杀) → 单包内置不可行
  manifest 任何修改 → 检测 (权限/组件新增即被杀)   → 单包内置不可行
  结论: 游戏包只能"重打包+重签名", 功能外置 = 分体模式唯一可行
''', encoding='utf-8')

    ok(f"部署包 → {deploy_dir}/")
    for f in sorted(deploy_dir.iterdir()):
        sz = f.stat().st_size if f.is_file() else 0
        info(f"  {f.name} ({sz:,} bytes)")

# ================================================================
#  MAIN
# ================================================================
def main():
    banner()
    
    ap = argparse.ArgumentParser(
        description='王者荣耀一键直装 + ESP 集成构建器',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  %(prog)s 官方.apk --server 127.0.0.1:6645
  %(prog)s 官方.apk --server sgame.myserver.com:8080 --output ./my_build/
  %(prog)s 官方.apk --server 10.0.0.1:6645 --skip-esp    # 只做私服直装
  %(prog)s 官方.apk --official                            # 直连官方，仅重打包+签名
  %(prog)s 官方.apk --skip-embed                          # 不生成 ESP 内置版
  %(prog)s 官方.apk --with-overlay                        # 同时产出独立 ESP Overlay APK
        """
    )
    ap.add_argument('apk', help='官方 APK 路径')
    ap.add_argument('--server', default=None,
                   help='私服地址，如 127.0.0.1:6645 (不填则直连官方)')
    ap.add_argument('--official', action='store_true',
                   help='直连官方，不替换服务器IP (仅重打包+签名)')
    ap.add_argument('--output', '-o', default='./output',
                   help='输出目录 (默认 ./output)')
    ap.add_argument('--game-pkg', default='com.tencent.tmgp.sgame',
                   help='游戏包名')
    ap.add_argument('--port', type=int, default=47291,
                   help='ESP Reader TCP 端口 (默认 47291)')
    ap.add_argument('--keys-dir', default='./keys',
                   help='签名密钥目录')
    ap.add_argument('--skip-esp', action='store_true',
                   help='跳过 ESP 构建 (只做私服直装)')
    ap.add_argument('--skip-embed', action='store_true',
                   help='跳过 ESP 内置注入 (不生成 game_embedded.apk 单包)')
    ap.add_argument('--with-overlay', action='store_true',
                   help='同时构建独立 ESP Overlay APK (分体模式; 默认仅内置版)')
    ap.add_argument('--skip-verify', action='store_true',
                   help='跳过最终校验')
    ap.add_argument('--skip-scan', action='store_true',
                   help='跳过 URL 扫描 (使用已有 replacement_rules.json)')
    ap.add_argument('--force', action='store_true',
                   help='覆盖已有输出目录')
    args = ap.parse_args()
    
    # 验证输入
    apk_path = Path(args.apk).resolve()
    if not apk_path.is_file():
        p(C.R, f"[错误] 找不到 APK: {apk_path}")
        return 1
    
    out_dir = Path(args.output).resolve()
    if out_dir.exists() and not args.force and any(out_dir.iterdir()):
        p(C.Y, f"[!] 输出目录已存在: {out_dir}")
        if input("    覆盖? [y/N] ").strip().lower() != 'y':
            return 0
    
    out_dir.mkdir(parents=True, exist_ok=True)
    keys_dir = Path(args.keys_dir).resolve()
    keys_dir.mkdir(parents=True, exist_ok=True)
    
    p(C.C, f"  输入 APK:  {apk_path}")
    if args.official or not args.server:
        p(C.C, f"  模式:      直连官方 (无IP替换)")
        server_addr = None
    else:
        p(C.C, f"  私服地址:  {args.server}")
        server_addr = args.server
    p(C.C, f"  游戏包名:  {args.game_pkg}")
    p(C.C, f"  输出目录:  {out_dir}")
    p()

    # ---- 判定模式 ----
    use_official = args.official or not args.server

    if use_official:
        # ---- 官方模式: 直接重打包 + 签名 ----
        section("模式: 直连官方 (不替换IP)")
        signed_apk = step3_patch_official(apk_path, out_dir, keys_dir)

        if not args.skip_verify:
            step4_verify(signed_apk)
    else:
        # ---- 私服模式: 扫描 + 替换 + 重打包 + 签名 ----
        if args.skip_scan:
            section("Step 1-2  使用已有替换规则")
            rules_path = out_dir / "replacement_rules.json"
            if not rules_path.exists():
                fail(f"未找到已有规则文件: {rules_path}")
                return 1
            ok(f"使用现有规则: {rules_path}")
        else:
            findings = step1_scan(apk_path)
            if not findings or sum(len(v) for v in findings.values()) == 0:
                warn("未找到任何服务器地址，生成空规则...")
            rules_path = step2_rules(findings, args.server, out_dir)

        signed_apk = step3_patch_and_sign(apk_path, rules_path, out_dir, keys_dir)

        if not args.skip_verify:
            step4_verify(signed_apk)
    
    # ---- Step 5: ESP 构建与内置注入 ----
    esp_apk_path = None
    embedded_apk = None
    probe_apks = []
    tv_bins = {}

    if not args.skip_esp:
        sdk = find_sdk()
        ndk = find_ndk()

        if not sdk:
            fail("未找到 Android SDK。请设置 ANDROID_HOME 或安装 SDK。")
            p(C.Y, "    可跳过 ESP 构建: --skip-esp")
        else:
            p(C.C, f"  Android SDK: {sdk}")
            p(C.C, f"  Android NDK: {ndk or '(未找到)'}")

            # ---- Step 5a: tv_reader 原生二进制 (内置注入与独立 Overlay 共用) ----
            if ndk:
                tv_bins = build_tv_reader_ndk(ndk, sdk, out_dir)
            else:
                warn("NDK 不可用，尝试复用历史构建产物")
                p(C.Y, "    请安装 NDK: sdkmanager 'ndk;25.2.9519653'")
                # 本地无 NDK 时回退: 复用上次构建的二进制 (源码未变时安全)
                for abi in ("arm64_v8a", "armeabi_v7a", "x86_64"):
                    prev = out_dir / f"tv_reader_{abi}"
                    if prev.exists():
                        tv_bins[abi] = prev
                        info(f"  复用 {prev}")
                if not tv_bins:
                    warn("无历史 tv_reader 产物，跳过原生二进制")

            # ---- Step 5b + 5c: 注入 dex → 内置注入 (单一直装包) ----
            if not args.skip_embed:
                inject_dex = build_esp_inject_dex(out_dir)
                if inject_dex is None:
                    warn("ESP 注入 dex 构建失败，跳过内置注入")
                elif not tv_bins:
                    warn("tv_reader 编译失败，跳过内置注入")
                else:
                    embedded_apk = embed_esp_into_apk(
                        signed_apk, inject_dex, tv_bins, out_dir,
                        keys_dir, args.game_pkg,
                    )
                    if embedded_apk is None:
                        warn("ESP 内置注入失败，仅保留分体模式产物")
                    else:
                        probe_apks = build_probe_apks(
                            signed_apk, inject_dex, tv_bins, out_dir, keys_dir,
                            args.game_pkg)
            else:
                section("Step 5b-5c  跳过 ESP 内置注入 (--skip-embed)")

            # ---- Step 5d: 独立 ESP Overlay APK (分体模式, 可选) ----
            if args.with_overlay:
                if not tv_bins:
                    fail("tv_reader 不可用 (--with-overlay 显式要求 overlay, 直接失败)")
                    sys.exit(1)
                esp_apk_path = build_esp_overlay_apk(
                    sdk, tv_bins, out_dir,
                    args.game_pkg, args.port
                )
                if esp_apk_path is None:
                    # 显式要求 overlay 却没产出 → 硬失败, 避免部署包静默缺文件
                    fail("ESP Overlay APK 构建失败 (--with-overlay 显式要求)")
                    sys.exit(1)
    else:
        section("Step 5/6  跳过 ESP 构建 (--skip-esp)")

    # ---- Step 6: 生成部署包 ----
    generate_deployment_bundle(
        signed_apk, esp_apk_path, tv_bins.get("arm64_v8a"),
        out_dir, args.game_pkg, server_addr, args.port, use_official,
        embedded_apk=embedded_apk,
        probe_apks=probe_apks,
    )
    
    # ---- 最终总结 ----
    p()
    p(C.BOLD + C.G, "╔══════════════════════════════════════════════╗")
    p(C.BOLD + C.G, "║              构建完成 ✓                      ║")
    p(C.BOLD + C.G, "╚══════════════════════════════════════════════╝")
    p()
    p(C.C, f"  输出目录: {out_dir}")
    if embedded_apk:
        p(C.G, f"  内置版 APK: {out_dir / 'deploy' / 'game_embedded.apk'}")
        p(C.C,  f"              ↑ 单包直装 (私服 + ESP 全内置, 推荐)")
    out_game_name = "game_official.apk" if use_official else "game_private.apk"
    p(C.C, f"  游戏 APK: {out_dir / 'deploy' / out_game_name}")
    if esp_apk_path:
        p(C.C, f"  ESP APK:  {out_dir / 'deploy' / 'esp_overlay.apk'} (分体模式)")
    p(C.C, f"  部署脚本: {out_dir / 'deploy' / 'install.sh'}")
    p()
    if embedded_apk:
        p(C.C, "  快速部署 (内置版):")
        p(C.C, f"    adb install -r {out_dir / 'deploy' / 'game_embedded.apk'}")
    else:
        p(C.C, "  快速部署:")
        p(C.C, f"    cd {out_dir / 'deploy'} && bash install.sh")
    p()
    return 0

if __name__ == '__main__':
    sys.exit(main())
