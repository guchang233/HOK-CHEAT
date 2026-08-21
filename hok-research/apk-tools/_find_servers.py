#!/usr/bin/env python3
"""Search for server URLs and config files in the APK."""
import zipfile
z = zipfile.ZipFile('最原始安装包（官网安装包.apk', 'r')
results = []

# Check INTERNET permission
xml = z.read('AndroidManifest.xml')
results.append(f'INTERNET permission: {b"INTERNET" in xml}')

# Search for server URLs in text/config files
for name in z.namelist():
    if not any(name.endswith(ext) for ext in ['.txt','.json','.xml','.config','.properties']):
        continue
    try:
        data = z.read(name)
        idx = data.find(b'http')
        while idx >= 0:
            end = data.find(b'"', idx)
            if end < 0: end = idx + 100
            url = data[idx:end].decode('ascii', errors='replace')
            if any(k in url for k in ['tencent','qq.com','game','api','server']):
                results.append(f'{name}: {url[:150]}')
            idx = data.find(b'http', idx + 1)
    except:
        pass

with open('_server_urls.txt', 'w', encoding='utf-8') as f:
    f.write('\n'.join(results))
print(f'Found {len(results)} results')
