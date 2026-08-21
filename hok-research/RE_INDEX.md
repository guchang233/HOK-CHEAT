# baba 5.25 完整 RE 索引

**日期**: 2026-05-27
**目标**: 把闭源王者荣耀作弊器 baba 5.25 完全逆向 (为开源做准备, 复刻分开做)
**输入**: `C:\Users\lsc\Documents\xwechat_files\...\baba无敌稳定525.zip` (32.4 MB)
**产出**: 14 份 RE 报告 + 7643 行 markdown + 45MB binary artifacts
**完成度**: 16/17 (1 项需设备动态 dump, 暂搁置)

---

## 0. 一句话总览

baba 5.25 = `baba无敌稳定版本.sh` 24.86MB 自解密 wrapper + `inner_reader.elf` 24MB AArch64 static binary, 用 8 个商业 driver (任一可用) 跨进程读 sgame native engine state, 用 Dear ImGui + OpenGL ES + SurfaceComposerClient (setTrustedOverlay) 画 ESP overlay, 用 /dev/uinput multi-touch protocol B 注入触屏, 在 KPM driver 配合下 `mount --bind /dev/null` 物理屏蔽 libtersafe.so (Tencent ACE)。

完全闭源, 无 frida 干扰可正常解密。

---

## 1. 报告清单 (按主题)

### 攻击链 (sgame 数据怎么读出来)
| # | 报告 | 行数 | 内容 |
|---|---|---|---|
| 1 | `init-and-discovery.md` | 674 | PID poll, libGameCore base, ":bss" 解析 (★ 重写认知), 6 步环境准备 |
| 2 | `offsets-db.md` | 599 | sgame v11.3.1.1 完整 offset 表 (3 处 hex 修正), 33 actor 字段, 14 chain |
| 3 | `main-loop-deep.md` | 1173 | sub_4cf9f8 第 2900-8545 行: 3 大 actor_type 分支 + 16 ESP draw call + 3 触屏注入 + virtual joystick + kill feed |
| 4 | `backend-mem-rw.md` | 635 | 9 个 mem RW backend (Paradise/TwT/DitPro KPM&KO/kma/Rt Hook&Dev) |

### Driver 层 (kernel module)
| # | 报告 | 行数 | 内容 |
|---|---|---|---|
| 5 | `kernel-drivers.md` | 644 | 22 个 rt .ko + 7 个 LQ .ko 提取, AES-CBC dropper 解密, ":离" UTF-8 boundary 解析 |
| 6 | `rt-driver-6.1.md` | 983 | 9 个 IOCTL CMD (0x259-0x261), TTBR0/TTBR1 swap mem R/W, kallsyms HW BP, list_del 模块隐藏 |

### Overlay 层 (ESP 渲染)
| # | 报告 | 行数 | 内容 |
|---|---|---|---|
| 7 | `opengl-render.md` | 439 | Dear ImGui 1.91.6 + ImGui_ImplOpenGL3 + Android backend, 8 套 GLSL shader (原版), stb_truetype 字体 |
| 8 | `surface-composer.md` | 326 | dlopen libgui.so, 6 个 createSurface ABI variants (Android 8/9/10/11+), setTrustedOverlay 绕 screen-capture |
| 9 | `uinput-injection.md` | 427 | /dev/uinput multi-touch protocol B, ≤20 触点, 设备名随机化, ABS_MT_POSITION_X/Y/TRACKING_ID |

### 反检测
| # | 报告 | 行数 | 内容 |
|---|---|---|---|
| 10 | `anti-debug.md` | 572 | 5 stub 密码学 gate (错任一 byte → ChaCha20 key 错 → inflate 失败 → 静默退出), inner 无 runtime check |
| 11 | `anti-ace.md` | 723 | mount --bind /dev/null over libtersafe.so, 4 个 /dev/virtpipe-* IPC, SurfaceFlinger DIRECT, baba 外部进程不注入 |

