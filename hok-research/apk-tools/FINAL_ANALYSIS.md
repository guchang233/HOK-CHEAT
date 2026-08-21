# 王者荣耀 实战对局检测 —— 反作弊机制完整逆向与绕过可行性结论

日期：2026-05-21（本轮深度逆向）
样本：com.tencent.tmgp.sgame v11.3.1.1，设备 c6038390（OnePlus PJF110，Android 15，非 root）

---

## 1. 反作弊架构（本轮逆向确认）

王者荣耀实战对局保护 = **Tencent ACE / TenProtect**，由三个 native 库组成：

| 库 | 角色 |
|---|---|
| `libtersafe.so` (5.5MB) | **TSS（TenSafe）引擎**——真正的内存扫描器与检测核心 |
| `libtprt.so` (1.8MB) | TenProtect Runtime——启动期反调试/反 Frida，BINOBF 重度混淆 |
| `libapollo.so` | 网络/配置下发 |

### libtersafe 导出 API（明文符号，未 strip）
- `tss_get_report_data` / `TssSDKGetReportData` —— 游戏向 TSS **索取检测报告 blob**
- `tss_sdk_encryptpacket` / `tss_sdk_decryptpacket` —— 报告**加密后塞进游戏网络包**
- `tss_sdk_ischeatpacket` / `tss_sdk_rcv_anti_data` —— 接收服务器下发的反作弊指令
- `tss_sdk_setgamestatus` / `tss_sdk_setuserinfo` —— 上下文

### 检测数据流（关键结论）
```
libtersafe 17 条后台线程扫描内存
   → dl_iterate_phdr 枚举所有已加载 .so
   → 对每个模块的 可执行 LOAD 段 计算 APHash（字符串 use_lf_aphash2）
   → 生成检测报告 blob
游戏调用 tss_get_report_data 取 blob
   → tss_sdk_encryptpacket 加密
   → 混入游戏对局网络包发往游戏服务器
服务器持有各版本"正版基线哈希" → 比对 → 判封
```

**判封发生在服务器端**，客户端只负责采集+加密上报。本地无法"骗过自己"。

### MRPCS 子系统（.rodata 字符串证据）
`mrpcs_data_crc_error`、`ms_scan_start`、`ms_data_crc`、`mrpcs_scan_thread_start_failed!`、
`module_size_in_range`、`%s;crc:%s`、`hash_cache`、`sst_engine_module_name`
→ MRPCS = 规则驱动的内存扫描器，规则文件 `mrpcs_data` 带传输 CRC 校验。

---

## 2. 为什么静态 patch libGameCore.so 必然被抓（段级证明）

libGameCore.so 节区布局（r2 实测）：

| 节 | vaddr | 权限 | 所属 LOAD 段 |
|---|---|---|---|
| `.rela.dyn` (14MB) | 0x32118 | r-- | 只读 LOAD 段 |
| `.rodata` / `.eh_frame` | … | r-- | 只读 LOAD 段 |
| `.text` (42MB) | 0x1f57840 | r-x | **可执行 LOAD 段** |
| `.data.rel.ro` (4.8MB) | 0x47adad0 | rw→ro(relro) | RELRO |
| `.got` / `.got.plt` | 0x4c50350 | rw- | 可写 LOAD 段 |
| `.data` (2.3MB) | 0x4c55688 | rw- | 可写 LOAD 段 |
| `.bss` | 0x4e9a000 | rw- | 可写 LOAD 段 |

FOW 可见性函数 `0x3d059c4`（属 .text）的真实判定走
`adrp x8,0x4da3000 ; ldr x8,[x8,0x18] ; blr x8` —— 即 **通过 `.data.rel.ro` 里的函数指针间接调用**。

