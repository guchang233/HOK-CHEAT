#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build_private_hok.py — 王者荣耀私服直装 APK 一键黑盒处理脚本

目标: 输入官方 APK + 私服地址，自动产出可直装的私服版 APK。
原理: 自动 scan 原 APK 里的服务器地址，生成替换规则，调用 hokstrap 管线完成处理。

用法:
  python3 build_private_hok.py 官方.apk --server 127.0.0.1:6645 --output 私服版.apk

核心逻辑:
  1. 扫描 APK 中所有 http/tcp 链接及腾讯相关域名
  2. 智能判断: 若原域名比私服地址长(或等长)，直接全串替换
  3. 若原域名比私服地址短，保留原域名后的路径/端口部分，只替换域名主体
  4. 生成 rules.json 并调用 hokstrap 完成替换、签名、校验
"""

import argparse
import json
import os
import re
import sys
import zipfile
from collections import defaultdict

import hokstrap


# 匹配: http(s)://domain:port/path  或  tcp://domain:port
# 捕获组: (prefix) (domain) (suffix)   prefix = 协议://, suffix = :port/path
# 注意: domain 允许下划线(_)，因为某些服务器域名可能包含它
URL_SPLIT_RE_STRICT = re.compile(
    rb'(?P<prefix>(?:https?|tcp|wss?)://)'
    rb'(?P<domain>[A-Za-z0-9.\-_]+)'
    rb'(?P<suffix>(?::\d{1,5})?(?:/[A-Za-z0-9.\-_:/?#\[\]@!$&\'()*+,;=%]*)?)'
    rb'\x00'  # 要求必须以 NUL 结尾 (C 字符串特征)
)

URL_SPLIT_RE_LENIENT = re.compile(
    rb'(?P<prefix>(?:https?|tcp|wss?)://)'
    rb'(?P<domain>[A-Za-z0-9.\-_]+)'
    rb'(?P<suffix>(?::\d{1,5})?(?:/[^\x00\s"\'<>]*)?)'
)

# 腾讯相关域名匹配 (用于纯域名场景，如直接写 battle.qq.com 而不带协议)
DOMAIN_RE_STRICT = re.compile(
    rb'\b((?:[A-Za-z0-9\-]+\.)+)(?:qq|tencent|gtimg|qpic|myapp|qcloud|tmgp|doubao)\.(?:com|cn)\b\x00'
)

DOMAIN_RE_LENIENT = re.compile(
    rb'\b((?:[A-Za-z0-9\-]+\.)+)(?:qq|tencent|gtimg|qpic|myapp|qcloud|tmgp|doubao)\.(?:com|cn)\b'
)


def _match_patterns(data, is_binary=False):
    """返回列表: [(original_bytes, ...)] 用于生成替换规则"""
    findings = []

    # 1. 匹配完整 URL (带协议)
    url_re = URL_SPLIT_RE_STRICT if is_binary else URL_SPLIT_RE_LENIENT
    for m in url_re.finditer(data):
        orig = m.group()
        # 对于二进制文件，去掉末尾的 NUL 进行存储（但匹配时已验证存在）
        if is_binary and orig.endswith(b'\x00'):
            orig = orig[:-1]

        prefix = m.group('prefix')
        domain = m.group('domain')
        suffix = m.group('suffix')

        # 修正: 如果 domain 中包含端口号 (因为 domain 允许下划线和冒号)
        # 将端口从 domain 移到 suffix
        if b':' in domain:
            # 拆分 domain 和端口
            parts = domain.split(b':', 1)
            domain = parts[0]
            port_part = b':' + parts[1]
            # 将端口添加到 suffix 的开头
            if suffix:
                suffix = port_part + suffix
            else:
                suffix = port_part
            # 更新 original (因为我们修改了解析，但 original 本身是对的)
            # 实际上 original 不需要改，因为它是从 data 中截取的原始字节

        findings.append({
            'type': 'url',
            'original': orig,
            'prefix': prefix,
            'domain': domain,
            'suffix': suffix,
            'offset': m.start(),
        })

    # 2. 匹配纯域名 (不带协议，通常在 .so 中大量出现)
    if is_binary:
        # 二进制文件中只匹配以 NUL 结尾的纯域名
        existing_ranges = [(f['offset'], f['offset'] + len(f['original'])) for f in findings]
        for m in DOMAIN_RE_STRICT.finditer(data):
            orig = m.group()[:-1]  # 去掉末尾的 NUL
            # 检查是否与已存在的 URL 匹配重叠
            overlap = False
            for s, e in existing_ranges:
                if not (m.end() <= s or m.start() >= e):
                    overlap = True
                    break
            if not overlap:
                findings.append({
                    'type': 'domain',
                    'original': orig,
                    'prefix': b'',
                    'domain': orig,
                    'suffix': b'',
                    'offset': m.start(),
                })
    else:
        # 文本文件中的纯域名（较少见，但以防万一）
        existing_ranges = [(f['offset'], f['offset'] + len(f['original'])) for f in findings]
        for m in DOMAIN_RE_LENIENT.finditer(data):
            overlap = False
            for s, e in existing_ranges:
                if not (m.end() <= s or m.start() >= e):
                    overlap = True
                    break
            if not overlap:
                findings.append({
                    'type': 'domain',
                    'original': m.group(),
                    'prefix': b'',
                    'domain': m.group(),
                    'suffix': b'',
                    'offset': m.start(),
                })

    return findings


def scan_apk_for_replacement(apk_path):
    """扫描 APK 并返回 {file: [findings]} 映射"""
    results = defaultdict(list)
    with zipfile.ZipFile(apk_path) as zf:
        for info in zf.infolist():
            if info.is_dir():
                continue
            low = info.filename.lower()
            # 判断是否为二进制文件
            is_binary = low.endswith('.so') or low.endswith('.dex')

            # 只处理可能包含文本/配置的文件 (或二进制 .so/.dex)
            if not is_binary and not any(low.endswith(ext) or ext in low for ext in [
                    '.json', '.xml', '.txt', '.properties', '.config', '.ini', '.cfg',
                    '.lua', '.html', '.js', '.css']):
                continue
            try:
                data = zf.read(info.filename)
                fnds = _match_patterns(data, is_binary=is_binary)
                if fnds:
                    results[info.filename] = fnds
            except Exception as e:
                print(f'  [warn] {info.filename}: {e}', flush=True)
    return results


def build_replacement_rules(apk_findings, server_addr):
    """
    根据扫描结果构造 hokstrap 规则。
    server_addr: 目标地址，如 '127.0.0.1:6645' 或 'my.server.com:8080'
    """
    rules = []
    new_addr = server_addr.encode()

    # 如果用户输入的地址带协议前缀，保留协议，用在 prefix 为空时的 fallback
    # 默认: 从 server_addr 中提取 "host:port"
    if b'://' in new_addr:
        new_addr_clean = new_addr.split(b'://', 1)[1]
    else:
        new_addr_clean = new_addr

    # 检测 suffix 是否包含域名结构（说明 URL 解析失败，应整体替换）
    bad_domain_re = re.compile(rb'[A-Za-z0-9\-]+\.(?:com|cn|net|org|io)')

    for fname, fnds in apk_findings.items():
        seen_originals = set()
        for fnd in fnds:
            orig = fnd['original']
            if orig in seen_originals:
                continue
            seen_originals.add(orig)

            prefix_bytes = fnd['prefix']
            domain_bytes = fnd['domain']
            suffix_bytes = fnd['suffix']
            is_url_type = (fnd['type'] == 'url')

            # === 核心修复 ===
            # 如果 suffix 中包含域名结构（如 _gateway.qq.com），说明 domain 解析被提前截断了
            # 此时，我们应该重新从 original 字符串中解析完整的 hostname
            suffix_has_domain = bool(bad_domain_re.search(suffix_bytes))

            if suffix_has_domain and is_url_type:
                # URL 解析失败，从 original 字符串重新解析
                # 找到 "://" 之后的 hostname 部分
                orig_str = orig.decode('latin-1')
                # 匹配 hostname (直到 ':' 或 '/' 或 '?' 或 '#')
                host_match = re.search(r'://([^:/?#]+)', orig_str)
                if host_match:
                    orig_host = host_match.group(1)
                    new_addr_str = new_addr_clean.decode('latin-1')
                    # 用新地址替换原 hostname
                    candidate_str = orig_str.replace(orig_host, new_addr_str, 1)
                    candidate = candidate_str.encode('latin-1')
                else:
                    # 无法解析，直接拼接
                    candidate = prefix_bytes + new_addr_clean + suffix_bytes
            else:
                # 正常解析的情况
                has_new_port = b':' in new_addr_clean
                orig_suffix_port = b''
                orig_suffix_path = b''

                # 分离原 suffix 的端口和路径
                if suffix_bytes:
                    if suffix_bytes.startswith(b':'):
                        slash_idx = suffix_bytes.find(b'/')
                        if slash_idx > 0:
                            orig_suffix_port = suffix_bytes[:slash_idx]
                            orig_suffix_path = suffix_bytes[slash_idx:]
                        else:
                            orig_suffix_port = suffix_bytes
                    elif suffix_bytes.startswith(b'/'):
                        orig_suffix_path = suffix_bytes

                # 决定最终的 suffix
                if has_new_port:
                    final_suffix = orig_suffix_path
                else:
                    final_suffix = orig_suffix_port + orig_suffix_path

                # 构造新值
                candidate = prefix_bytes + new_addr_clean + final_suffix

                # 如果新值太长，截断路径
                if len(candidate) > len(orig):
                    overflow = len(candidate) - len(orig)
                    if len(orig_suffix_path) > overflow:
                        final_suffix = orig_suffix_path[:len(orig_suffix_path) - overflow]
                        candidate = prefix_bytes + new_addr_clean + orig_suffix_port + final_suffix
                    else:
                        candidate = prefix_bytes + new_addr_clean

            # 最终兜底: 确保长度 <= 原长度
            if len(candidate) > len(orig):
                candidate = candidate[:len(orig)]

            assert len(candidate) <= len(orig), f"Logic error: candidate longer than orig for {fname}"

            rules.append({
                'file': fname,
                'find_original': orig.decode('latin-1'),
                'replace_with': candidate.decode('latin-1'),
                'is_binary': fname.endswith('.so'),
            })

    return rules


def main():
    parser = argparse.ArgumentParser(
        description='王者荣耀私服直装 APK 黑盒处理 (scan → patch → sign → verify)')
    parser.add_argument('apk', help='官方 APK 路径')
    parser.add_argument('--server', required=True,
                        help='私服地址，如 127.0.0.1:6645 或 sgame.myserver.com:8080')
    parser.add_argument('--output', '-o', help='输出 APK 路径 (默认在同目录下)')
    parser.add_argument('--keys-dir', default='./keys', help='签名密钥目录')
    parser.add_argument('--skip-verify', action='store_true', help='跳过最终验证')
    args = parser.parse_args()

    if not os.path.isfile(args.apk):
        print(f'[错误] 找不到 APK: {args.apk}')
        return 1

    out_apk = args.output or args.apk.replace('.apk', f'_private_server.apk')
    scan_report_path = out_apk + '.scan_report.json'
    rules_path = out_apk + '.rules.json'

    print(f'\n==> [1/4] 扫描官方 APK 中的服务器地址')
    print(f'     目标: {args.apk}')
    print(f'     查找 http/tcp/wss 链接及腾讯相关域名...')
    findings = scan_apk_for_replacement(args.apk)

    total_findings = sum(len(v) for v in findings.values())
    print(f'     命中 {len(findings)} 个文件，共 {total_findings} 处')

    if total_findings == 0:
        print(f'     [!] 未找到任何服务器地址。若确认 APK 包含自定义服务端，'
              f'请手动编辑 rules.json。')
        return 2

    # 保存原始扫描报告供参考
    serializable_findings = {}
    for k, v in findings.items():
        serializable_findings[k] = [
            {'type': f['type'], 'original': f['original'].decode('latin-1'),
             'offset': f['offset']} for f in v
        ]
    with open(scan_report_path, 'w', encoding='utf-8') as f:
        json.dump(serializable_findings, f, ensure_ascii=False, indent=2)
    print(f'     原始扫描报告: {scan_report_path}')

    print(f'\n==> [2/4] 生成替换规则 (目标: {args.server})')
    rules = build_replacement_rules(findings, args.server)

    # 构造 hokstrap 兼容的 rules.json
    hokstrap_rules = {'replacements': [], 'file_overrides': {}}
    for r in rules:
        hokstrap_rules['replacements'].append({
            'path': r['file'],
            'find': r['find_original'],
            'replace': r['replace_with'],
            'binary': r['is_binary'],
        })

    with open(rules_path, 'w', encoding='utf-8') as f:
        json.dump(hokstrap_rules, f, ensure_ascii=False, indent=2)
    print(f'     生成 {len(hokstrap_rules["replacements"])} 条替换规则 → {rules_path}')

    for r in rules[:10]:
        show_find = r['find_original'][:60] + ('...' if len(r['find_original']) > 60 else '')
        show_rep = r['replace_with'][:60] + ('...' if len(r['replace_with']) > 60 else '')
        print(f'     [{ "二进制" if r["is_binary"] else "文本" }] {r["file"]}')
        print(f'            {show_find}  →  {show_rep}')
    if len(rules) > 10:
        print(f'     ... 其余 {len(rules) - 10} 条见 {rules_path}')

    print(f'\n==> [3/4] 应用替换 + ZIP级重打包 + 重签名')
    # 调用 hokstrap 子流程
    class Args: pass
    a = Args()
    a.apk = args.apk
    a.rules = rules_path
    a.out = out_apk
    a.allow_miss = False

    overrides = hokstrap.apply_replacements(a)
    hokstrap.rebuild_apk(args.apk, out_apk, overrides)

    a2 = Args()
    a2.apk = out_apk
    a2.out = out_apk
    a2.keys_dir = args.keys_dir
    hokstrap.cmd_sign(a2)

    print(f'     输出 APK: {out_apk} ({os.path.getsize(out_apk):,} bytes)')

    if not args.skip_verify:
        print(f'\n==> [4/4] 校验 APK 结构与签名')
        a3 = Args()
        a3.apk = out_apk
        ret = hokstrap.cmd_verify(a3)
        if ret != 0:
            print(f'     [错误] 校验失败，APK 可能无效')
            return 3
        print(f'     ✓ APK 有效，可安装测试')

    print(f'\n\n============= 处理完成 =============')
    print(f'  私有服务器 APK: {out_apk}')
    print(f'  扫描报告:       {scan_report_path}')
    print(f'  替换规则:       {rules_path}')
    print(f'  安装: adb install -r {out_apk}')
    print(f'  启动: adb shell am start -n com.tencent.tmgp.sgame/com.tencent.tmgp.sgame.SGameActivity')
    return 0


if __name__ == '__main__':
    sys.exit(main())
