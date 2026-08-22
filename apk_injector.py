#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
apk_injector.py — APK 功能内置注入器
=====================================
将 ESP 注入 dex + tv_reader 原生二进制 + Manifest 组件注入到 (官方/已改) APK，
产出单一直装 APK：启动游戏即自动运行 ESP 悬浮窗。

管线:
  1. 解析 AndroidManifest.xml (AXML 二进制)
  2. 注入 <uses-permission> / <provider> / <service>
  3. ZIP 级重打包: 保留全部原条目 + 替换 manifest + 追加 classesN.dex + assets
  4. 剥离旧签名 → v2 重签名

用法:
  python3 apk_injector.py 官方.apk -o out/内置版.apk \
      [--dex esp_system/inject/build/dex/classes.dex] \
      [--tv-reader-arm64 tv_reader] [--tv-reader-v7a tv_reader_v7a] \
      [--keys-dir keys]

依赖: hokstrap (重打包/签名), axml_editor (manifest 注入)
"""

import argparse
import json
import os
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

import hokstrap
from axml_editor import AxmlDocument, inject_esp_into_manifest

PROVIDER_CLASS = "com.esp.EspInjectProvider"
SERVICE_CLASS = "com.esp.OverlayService"
ASSET_ARM64 = "assets/esp_native/tv_reader_arm64"
ASSET_V7A = "assets/esp_native/tv_reader_v7a"


def next_dex_name(apk_path: Path) -> str:
    """返回可用的 classesN.dex 名称 (避开已有编号)。
    主 dex 为 classes.dex (n=1)，第二个为 classes2.dex。"""
    with zipfile.ZipFile(apk_path) as zf:
        names = set(zf.namelist())
    if "classes.dex" not in names:
        return "classes.dex"
    n = 2
    while f"classes{n}.dex" in names:
        n += 1
    return f"classes{n}.dex"


def inject_esp_into_apk(
    base_apk: Path,
    out_apk: Path,
    dex_path: Path,
    tv_reader_arm64: Path = None,
    tv_reader_v7a: Path = None,
    keys_dir: Path = None,
    provider_class: str = PROVIDER_CLASS,
    service_class: str = SERVICE_CLASS,
    extra_permissions=None,
    sign: bool = True,
    inject_manifest: bool = True,
    include_dex: bool = True,
    include_assets: bool = True,
    provider_enabled: bool = True,
    asset_arm64_name: str = ASSET_ARM64,
    asset_v7a_name: str = ASSET_V7A,
    authorities: str = None,
) -> dict:
    """完整注入管线。返回报告 dict。

    inject_manifest / include_dex / include_assets 用于构建 TP 检测探针变体:
    关闭后对应内容不注入 (纯文件级探针, ESP 代码不会运行)。
    provider_enabled=False: provider 声明但禁用 (manifest 探针用)。
    asset_arm64_name / asset_v7a_name / authorities / provider_class /
    service_class: 混淆构建可传中性化名称。"""
    report = {"base": str(base_apk), "out": str(out_apk)}

    # ---- 1. 读取并注入 manifest ----
    overrides = {}
    if inject_manifest:
        with zipfile.ZipFile(base_apk) as zf:
            manifest = zf.read("AndroidManifest.xml")

        # authorities 必须全局唯一: 用包名前缀
        doc = AxmlDocument.parse(manifest)
        pkg = doc.find_attr_str(doc.root, "package") or "unknown.pkg"
        authorities = authorities or f"{pkg}.esp.inject"

        new_manifest, mrep = inject_esp_into_manifest(
            manifest,
            provider_class=provider_class,
            authorities=authorities,
            service_class=service_class,
            extra_permissions=extra_permissions,
            provider_enabled=provider_enabled,
        )
        report["manifest"] = mrep
        report["manifest"]["authorities"] = authorities
        hokstrap.LOG(f"  [manifest] package={pkg}")
        hokstrap.LOG(f"  [manifest] +permissions={len(mrep['permissions'])} "
                     f"provider={mrep['provider']} service={mrep['service']}")

        # 往返校验: 注入结果必须能被重新解析
        doc2 = AxmlDocument.parse(new_manifest)
        assert doc2.find_child(doc2.root, "application") is not None, "manifest 往返校验失败"

        overrides["AndroidManifest.xml"] = new_manifest

    # ---- 2. 计算新 dex 名称 ----
    dex_name = next_dex_name(base_apk) if include_dex else None
    report["dex_entry"] = dex_name
    if include_dex:
        hokstrap.LOG(f"  [dex] 注入条目: {dex_name} ({Path(dex_path).stat().st_size:,} bytes)")

    # ---- 3. 新增 assets 条目 ----
    added = {}
    if include_assets:
        if tv_reader_arm64 and Path(tv_reader_arm64).exists():
            added[asset_arm64_name] = Path(tv_reader_arm64).read_bytes()
            hokstrap.LOG(f"  [assets] +{asset_arm64_name} ({len(added[asset_arm64_name]):,} bytes)")
        if tv_reader_v7a and Path(tv_reader_v7a).exists():
            added[asset_v7a_name] = Path(tv_reader_v7a).read_bytes()
            hokstrap.LOG(f"  [assets] +{asset_v7a_name} ({len(added[asset_v7a_name]):,} bytes)")
    report["added_entries"] = list(added.keys()) + ([dex_name] if dex_name else [])

    # ---- 4. ZIP 级重打包 (raw copy: 未修改条目原样保留压缩字节) ----
    out_apk.parent.mkdir(parents=True, exist_ok=True)
    entries = hokstrap.read_cd_entries(base_apk)
    stripped = [e['name'] for e in entries if hokstrap.OLD_SIG_RE.match(e['name'])]
    with open(base_apk, 'rb') as fin, open(out_apk, 'wb') as fh:
        w = hokstrap.AlignedZipWriter(fh)
        for e in entries:
            name = e['name']
            if hokstrap.OLD_SIG_RE.match(name):
                continue
            method = (zipfile.ZIP_STORED
                      if name in hokstrap.FORCE_STORED
                      else e['method'])
            is_so = name.startswith('lib/') and name.endswith('.so')
            if method == zipfile.ZIP_DEFLATED:
                align = 1
            elif is_so:
                align = hokstrap.ALIGN_PAGE
            else:
                align = hokstrap.ALIGN_DEFAULT
            if name in overrides:
                # 被替换条目 (manifest): 重新写出
                w.add(name, overrides[name], method, e['time'], e['date'], align)
            else:
                # 原样拷贝: 保留源压缩字节与时间戳
                raw = hokstrap.read_raw_entry(fin, e)
                w.add_raw(name, raw, method, e['crc'], e['csize'], e['usize'],
                          e['time'], e['date'], e['flag'], align)
        # ---- 新增条目 ----
        now_t, now_d = hokstrap.dos_datetime((2026, 1, 1, 0, 0, 0))
        if include_dex:
            dex_data = Path(dex_path).read_bytes()
            w.add(dex_name, dex_data, zipfile.ZIP_DEFLATED, now_t, now_d, 1)
        for name, data in added.items():
            w.add(name, data, zipfile.ZIP_DEFLATED, now_t, now_d, 1)
        w.finish()
    if stripped:
        hokstrap.LOG(f"  [zip] 已剥离旧签名条目: {', '.join(stripped)}")

    # ---- 5. 签名 (优先官方 apksigner, 保持密钥身份与 keys/ 一致) ----
    if sign:
        keys_dir = keys_dir or (ROOT / "keys")
        signed = sign_apk(out_apk, Path(keys_dir))
        if not signed:
            return None

    report["size"] = os.path.getsize(out_apk)
    return report


# ============================================================
# 签名: 官方 apksigner (PEM 密钥转换 PKCS#8 DER, 身份不变)
# ============================================================

def _sdk_roots() -> list:
    """SDK 根目录候选 (含 CI 标准布局 $ANDROID_HOME)"""
    import os
    roots = []
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        v = os.environ.get(env)
        if v:
            roots.append(v)
    roots += ["/opt/android-tools", "/opt/android-sdk"]
    return roots


def _find_tool(pattern: str):
    import glob
    pats = []
    for root in _sdk_roots():
        pats.append(f"{root}/build-tools/*/{pattern}")
    pats.append(f"/opt/android-tools/*/{pattern}")  # 本沙盒布局: /opt/android-tools/android-14/
    for pat in pats:
        for p in sorted(glob.glob(pat)):
            return p
    return None


def sign_apk(apk: Path, keys_dir: Path, min_sdk: int = 24) -> bool:
    """用 apksigner v1+v2+v3 签名。密钥来自 keys_dir/key.pem + cert.pem (身份一致)。
    返回是否成功; apksigner 不可用时回退 hokstrap v2。"""
    keys_dir.mkdir(parents=True, exist_ok=True)
    key_pem = keys_dir / "key.pem"
    cert_pem = keys_dir / "cert.pem"
    # 密钥缺失时先生成（否则会错误地回退到无 v1 的自研签名，
    # 导致部分模拟器安装器报"安装包解析失败"）
    if not (key_pem.exists() and cert_pem.exists()):
        hokstrap.get_or_create_key(str(keys_dir))

    apksigner = _find_tool("apksigner")
    if apksigner and key_pem.exists() and cert_pem.exists():
        # PEM → PKCS#8 DER (仅格式转换, 密钥身份不变)
        pk8 = keys_dir / "key.pk8"
        import subprocess
        r = subprocess.run(["openssl", "pkcs8", "-topk8", "-nocrypt",
                            "-in", str(key_pem), "-outform", "DER",
                            "-out", str(pk8)],
                           capture_output=True, text=True)
        if r.returncode != 0:
            hokstrap.LOG(f"  [sign] openssl 密钥转换失败: {r.stderr[:200]}")
            return False
        r = subprocess.run(
            [apksigner, "sign",
             "--key", str(pk8), "--cert", str(cert_pem),
             f"--min-sdk-version", str(min_sdk),
             "--v1-signing-enabled", "true",
             "--v2-signing-enabled", "true",
             "--v3-signing-enabled", "true",
             "--out", str(apk) + ".signed",
             "--in", str(apk)],
            capture_output=True, text=True, timeout=600)
        err = (r.stderr or "").split("WARNING")
        if r.returncode != 0:
            hokstrap.LOG(f"  [sign] apksigner 失败: {err[-1][:300]}")
            return False
        Path(str(apk) + ".signed").replace(apk)
        # apksigner 附带的 v4 .idsig 侧车文件不需要, 清理
        idsig = Path(str(apk) + ".signed.idsig")
        if idsig.exists():
            idsig.unlink()
        hokstrap.LOG(f"  [sign] apksigner v1+v2+v3 签名完成 → {apk}")
        return True

    # 回退: hokstrap 自研 v2 (摘要算法已与 apksig 权威实现对齐并通过交叉校验;
    # 但仅 v2 无 v1 — Android 7.0 以下无法安装, 生产环境务必安装 build-tools)
    hokstrap.LOG("  [sign] apksigner 不可用, 回退 hokstrap v2 (仅 Android 7+, 无 v1)")
    key, cert = hokstrap.get_or_create_key(str(keys_dir))
    blen = hokstrap.sign_v2_inplace(str(apk), key, cert)
    hokstrap.LOG(f"  [sign] v2 签名块 {blen} 字节 → {apk}")
    return True


# ============================================================
# 验证: aapt2 解析注入后的 manifest + apksigner 校验签名
# ============================================================

def find_aapt2() -> str:
    return _find_tool("aapt2")


def verify_with_aapt2(apk_path: Path, provider_name: str = PROVIDER_CLASS,
                      service_name: str = SERVICE_CLASS) -> bool:
    """用 aapt2 解析注入后的 manifest — 官方工具链校验 AXML 合法性
    provider_name/service_name 传混淆构建的实际类名 (None 跳过该项检查)"""
    aapt2 = find_aapt2()
    if not aapt2:
        hokstrap.LOG("  [verify] aapt2 不可用，跳过")
        return True
    import subprocess
    r = subprocess.run([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml",
                        str(apk_path)], capture_output=True, text=True, timeout=60)
    if r.returncode != 0:
        hokstrap.LOG(f"  [verify] aapt2 解析失败:\n{r.stderr[:600]}")
        return False
    out = r.stdout
    ok_provider = (provider_name is None) or (provider_name in out)
    ok_service = (service_name is None) or (service_name in out)
    ok_perm = "SYSTEM_ALERT_WINDOW" in out
    hokstrap.LOG(f"  [verify] aapt2 xmltree: provider={ok_provider} service={ok_service} "
                 f"SYSTEM_ALERT_WINDOW={ok_perm}")
    return ok_provider and ok_service and ok_perm


def verify_with_apksigner(apk_path: Path) -> bool:
    import subprocess
    apksigner = _find_tool("apksigner")
    if not apksigner:
        hokstrap.LOG("  [verify] apksigner 不可用，跳过")
        return True
    r = subprocess.run([apksigner, "verify", "--min-sdk-version", "24",
                        "--print-certs", str(apk_path)],
                       capture_output=True, text=True, timeout=120)
    ok = r.returncode == 0
    hokstrap.LOG(f"  [verify] apksigner: {'PASS' if ok else 'FAIL'}")
    if not ok:
        hokstrap.LOG("    " + (r.stderr or r.stdout)[:400])
    return ok


# ============================================================
# CLI
# ============================================================

def main():
    ap = argparse.ArgumentParser(
        description="APK 功能内置注入器 — ESP dex + tv_reader + manifest 组件",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  %(prog)s 官方.apk -o 内置版.apk
  %(prog)s 已改私服.apk -o 内置私服版.apk --dex path/to/classes.dex
        """)
    ap.add_argument('apk', help='输入 APK (官方或已打补丁)')
    ap.add_argument('-o', '--out', required=True, help='输出 APK 路径')
    ap.add_argument('--dex', default=str(ROOT / "esp_system/inject/build/dex/classes.dex"),
                    help='注入 dex (默认 ESP 注入 dex 构建产物)')
    ap.add_argument('--tv-reader-arm64', default=None,
                    help='arm64 tv_reader 二进制 (注入 assets/esp_native/)')
    ap.add_argument('--tv-reader-v7a', default=None,
                    help='armeabi-v7a tv_reader 二进制')
    ap.add_argument('--keys-dir', default=str(ROOT / 'keys'), help='签名密钥目录')
    ap.add_argument('--no-sign', action='store_true', help='不签名 (仅注入)')
    ap.add_argument('--skip-verify', action='store_true', help='跳过 aapt2/apksigner 校验')
    args = ap.parse_args()

    base = Path(args.apk).resolve()
    if not base.is_file():
        print(f"[错误] 找不到 APK: {base}")
        return 1

    dex = Path(args.dex)
    if not dex.exists():
        print(f"[错误] 注入 dex 不存在: {dex}")
        print("      先构建: python3 esp_system/inject/build_inject_dex.py")
        return 1

    print(f"[*] 输入 APK: {base}")
    print(f"[*] 注入 dex: {dex}")
    report = inject_esp_into_apk(
        base, Path(args.out).resolve(), dex,
        tv_reader_arm64=args.tv_reader_arm64,
        tv_reader_v7a=args.tv_reader_v7a,
        keys_dir=Path(args.keys_dir),
        sign=not args.no_sign,
    )
    print(f"[✓] 注入完成 → {report['out']} ({report['size']:,} bytes)")

    if not args.skip_verify:
        out = Path(args.out).resolve()
        if not verify_with_aapt2(out):
            return 1
        if not args.no_sign:
            if not verify_with_apksigner(out):
                return 1

    print(json.dumps({k: v for k, v in report.items() if k != 'manifest'},
                     ensure_ascii=False, indent=2))
    return 0


if __name__ == '__main__':
    sys.exit(main())