### 二级数据
| # | 报告 | 行数 | 内容 |
|---|---|---|---|
| 12 | `blob-10mb.md` | 176 | ★ 推翻: 不是加密 blob, 是 524 张 PNG 图标 14MB (英雄头像/技能/buff/Re:Zero 蕾姆 splash) |
| 13 | `functions-overview.md` | 247 | 3442 真函数 (r2 严格计数, vs IDA 10771 含 thunks), 8 商业驱动 dispatcher, C2 IP 45.207.196.6 写死 |
| 14 | `baba-data-dir.md` | ~500 | ★ /data/.BABA配置/ 路径 + 22+ .ini 清单 + C2 协议完整 RE (POST wy.llua.cn) + 加密 key `Th0m3Lu@_M@st3r_S3cr3t_2025` |
| 15 | (本文档) `INDEX.md` | -- | 主索引 |

总: **~8100 行 markdown** + **45MB binary artifacts**

---

## 2. 核心架构 (RE 后总结)

```
┌─────────────────────────────────────────────────────────────────┐
│  baba_reader.elf (24.86 MB, ELF 252KB stub + 24.6MB payload)   │
│  ──────────────────────────────────────────────────────────    │
│  Stub @ 0x1120c:                                                │
│    5 anti-debug stub gen 1 byte each → v129 = 5-byte XOR        │
│    v129 XOR'd into 32-byte ChaCha20 key                         │
│    ChaCha20 decrypt + zlib inflate                              │
│    output → memfd 47171 (24MB inner_reader.elf)                 │
│    fexecve / dlopen 启动 inner_reader                           │
└─────────────────────────────────────────────────────────────────┘
                              ↓ (memfd)
┌─────────────────────────────────────────────────────────────────┐
│  inner_reader.elf (24 MB ARM64 static-linked, 3442 fn)         │
│  ──────────────────────────────────────────────────────────    │
│                                                                  │
│  Phase A (sub_4CF170): 拿 sgame                                  │
│   1. sub_4FBEB0: popen("pidof com.tencent.tmgp.sgame") 5s poll  │
│   2. sub_4CF230 (":bss" parser):                                │
│      - 扫 /proc/<pid>/maps                                       │
│      - 找 [anon:.bss] 标签行 (Android 8+ linker64)              │
│      - 拿 libGameCore.bss base + libil2cpp.bss base             │
│   3. sub_4B0DAC: 6 步环境准备                                    │
│      - 部 /dev/virtpipe-{sec,render,codec,common-yyb} (4 FIFO) │
│      - chcon (借 /dev/null context)                              │
│      - 拉起 sgame                                                │
│      - ★ mount --bind /dev/null over libtersafe.so (反 ACE!)    │
│      - 19s 倒计时等用户进大厅                                    │
│   4. sub_4FF170: probe 5 个 backend (case [5,0,7,16,17]) 试 mem rw│
│                                                                  │
│  Phase B (sub_4cf9f8, 8545 行 main loop): 读 actor 数据            │
│   1. read libGameCore.bss + 0x2844 = is_in_battle               │
│   2. read libGameCore.bss + 0x17BB58 → +0x238 = actor list base │
│   3. iterate 10 actor slot (stride 0x18):                        │
│      - actor + 0x50 = type (HERO=257, NPC=169, Tower=225 etc)    │
│      - actor + 0x5C = camp (1=blue, 2=red)                       │
│      - actor + 0x188 → +0xA8 = 16 bytes pos (X_int32 + Z_int32) │
│      - 多 chain RE 出来 (HP, skill CD, hero_id, buff)            │
│   4. read libil2cpp.bss + 0x53D230 chain → 64 bytes MVP matrix  │
│   5. world_to_screen 用 MVP 简化公式                            │
│                                                                  │
│  Phase C (Dear ImGui 1.91.6): 画 ESP                            │
│   - dlopen /system/lib64/libgui.so + libutils.so                │
│   - SurfaceComposerClient::mirrorSurface + setTrustedOverlay     │
│   - 6 个 createSurface ABI variants (Android 8/9/10/11+)         │
│   - ImGui_ImplOpenGL3 + ImGui_ImplAndroid                        │
│   - 16 个 baba 自己的 draw call (line, rect, circle, text)       │
│   - 524 张 PNG 图标 (英雄/技能/buff) 从 .data 区拼出           │
│                                                                  │
│  Phase D (sub_4F4164/4288/4370): auto-skill 触屏注入             │
│   - /dev/uinput multi-touch protocol B                           │
│   - 设备名随机化 (避免 fingerprint)                              │
│   - 注入 BTN_TOUCH + ABS_MT_POSITION_X/Y                        │
│   - 3 条独立路径 (水晶/队友救援/野怪 buff) + virtual joystick     │
│                                                                  │
│  Backend mem RW (sub_4F2304 dispatcher, 9 case):                │
│   - case 6/10/14: Paradise (anon_inode fd, MOVK+BR 混淆 CMD)    │
│   - case 7: TwT_driver + /proc/PID/mem (syscall 142)            │
│   - case 12: DitPro_KPM (syscall 18 hijack lookup_dcookie)       │
│   - case 13: kma KPM (license ORANGINECLJP7MCSLA2XJSUHDJQBU5ZL)  │
│   - case 15: DitPro_KO (ioctl CMDs 3/5/601/...)                  │
│   - case 16: Rt Hook (ioctl + 26-byte XOR)                       │
│   - case 17: Rt Dev (ioctl 同 16 无 XOR)                         │
│                                                                  │
│  C2 (sub_4FC258): 45.207.196.6 写死 IP, 远程 license/heartbeat  │
└─────────────────────────────────────────────────────────────────┘
                              ↓ (kernel)
┌─────────────────────────────────────────────────────────────────┐
│  rt_passcheck_6.1.ko (kernel module, 6.1.112 GKI)              │
│  ──────────────────────────────────────────────────────────    │
│  install_hook(0x1d) → sys_call_table[__NR_ioctl] = hook_ioctl   │
│  hook_ioctl 9 个 CMD:                                            │
│   - 0x259 read_process_memory(pid, addr, dst, size)             │
│   - 0x25A write_process_memory(pid, addr, src, size)            │
│   - 0x25B get_module_base(pid, name, len)                       │
│   - 0x25C passthrough (KCFI check)                              │
│   - 0x25D list_del task+0x6a0 (process unhide)                  │
│   - 0x25E unhook                                                 │
│   - 0x25F list_add task+0x6a0                                   │
│   - 0x260 HW BP get hits                                         │
│   - 0x261 HW BP control                                          │
│                                                                  │
│  mem R/W: TTBR0/TTBR1 swap + PAN toggle + pgtable walk + vmap   │
│           (NOT access_process_vm, NOT get_user_pages — 自定义)  │
│                                                                  │
│  HW BP: kallsyms 动态查 register_user_hw_breakpoint (绕 GPL 限制)│
│                                                                  │
│  模块隐藏: list_del + kobject_del + sysfs_remove_link + rb_erase│
└─────────────────────────────────────────────────────────────────┘
```

