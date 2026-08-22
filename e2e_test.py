#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
E2E 测试: 合成游戏 APK → ultimate_builder 一键流程 → 内置版 APK 校验
====================================================================
1. 用 aapt2 构造带真实 AXML manifest 的合成"游戏" APK
   (含 resources.arsc / classes.dex / lib .so 内嵌URL / 旧签名条目)
2. 运行 ultimate_builder.py --server 127.0.0.1:6645
3. 校验 game_embedded.apk: manifest 注入 + dex + tv_reader assets + 签名 + 对齐
"""
import json
import os
import shutil
import struct
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
WORK = ROOT / "build_e2e"


def _find_bt(name: str) -> str:
    """build-tools 工具探测: 优先 $ANDROID_HOME (CI), 回退本沙盒 /opt 布局"""
    import glob
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        v = os.environ.get(env)
        if v:
            for p in sorted(glob.glob(f"{v}/build-tools/*/{name}")):
                return p
    for p in sorted(glob.glob(f"/opt/android-tools/*/{name}")):
        return p
    raise SystemExit(f"[!] 未找到 {name}")


def _find_platform_jar() -> str:
    import glob
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        v = os.environ.get(env)
        if v:
            for p in sorted(glob.glob(f"{v}/platforms/*/android.jar")):
                return p
    for p in sorted(glob.glob("/opt/android-tools/android-*/android.jar")):
        return p
    raise SystemExit("[!] 未找到 android.jar")


AAPT2 = Path(_find_bt("aapt2"))
APKSIGNER = Path(_find_bt("apksigner"))
DEXDUMP = Path(_find_bt("dexdump"))
PLATFORM_JAR = Path(_find_platform_jar())
R8 = os.environ.get("R8_JAR") or "/opt/r8/r8.jar"
UB = ROOT / "ultimate_builder.py"
GAME_PKG = "com.tencent.tmgp.sgame"
SERVER = "127.0.0.1:6645"

results = []


def check(name, cond, detail=""):
    results.append((name, bool(cond)))
    mark = "PASS" if cond else "FAIL"
    print(f"  [{mark}] {name}" + (f" — {detail}" if detail else ""), flush=True)


def run(cmd, **kw):
    r = subprocess.run([str(c) for c in cmd], capture_output=True, text=True, **kw)
    if r.returncode != 0:
        print("  $ " + " ".join(str(c) for c in cmd))
        print((r.stdout or "")[-1500:])
        print((r.stderr or "")[-1500:])
        raise SystemExit(f"[!] 命令失败: {cmd[0]}")
    return r


# ================================================================
# 1. 构造合成游戏 APK
# ================================================================
def build_synthetic_apk(out_apk: Path):
    src = WORK / "game_src"
    if src.exists():
        shutil.rmtree(src)
    (src / "res" / "values").mkdir(parents=True)
    (src / "java").mkdir(parents=True)

    # ---- manifest (源码 XML, aapt2 会编译为二进制 AXML) ----
    (src / "AndroidManifest.xml").write_text(f'''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="{GAME_PKG}">

    <uses-permission android:name="android.permission.INTERNET"/>

    <application android:label="HoK-Synthetic"
                 android:extractNativeLibs="false">
        <activity android:name=".SGameActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
    </application>
</manifest>
''', encoding='utf-8')

    # ---- 资源 (强制生成 resources.arsc) ----
    (src / "res" / "values" / "strings.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<resources>\n    <string name="app_name">HoK-Synthetic</string>\n</resources>\n',
        encoding='utf-8')

    # ---- Java 源码 (游戏入口 Activity) ----
    pkg_dir = src / "java" / "com" / "tencent" / "tmgp" / "sgame"
    pkg_dir.mkdir(parents=True)
    (pkg_dir / "SGameActivity.java").write_text(
        'package com.tencent.tmgp.sgame;\n'
        'import android.app.Activity;\n'
        'public class SGameActivity extends Activity {}\n', encoding='utf-8')

    # ---- aapt2: compile + link ----
    flata = WORK / "res.zip"
    run([AAPT2, "compile", "--dir", src / "res" / "values", "-o", flata])
    base_apk = WORK / "game_base.apk"
    run([AAPT2, "link", "-I", PLATFORM_JAR,
         "--manifest", src / "AndroidManifest.xml",
         "--min-sdk-version", "24", "--target-sdk-version", "34",
         "-o", base_apk, flata])

    # ---- javac + d8: classes.dex ----
    classes = WORK / "game_classes"
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir(parents=True)
    run(["javac", "--release", "8", "-nowarn", "-parameters", "-cp", PLATFORM_JAR,
         "-d", classes] + [str(p) for p in (src / "java").rglob("*.java")])
    run(["java", "-cp", R8, "com.android.tools.r8.D8",
         "--release", "--lib", PLATFORM_JAR, "--min-api", "24",
         "--output", classes] + [str(p) for p in classes.rglob("*.class")])
    dex = classes / "classes.dex"
    assert dex.exists() and dex.read_bytes()[:4] == b"dex\n"

    # ---- 组装完整 APK: aapt2 产物 + dex + lib/.so + assets + 旧签名 ----
    so_arm64 = bytearray(b'\x7fELF' + os.urandom(120_000))
    marker = b"tcp://battle.qq.com:6645\x00"
    so_arm64[50000:50000 + len(marker)] = marker
    so_v7a = bytearray(b'\x7fELF' + os.urandom(100_000))
    so_v7a[40000:40000 + len(marker)] = marker

    cfg = {
        "gateway": "https://sgame_gateway.qq.com:8080/serverlist",
        "battle_tcp": "tcp://battle.qq.com:6645",
        "hotupdate_cdn": "https://dliir.path.qq.com/hok/res/",
    }
    with zipfile.ZipFile(out_apk, "w") as zf:
        with zipfile.ZipFile(base_apk) as zin:
            for item in zin.infolist():
                zf.writestr(item, zin.read(item.filename))
        zf.writestr("classes.dex", dex.read_bytes(),
                    compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr("assets/Config/ServerConfig.json", json.dumps(cfg, indent=2),
                    compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr("assets/version.ini",
                    "version=11.4.1.1\nurl=https://version.qq.com/check\n",
                    compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr("lib/arm64-v8a/libGameCore.so", bytes(so_arm64),
                    compress_type=zipfile.ZIP_STORED)
        zf.writestr("lib/armeabi-v7a/libGameCore.so", bytes(so_v7a),
                    compress_type=zipfile.ZIP_STORED)
        zf.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n",
                    compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr("META-INF/CERT.SF", "Signature-Version: 1.0\n",
                    compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr("META-INF/CERT.RSA", os.urandom(512),
                    compress_type=zipfile.ZIP_DEFLATED)
    return out_apk


# ================================================================
# 3. 校验内置版 APK
# ================================================================
def elf_machine(data: bytes) -> int:
    """读 ELF e_machine (offset 18, u16 LE)"""
    if data[:4] != b'\x7fELF':
        return -1
    return struct.unpack_from('<H', data, 18)[0]


def stored_data_offset(apk_path: Path, name: str) -> int:
    """计算 STORED 条目数据区的绝对偏移 (LFH 偏移 + 30 + name_len + extra_len)"""
    with zipfile.ZipFile(apk_path) as zf:
        info = zf.getinfo(name)
        hdr_off = info.header_offset
    with open(apk_path, 'rb') as f:
        f.seek(hdr_off)
        hdr = f.read(30)
    name_len, extra_len = struct.unpack_from('<HH', hdr, 26)
    return hdr_off + 30 + name_len + extra_len


def verify_embedded(apk: Path):
    with zipfile.ZipFile(apk) as zf:
        names = zf.namelist()

        # ---- 注入条目存在性 ----
        check("classes2.dex 已注入", "classes2.dex" in names)
        check("assets gsvc_arm64 已注入 (混淆路径)", "assets/native/gsvc_arm64" in names)
        check("assets gsvc_v7a 已注入 (混淆路径)", "assets/native/gsvc_v7a" in names)
        # 旧签名(CERT.*)必须剥离; apksigner v1 重签会生成新的 KEY.* 条目 (预期行为)
        meta = [n for n in names if n.startswith("META-INF/")]
        check("旧签名 CERT.* 已剥离",
              not any("META-INF/CERT." in n for n in meta),
              f"META-INF: {meta}")
        check("v1 重签条目存在 (KEY.*)",
              any("META-INF/KEY." in n for n in meta))

        # ---- dex 内容 (混淆: com.gs.* + 无特征串) ----
        if "classes2.dex" in names:
            d = zf.read("classes2.dex")
            check("classes2.dex magic 合法", d[:4] == b"dex\n")
            check("classes2.dex 含 GsProvider (混淆类名)",
                  b"com/gs/GsProvider" in d)
            check("classes2.dex 含 GsService (混淆类名)",
                  b"com/gs/GsService" in d)
            # TP 内容扫描对抗: dex 字节级不得残留 cheat 特征串
            forbidden = [b"esp", b"Esp", b"ESP", b"tv_reader", b"/data/adb",
                         b"tmgp.sgame", b"cheat", b"hack", b"inject"]
            leaked = [s.decode() for s in forbidden if s in d]
            check("classes2.dex 无 cheat 特征串 (TP 对抗)", not leaked,
                  f"leaked={leaked}" if leaked else "")

        # ---- reader ELF 架构 ----
        a64 = zf.read("assets/native/gsvc_arm64")
        v7 = zf.read("assets/native/gsvc_v7a")
        check("gsvc_arm64 是 AArch64 ELF", elf_machine(a64) == 183,
              f"e_machine={elf_machine(a64)}")
        check("gsvc_v7a 是 ARM ELF", elf_machine(v7) == 40,
              f"e_machine={elf_machine(v7)}")

        # ---- 私服替换已传播 ----
        cfg = json.loads(zf.read("assets/Config/ServerConfig.json"))
        check("ServerConfig 已替换为私服", cfg["battle_tcp"] == f"tcp://{SERVER}",
              cfg["battle_tcp"])
        so = zf.read("lib/arm64-v8a/libGameCore.so")
        check(".so 内 URL 已等长替换", f"tcp://{SERVER}".encode() in so and
              b"battle.qq.com" not in so)

        # ---- 压缩与对齐 ----
        check("resources.arsc 为 STORED",
              zf.getinfo("resources.arsc").compress_type == zipfile.ZIP_STORED)
        check("lib/arm64-v8a .so 为 STORED",
              zf.getinfo("lib/arm64-v8a/libGameCore.so").compress_type == zipfile.ZIP_STORED)
        off64 = stored_data_offset(apk, "lib/arm64-v8a/libGameCore.so")
        check("lib/arm64-v8a .so 4096 页对齐", off64 % 4096 == 0, f"off={off64}")
        off_arsc = stored_data_offset(apk, "resources.arsc")
        check("resources.arsc 4 字节对齐", off_arsc % 4 == 0, f"off={off_arsc}")
        check("ZIP CRC 完整", zf.testzip() is None)

    # ---- dexdump: 官方工具解析注入 dex ----
    tmp_dex = WORK / "_c2.dex"
    with zipfile.ZipFile(apk) as zf:
        tmp_dex.write_bytes(zf.read("classes2.dex"))
    r = subprocess.run([str(DEXDUMP), "-l", "plain", str(tmp_dex)],
                       capture_output=True, text=True)
    check("dexdump 解析 classes2.dex", r.returncode == 0 and
          "com/gs/GsProvider" in r.stdout)

    # ---- aapt2: manifest 注入校验 (官方工具链) ----
    r = subprocess.run([str(AAPT2), "dump", "xmltree", "--file",
                        "AndroidManifest.xml", str(apk)],
                       capture_output=True, text=True)
    out = r.stdout
    check("aapt2 解析注入后 manifest", r.returncode == 0)
    check("provider 已注册 (混淆名)", "GsProvider" in out)
    check("service 已注册 (混淆名)", "GsService" in out)
    check("SYSTEM_ALERT_WINDOW 权限已注入", "SYSTEM_ALERT_WINDOW" in out)
    check("FOREGROUND_SERVICE 权限已注入", "FOREGROUND_SERVICE" in out)
    check("FOREGROUND_SERVICE_SPECIAL_USE 权限已注入",
          "FOREGROUND_SERVICE_SPECIAL_USE" in out)
    check("原有 activity 保留", "SGameActivity" in out)

    # ---- apksigner: 签名校验 (官方工具链) ----
    r = subprocess.run([str(APKSIGNER), "verify", "--min-sdk-version", "24",
                        "--print-certs", str(apk)],
                       capture_output=True, text=True)
    check("apksigner v1+v2+v3 校验通过", r.returncode == 0,
          (r.stderr or "").strip().split("\n")[0][:120] if r.returncode else "")
    if r.returncode == 0:
        check("签名者证书与 keys/ 一致 (Hok Private Server Test)",
              "hok private server test" in r.stdout.lower())


def main():
    print("=" * 60)
    print("E2E: 合成 APK → ultimate_builder → 内置版校验")
    print("=" * 60, flush=True)

    if WORK.exists():
        shutil.rmtree(WORK)
    WORK.mkdir(parents=True)

    print("\n== 1/3 构造合成游戏 APK (aapt2 + javac + d8) ==")
    game_apk = build_synthetic_apk(WORK / "synthetic_game.apk")
    print(f"  合成 APK: {game_apk} ({game_apk.stat().st_size:,} bytes)")
    check("合成 APK 构建成功", game_apk.exists() and game_apk.stat().st_size > 100_000)

    print("\n== 2/3 运行 ultimate_builder.py (私服 + ESP 内置) ==")
    out_dir = WORK / "output"
    env = os.environ.copy()
    r = subprocess.run([sys.executable, str(UB), str(game_apk),
                        "--server", SERVER, "-o", str(out_dir), "--force"],
                       env=env, capture_output=True, text=True, timeout=1800)
    print((r.stdout or "")[-4000:])
    if r.returncode != 0:
        print(r.stderr[-2000:])
    check("ultimate_builder 全流程退出码 0", r.returncode == 0)

    print("\n== 3/3 校验产物 ==")
    embedded = out_dir / "deploy" / "game_embedded.apk"
    check("game_embedded.apk 已产出", embedded.exists(),
          f"{embedded.stat().st_size:,} bytes" if embedded.exists() else "")
    if embedded.exists():
        verify_embedded(embedded)

    # 部署包完整性
    deploy = out_dir / "deploy"
    check("install.sh 已生成", (deploy / "install.sh").exists())
    check("README.txt 已生成", (deploy / "README.txt").exists())
    if (deploy / "install.sh").exists():
        sh = (deploy / "install.sh").read_text(encoding='utf-8')
        check("install.sh 含内置版安装逻辑", "game_embedded.apk" in sh)
    check("game_private.apk 已产出", (deploy / "game_private.apk").exists())
    # TP 检测探针
    check("探针 game_test_hdex.apk 已产出", (deploy / "game_test_hdex.apk").exists())
    check("探针 game_test_mfo.apk 已产出", (deploy / "game_test_mfo.apk").exists())

    # ---- 汇总 ----
    n_pass = sum(1 for _, ok in results if ok)
    n_fail = len(results) - n_pass
    print("\n" + "=" * 60)
    print(f"结果: {n_pass} PASS / {n_fail} FAIL / {len(results)} 总计")
    print("=" * 60)
    if n_fail:
        for name, ok in results:
            if not ok:
                print(f"  FAIL: {name}")
    return 1 if n_fail else 0


if __name__ == "__main__":
    sys.exit(main())
