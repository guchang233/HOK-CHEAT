#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AXML 二进制编辑器 — AndroidManifest.xml 注入 (纯 Python 实现)

功能:
  - 解析 Android 二进制 XML (AXML) 为结构化树
  - 注入 <uses-permission> / <provider> / <service> 等元素
  - 重新序列化为合法 AXML (字符串池扩展 + 资源映射扩展)

AXML 格式参考: frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h
"""

import struct
from typing import List, Optional, Tuple

# ---- Chunk types ----
RES_XML_TYPE = 0x0003
RES_STRING_POOL_TYPE = 0x0001
RES_XML_RESOURCE_MAP_TYPE = 0x0180
RES_XML_START_NAMESPACE_TYPE = 0x0100
RES_XML_END_NAMESPACE_TYPE = 0x0101
RES_XML_START_ELEMENT_TYPE = 0x0102
RES_XML_END_ELEMENT_TYPE = 0x0103
RES_XML_CDATA_TYPE = 0x0104
RES_XML_LAST_CHUNK_TYPE = 0x0104

# ---- Value types ----
TYPE_NULL = 0x00
TYPE_REFERENCE = 0x01
TYPE_STRING = 0x03
TYPE_INT_BOOLEAN = 0x12
TYPE_INT_ENUM = 0x10

UTF8_FLAG = 0x100

# ---- 已知属性的资源 ID (android namespace) ----
KNOWN_ATTR_IDS = {
    "name": 0x01010003,
    "permission": 0x01010006,
    "protectionLevel": 0x01010009,
    "exported": 0x01010010,
    "authorities": 0x01010018,
    "enabled": 0x0101000e,
    "process": 0x01010011,
    "label": 0x01010001,
    "value": 0x01010024,
    "targetSdkVersion": 0x01010270,
    "minSdkVersion": 0x0101020c,
    "extractNativeLibs": 0x010104ea,
    "debuggable": 0x0101000f,
    "theme": 0x01010000,
    "foregroundServiceType": 0x010105f8,
}

ANDROID_NS = "http://schemas.android.com/apk/res/android"
NO_NS = 0xFFFFFFFF  # 无命名空间 (元素名不带前缀)

# ---- android:foregroundServiceType 枚举值 (ServiceInfo.FOREGROUND_SERVICE_TYPE_*) ----
FGS_TYPE_ENUMS = {
    "dataSync": 0x00000001,
    "phoneCall": 0x00000002,
    "location": 0x00000008,
    "camera": 0x00000040,
    "microphone": 0x00000080,
    "health": 0x00000200,
    "connectedDevice": 0x00000400,
    "mediaPlayback": 0x00000800,
    "mediaProjection": 0x00001000,
    "remoteMessaging": 0x00000010,
    "systemExempted": 0x40000000,
    "shortService": 0x80000000,
    "fileManagement": 0x00000020,
    "specialUse": 0x40000000,
}


class AxmlError(Exception):
    pass


class Attribute:
    __slots__ = ("ns", "name", "raw_value", "vtype", "vdata")

    def __init__(self, ns: int, name: int, raw_value: int, vtype: int, vdata: int):
        self.ns = ns
        self.name = name
        self.raw_value = raw_value
        self.vtype = vtype
        self.vdata = vdata


class XmlElem:
    """一个 XML 元素 (对应 START_ELEMENT + 子节点 + END_ELEMENT)"""

    def __init__(self, ns: int, name: int, line: int = 1):
        self.ns = ns
        self.name = name          # 字符串池索引
        self.line = line
        self.attrs: List[Attribute] = []
        self.id_index = 0
        self.class_index = 0
        self.style_index = 0
        self.children: List["XmlElem"] = []
        self.text: Optional[str] = None  # CDATA (字符串池索引)

    def find_attr(self, pool, name: str) -> Optional[Attribute]:
        for a in self.attrs:
            if pool.get_string(a.name) == name:
                return a
        return None


class AxmlDocument:
    """解析后的 AXML 文档 (保留顶层字符串池与资源映射)"""

    def __init__(self):
        self.strings: List[str] = []          # 原始字符串 (解码后)
        self.orig_flags = 0
        self.resource_ids: List[int] = []      # 与字符串索引对齐
        self.root: Optional[XmlElem] = None
        self._ns_prefixes: List[Tuple[int, int]] = []  # (prefix_idx, uri_idx) 按出现顺序

    # ---------- 解析 ----------

    @classmethod
    def parse(cls, data: bytes) -> "AxmlDocument":
        if len(data) < 8:
            raise AxmlError("数据过短")
        ftype, fhdr, fsize = struct.unpack_from("<HHI", data, 0)
        if ftype != RES_XML_TYPE:
            raise AxmlError(f"非 AXML 文件 (type=0x{ftype:04x})")

        doc = cls()
        off = 8  # 跳过文件头

        def read_string_pool(offset: int) -> int:
            ctype, chdr, csize = struct.unpack_from("<HHI", data, offset)
            if ctype != RES_STRING_POOL_TYPE:
                raise AxmlError(f"预期字符串池, 得到 0x{ctype:04x}")
            (str_count, style_count, flags,
             strings_start, styles_start) = struct.unpack_from("<IIIII", data, offset + 8)
            doc.orig_flags = flags
            is_utf8 = bool(flags & UTF8_FLAG)

            offsets = struct.unpack_from(f"<{str_count}I", data, offset + 28)
            for so in offsets:
                pos = offset + strings_start + so
                doc.strings.append(_read_string(data, pos, is_utf8))

            if style_count > 0:
                raise AxmlError("带样式的字符串池暂不支持 (manifest 不应出现)")
            return csize

        # 顺序: string pool → [resource map] → xml chunks
        ctype, _, csize = struct.unpack_from("<HHI", data, off)
        if ctype != RES_STRING_POOL_TYPE:
            raise AxmlError("第一个 chunk 必须是字符串池")
        read_string_pool(off)
        off += csize

        # resource map (可选)
        if off < fsize:
            ctype, _, csize = struct.unpack_from("<HHI", data, off)
            if ctype == RES_XML_RESOURCE_MAP_TYPE:
                n_ids = (csize - 8) // 4
                doc.resource_ids = list(struct.unpack_from(f"<{n_ids}I", data, off + 8))
                off += csize

        # XML 树
        stack: List[XmlElem] = []
        while off < fsize:
            ctype, chdr, csize = struct.unpack_from("<HHI", data, off)
            if csize < 8 or off + csize > fsize:
                raise AxmlError(f"chunk 越界 @0x{off:x}")

            if ctype == RES_XML_START_NAMESPACE_TYPE:
                # header(8) + lineNumber(4) + comment(4) + prefix(4) + uri(4)
                prefix, uri = struct.unpack_from("<II", data, off + 16)
                doc._ns_prefixes.append((prefix, uri))
            elif ctype == RES_XML_END_NAMESPACE_TYPE:
                pass
            elif ctype == RES_XML_START_ELEMENT_TYPE:
                line, comment, ns, name = struct.unpack_from("<IIII", data, off + 8)
                attr_start, attr_size, attr_count, id_idx, cls_idx, style_idx = \
                    struct.unpack_from("<HHHHHH", data, off + 24)
                elem = XmlElem(ns, name, line)
                elem.id_index = id_idx
                elem.class_index = cls_idx
                elem.style_index = style_idx
                base = off + 16 + attr_start
                for i in range(attr_count):
                    a_off = base + i * attr_size
                    ans, aname, araw = struct.unpack_from("<III", data, a_off)
                    vsize, vres0, vtype, vdata = struct.unpack_from("<HBBI", data, a_off + 12)
                    elem.attrs.append(Attribute(ans, aname, araw, vtype, vdata))
                if stack:
                    stack[-1].children.append(elem)
                else:
                    if doc.root is not None:
                        raise AxmlError("多个根元素")
                    doc.root = elem
                stack.append(elem)
            elif ctype == RES_XML_END_ELEMENT_TYPE:
                if stack:
                    stack.pop()
            elif ctype == RES_XML_CDATA_TYPE:
                line, comment, dstr = struct.unpack_from("<III", data, off + 8)
                if stack:
                    stack[-1].text = dstr
            else:
                # 未知 chunk, 跳过
                pass
            off += csize

        if doc.root is None:
            raise AxmlError("未找到根元素")
        return doc

    # ---------- 查询 ----------

    def get_string(self, idx: int) -> str:
        if 0 <= idx < len(self.strings):
            return self.strings[idx]
        return ""

    def find_child(self, elem: XmlElem, tag: str) -> Optional[XmlElem]:
        for c in elem.children:
            if self.get_string(c.name) == tag:
                return c
        return None

    def find_attr_str(self, elem: XmlElem, name: str) -> Optional[str]:
        a = elem.find_attr(self, name)
        if a is None:
            return None
        return self.get_string(a.raw_value) if a.raw_value != 0xFFFFFFFF else None

    # ---------- 注入 ----------

    def ensure_string(self, s: str) -> int:
        """返回字符串索引, 不存在则追加"""
        try:
            return self.strings.index(s)
        except ValueError:
            self.strings.append(s)
            return len(self.strings) - 1

    def ensure_attr_id(self, str_idx: int) -> None:
        """确保 resource_ids 覆盖到 str_idx, 新条目按属性名填已知 ID"""
        while len(self.resource_ids) <= str_idx:
            s = self.strings[len(self.resource_ids)]
            self.resource_ids.append(KNOWN_ATTR_IDS.get(s, 0))

    def add_uses_permission(self, perm_name: str) -> bool:
        """在 <manifest> 下添加 uses-permission (已存在则跳过)"""
        manifest = self.root
        if self.get_string(manifest.name) != "manifest":
            raise AxmlError("根元素不是 manifest")

        for c in manifest.children:
            if self.get_string(c.name) == "uses-permission":
                if self.find_attr_str(c, "name") == perm_name:
                    return False

        elem = XmlElem(NO_NS, self.ensure_string("uses-permission"), 1)
        ns_idx = self._android_ns_index()
        perm_idx = self.ensure_string(perm_name)
        name_attr = Attribute(
            ns_idx, self.ensure_string("name"),
            perm_idx, TYPE_STRING, perm_idx)
        elem.attrs.append(name_attr)
        # 插入到 application 元素之前 (标准顺序)
        app = self.find_child(manifest, "application")
        pos = manifest.children.index(app) if app is not None else len(manifest.children)
        manifest.children.insert(pos, elem)
        self._sync_resource_map()
        return True

    def add_provider(self, class_name: str, authorities: str) -> bool:
        """在 <application> 下添加 provider"""
        app = self.find_child(self.root, "application")
        if app is None:
            raise AxmlError("未找到 <application> 元素")

        for c in app.children:
            if self.get_string(c.name) == "provider":
                if self.find_attr_str(c, "name") == class_name:
                    return False

        ns_idx = self._android_ns_index()
        elem = XmlElem(NO_NS, self.ensure_string("provider"), 1)

        def attr(name: str, value: str):
            vi = self.ensure_string(value)
            return Attribute(ns_idx, self.ensure_string(name), vi, TYPE_STRING, vi)

        elem.attrs.append(attr("name", class_name))
        elem.attrs.append(attr("authorities", authorities))
        # exported=false (布尔值)
        false_idx = self.ensure_string("false")
        elem.attrs.append(Attribute(
            ns_idx, self.ensure_string("exported"),
            false_idx, TYPE_INT_BOOLEAN, 0))
        # enabled=true
        true_idx = self.ensure_string("true")
        elem.attrs.append(Attribute(
            ns_idx, self.ensure_string("enabled"),
            true_idx, TYPE_INT_BOOLEAN, 0xFFFFFFFF))

        # provider 放在 application 子元素末尾 (activity 之前也可, 顺序无碍)
        app.children.append(elem)
        self._sync_resource_map()
        return True

    def add_service(self, class_name: str,
                    foreground_service_type: Optional[str] = None) -> bool:
        """在 <application> 下添加 service。

        foreground_service_type: "specialUse" 等 (写入 INT_ENUM 枚举值)
        """
        app = self.find_child(self.root, "application")
        if app is None:
            raise AxmlError("未找到 <application> 元素")

        for c in app.children:
            if self.get_string(c.name) == "service":
                if self.find_attr_str(c, "name") == class_name:
                    return False

        ns_idx = self._android_ns_index()
        elem = XmlElem(NO_NS, self.ensure_string("service"), 1)

        def attr(name: str, value: str):
            vi = self.ensure_string(value)
            return Attribute(ns_idx, self.ensure_string(name), vi, TYPE_STRING, vi)

        elem.attrs.append(attr("name", class_name))

        # exported=false
        false_idx = self.ensure_string("false")
        elem.attrs.append(Attribute(
            ns_idx, self.ensure_string("exported"),
            false_idx, TYPE_INT_BOOLEAN, 0))

        if foreground_service_type:
            # 枚举值直接以 INT_TYPED_DATA 写入 (aapt 编译期行为)
            enum_val = FGS_TYPE_ENUMS.get(foreground_service_type, 0)
            fgs_idx = self.ensure_string("foregroundServiceType")
            raw_idx = self.ensure_string(foreground_service_type)
            elem.attrs.append(Attribute(
                ns_idx, fgs_idx, raw_idx, TYPE_INT_ENUM, enum_val))

        app.children.append(elem)
        self._sync_resource_map()
        return True

    def _android_ns_index(self) -> int:
        for prefix, uri in self._ns_prefixes:
            if self.get_string(uri) == ANDROID_NS:
                return uri
        # manifest 通常都有 android namespace; 没有则补一个字符串
        return self.ensure_string(ANDROID_NS)

    def _sync_resource_map(self) -> None:
        """确保 resource_ids 覆盖所有字符串"""
        self.ensure_attr_id(len(self.strings) - 1)

    # ---------- 序列化 ----------

    def serialize(self) -> bytes:
        # 计算字符串数据块
        str_data = bytearray()
        offsets = []
        for s in self.strings:
            offsets.append(len(str_data))
            # UTF-8 编码: ulen(varint) + blen(varint) + bytes + \0
            b = s.encode("utf-8")
            str_data += _uvarint(len(s))
            str_data += _uvarint(len(b))
            str_data += b
            str_data += b"\x00"

        str_count = len(self.strings)
        strings_start = 28 + str_count * 4  # header + offsets (styles 无)
        # 4 字节对齐字符串数据起始
        pad = (-strings_start) % 4
        strings_start += pad

        pool_size = strings_start + len(str_data)
        pool_size += (-pool_size) % 4

        # ---- 字符串池 chunk ----
        pool = bytearray()
        pool += struct.pack("<HHI", RES_STRING_POOL_TYPE, 28, pool_size)
        pool += struct.pack("<IIIII", str_count, 0, UTF8_FLAG, strings_start, 0)
        pool += struct.pack(f"<{str_count}I", *offsets)
        pool += b"\x00" * pad
        pool += str_data
        pool += b"\x00" * ((-len(pool)) % 4)
        assert len(pool) == pool_size, f"pool size mismatch {len(pool)} != {pool_size}"

        # ---- 资源映射 chunk ----
        res_map = bytearray()
        n_ids = len(self.resource_ids)
        rm_size = 8 + n_ids * 4
        res_map += struct.pack("<HHI", RES_XML_RESOURCE_MAP_TYPE, 8, rm_size)
        if n_ids:
            res_map += struct.pack(f"<{n_ids}I", *self.resource_ids)

        # ---- XML 树 chunks ----
        tree = bytearray()
        # namespace 声明 (在根元素前)
        ns_prefix_idx = self.ensure_string_prefix()
        seen_ns = False

        def emit(elem: XmlElem, depth: int):
            nonlocal seen_ns
            # START_ELEMENT
            n_attrs = len(elem.attrs)
            hdr_size = 16 + 20  # 基础 + 第一个属性偏移
            attr_start = 20
            chunk = bytearray()
            chunk += struct.pack("<HHI", RES_XML_START_ELEMENT_TYPE, 16, hdr_size + n_attrs * 20)
            chunk += struct.pack("<IIII", elem.line, 0xFFFFFFFF, elem.ns, elem.name)
            chunk += struct.pack("<HHHHHH", attr_start, 20, n_attrs,
                                 elem.id_index, elem.class_index, elem.style_index)
            for a in elem.attrs:
                chunk += struct.pack("<III", a.ns, a.name, a.raw_value)
                chunk += struct.pack("<HBBI", 8, 0, a.vtype, a.vdata)
            tree.extend(chunk)

            for c in elem.children:
                emit(c, depth + 1)

            # END_ELEMENT
            tree.extend(struct.pack("<HHI", RES_XML_END_ELEMENT_TYPE, 16, 24))
            tree.extend(struct.pack("<IIII", elem.line, 0xFFFFFFFF, elem.ns, elem.name))

        # 根元素前发射 namespace start
        if self._ns_prefixes:
            prefix, uri = self._ns_prefixes[0]
            tree.extend(struct.pack("<HHI", RES_XML_START_NAMESPACE_TYPE, 16, 24))
            tree.extend(struct.pack("<IIII", 1, 0xFFFFFFFF, prefix, uri))

        if self.root is not None:
            emit(self.root, 0)

        if self._ns_prefixes:
            prefix, uri = self._ns_prefixes[0]
            tree.extend(struct.pack("<HHI", RES_XML_END_NAMESPACE_TYPE, 16, 24))
            tree.extend(struct.pack("<IIII", 1, 0xFFFFFFFF, prefix, uri))

        # ---- 文件头 ----
        total = 8 + len(pool) + len(res_map) + len(tree)
        out = bytearray()
        out += struct.pack("<HHI", RES_XML_TYPE, 8, total)
        out += pool
        out += res_map
        out += tree
        return bytes(out)

    def ensure_string_prefix(self) -> int:
        """确保 android namespace 的 prefix 字符串存在, 返回其索引"""
        if self._ns_prefixes:
            return self._ns_prefixes[0][0]
        return self.ensure_string("android")


def _read_string(data: bytes, pos: int, is_utf8: bool) -> str:
    if is_utf8:
        ulen, p = _read_uvarint(data, pos)
        blen, p = _read_uvarint(data, p)
        return data[p:p + blen].decode("utf-8", errors="replace")
    else:
        ulen = struct.unpack_from("<H", data, pos)[0]
        p = pos + 2
        if ulen & 0x8000:  # 高位标志: 长度扩展
            ulen = ((ulen & 0x7FFF) << 16) | struct.unpack_from("<H", data, p)[0]
            p += 2
        return data[p:p + ulen * 2].decode("utf-16-le", errors="replace")


def _uvarint(n: int) -> bytes:
    """AXML UTF-8 字符串长度编码 (ResourceTypes.cpp encodeLength):
       len < 0x80 → 1 字节; 否则 2 字节 big-endian (0x80|len>>8, len&0xFF)"""
    if n < 0x80:
        return bytes([n])
    return bytes([(n >> 8) | 0x80, n & 0xFF])


def _read_uvarint(data: bytes, pos: int) -> Tuple[int, int]:
    """对应 _uvarint 的解码"""
    b = data[pos]
    pos += 1
    if b & 0x80:
        b2 = data[pos]
        pos += 1
        return ((b & 0x7F) << 8) | b2, pos
    return b, pos


# ---- 高层注入 API ----

def inject_esp_into_manifest(
    manifest_bytes: bytes,
    provider_class: str,
    authorities: str,
    service_class: Optional[str] = None,
    extra_permissions: Optional[List[str]] = None,
) -> Tuple[bytes, dict]:
    """
    注入 ESP 组件到 AndroidManifest.xml

    返回 (新 manifest 字节, 注入报告 dict)
    """
    doc = AxmlDocument.parse(manifest_bytes)
    report = {"permissions": [], "provider": False, "service": False, "package": None}

    pkg = doc.find_attr_str(doc.root, "package")
    report["package"] = pkg

    perms = list(extra_permissions or [
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.WAKE_LOCK",
        # startForegroundService 必需 (targetSdk 28+), 缺失抛 SecurityException
        "android.permission.FOREGROUND_SERVICE",
        # foregroundServiceType=specialUse 必需 (targetSdk 34+)
        "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
    ])
    for p in perms:
        if doc.add_uses_permission(p):
            report["permissions"].append(p)

    if doc.add_provider(provider_class, authorities):
        report["provider"] = True

    if service_class:
        if doc.add_service(service_class, foreground_service_type="specialUse"):
            report["service"] = True

    return doc.serialize(), report


# ---- 自测 ----

if __name__ == "__main__":
    import sys
    import zipfile

    path = sys.argv[1] if len(sys.argv) > 1 else None
    if path is None:
        print("用法: python3 axml_editor.py <apk 或 manifest 二进制>")
        sys.exit(1)

    if path.endswith(".apk"):
        with zipfile.ZipFile(path) as z:
            data = z.read("AndroidManifest.xml")
    else:
        with open(path, "rb") as f:
            data = f.read()

    doc = AxmlDocument.parse(data)
    print(f"解析成功: {len(doc.strings)} 字符串, {len(doc.resource_ids)} 资源ID")
    print(f"根元素: {doc.get_string(doc.root.name)}")
    app = doc.find_child(doc.root, "application")
    if app:
        print(f"application 子元素: {len(app.children)}")
        for c in app.children[:10]:
            print(f"  - {doc.get_string(c.name)}")

    # 往返测试
    out = doc.serialize()
    doc2 = AxmlDocument.parse(out)
    assert doc2.get_string(doc2.root.name) == doc.get_string(doc.root.name)
    print(f"往返序列化成功: {len(out)} 字节 (原 {len(data)} 字节)")