---

## 3. ★ 推翻 / 修正的关键认知

1. **`:bss` 解析**: 不解析 ELF section header, **也不 hardcode `+0x4E9A000`**。baba 扫 `/proc/PID/maps` 找 `[anon:.bss]` 标签行 (Android 8+ linker64 给 .bss anon mapping 打的 `PR_SET_VMA_ANON_NAME`)。自适应所有 sgame 版本, **不依赖固定 offset**。
   → 推翻 [[sgame-baba-attack-chain-2026-05-26]] 的"hardcode +0x4E9A000"暗示

2. **10MB "加密 blob"**: 不是加密, 是 **524 张明文 PNG 图标** 拼接 14MB (英雄头像 / 技能 / buff / Re:Zero 蕾姆 splash, 作者 Adobe Photoshop 25.11 编辑)。Entropy 7.99 是 PNG 内部 zlib + filter 特征, 不是加密。
   → 推翻 [[sgame-baba-inner-dumped-2026-05-26]] "10MB 加密 blob 估计是 sgame offsets database"

3. **3 处 hex offset 错** (memory + OFFSETS.md 都中招):
   - `+0x17BF8` ❌ → `+0x17BB8` ✓ (dec 97208)
   - `+0x37AB80` ❌ → `+0x37AB00` ✓ (dec 3648256)
   - `+0x53D930` ❌ → `+0x53D230` ✓ (dec 5493296)
   错误源: 老 IDA python 脚本 hex 算错, 但 decimal 是 ground truth, hex 重算后对。

