#!/usr/bin/env python3
"""Build v37 APK from ORIGINAL APK (not v24 base)"""
import zipfile, shutil, os, hashlib

orig_apk = '10040714_com.tencent.tmgp.sgame_a4202103_11.3.1.1_QBcYZh.apk'
output_apk = 'APK_v37_clean_signal.apk'

# Read original APK structure
print(f"Reading original APK: {orig_apk}")
with zipfile.ZipFile(orig_apk, 'r') as zin:
    # Check what's in the APK
    names = zin.namelist()
    print(f"  Contains {len(names)} files")
    
    # Find libtprt.so entry
    has_tprt = any('libtprt.so' in n for n in names)
    print(f"  Has libtprt.so: {has_tprt}")
    
    if has_tprt:
        # Read original libtprt.so
        for n in names:
            if 'libtprt.so' in n and 'arm64' in n:
                print(f"  Found: {n}")
                orig_lib = zin.read(n)
                print(f"  Original size: {len(orig_lib)}, MD5: {hashlib.md5(orig_lib).hexdigest()}")
                break
    
    # Check resources.arsc compression
    for n in names:
        if n == 'resources.arsc':
            info = zin.getinfo(n)
            print(f"  resources.arsc: compress_type={info.compress_type} ({'STORED' if info.compress_type == 0 else 'DEFLATED'})")
    
    # Read our patched lib
    patched_path = 'APK_patched/lib/arm64-v8a/libtprt.so'
    with open(patched_path, 'rb') as f:
        patched_lib = f.read()
    print(f"  Patched size: {len(patched_lib)}, MD5: {hashlib.md5(patched_lib).hexdigest()}")
    
    # Build new APK
    print(f"\nBuilding {output_apk}...")
    with zipfile.ZipFile(output_apk, 'w', zipfile.ZIP_DEFLATED) as zout:
        for name in names:
            data = zin.read(name)
            if 'libtprt.so' in name and 'arm64' in name:
                data = patched_lib
                print(f"  Replaced: {name}")
            
            # Store resources.arsc uncompressed (API 30+ requirement)
            if name == 'resources.arsc':
                zout.writestr(name, data, compress_type=zipfile.ZIP_STORED)
            else:
                zout.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)

print(f"\n✓ APK built: {output_apk}")
# Get output size
print(f"  Size: {os.path.getsize(output_apk)} bytes")
