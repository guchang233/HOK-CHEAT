#!/usr/bin/env python3
"""Rebuild APK from v24 base, replacing only libtprt.so with v19 patched version.
Keeps resources.arsc uncompressed (required for API 30+)."""
import zipfile, shutil, os

base_apk = 'D:/ctf/APK_v24_aligned.apk'
patched_lib = 'D:/ctf/APK_patched/lib/arm64-v8a/libtprt.so'
output_unaligned = 'D:/ctf/APK_v25_unaligned.apk'

# Read patched lib
with open(patched_lib, 'rb') as f:
    patched_data = f.read()
print(f'Patched libtprt.so: {len(patched_data)} bytes, MD5: {__import__("hashlib").md5(patched_data).hexdigest()}')

# Build new APK preserving compression of all entries except libtprt.so (replaced)
with zipfile.ZipFile(base_apk, 'r') as zin:
    with zipfile.ZipFile(output_unaligned, 'w', zipfile.ZIP_STORED) as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            if item.filename == 'lib/arm64-v8a/libtprt.so':
                data = patched_data
                print(f'Replaced {item.filename} ({len(patched_data)} bytes)')

            # Keep original compression for resources.arsc (must be STORED for API30+)
            # and preserve compression for everything else
            if item.filename == 'resources.arsc':
                # Must be uncompressed for API 30+
                zout.writestr(item, data, compress_type=zipfile.ZIP_STORED)
            else:
                # Preserve original compression type
                zout.writestr(item, data, compress_type=item.compress_type)

print(f'APK written: {output_unaligned}')