4. **anti-ACE 不靠 fake /proc/maps**: baba 主招是 `mount --bind /dev/null over libtersafe.so` + Surface DIRECT, **不做** maps fake, hide_proc kallsyms hook, sgame inject。我们之前 KPM v17 fake maps 不是必需。
   → 修正 [[sgame-budget-unlock-2026-05-26]] 的"ACE detection 在读 maps zygisk 痕迹"判断 (那是 zygisk 路径的事; baba 走外部路径不用)

5. **8 个商业 driver** 不是 7 个: 比之前 [[sgame-commercial-drivers-RE-2026-05-26]] 多 1 个 TwT_driver (case 7, syscall 142 加载) + kma KPM (case 13, ORANGINE... license key)

6. **GUI 框架是 Dear ImGui 1.91.6** + ImGui_ImplOpenGL3, 不是自己写的 OpenGL 代码。8 套 GLSL shader 是 ImGui 原版没改。imgui_impl_vulkan 编译进去了但 runtime 不走 (运行时只走 GL 路径)。

7. **inner_reader.elf 几乎无反调试** (TracerPid/SIGTRAP/frida 字符串全无)。反调试整体设计是**密码学 gate** 在 outer stub (5 字节 XOR 错任意 1 byte 就 inflate 失败), 不是"运行时检查 + 退出"。

8. **rt 5.10/5.15 .ko vermagic 嵌 `离` UTF-8** = Telegram 频道 watermark, 不是 binary corruption。

9. **LQ 驱动 AES-CBC dropper** key/IV 都 RE 出来了:
   - key = sha256(__a || __s)
   - IV = sha256(__a || __s || "iv")[:32]

10. **rt 6.1 mem R/W 用 TTBR0/TTBR1 swap + PAN toggle**, 不是 `access_process_vm`, 不是 `get_user_pages`。完全自定义低层路径。

11. **真 C2 host 是 `wy.llua.cn`, 不是 `45.207.196.6`** (后者是 inner_reader.elf 里 legacy 字符串)。实测捕获 POST /v2/a6961571bc335a0f3197e2750c8b79a9 请求, body = b64(b64(56-byte AES/DES encrypted))。加密 key 明文找到: `Th0m3Lu@_M@st3r_S3cr3t_2025` + plaintext handshake `session_establishment_2025`。

12. **配置目录是 `/data/.BABA配置/`** (含中文"配置" UTF-8), 不是 `/data/.BABA/`。22+ `.ini` 配置文件 + 3 个二进制 (icon_visible/icon_positions/delay)。**仅 kami login 成功后才创建**。本设备 license gate 死锁 (kami 验证 C2 加密响应解不开)。

---

## 4. 重大未解 (留给未来 RE)

### 高优先
- Paradise IOCTL CMD: MOVK+BR 混淆, 静态拿不到, 需要 kernel-side `paradise_6.1.ko` RE 或 Frida 运行时 trace
- DitPro KPM sub-cmd bit-field semantics: kernel-side handler 未 RE
- /data/.BABA配置/ 实际文件内容: 文件名/路径全 RE (22+ .ini + 3 binary), 但 binary 内容需突破 kami gate (要解 C2 加密响应)
- C2 响应加密算法: key `Th0m3Lu@_M@st3r_S3cr3t_2025` + handshake `session_establishment_2025` 明文已找到, 但 AES/DES/RC4 + IV/mode/key-schedule 没匹配通

