# TrueVision — 王者荣耀手游内存安全研究与 ESP 透视实现

> **A full-stack mobile game-security research project**: kernel-level cross-process memory reading, anti-cheat (Tencent ACE / libtersafe) reverse engineering, IL2CPP/native engine RE, and a real-time ESP overlay for *Honor of Kings* (王者荣耀 / sgame v11.3.1.x) — documented end-to-end, including every dead end.

<p align="center">
  <img alt="platform" src="https://img.shields.io/badge/platform-Android%20arm64-3ddc84">
  <img alt="kernel" src="https://img.shields.io/badge/kernel-KernelPatch%20KPM-orange">
  <img alt="lang" src="https://img.shields.io/badge/lang-C%20%7C%20Zig%20%7C%20Kotlin%20%7C%20Python-blue">
  <img alt="status" src="https://img.shields.io/badge/status-research%20%2F%20educational-red">
</p>

---

## ⚠️ 免责声明 / Disclaimer

**中文**：本仓库是一个**安全研究 / 逆向工程教育项目**，完整记录了对一款主流移动 MOBA 游戏（王者荣耀）的内存布局、反作弊机制（腾讯 ACE / libtersafe / libtprt）、内核态读取原语、以及游戏引擎数据流的逆向分析全过程。其价值在于**反作弊对抗研究、Android 内核安全（KernelPatch/KPM）、IL2CPP/Unity 逆向方法论、以及一个真实项目的研究日志（含大量被推翻的弯路）**。

- ❌ **请勿**用于在任何在线对战游戏中作弊。在真实对局中使用此类工具违反游戏服务条款，会导致封号，并损害其他玩家体验。
- ❌ 本仓库**不包含**任何第三方专有二进制（游戏 .so / APK / 商业外挂二进制 / 反作弊库），只包含**作者自己的代码**与**逆向分析笔记/反编译产物**。
- ✅ 适用于：安全研究人员、反作弊工程师、内核/移动安全学习者、逆向工程方法论研究。

**English**: This is a **security-research / reverse-engineering educational project** documenting the full process of analysing a major mobile MOBA's memory layout, its anti-cheat stack (Tencent ACE), kernel-mode read primitives, and engine dataflow. **Do not use it to cheat in live online games** — doing so violates the game's Terms of Service and harms other players. No proprietary third-party binaries are redistributed here.

Use at your own risk and within the law of your jurisdiction.

---

## 这是什么 / What this project is

王者荣耀（内部代号 *sgame*）是腾讯的一款帧同步 MOBA。它使用 **Unity / IL2CPP** 渲染层 + **native `libGameCore.so`** 模拟层，并由腾讯 **ACE（`libtersafe.so` / `libtprt.so`）** 反作弊保护。

本项目的研究目标（一个经典的游戏安全命题）是：**在不触发反作弊降级/封号的前提下，从游戏进程内存中读取所有英雄（含迷雾/草丛中的敌方）的实时世界坐标，并实时显示出来。**

围绕这个目标，仓库沉淀了：

