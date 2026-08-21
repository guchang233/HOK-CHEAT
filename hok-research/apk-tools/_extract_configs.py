#!/usr/bin/env python3
import zipfile
z = zipfile.ZipFile('最原始安装包（官网安装包.apk', 'r')
for name in ['assets/Config/GameModeConfig.json', 'assets/Config/GameModeConfigConstraint.json']:
    d = z.read(name)
    fn = name.replace("/", "_")
    open(f'_{fn}', 'wb').write(d)
    print(f'Extracted {name} ({len(d)} bytes)')