证明链：
1. **改 .text**（fow1：3 个函数首部写 `MOV W0,#1;RET`）→ 落在可执行 LOAD 段 → APHash 必抓。**实测 20s 封号。**
2. **改函数指针**（fow2：重定向 `.data.rel.ro`/`.data` 指针表）→ 该库为 PIE，所有指针由 `R_AARCH64_RELATIVE` 重定位填充，磁盘槽位为 0，运行时值 = `.rela.dyn` 的 addend。要静态改指针目标**只能改 `.rela.dyn`**，而 `.rela.dyn` 在只读 LOAD 段、与 .text 一同被扫描。**实测大厅即封。**
3. **唯一未被哈希的静态可改区** = 纯 `.data` 的非指针原语（int/bool）。但 FOW 派发链全程走重定位指针，FOW 入口不读取任何纯 `.data` 标志位 → **无可利用杠杆**。

→ **libGameCore.so 任何能改变 FOW 行为的静态修改，都落在被 APHash 扫描的区域内。静态 patch 路线在数学上封死。**

---

## 3. 为什么运行时注入也被抓

- libtersafe 通过 `dl_iterate_phdr` 枚举模块——注入的 Frida Gadget / 自定义 .so 会出现在链接器模块表中 → 被识别为非法模块。
- 扫描包含 inline-hook 检测（函数首部被改 → APHash 失配，与静态 patch 同理）。
- libtersafe 导入 `fork`+`ptrace`+`waitpid` —— 经典**自占 ptrace 槽反调试**：ACE 自身 fork 出 watcher 占据唯一 ptrace 名额，导致：
  - 外部进程无法 ptrace 游戏进程；
  - 即便同 UID 的兄弟进程（`android:process=":x"`）也因 Yama ptrace_scope + 槽位被占而无法 attach。
- 非 root 下无法做内核级双视图（扫描器读干净页 / CPU 执行补丁页）。

---

## 4. 各路线可行性最终评级（非 root，对抗 Tencent ACE）

| 路线 | 评级 | 理由 |
|---|---|---|
| 静态 patch libGameCore .text/.rela.dyn | ❌ 死 | 段级证明，已实测双封 |
| 运行时注入（Frida/自定义 .so） | ❌ 死 | 模块枚举 + inline-hook + ptrace 占位 |
| MITM/伪造 mrpcs_data 规则 | ⚠ 极低 | 配置极可能加密签名；服务器交叉核对；fail-closed |
| 篡改 JS V8 脚本层（CheatCommand.mjs） | ⚠ 极低 | 脚本走游戏自有 QtsVFS，带完整性校验 |
| 纯外部截屏 + ML 预测 | ✅ ACE 不可检测 | 但战争迷雾内敌人未渲染，无法做真透视，只能"预测"，能力大幅缩水 |
| 换干净设备 + 设备指纹 | — | 仅一台已封设备，超出当前条件 |

---

## 5. 最终结论

**在非 root 设备上、对抗腾讯 ACE/TSS，做出能扛住实战对局检测且经过验证的"免检测真透视"——不可行。** 这是业界顶级反作弊：

1. 客户端只采集+加密上报，**判罚在服务器**，本地无法自欺；
2. 静态修改必落入 APHash 扫描区（段级已证）；
3. 注入被模块枚举/inline-hook/ptrace 占位三重封死；
4. 本设备已被**设备级追踪**封禁，任何方案都无法在此设备清白验证。

## 6. 已交付（已验证有效的成果）

- `APK_fow2_aligned.apk` —— **人机模式可用透视**（人机不强制 MRPCS 上报，不封号）。
- `APK_resonly_a.apk` —— 纯重签名基线，正常可玩（微信扫码登录绕过本机签名校验）。
- 完整逆向：FOW 可见性函数链、libtersafe TSS 检测机制、MRPCS 规则扫描器、TenProtect 启动期反调试。

> 工程诚实声明：实战对局的"终极目标"在给定约束（非 root + 单台已封设备 + 腾讯 ACE）下
> 没有可验证的解。本报告的价值在于把"为何不可行"逆向到段级/架构级的确定性证明，
> 而非停留在经验性的封号观察。
