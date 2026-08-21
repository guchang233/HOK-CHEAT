#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
hokstrap — 王者荣耀直装 APK 管线工具（私服测试用）

参考仓库: https://github.com/wwweeeqqu/honor-of-kings-RE-research (apk-tools)
管线: scan(扫描服务器地址) → patch(替换+ZIP级重打包) → sign(v2签名) → verify(校验) → install(adb安装监控)

核心约束（来自原仓库实测）:
  - resources.arsc 必须 STORED 且 4 字节对齐（API 30+ 要求）
  - STORED 状态的 lib/**/*.so 需 4096 页对齐（extractNativeLibs=false 时 mmap 直载）
  - 保持每个条目原始压缩方式与顺序，除 resources.arsc 强制 STORED
  - .so 内字符串必须等长替换（NUL 填充），避免破坏 ELF 布局
  - 旧签名(META-INF/*.SF|*.RSA|*.DSA|*.EC|MANIFEST.MF)必须剥离后重签

用法:
  python3 hokstrap.py scan   官方.apk --out scan_report.json
  python3 hokstrap.py patch  官方.apk -r rules.json -o patched.apk
  python3 hokstrap.py sign   patched.apk -o signed.apk
  python3 hokstrap.py verify signed.apk
  python3 hokstrap.py install signed.apk [--watch 120]
  python3 hokstrap.py dump   官方.apk -e assets/Config/ServerConfig.json -d out/
  python3 hokstrap.py selftest        # 无需真实APK，合成样本全链路验证

依赖: 仅标准库；sign/verify 需要 `pip install cryptography`
签名方案: APK Signature Scheme v2 (RSA-2048, SHA-256, 0x0103)，Android 7.0+ 生效
"""

import argparse
import datetime
import fnmatch
import hashlib
import json
import os
import re
import struct
import subprocess
import sys
import time
import zlib
import zipfile

# ---------------- 常量 ----------------
V2_BLOCK_MAGIC = b'APK Sig Block 42'
V2_SIGNATURE_ID = 0x7109871a      # APK Signature Scheme v2 的 ID-value 对 ID
RSA_SHA256_PKCS1 = 0x0103         # RSASSA-PKCS1-v1_5 + SHA2-256 + chunked SHA-256
CHUNK_SIZE = 1048576              # v2 摘要分块大小 (1MB)
ALIGN_DEFAULT = 4                 # STORED 条目 4 字节对齐 (zipalign -f 4)
ALIGN_PAGE = 4096                  # STORED .so 页对齐 (zipalign -p 4)
FORCE_STORED = ('resources.arsc',)  # API 30+ 强制不压缩
OLD_SIG_RE = re.compile(r'^META-INF/(MANIFEST\.MF|.*\.(SF|RSA|DSA|EC))$', re.I)

URL_RE = re.compile(rb'(?:https?|tcp|ws|wss|ssl)://[A-Za-z0-9.\-]+(?::\d{1,5})?(?:/[A-Za-z0-9.\-/_]*)?')
TENCENT_DOMAIN_RE = re.compile(rb'\b(?:[A-Za-z0-9\-]+\.)+(?:qq|tencent|gtimg|qpic|myapp|qcloud|tmgp|cdn\.doubao)\.(?:com|cn)\b(?::\d{1,5})?')
IP_RE = re.compile(rb'\b(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?\b')

LOG = lambda *a: print(*a, flush=True)


def u16(x): return struct.pack('<H', x)
def u32(x): return struct.pack('<I', x)
def u64(x): return struct.pack('<Q', x)
def lp(b):  # v2 结构的 uint32 长度前缀
    return u32(len(b)) + b


def r16(b, o): return struct.unpack_from('<H', b, o)[0]
def r32(b, o): return struct.unpack_from('<I', b, o)[0]
def r64(b, o): return struct.unpack_from('<Q', b, o)[0]


# ============================================================
# ZIP 写入器：支持对齐（等价 zipalign -f -p 4）
# ============================================================
class AlignedZipWriter:
    """顺序写入 zip；对 STORED 条目用 extra 字段填充实现数据起始偏移对齐。
    这正是 zipalign 的做法：不动数据、不动中央目录，只调本地头 extra 填充。"""

    LFH_FMT = '<IHHHHHIIIHH'   # 30 字节本地文件头
    CDH_FMT = '<IHHHHHHIIIHHHHHII'  # 46 字节中央目录头
    EOCD_FMT = '<IHHHHIIH'     # 22 字节 EOCD

    def __init__(self, fh):
        self.fh = fh
        self.entries = []

    def add(self, name, data, method, dos_time, dos_date, align=1):
        name_b = name.encode('utf-8')
        crc = zlib.crc32(data) & 0xFFFFFFFF
        if method == zipfile.ZIP_DEFLATED:
            co = zlib.compressobj(9, zlib.DEFLATED, -15)
            payload = co.compress(data) + co.flush()
        else:
            payload = data
        csize, usize = len(payload), len(data)

        header_pos = self.fh.tell()
        # 计算 extra 填充量，使数据区起始偏移满足对齐
        extra = b''
        if align > 1:
            data_off = header_pos + 30 + len(name_b)
            pad = (-data_off) % align
            extra = b'\x00' * pad

        self.fh.write(struct.pack(self.LFH_FMT, 0x04034B50, 20, 0x0800, method,
                                  dos_time, dos_date, crc, csize, usize,
                                  len(name_b), len(extra)))
        self.fh.write(name_b)
        self.fh.write(extra)
        self.fh.write(payload)
        self.entries.append((name_b, method, dos_time, dos_date, crc, csize, usize, header_pos))

    def finish(self):
        cd_off = self.fh.tell()
        for (name_b, method, t, d, crc, csize, usize, hpos) in self.entries:
            self.fh.write(struct.pack(self.CDH_FMT, 0x02014B50, (3 << 8) | 20, 20, 0x0800,
                                      method, t, d, crc, csize, usize,
                                      len(name_b), 0, 0, 0, 0, 0, hpos))
            self.fh.write(name_b)
        cd_size = self.fh.tell() - cd_off
        n = len(self.entries)
        self.fh.write(struct.pack(self.EOCD_FMT, 0x06054B50, 0, 0, n, n, cd_size, cd_off, 0))
        return cd_off, cd_size


def dos_datetime(dt):
    """(y,m,d,h,mi,s) → (dos_time, dos_date)"""
    d = max(0, ((dt[0] - 1980) << 9) | (dt[1] << 5) | dt[2])
    t = (dt[3] << 11) | (dt[4] << 5) | (dt[5] // 2)
    return t & 0xFFFF, d & 0xFFFF


# ============================================================
# 通用 zip 结构解析
# ============================================================
def find_eocd(buf):
    """在文件尾部搜索 EOCD 魔数并校验注释长度，返回 (eocd_off, cd_off)"""
    max_back = min(len(buf), 22 + 65535)
    seg = buf[-max_back:]
    i = seg.rfind(b'PK\x05\x06')
    if i < 0:
        raise ValueError('找不到 EOCD（不是合法 zip?）')
    off = len(buf) - max_back + i
    clen = r16(buf, off + 20)
    if off + 22 + clen != len(buf):
        raise ValueError('EOCD 注释长度不匹配')
    return off, r32(buf, off + 16)


def entry_data_offsets(path):
    """解析中央目录，返回 {name: (method, header_off, data_off)}"""
    out = {}
    with open(path, 'rb') as f:
        buf = f.read()
    eocd_off, cd_off = find_eocd(buf)
    pos, n = cd_off, r16(buf, eocd_off + 10)
    for _ in range(n):
        if r32(buf, pos) != 0x02014B50:
            raise ValueError(f'中央目录头错误 @0x{pos:x}')
        method = r16(buf, pos + 10)
        fnlen, extralen = r16(buf, pos + 28), r16(buf, pos + 30)
        cmlen = r16(buf, pos + 32)
        hpos = r32(buf, pos + 42)
        name = buf[pos + 46: pos + 46 + fnlen].decode('utf-8', 'replace')
        lfn, lextra = r16(buf, hpos + 26), r16(buf, hpos + 28)
        out[name] = (method, hpos, hpos + 30 + lfn + lextra)
        pos += 46 + fnlen + extralen + cmlen
    return out


# ============================================================
# scan — 扫描 APK 内的服务器地址/域名/IP
# ============================================================
def _stream_scan(zf, name, patterns, limit_per_pat=64):
    """流式读取大条目（.so/.dex 可达数百 MB），分块匹配，返回命中列表"""
    hits = []
    overlap = 512
    offset = 0
    tail = b''
    with zf.open(name) as f:
        while True:
            chunk = f.read(8 * 1024 * 1024)
            if not chunk:
                break
            window = tail + chunk
            base = offset - len(tail)
            for pat in patterns:
                cnt = 0
                for m in pat.finditer(window):
                    s = m.group()
                    if len(s) < 7:
                        continue
                    hits.append({'offset': base + m.start(), 'match': s.decode('ascii', 'replace')})
                    cnt += 1
                    if cnt >= limit_per_pat:
                        break
            offset += len(chunk)
            tail = window[-overlap:]
    # 去重（overlap 区可能重复命中）
    seen, uniq = set(), []
    for h in hits:
        key = (h['offset'], h['match'])
        if key not in seen:
            seen.add(key)
            uniq.append(h)
    return uniq


def cmd_scan(args):
    patterns = []
    if not args.no_urls:
        patterns.append(URL_RE)
    patterns.append(TENCENT_DOMAIN_RE)
    patterns.append(IP_RE)

    report = {'apk': args.apk, 'text_files': {}, 'binaries': {}, 'config_files': []}
    with zipfile.ZipFile(args.apk) as zf:
        for info in zf.infolist():
            if info.is_dir():
                continue
            name = info.file_size and info.filename or info.filename
            low = info.filename.lower()
            try:
                if low.endswith(('.json', '.xml', '.txt', '.properties', '.config', '.ini', '.lua', '.cfg')):
                    data = zf.read(info.filename)
                    ms = []
                    for pat in patterns:
                        ms += [m.group().decode('ascii', 'replace') for m in pat.finditer(data)]
                    if ms:
                        report['text_files'][info.filename] = sorted(set(ms))
                    if any(k in low for k in ('config', 'server', 'gateway', 'channel', 'version', 'revision', 'url', 'ip')):
                        report['config_files'].append(f'{info.filename} ({info.file_size} bytes)')
                elif low.endswith(('.so', '.dex')) or '/assets/' in info.filename and info.file_size > 4096:
                    hits = _stream_scan(zf, info.filename, patterns)
                    if hits:
                        report['binaries'][info.filename] = hits
            except Exception as e:
                LOG(f'  [warn] {info.filename}: {e}')

    n_text = len(report['text_files'])
    n_bin = len(report['binaries'])
    n_cfg = len(report['config_files'])
    LOG(f'文本配置命中: {n_text} 个文件')
    for f, ms in list(report['text_files'].items())[:40]:
        LOG(f'  {f}:')
        for m in ms[:8]:
            LOG(f'    {m}')
    LOG(f'二进制命中: {n_bin} 个文件')
    for f, hits in list(report['binaries'].items())[:40]:
        LOG(f'  {f}: {len(hits)} 处')
        for h in hits[:6]:
            off, val = h['offset'], h['match']
            LOG(f'    @0x{off:x}  {val}')
    LOG(f'疑似配置文件: {n_cfg} 个')

    if args.out:
        with open(args.out, 'w', encoding='utf-8') as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
        LOG(f'报告已写入 {args.out}')
    return 0


# ============================================================
# patch — 应用替换规则 + ZIP 级重打包（保持压缩方式/顺序）
# ============================================================
def load_rules(path):
    with open(path, encoding='utf-8') as f:
        rules = json.load(f)
    rules.setdefault('replacements', [])
    rules.setdefault('file_overrides', {})
    return rules


def apply_replacements(args):
    rules = load_rules(args.rules)
    overrides = {}
    summary = []

    with zipfile.ZipFile(args.apk) as zf:
        names = zf.namelist()

        # 整文件替换
        for entry, local in rules['file_overrides'].items():
            if entry not in names:
                raise SystemExit(f'[错误] 规则中的条目不存在: {entry}')
            with open(local, 'rb') as f:
                overrides[entry] = f.read()
            summary.append(f'[整文件] {entry} ← {local} ({len(overrides[entry])} bytes)')

        # 字符串替换
        for i, r in enumerate(rules['replacements']):
            find, repl = r['find'], r['replace']
            if 'path' in r:
                targets = [r['path']] if r['path'] in names else []
            elif 'path_glob' in r:
                targets = fnmatch.filter(names, r['path_glob'])
            else:
                raise SystemExit(f'[错误] 规则 #{i} 缺少 path/path_glob')
            if not targets:
                if args.allow_miss:
                    summary.append(f'[跳过] 规则 #{i} 无匹配条目: {r.get("path") or r.get("path_glob")}')
                    continue
                raise SystemExit(f'[错误] 规则 #{i} 无匹配条目: {r.get("path") or r.get("path_glob")}')
            is_glob = 'path_glob' in r
            total_cnt = 0
            for t in targets:
                data = overrides.get(t) or zf.read(t)
                binary = bool(r.get('binary')) or t.endswith('.so')
                if binary:
                    fb, rb = find.encode(), repl.encode()
                    if len(rb) > len(fb):
                        raise SystemExit(f'[错误] {t}: 二进制替换必须等长或更短 '
                                         f'({len(rb)} > {len(fb)})，改短或换更短的私服地址')
                    cnt = data.count(fb)
                    if cnt:
                        data = data.replace(fb, rb + b'\x00' * (len(fb) - len(rb)))
                        overrides[t] = data
                else:
                    text = data.decode('utf-8', errors='strict' if t.endswith('.json') else 'replace')
                    cnt = text.count(find)
                    if cnt:
                        text = text.replace(find, repl)
                        overrides[t] = text.encode('utf-8')
                total_cnt += cnt
                kind = '二进制' if binary else '文本'
                note = '' if cnt or is_glob else ' ← 未命中'
                summary.append(f'[{kind}] {t}: {find} → {repl} ({cnt} 处){note}')
            if total_cnt == 0 and not args.allow_miss:
                hint = '（glob 目标均未命中；确认 scan 报告，或加 --allow-miss）'
                raise SystemExit(f'[错误] 规则 #{i} 整体未命中: {find} {hint}')

    for s in summary:
        LOG(f'  {s}')
    return overrides


def rebuild_apk(base_apk, out_apk, overrides):
    """ZIP 级重建：保持条目顺序与压缩方式；剥离旧签名；
    resources.arsc 强制 STORED；STORED 条目 4 字节对齐；STORED .so 页对齐。"""
    with zipfile.ZipFile(base_apk) as zin:
        infos = zin.infolist()
        stripped = [i.filename for i in infos if OLD_SIG_RE.match(i.filename)]
        with open(out_apk, 'wb') as fh:
            w = AlignedZipWriter(fh)
            for item in infos:
                if OLD_SIG_RE.match(item.filename):
                    continue
                data = overrides.get(item.filename, None)
                if data is None:
                    data = zin.read(item.filename)
                method = zipfile.ZIP_STORED if item.filename in FORCE_STORED else item.compress_type
                is_so = item.filename.startswith('lib/') and item.filename.endswith('.so')
                if method == zipfile.ZIP_DEFLATED:
                    align = 1
                elif is_so:
                    align = ALIGN_PAGE
                else:
                    align = ALIGN_DEFAULT
                t, d = dos_datetime(item.date_time)
                w.add(item.filename, data, method, t, d, align)
            cd_off, cd_size = w.finish()
    if stripped:
        LOG(f'  已剥离旧签名条目: {", ".join(stripped)}')
    return cd_off, cd_size


def cmd_patch(args):
    overrides = apply_replacements(args)
    rebuild_apk(args.apk, args.out, overrides)
    size = os.path.getsize(args.out)
    LOG(f'  输出: {args.out} ({size:,} bytes)')
    LOG('  提示: 接下来执行 sign 子命令完成重签名')
    return 0


# ============================================================
# sign — APK Signature Scheme v2（RSA-2048 / SHA-256 / PKCS#1 v1.5）
# ============================================================
def get_or_create_key(keys_dir):
    """首次生成本地密钥并持久化——保证多次构建签名身份一致，adb install -r 不冲突"""
    os.makedirs(keys_dir, exist_ok=True)
    key_p, cert_p = os.path.join(keys_dir, 'key.pem'), os.path.join(keys_dir, 'cert.pem')
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID
    if os.path.exists(key_p) and os.path.exists(cert_p):
        with open(key_p, 'rb') as f:
            key = serialization.load_pem_private_key(f.read(), password=None)
        with open(cert_p, 'rb') as f:
            cert = x509.load_pem_x509_certificate(f.read())
        return key, cert

    LOG(f'  生成新签名密钥 → {keys_dir}')
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    subj = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, u'Hok Private Server Test')])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder()
            .subject_name(subj).issuer_name(subj)
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=3650))
            .sign(key, hashes.SHA256()))
    with open(key_p, 'wb') as f:
        f.write(key.private_bytes(serialization.Encoding.PEM,
                                  serialization.PrivateFormat.PKCS8,
                                  serialization.NoEncryption()))
    with open(cert_p, 'wb') as f:
        f.write(cert.public_bytes(serialization.Encoding.PEM))
    return key, cert


def chunked_digest(data):
    """v2 chunked SHA-256: SHA256(0xA7 ‖ Σ( u32(len(chunk)) ‖ SHA256(u32(len)+chunk) ))"""
    h = hashlib.sha256()
    h.update(b'\xa7')
    if not data:
        chunks = [b'']
    else:
        chunks = [data[i:i + CHUNK_SIZE] for i in range(0, len(data), CHUNK_SIZE)]
    for chunk in chunks:
        h.update(u32(len(chunk)))
        d = hashlib.sha256(u32(len(chunk)) + chunk).digest()
        h.update(u32(len(d)) + d)
    return h.digest()


def strip_existing_v2(buf):
    """若已含 v2 签名块则剥离（支持对已签名文件重签）"""
    eocd_off, cd_off = find_eocd(buf)
    if cd_off >= 24 and buf[cd_off - 16:cd_off] == V2_BLOCK_MAGIC:
        size2 = r64(buf, cd_off - 24)
        block_start = cd_off - 8 - size2  # size2 计入 pairs+尾size+magic，不含首 size 字段
        if block_start < 0 or r64(buf, block_start) != size2:
            raise ValueError('已存在签名块但结构非法')
        del buf[block_start:cd_off]
        eocd_off, _ = find_eocd(buf)
        struct.pack_into('<I', buf, eocd_off + 16, block_start)  # EOCD 回填为真实 CD 起点
        cd_off = block_start
    return buf, eocd_off, cd_off


def sign_v2_inplace(path, key, cert):
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding

    with open(path, 'rb') as f:
        buf = bytearray(f.read())
    buf, eocd_off, cd_off = strip_existing_v2(buf)

    # 三个 section：① 条目数据 [0, cd_off) ② 中央目录 ③ EOCD
    # （摘要计算时 EOCD 的 CD 偏移字段视为签名块起始位置 == 插入前的 cd_off，
    #   即原始 EOCD 字节本身；写入文件的 EOCD 则指向真实 CD 起点）
    sec1 = bytes(buf[:cd_off])
    sec2 = bytes(buf[cd_off:eocd_off])
    sec3 = bytes(buf[eocd_off:])
    dig96 = chunked_digest(sec1) + chunked_digest(sec2) + chunked_digest(sec3)

    digests_field = lp(lp(u32(RSA_SHA256_PKCS1) + lp(dig96)))
    cert_der = cert.public_bytes(serialization.Encoding.DER)
    certs_field = lp(lp(cert_der))
    attrs_field = lp(b'')
    signed_data_content = digests_field + certs_field + attrs_field
    signed_data = lp(signed_data_content)

    sig = key.sign(signed_data_content, padding.PKCS1v15(), hashes.SHA256())
    signatures_field = lp(lp(u32(RSA_SHA256_PKCS1) + lp(sig)))

    pub_der = key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    signer = lp(signed_data + signatures_field + lp(pub_der))
    block_value = lp(signer)

    pair = u64(4 + len(block_value)) + u32(V2_SIGNATURE_ID) + block_value
    size_field = len(pair) + 8 + 16  # pairs + 尾部 size + magic
    block = u64(size_field) + pair + u64(size_field) + V2_BLOCK_MAGIC

    # 在中央目录前插入签名块，并把 EOCD 的 CD 偏移改为真实 CD 起点
    buf[cd_off:cd_off] = block
    struct.pack_into('<I', buf, eocd_off + 16 + len(block), cd_off + len(block))

    with open(path, 'wb') as f:
        f.write(bytes(buf))
    return len(block)


def cmd_sign(args):
    key, cert = get_or_create_key(args.keys_dir)
    if args.out and os.path.abspath(args.out) != os.path.abspath(args.apk):
        import shutil
        shutil.copyfile(args.apk, args.out)
        target = args.out
    else:
        target = args.apk
    blen = sign_v2_inplace(target, key, cert)
    LOG(f'  v2 签名块 {blen} 字节已写入: {target}')
    LOG('  提示: 用 verify 子命令自检签名与对齐')
    return 0


# ============================================================
# verify — 结构/对齐/签名校验
# ============================================================
def check_alignment(path):
    problems, checked = [], 0
    info = entry_data_offsets(path)
    for name, (method, hpos, doff) in info.items():
        if method != zipfile.ZIP_STORED or name.endswith('/'):
            continue
        checked += 1
        need = ALIGN_PAGE if (name.startswith('lib/') and name.endswith('.so')) else ALIGN_DEFAULT
        if doff % need:
            problems.append(f'{name}: 数据偏移 0x{doff:x} 未 {need} 字节对齐')
    return checked, problems


def parse_v2_block(path):
    """解析 v2 签名块，返回结构化信息（解析器与写入器互为镜像，便于交叉校验）"""
    with open(path, 'rb') as f:
        buf = f.read()
    eocd_off, cd_off = find_eocd(buf)
    if buf[cd_off - 16:cd_off] != V2_BLOCK_MAGIC:
        return None, buf, cd_off, eocd_off
    size2 = r64(buf, cd_off - 24)
    block_start = cd_off - 8 - size2  # size2 = pairs + 尾部 size + magic
    if r64(buf, block_start) != size2:
        raise ValueError('签名块 size 字段不一致')
    pairs = {}
    pos, end = block_start + 8, cd_off - 24
    while pos < end:
        plen = r64(buf, pos)
        pid = r32(buf, pos + 8)
        pairs.setdefault(pid, []).append(buf[pos + 12: pos + 8 + plen])
        pos += 8 + plen
    v2 = pairs.get(V2_SIGNATURE_ID, [None])[0]
    if v2 is None:
        raise ValueError('未找到 v2 签名 ID-value 对 (0x7109871a)')

    # 逐层剥长度前缀：v2 → signer → (signed data / signatures / public key)
    def parse_lp(b, o):
        n = r32(b, o)
        return b[o + 4: o + 4 + n], o + 4 + n

    signer, _ = parse_lp(v2, 0)                    # signer = lp(signer_content)
    signer_content, _ = parse_lp(signer, 0)        # = signed_data + signatures_field + pubkey_field
    o = 0
    sd_content, o = parse_lp(signer_content, o)      # 第一个字段的载荷 = 签名覆盖的原始字节
    signatures_payload, o = parse_lp(signer_content, o)  # 第二字段载荷 = lp(entry2)
    pub_der, o = parse_lp(signer_content, o)         # 第三字段载荷 = SPKI DER
    digests_payload, o2 = parse_lp(sd_content, 0)
    certs_payload, o2 = parse_lp(sd_content, o2)
    _attrs, o2 = parse_lp(sd_content, o2)
    # digests → 单条 (algo uint32 + lp(digest))
    one, _ = parse_lp(digests_payload, 0)
    algo = r32(one, 0)
    dg, _ = parse_lp(one, 4)
    # certs → 首张证书 DER
    cert_der, _ = parse_lp(certs_payload, 0)
    # signatures → 单条 (algo uint32 + lp(sig))
    sig_one, _ = parse_lp(signatures_payload, 0)
    sig_algo = r32(sig_one, 0)
    sig_bytes, _ = parse_lp(sig_one, 4)

    return {
        'block_start': block_start, 'block_size': cd_off - block_start,
        'algo': algo, 'digest': dg, 'sig_algo': sig_algo, 'sig': sig_bytes,
        'cert_der': cert_der, 'pubkey_der': pub_der,
        'signed_data_content': sd_content,
    }, buf, cd_off, eocd_off


def cmd_verify(args):
    ok = True
    path = args.apk

    # 1. zip 完整性
    try:
        with zipfile.ZipFile(path) as zf:
            bad = zf.testzip()
            if bad:
                LOG(f'[FAIL] CRC 损坏: {bad}')
                ok = False
            else:
                LOG('[PASS] zip 结构与 CRC 校验通过')
    except Exception as e:
        LOG(f'[FAIL] 无法打开 zip: {e}')
        return 1

    # 2. resources.arsc 未压缩
    with zipfile.ZipFile(path) as zf:
        for n in FORCE_STORED:
            if n in zf.namelist() and zf.getinfo(n).compress_type != zipfile.ZIP_STORED:
                LOG(f'[FAIL] {n} 被压缩（API 30+ 要求 STORED）')
                ok = False
            else:
                LOG(f'[PASS] {n} 为 STORED')

    # 3. 对齐
    checked, problems = check_alignment(path)
    if problems:
        for p in problems:
            LOG(f'[FAIL] {p}')
        ok = False
    else:
        LOG(f'[PASS] {checked} 个 STORED 条目对齐检查通过（.so=4096, 其余=4）')

    # 4. v2 签名
    try:
        info, buf, cd_off, eocd_off = parse_v2_block(path)
    except Exception as e:
        LOG(f'[FAIL] v2 签名块解析: {e}')
        return 1 if not ok else 1
    if info is None:
        LOG('[FAIL] 未找到 v2 签名块（Android 7+ 无法安装）')
        return 1

    # 重算三段摘要比对（EOCD 的 CD 偏移字段按签名块起始位置取值）
    sec1 = buf[:info['block_start']]
    sec2 = buf[cd_off:eocd_off]
    sec3 = bytearray(buf[eocd_off:])
    struct.pack_into('<I', sec3, 16, info['block_start'])
    dig96 = chunked_digest(sec1) + chunked_digest(sec2) + chunked_digest(bytes(sec3))
    if dig96 != info['digest']:
        LOG('[FAIL] APK 内容摘要与签名不符（签名后文件被改动）')
        ok = False
    else:
        LOG('[PASS] 三段 chunked SHA-256 摘要与签名记录一致')

    try:
        from cryptography import x509
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import padding
        from cryptography.hazmat.primitives.serialization import load_der_public_key
        cert = x509.load_der_x509_certificate(info['cert_der'])
        load_der_public_key(info['pubkey_der']).verify(
            info['sig'], info['signed_data_content'],
            padding.PKCS1v15(), hashes.SHA256())
        LOG(f"[PASS] RSA 签名验证通过，证书 CN={cert.subject.rfc4514_string()}")
    except ImportError:
        LOG('[SKIP] 未安装 cryptography，跳过密码学验证（pip install cryptography）')
    except Exception as e:
        LOG(f'[FAIL] 签名验证失败: {e}')
        ok = False

    LOG('[PASS] 整体校验通过 ✓' if ok else '[FAIL] 存在问题，见上')
    return 0 if ok else 1


# ============================================================
# dump — 提取条目
# ============================================================
def cmd_dump(args):
    with zipfile.ZipFile(args.apk) as zf:
        names = zf.namelist()
        if args.entry:
            targets = [args.entry] if args.entry in names else fnmatch.filter(names, args.entry)
            if not targets:
                raise SystemExit(f'未找到: {args.entry}')
        else:
            targets = names
        os.makedirs(args.dir, exist_ok=True)
        for t in targets:
            data = zf.read(t)
            out = os.path.join(args.dir, t.replace('/', '__'))
            with open(out, 'wb') as f:
                f.write(data)
            LOG(f'  {t} → {out} ({len(data):,} bytes)')
    return 0


# ============================================================
# install — adb 安装 + 启动 + 存活监控（流程来自原仓库 process.md）
# ============================================================
def cmd_install(args):
    adb = args.adb or 'adb'
    def run(cmd, **kw):
        return subprocess.run([adb] + cmd, capture_output=True, text=True, **kw)
    r = run(['wait-for-device'], timeout=30)
    if r.returncode != 0:
        raise SystemExit(f'adb 不可用（{adb}）: {r.stderr.strip()}')
    LOG('  设备已连接，开始安装（先卸载旧版可避免签名冲突）…')
    r = run(['install', '-r', '-d', args.apk])
    LOG(f'  adb install: {(r.stdout + r.stderr).strip()}')
    if r.returncode != 0:
        return 1
    if args.activity:
        run(['shell', 'logcat', '-c'])
        run(['shell', 'am', 'start', '-n', args.activity])
        LOG(f'  已启动 {args.activity}')
        deadline = time.time() + (args.watch or 60)
        start = time.time()
        while time.time() < deadline:
            time.sleep(2)
            p = run(['shell', 'pidof', args.package])
            pid = (p.stdout or '').strip()
            if not pid:
                LOG(f'[结果] 进程消失，存活 {time.time() - start:.0f}s — 拉取 tombstone:')
                run(['shell', 'run-as', args.package, 'true'])
                t = run(['shell', 'ls', '-t', '/data/tombstones'])
                LOG('    ' + (t.stdout or t.stderr).strip()[:300])
                d = run(['shell', 'logcat', '-d', '-b', 'crash'])
                for line in (d.stdout or '').splitlines()[:40]:
                    LOG('    ' + line)
                return 2
            LOG(f'  {time.time() - start:.0f}s 存活 (pid {pid})')
        LOG(f'[结果] 监控窗口 {args.watch}s 内稳定运行 ✓')
    return 0


# ============================================================
# selftest — 合成 APK 全链路验证（不需要真实游戏包与设备）
# ============================================================
def _make_synthetic_apk(path):
    """构造一个结构上模仿游戏包的小 APK：
    - resources.arsc 故意用 DEFLATED（测试强制转 STORED）
    - libGameCore.so STORED 且内嵌 tcp:// 域名串（测试等长替换 + 页对齐）
    - 携带旧签名条目（测试剥离）
    """
    so = bytearray(os.urandom(120_000))
    marker = b'tcp://battle.qq.com:6645\x00'
    so[50000:50000 + len(marker)] = marker
    cfg = {
        'gateway': 'https://sgame_gateway.qq.com:8080/serverlist',
        'battle_tcp': 'tcp://battle.qq.com:6645',
        'hotupdate_cdn': 'https://dliir.path.qq.com/hok/res/',
    }
    with zipfile.ZipFile(path, 'w') as zf:
        zf.writestr('AndroidManifest.xml', b'\x03\x00\x08\x00' + os.urandom(400),
                    compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr('resources.arsc', b'\x02\x00\x0c\x00' + os.urandom(600),
                    compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr('classes.dex', b'dex\n035\x00' + os.urandom(3000),
                    compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr('assets/Config/ServerConfig.json', json.dumps(cfg, indent=2),
                    compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr('assets/version.ini', 'version=11.4.1.1\nurl=https://version.qq.com/check\n',
                    compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr('lib/arm64-v8a/libGameCore.so', bytes(so),
                    compress_type=zipfile.ZIP_STORED)
        zf.writestr('lib/arm64-v8a/libtprt.so', os.urandom(2000),
                    compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n',
                    compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr('META-INF/CERT.SF', 'Signature-Version: 1.0\n',
                    compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr('META-INF/CERT.RSA', os.urandom(512),
                    compress_type=zipfile.ZIP_DEFLATED)
    return len(marker) - 1  # 不含结尾 NUL 的域名串长度


def cmd_selftest(args):
    from cryptography import x509  # noqa: F401

    build = args.build_dir
    os.makedirs(build, exist_ok=True)
    base = os.path.join(build, 'synthetic_base.apk')
    patched = os.path.join(build, 'synthetic_patched.apk')
    signed = os.path.join(build, 'synthetic_signed.apk')
    rules = os.path.join(build, 'test_rules.json')
    keys = os.path.join(build, 'keys')
    results = []

    def check(name, cond, detail=''):
        results.append((name, bool(cond), detail))
        LOG(f"  [{'PASS' if cond else 'FAIL'}] {name}" + (f' — {detail}' if detail else ''))

    LOG('== 1/6 构造合成 APK（模拟 resources.arsc 压缩/存储 .so/旧签名） ==')
    marker_len = _make_synthetic_apk(base)
    LOG(f'  {base} ({os.path.getsize(base):,} bytes)')

    LOG('== 2/6 scan：应找到腾讯域名与 URL ==')
    report = {'text_files': {}, 'binaries': {}}
    sys_argv = sys.argv
    with zipfile.ZipFile(base) as zf:
        for pat, sink in ((URL_RE, 'u'), (TENCENT_DOMAIN_RE, 'd')):
            for info in zf.infolist():
                low = info.filename.lower()
                if low.endswith(('.json', '.xml', '.ini', '.txt')):
                    data = zf.read(info.filename)
                    ms = [m.group().decode() for m in pat.finditer(data)]
                    if ms:
                        report['text_files'].setdefault(info.filename, []).extend(ms)
                elif low.endswith('.so'):
                    hits = _stream_scan(zf, info.filename, [pat])
                    if hits:
                        report['binaries'].setdefault(info.filename, []).extend(
                            h['match'] for h in hits)
    check('scan 命中文本 URL', 'tcp://battle.qq.com:6645'
          in report['text_files'].get('assets/Config/ServerConfig.json', []))
    check('scan 命中 .so 内域名', any('battle.qq.com' in m
          for ms in report['binaries'].values() for m in ms))

    LOG('== 3/6 patch：私服地址替换 + ZIP 级重建 ==')
    priv = args.private_host or '127.0.0.1'
    with open(rules, 'w', encoding='utf-8') as f:
        json.dump({'replacements': [
            {'path': 'assets/Config/ServerConfig.json',
             'find': 'battle.qq.com:6645', 'replace': f'{priv}:6645'},
            {'path': 'assets/version.ini',
             'find': 'https://version.qq.com/check', 'replace': f'http://{priv}:8080/check'},
            {'path_glob': 'lib/arm64-v8a/*.so',
             'find': 'tcp://battle.qq.com:6645', 'replace': f'tcp://{priv}:6645'},
        ]}, f, ensure_ascii=False, indent=2)
    ns = argparse.Namespace(apk=base, out=patched, rules=rules, allow_miss=False)
    overrides = apply_replacements(ns)
    rebuild_apk(base, patched, overrides)
    with zipfile.ZipFile(patched) as zf:
        cfg2 = json.loads(zf.read('assets/Config/ServerConfig.json'))
        check('json 替换生效', cfg2['battle_tcp'] == f'tcp://{priv}:6645')
        so2 = zf.read('lib/arm64-v8a/libGameCore.so')
        newm = f'tcp://{priv}:6645'.encode()
        pos = so2.find(newm)
        check('.so 等长替换+NUL填充', pos == 50000 and
              so2[50000 + len(newm):50000 + marker_len + 1] == b'\x00' * (marker_len - len(newm) + 1),
              f'pos={pos}')
        check('resources.arsc 转 STORED',
              zf.getinfo('resources.arsc').compress_type == zipfile.ZIP_STORED)
        check('libGameCore.so 保持 STORED',
              zf.getinfo('lib/arm64-v8a/libGameCore.so').compress_type == zipfile.ZIP_STORED)
        check('DEFLATED 条目压缩保留',
              zf.getinfo('classes.dex').compress_type == zipfile.ZIP_DEFLATED and
              zf.getinfo('lib/arm64-v8a/libtprt.so').compress_type == zipfile.ZIP_DEFLATED)
        names2 = zf.namelist()
        check('旧签名条目剥离',
              not any(OLD_SIG_RE.match(n) for n in names2) and len(names2) == 7)
        check('条目顺序保持', names2[0] == 'AndroidManifest.xml' and
              names2.index('lib/arm64-v8a/libGameCore.so') < names2.index('lib/arm64-v8a/libtprt.so'))
        check('重打包后 CRC 完整', zf.testzip() is None)

    LOG('== 4/6 对齐检查（等价 zipalign -f -p 4） ==')
    _, problems = check_alignment(patched)
    check('4/4096 对齐', not problems, '; '.join(problems))

    LOG('== 5/6 sign：v2 签名 ==')
    key, cert = get_or_create_key(keys)
    import shutil
    shutil.copyfile(patched, signed)
    blen = sign_v2_inplace(signed, key, cert)
    check('签名块写入', blen > 0, f'{blen} bytes')

    LOG('== 6/6 verify：签名后全量自检 ==')
    info, buf, cd_off, eocd_off = parse_v2_block(signed)
    check('v2 块可解析', info is not None and info['algo'] == RSA_SHA256_PKCS1)
    sec1 = buf[:info['block_start']]
    sec2 = buf[cd_off:eocd_off]
    sec3 = bytearray(buf[eocd_off:])
    struct.pack_into('<I', sec3, 16, info['block_start'])
    dig96 = chunked_digest(sec1) + chunked_digest(sec2) + chunked_digest(bytes(sec3))
    check('三段摘要一致', dig96 == info['digest'])
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import padding as apad
    from cryptography.hazmat.primitives.serialization import load_der_public_key
    try:
        load_der_public_key(info['pubkey_der']).verify(
            info['sig'], info['signed_data_content'], apad.PKCS1v15(), hashes.SHA256())
        check('RSA 签名验证', True)
    except Exception as e:
        check('RSA 签名验证', False, str(e))
    with zipfile.ZipFile(signed) as zf:
        check('签名后 zip 可读/CRC OK', zf.testzip() is None)
        check('签名不破坏替换内容',
              json.loads(zf.read('assets/Config/ServerConfig.json'))['battle_tcp']
              == f'tcp://{priv}:6645')
    _, problems2 = check_alignment(signed)
    check('签名后对齐保持', not problems2, '; '.join(problems2))

    # 重签路径（剥离旧块）
    sign_v2_inplace(signed, key, cert)
    info2, _, _, _ = parse_v2_block(signed)
    check('重复签名（剥离旧块）', info2 is not None)
    with zipfile.ZipFile(signed) as zf:
        check('重签后 zip 可读', zf.testzip() is None)

    n_pass = sum(1 for _, ok_, _ in results if ok_)
    LOG('')
    LOG(f'== selftest 结果: {n_pass}/{len(results)} PASS ==')
    for name, ok_, detail in results:
        if not ok_:
            LOG(f'  FAIL: {name} {detail}')
    return 0 if n_pass == len(results) else 1


# ============================================================
def main():
    ap = argparse.ArgumentParser(
        prog='hokstrap',
        description='王者荣耀直装 APK 管线（私服测试）：scan → patch → sign → verify → install',
        formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='cmd', required=True)

    p = sub.add_parser('scan', help='扫描 APK 内服务器地址/域名/IP/配置文件')
    p.add_argument('apk')
    p.add_argument('--out', help='JSON 报告输出路径')
    p.add_argument('--no-urls', action='store_true', help='跳过通用 URL 模式')
    p.set_defaults(func=cmd_scan)

    p = sub.add_parser('patch', help='按 rules.json 替换 + ZIP 级重打包（保持压缩/顺序/对齐）')
    p.add_argument('apk')
    p.add_argument('-r', '--rules', required=True)
    p.add_argument('-o', '--out', required=True)
    p.add_argument('--allow-miss', action='store_true', help='未命中不报错')
    p.set_defaults(func=cmd_patch)

    p = sub.add_parser('sign', help='写入 v2 签名块（自动生成并复用本地密钥）')
    p.add_argument('apk')
    p.add_argument('-o', '--out', help='输出到新文件（缺省原地签名）')
    p.add_argument('--keys-dir', default='keys', help='密钥目录（默认 ./keys，跨构建复用）')
    p.set_defaults(func=cmd_sign)

    p = sub.add_parser('verify', help='校验 zip 完整性/压缩/对齐/v2 签名')
    p.add_argument('apk')
    p.set_defaults(func=cmd_verify)

    p = sub.add_parser('dump', help='提取 APK 条目到目录')
    p.add_argument('apk')
    p.add_argument('-e', '--entry', help='条目名或通配符（缺省全部）')
    p.add_argument('-d', '--dir', default='extracted')
    p.set_defaults(func=cmd_dump)

    p = sub.add_parser('install', help='adb 安装 + 启动 + 存活监控')
    p.add_argument('apk')
    p.add_argument('--package', default='com.tencent.tmgp.sgame')
    p.add_argument('--activity', default='com.tencent.tmgp.sgame/com.tencent.tmgp.sgame.SGameActivity')
    p.add_argument('--watch', type=int, default=120, help='监控秒数')
    p.add_argument('--adb', default='adb', help='adb 可执行文件路径')
    p.set_defaults(func=cmd_install)

    p = sub.add_parser('selftest', help='合成 APK 全链路自检（无需真实包/设备）')
    p.add_argument('--build-dir', default='build')
    p.add_argument('--private-host', default='127.0.0.1', help='自测用私服地址')
    p.set_defaults(func=cmd_selftest)

    args = ap.parse_args()
    sys.exit(args.func(args))


if __name__ == '__main__':
    main()