1. **TrueVision** — 自研的完整三段式实现：内核态 KPM 提供跨进程读 ABI → 原生 reader（C/Zig 交叉编译）解析 actor 链 → Android 悬浮窗 / PC 小地图实时显示。
2. **内核基础设施** — 基于 [KernelPatch](https://github.com/bmax121/KernelPatch) 的 KPM 内核模块，在 LTO/BOLT/PGO 内核（OnePlus PJF110, Android 14, 6.1.75）上跑通跨进程内存读取。
3. **反作弊与引擎逆向** — 对 `libtersafe`/`libtprt`（ACE）、`libunity`、`libGameCore`、以及多个商业外挂驱动的极限逆向（Hex-Rays 反编译 + 调用链分析 + 文档）。
4. **完整研究日志** — `research-journal/` 下 **109 篇** 带时间戳、带概率标注的研究记录，真实还原了从错误假设（IL2CPP 路线、server-side FOW、LKM 路线）到一次次突破（KPatch-Next 复活 KPM、4-follow 坐标链、FakeFow 客户端持有真值、HW-BP 在 BOLT 内核整条死亡）的全过程。

---

## 系统架构 / Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  Android 设备 (rooted: KernelPatch + KernelSU/SukiSU)          │
│                                                                │
│  王者荣耀 sgame v11.3.1.x  (正常运行, ACE 反作弊完整加载)        │
│       │  (内核态跨进程读, ACE 看不到 — 无 syscall 经过)          │
│       ▼                                                        │
│  KPM: sgame-fakemem.kpm   (KernelPatch 内核模块)               │
│    · supercall(45) ABI                                         │
│    · ctl0 'r <pid> <addr> <len>'  → hex                       │
│    · sgame_tgid=0  (绝不干预 sgame 的 syscall)                 │
│       │                                                        │
│       ▼                                                        │
│  tv_reader  (静态 ELF, zig cross-compile, /data/local/tmp)     │
│    · resolve_anon_bss(libGameCore.so) → gc_bss                 │
│    · actor list chain + 4-follow position chain                │
│    · 每帧 10 个 hero (hero_id, camp, world_x, world_z, hp)     │
│    · 雾中追踪: thin-sync proxy / DisplayInfoData / FakeFow      │
│       │  TCP 47291  (TvFrame 协议)                             │
│       ▼                                                        │
│  Android Overlay App (Kotlin)  ──or──  PC minimap.py (Python)  │
│    · 全屏透明 SurfaceControl / Canvas 小地图                    │
│    · 红蓝点 + 自动定向 + 死亡检测 + 雾中续接                     │
└──────────────────────────────────────────────────────────────┘
```

设计细节见 [`truevision/docs/DESIGN.md`](truevision/docs/DESIGN.md) 与 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

---

## 仓库结构 / Repository layout

| 目录 | 内容 |
|------|------|
| [`truevision/`](truevision/) | **主项目**。`src/` 原生 reader (C/Zig)、`android/` Kotlin 悬浮窗 App、`tools/` PC 端 Python 小地图与诊断、`scripts/` RE 脚本、`docs/` 设计文档 + offset 表 + baba RE 报告、`kpm/` KPM 源码归档 |
| [`kernel/`](kernel/) | **内核层**。`KernelPatch/` 框架源码、`kpatch/` sgame 专用 KPM 工作区与扫描脚本、`kpatch-next-module/` 可刷入的 Magisk/KSU 模块 |
| [`zygisk-esp/`](zygisk-esp/) | **替代方案**。基于 Zygisk + IL2CppDumper 的 ESP 注入模块（C++）+ 悬浮窗 App（Java） |
| [`re/`](re/) | **逆向产物**。`libtersafe/`（ACE 反作弊）、`libunity/`、`libgamecore/`、`baba-525/`（商业外挂）、`drivers/`（商业驱动）的反编译笔记与分析脚本 |
| [`research/`](research/) | **研究工具集**。ACE SDK 文档分析、IL2CPP 离线逆向、注入器（TInjector）、内存检测、HoK 强化学习环境集成、外部 reader PoC 等 |
| [`research-journal/`](research-journal/) | **★ 研究日志**。109 篇带时间戳/概率标注的研究记录，含 [`MEMORY.md`](research-journal/MEMORY.md) 主索引 — 这是本项目最有价值的部分之一 |
| [`apk-tools/`](apk-tools/) | APK 重打包/patch 工具链（smali patch、IDA 脚本、Frida 脚本、SSH 部署脚本，250+ 个） |
| [`tools/`](tools/) | EDL 刷机/boot 镜像处理工具 |
| [`docs/`](docs/) | 顶层架构与上手文档 |

> **未包含的内容（有意排除）**：游戏专有二进制（`libGameCore.so` / `libunity.so` / `libil2cpp.so` / `libtersafe.so` / `global-metadata.dat`）、整包修改版 APK、IDA 数据库（`.i64`）、反汇编 dump（`.asm`）、32 万个逐函数自动导出的 `.c`、启动镜像、签名密钥。这些要么是腾讯专有资产、要么可由原二进制再生、要么过大。详见各子目录说明与 [`.gitignore`](.gitignore)。

---

## 研究历程 / The research journey

这个项目最大的价值不在最终代码，而在**真实的研究过程**——包括所有被推翻的假设。完整时间线见 [`research-journal/MEMORY.md`](research-journal/MEMORY.md)，这里是骨架：

**阶段一 · 找错层级（2026-05-22 → 05-26）**
- ❌ IL2CPP 路线：以为真实坐标只在 IL2CPP 托管层 → 错。`baba` 商业外挂不用 IL2CPP。
- ❌ LKM 路线：stock loader / paradise.ko / 自编译内核全部黑屏（OPlus 私有 `sig_protect`）。
- ❌ "server-side FOW"：以为迷雾是服务端裁剪 → 归因错误。
- ✅ **大修正**：数据找错了层级——OOR（越界视野）真实数据在 **native `libGameCore.so:.bss`**，不在 IL2CPP。

**阶段二 · 基础设施复活（2026-05-26 → 05-28）**
- ✅ **KPatch-Next 复活 KPM**：在 PJF110 的 LTO 内核上跑通 KernelPatch，所有旧 KPM 死路翻案。
- ✅ **反检测真相**：ACE 闪退根因是读 `/proc/maps` 暴露 zygisk 痕迹（不是读得多）→ fake maps + 500 次读零崩溃。
- ✅ **极限逆向 ACE**：`libtersafe`+`libtprt` 全 35383 函数 Hex-Rays（OLLVM + 腾讯 VM + 自杀 `kill(getpid,9)` + inline-hook 自检）。

**阶段三 · 透视跑通（2026-05-28 → 05-29）**
- ✅ **4-follow 坐标链**：`actor + 0x268 → +0x10 → +0x00 → +0x60`，推翻 baba 文档（它把 hp 标成了 pos）。
- ✅ **帧同步认知**：王者是帧同步架构 → 每个客户端必须本地模拟全部 10 人 → **全图真值必然在客户端，腾讯只能藏不能删**（解释了为何十几年每年都有透视）。
- ✅ **DisplayInfoData[] 旁路**：迷雾中的敌人在 DD 数组里仍有真实实时坐标。

**阶段四 · 迷雾最后一公里（2026-05-29 → 06-06）**
- ⚠️ **HW-BP 整条死亡**：在 BOLT/PGO/LTO 内核上，arming 任何 perf 硬件断点都立即 wedge 整机 → 写拦截路线彻底放弃（牺牲进程安全闸 2 次都拦住了，sgame 全程没碰）。
- ✅ **FakeFow 决定性突破**：证明客户端 100% 持有所有雾中敌人真位，迷雾是**客户端本地伪造**（`ViewManager::ModifyEnemyInvisibleHeroPosition`），零服务端 AOI。
- ✅ **thin-sync proxy 追踪**：雾里"移动不开火"的敌人可解——绑定活跃的瘦同步 proxy（own=0）而非冻结的身份 proxy。
- ✅ **camp-agnostic 追踪 + 红蓝翻转**：拆掉 is_enemy 门控，两队都追，位置永远对，阵营退化成纯颜色标签。

> 方法论沉淀：见 `research-journal/feedback-*.md`——例如"标永久死路前必须先 GitHub 搜新 fork"、"不可逆操作前强制 dry-run + 双备份 + sha256"、"banner 版本字串 ≠ 实际 runtime config"。

---

## 技术栈 / Tech stack

- **内核**：KernelPatch（KPM 内核模块，supercall ABI，inline/syscall hook）；KernelSU / SukiSU；Magisk 模块打包。
- **原生**：C + **Zig** 交叉编译（静态 arm64 ELF）；`process_vm_readv` 故障安全读；ARM64 HW 断点研究。
- **Android**：Kotlin 悬浮窗（SurfaceControl / `TYPE_APPLICATION_OVERLAY`，Canvas 渲染）；Zygisk（C++）注入替代方案。
- **逆向**：IDA Pro 9.1 + Hex-Rays、radare2、Frida、IL2CppDumper / Zygisk-Il2CppDumper、Unicorn。
- **PC 工具**：Python（tkinter 小地图、actor 解析、稳定性测试、fake C2 server）。
- **设备**：OnePlus PJF110（Android 14, kernel 6.1.75-android14-11-o，ColorOS）。

---

## 构建与运行 / Build & run

> 需要一台已 root（KernelPatch + KernelSU/SukiSU）的 arm64 Android 设备。详细步骤见各子目录 README 与 [`truevision/docs/DESIGN.md`](truevision/docs/DESIGN.md) 第 4 节。

**1. 编译原生 reader（Zig）**
```bash
cd truevision/src
make            # zig cc 交叉编译 → tv_reader (static arm64 ELF)
```

**2. 构建 KPM**（本地无工具链时走 GitHub CI，见 `kernel/` 说明）

**3. 加载 + 运行**
```bash
adb push tv_reader /data/local/tmp/
adb shell "su -c '/data/adb/modules/KPatch-Next/bin/kpatch kpm load /data/local/tmp/sgame-fakemem.kpm'"
adb shell "su -c 'chmod 755 /data/local/tmp/tv_reader; nohup /data/local/tmp/tv_reader 500 -q -s &'"
adb forward tcp:47291 tcp:47291
python3 truevision/tools/minimap.py        # PC 端实时小地图
```

**4. Android 悬浮窗 App**
```bash
cd truevision/android
./gradlew assembleRelease   # 或用 .github/workflows/build.yml 的 CI 产物
```

---

## 许可 / License

- 作者**自有代码**（TrueVision reader / Android App / PC 工具 / 研究脚本 / 研究日志）采用 [MIT License](LICENSE)。
- `kernel/KernelPatch/` 为上游 [KernelPatch](https://github.com/bmax121/KernelPatch) 源码，遵循其自带的 **GPL-2.0** 许可（见该目录 `LICENSE`）。
- `zygisk-esp/` 基于上游 Zygisk-Il2CppDumper，遵循其自带许可。
- `re/`、`research/` 下的逆向笔记与反编译产物仅用于研究/教育目的，相关第三方代码版权归各自所有者。

详见 [`DISCLAIMER.md`](DISCLAIMER.md)。

---

## 致谢 / Credits

- [bmax121/KernelPatch](https://github.com/bmax121/KernelPatch) — 内核补丁框架
- [Perfare/Il2CppDumper](https://github.com/Perfare/Il2CppDumper) & [Perfare/Zygisk-Il2CppDumper](https://github.com/Perfare/Zygisk-Il2CppDumper)
- KernelSU / SukiSU / Zygisk Next 社区
- Tencent *Honor of Kings* RL 环境 [tencent-ailab/hok_env](https://github.com/tencent-ailab/hok_env)（研究参考）
