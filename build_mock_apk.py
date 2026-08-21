#!/usr/bin/env python3
"""构造一个模仿真实王者荣耀 APK 结构与配置的测试样本（用于黑盒管线验证）。"""
import json
import os
import zipfile

def make_game_so_data():
    """构造一个模拟 libGameCore.so 的二进制，内部散落多处服务器地址与腾讯域名"""
    # 填充一些随机数据作为背景
    data = bytearray(os.urandom(500_000))

    # 在随机位置植入典型的服务器/域名字符串
    injections = [
        (b'tcp://battle.qq.com:6645',              0x10000),
        (b'tcp://match.qq.com:5555',               0x15000),
        (b'https://sgame_gateway.qq.com:8080/serverlist', 0x20000),
        (b'https://dliir.path.qq.com/hok/res/',    0x25000),
        (b'http://hotupdate.qq.com/sg/',           0x30000),
        (b'battle.qq.com\x00',                      0x35000),
        (b'match.qq.com\x00',                       0x35100),
        (b'sgame_gateway.qq.com\x00',              0x35200),
        (b'dliir.path.qq.com\x00',                 0x35300),
        (b'version.qq.com\x00',                     0x35400),
        (b'api.tencent.com\x00',                   0x35500),
        (b'social.qq.com\x00',                     0x35600),
        (b'cdndownload.qq.com\x00',                0x35700),
        (b'cfs.qq.com\x00',                        0x35800),
    ]
    for s, off in injections:
        end = off + len(s)
        if end <= len(data):
            data[off:end] = s
    return bytes(data)


def make_tprt_so_data():
    """模拟 libtprt.so (ACE 反作弊)，内含少量域名"""
    data = bytearray(os.urandom(200_000))
    data[0x5000:0x5000+32] = b'secure.qq.com\x00'
    data[0x5100:0x5100+24] = b'tpp.qq.com\x00'
    data[0x5200:0x5200+30] = b'https://tpp.qq.com/check\x00'
    return bytes(data)


def main():
    out = 'mock_game.apk'
    cfg = {
        'serverlist': {
            'gateway': 'https://sgame_gateway.qq.com:8080/serverlist',
            'battle_tcp': 'tcp://battle.qq.com:6645',
            'match_tcp': 'tcp://match.qq.com:5555',
            'hotupdate': 'https://dliir.path.qq.com/hok/res/',
            'version': 'https://version.qq.com/check',
        },
        'timeout': 30,
        'version': '11.4.1.1',
    }
    ini = (
        '[General]\n'
        'version=11.4.1.1\n'
        'channel=official\n'
        'check_url=https://version.qq.com/check\n'
        'hot_update=https://dliir.path.qq.com/hok/res/\n'
    )
    manifest_xml = (
        b'\x03\x00\x08\x00' +  # binary AXML magic (not fully valid but OK for our zip)
        b'package="com.tencent.tmgp.sgame"\n'
        b'android:versionCode="114131"\n'
        b'android:versionName="11.4.1.36"\n'
    )
    dex_head = b'dex\n035\x00' + os.urandom(4000)

    with zipfile.ZipFile(out, 'w') as zf:
        zf.writestr('AndroidManifest.xml', manifest_xml, compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr('resources.arsc', b'\x02\x00\x0c\x00' + os.urandom(800),
                    compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr('classes.dex', dex_head, compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr('classes2.dex', dex_head, compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr('classes3.dex', dex_head[:2000], compress_type=zipfile.ZIP_DEFLATED)

        # 配置文件 (assets)
        zf.writestr('assets/Config/ServerConfig.json', json.dumps(cfg, indent=2),
                    compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr('assets/version.ini', ini, compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr('assets/Config/GameModeConfig.json', '{"mode": "ranked", "map": "summoners_rift"}',
                    compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr('assets/webview/index.html',
                    b'<html><body><iframe src="https://sgame.qq.com/activity"></iframe></body></html>',
                    compress_type=zipfile.ZIP_DEFLATED)

        # 模拟 native .so 库
        zf.writestr('lib/arm64-v8a/libGameCore.so', make_game_so_data(),
                    compress_type=zipfile.ZIP_STORED)
        zf.writestr('lib/arm64-v8a/libtprt.so', make_tprt_so_data(),
                    compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr('lib/arm64-v8a/libil2cpp.so', os.urandom(3000),
                    compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr('lib/arm64-v8a/libtersafe.so', os.urandom(2000),
                    compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr('lib/arm64-v8a/libunity.so', os.urandom(1500),
                    compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr('lib/armeabi-v7a/libGameCore.so', make_game_so_data()[:20000],
                    compress_type=zipfile.ZIP_DEFLATED)  # 小一点的 32-bit 版本
        zf.writestr('lib/x86_64/libGameCore.so', make_game_so_data()[:15000],
                    compress_type=zipfile.ZIP_DEFLATED)  # 模拟器版本

        # 其他常见文件
        zf.writestr('assets/META-INF/AIR/application.xml',
                    b'<application xmlns="http://ns.adobe.com/air/application/24.0"></application>',
                    compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr('res/drawable/logo.png', os.urandom(100), compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr('lib/arm64-v8a/libapollo.so',
                    b'str_https://apollo.qq.com/config' + b'\x00' * 100 + os.urandom(500),
                    compress_type=zipfile.ZIP_DEFLATED)

        # 旧签名 (模拟官方签名)
        zf.writestr('META-INF/MANIFEST.MF',
                    f'Manifest-Version: 1.0\n'
                    f'Name: lib/arm64-v8a/libGameCore.so\n'
                    f'SHA1-Digest: {os.urandom(20).hex()}\n'.encode(),
                    compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr('META-INF/CERT.SF',
                    b'Signature-Version: 1.0\n',
                    compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr('META-INF/CERT.RSA', os.urandom(512),
                    compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr('META-INF/CERT.EC', os.urandom(256),
                    compress_type=zipfile.ZIP_DEFLATED)

    size = os.path.getsize(out)
    print(f'[OK] 模拟官方 APK 已生成: {out} ({size:,} bytes)')
    print('     包含: 18 条目, 5 个 .so, 多个配置文件, 旧签名')


if __name__ == '__main__':
    main()