### 中
- C2 完整协议握手 (POST wy.llua.cn 已 RE, 但加密未解开)
- 32-byte hex license 在 baba 内: 没解密
- TwT_driver syscall 142 内核侧 hook 机制: 需 dump 对应 .ko
- LQ aescbc helper UPX 内部: 没 unpack (Python 端解密已通, 设备端没必要)

### 低
- 6.12 16K-page kernel: LQ stage-1 有 `_p16k` token 但没匹配 .ko
- baba 内 dlsym loader 完整重映射逻辑: 函数 thunk index 到 string index 在 libc 范围有偏差未完全 RE
- skill CD 单位 (8192000 是 cs 还是 ticks?): 50% 概率

---

## 5. Binary artifacts 清单

```
D:/ctf/_root/truevision/docs/RE/
├── 14 个 markdown 报告 (7643 行)
├── blob-10mb.bin (14.5 MB) - 524 张 PNG 拼接 dump
├── png_samples/ (11 张 PNG sample 含蕾姆 splash)
├── entropy_rw.csv (108 KB - RW seg entropy 扫描)
├── strings_rw_header.txt + strings_rw_trailer.txt (RW seg 字符串)
├── strings_text.txt (840 KB - text+rodata 100k+ strings)
├── r2_functions.txt + r2_strings.txt (radare2 输出)
├── func_categories.md + func_strings.txt (per-fn classification)
├── imports.txt (272 dynsym imports)
└── drivers_extracted/
    ├── rt/ (22 个 rt .ko, 4.14.117 - 6.12)
    ├── LQ/ (7 个 LQ .ko, GKI 5.10/5.15/6.1/6.6/6.12)
    ├── LQ_aescbc_helper.elf (UPX-packed AES dropper)
    ├── LQ_payload.enc + tar.gz + stage1.sh (LQ container artifacts)
    └── _analysis.json (机器可读 inventory)
```

---

## 6. 引用其他 RE 资产 (preexisting)

- `D:\ctf\_root\baba_525\inner_reader.elf` (24MB) + `.i64` IDA DB
- `D:\ctf\_root\baba_525\baba_reader.elf` (24.86MB) + `.i64`
- `D:\ctf\_root\baba_525\stub_reader.elf` (308KB)
- `D:\ctf\_root\baba_525\` 100+ .c 反编译文件
- `D:\ctf\_root\gc.so.i64` (libGameCore IDA DB)
- `D:\ctf\_root\truevision\docs\DESIGN.md` (truevision 项目设计)
- `D:\ctf\_root\truevision\docs\OFFSETS.md` (TruVision 用的 offset 表, 已修 hex 错)

---

## 7. 后续 (不在这次 RE 范围)

- 复刻 / 开源版实现: kernel/, reader/, overlay/, tools/ 目录 (user 明确要求 RE 完成后再做)
- 设备动态 dump: /data/.BABA/ 文件实际内容; Paradise/TwT IOCTL CMD 运行时 trace
- 完整 C2 protocol RE

---

## 8. 致谢 / 参考

- IDA Pro 9.1 + Hex-Rays 反编译
- radare2 (r2) 用于 3442 真函数严格计数
- Python (Pillow / cryptography) 用于 PNG 提取 + AES 解密
- Zig 0.16.0 用于 aarch64 cross-compile dump tool
- KPatch-Next 框架 (sgame-fakemem-v17 KPM 复用基础)

baba 5.25 由 Telegram 频道 `@离` (UTF-8 0xE7A6BB) 维护; 商业 driver `kma KPM` 上 license 字符串 `ORANGINECLJP7MCSLA2XJSUHDJQBU5ZL` 暗示 "Orange" 品牌。
