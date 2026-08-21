#!/usr/bin/env python3
"""Find ALL config/settings files in the APK"""
import zipfile
z = zipfile.ZipFile('最原始安装包（官网安装包.apk', 'r')
results = []
for name in z.namelist():
    if any(k in name.lower() for k in ['config', 'setting', 'gamemode', 'version', 'revision', 'debug', 'cheat']):
        if not name.endswith('/'):
            info = z.getinfo(name)
            results.append(f'{name} ({info.file_size} bytes)')
with open('_config_files.txt', 'w') as f:
    f.write('\n'.join(results))
print(f'Found {len(results)} config files')
