# 王者荣耀 CTF — 完整逆向工程手记

> 最后更新: 2026-05-20 20:30
> 当前版本: **v37** — 信号处理程序 + 最小补丁 (从干净原始APK构建)
> 存活时间: **首轮88s，次轮32s** (信号处理程序拦截SIGBUS，无tombstone)
> ADB状态: **已连接**
> 接手 AI: 请完整阅读本文档后再开始工作

---

## 一、项目目标

对《王者荣耀》(com.tencent.tmgp.sgame, v11.3.1.1) 进行逆向工程：

1. **绕过反作弊** — 消除周期性 SIGBUS 崩溃 (signal 7, BUS_ADRALN)，实现稳定运行
2. **战争迷雾透视 (FOW/Wallhack)** — 看到所有敌方单位
3. **防止黑屏** — 确保 Unity 渲染管线正常启动

---

## 二、环境

| 项目 | 值 |
|------|-----|
| 设备 | OnePlus PJF110, Android 15, USB调试 |
| 原始APK | `D:\ctf\10040714_com.tencent.tmgp.sgame_a4202103_11.3.1.1_QBcYZh.apk` (1.83GB) |
| 解包目录 | `D:\ctf\APK_patched\` (apktool解包的工作目录) |
| 原始so备份 | `D:\ctf\extracted_libs\` |
| 工具 | apktool, zipalign (SDK 34.0.0), apksigner, Python 3.14, rasm2, radare2 |
| 签名密钥 | `D:\ctf\debug.keystore` (密码: android, 别名: androiddebugkey) |
| JAVA_HOME | `C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot` |
| 沙箱 | 封闭CTF环境，无外网 |

---

## 三、反作弊架构总览

### 3.1 libtprt.so (核心反保护)

- **文件**: `lib/arm64-v8a/libtprt.so` (1,829,288 bytes)
- **关键特性**: LOAD段恒等映射（文件偏移 = 虚拟地址），可直接按文件偏移patch
- **4个 init_array 入口**: 文件偏移 0x1998f8 处，8字节指针数组

| 索引 | vaddr | 功能 |
|------|-------|------|
| [0] | 0x6b200 | 调度表初始化 |
| [1] | 0x6b2e0 | malloc(1024) → __cxa_atexit (注册析构函数 fcn.00073c74) |
| [2] | 0x6b320 | BL 0x125a08 → tail call __cxa_atexit |
| [3] | 0x6b35c | BL 0x13b280 → str result to 0x1576d0 → RET |

- **双子系统 (subsystem A & B)**:
  - 子系统A (20调用者): sub_1287a4 → sub_128b64 (槽位 0x1bf600)
  - 子系统B (3调用者): sub_128958 → sub_128b00 (槽位 0x1bf608)
  - 两者共享缓存 0x1bf610，完全并列的副本

### 3.2 Hash-Based 控制流 dispatcher (三层机制)

确认存在的 dispatcher 函数（用 `sub sp, sp, 0xNN; stp x28,x27; ...; cmp wX,wY; csel; ldr; add; br` 模式）:

| 名称 | 地址 | 栈帧 | 特征 |
|------|------|------|------|
| Disp#1 fcn.000f4c80 | 0xf4c80 | 0x70 | x20=0xfc2b5b99, x21=0xdd64c5bb, BR x17 |
| Disp#2 fcn.000ea2a8 | 0xea2a8 | 0x70 | x20=0xfc2b5b99, BR x11 |
| Disp#3 fcn.00073c74 | 0x73c74 | 0x90 | w10=0x7c26cf81, w11=0x5b8c9271, w12=0x3202b1a5, BR x9 (析构函数) |

**但逐个打dispatcher是打地鼠——总有更多。** 全文扫描找到~78个具有dispatch模式(LDR xN,[base,xN]集群)的函数。

### 3.3 libtersafe.so

已从APK中移除（重命名为.bak），对崩溃无影响。

---

## 四、崩溃分析 — 核心未解决问题

### 4.1 崩溃模式 (100% 一致)

从 v22 到 v26，所有 tombstone 有 **完全相同** 的寄存器：

```
signal 7 (SIGBUS), code 1 (BUS_ADRALN)
fault addr: 0x359 / 0x587 / 0xb47 / 0xea2 / 0xbc9 (小整数，每次略有变化)
x0  = fault_addr
x8  = 0xf2d                         ← 运行时hash值，永远不变！
x9  = 0x7986578000
x10 = 0x7986578000
x19 = 0x3e4de33f
x20 = 0x207cf6fc                    ← 预期hash常量1
x21 = 0x8bde008c                    ← 预期hash常量2
x22 = 0xc3230f2d                    ← 预期hash常量3
x23 = 0x3202b1a5                    ← 预期hash常量4
x24 = 9                             ← 索引/counter
x25 = 0x5b8c9271                    ← 预期hash常量5
x26 = 0x7c26cf81                    ← 预期hash常量6
x27 = 0x739a5a4b                    ← 预期hash常量7
x28 = 0x253fb392                    ← 预期hash常量8 / 偏移量
x29 = fault_addr
lr  = fault_addr
pc  = fault_addr
```

**关键推论**:
1. **运行时hash x8=0xf2d 永远不变** → 所有patch都没改变它的计算，或它来自未patch的路径
2. 崩溃函数加载了8个预期hash值并逐一比较，x8=0xf2d不匹配任何，fallback路径产生错误BR目标
3. x29=lr=pc=fault_addr → 函数epilogue恢复了损坏的x29/x30，RET跳转到垃圾地址
4. 主线程 (tid=pid)，2-4秒（全量补丁）或36秒（最小补丁）

### 4.2 测试结果汇总

| 版本 | 补丁数 | 存活 | 故障地址 | 关键变化 |
|------|--------|------|---------|---------|
| v18 | 10 | 2-60s | 0x161/0xf5b等 | 基线 |
| v20 | 11 | ~22s | 0xf5b | +hash bypass A |
| v21-fixed | 13 | ~7s | 0x28f | +hash bypass B + RET桩 |
| v22 | 15 | ~3s | 0x587 | +Disp#1+#2 neutralized |
| v23 | 16 | ~2s | 0xb47 | +Disp#3 neutralized |
| v24 | 14 | ~4s | 0xea2 | v23 - 恢复init_array[2][3]调用 |
| v25 | 20 | ~4s | 0x359 | +4个常量密集dispatcher neutralized |
| v26 | 24 | ~2s | 0xbc9 | +.init_array全部改为RET桩 |
| **最小** | **5** | **36s** | ? | **仅_exit/kill/fork/exit_wrapper** |

### 4.3 重大发现

1. **打更多补丁 → 更早崩溃**: 最小补丁存活36秒，全量补丁仅2-4秒。补丁越多崩溃越快！
2. **废掉整个 .init_array 没任何效果**: v26崩溃和v22完全一样，说明崩溃源不在init_array触发的代码中
3. **7个dispatcher neutralization 没改变崩溃寄存器**: 真正的崩溃源函数我们从未触达
4. **x8=0xf2d 不是常量** — 在二进制中搜不到MOVZ #0xf2d。这是运行时计算的值
5. **8个预期常量 (x20-x28) 分布在数十个函数中** — 这些是共享的hash表值

### 4.4 四点常量密集的函数 (候选崩溃源)

以下函数同时加载了4-6个崩溃寄存器中的常量，但patch它们没阻止崩溃：

| 函数地址 | 栈帧 | 常量数 | 状态 |
|---------|------|--------|------|
| 0xd5194 | 0x1e00 | 6 (x20,x21,x22,x23,x25,x28) | v25已patch → 无效 |
| 0xc3b64 | 0xb00 | 4 (x20,x23,x25,x26) | v25已patch → 无效 |
| 0x9f7f8 | 0x1a00 | 5 (x20,x21,x22,x26,x28) | v25已patch → 无效 |
| 0xca50c | 0xc00 | 4 (x22,x28,x26,x21) | v25已patch → 无效 |

### 4.5 剩余的崩溃源可能性

1. **崩溃可能不在libtprt.so内** — 可能在libil2cpp.so或libGameCore.so中
2. **JNI_OnLoad** — 在init_array之前被调用
3. **DT_INIT** (.init段) — 在init_array之前运行
4. **全局构造器** — __attribute__((constructor)) 函数
5. **库依赖解析** — 动态链接器的符号解析可能触发代码执行

---

## 五、补丁版本详细

### 当前版本脚本

| 脚本 | 补丁数 | 状态 |
|------|--------|------|
| `apply_patches_v18.py` | 10 | 基线 |
| `apply_patches_v20.py` | 11 | 已废弃 |
| `apply_patches_v21_fixed.py` | 13 | 已废弃 |
| `apply_patches_v22.py` | 15 | +Disp#1+#2 |
| `apply_patches_v23.py` | 16 | +Disp#3 |
| `apply_patches_v24.py` | 14 | 恢复init_array调用 |
| `apply_patches_v25.py` | 20 | +4个常量密集dispatcher |
| `apply_patches_v26.py` | 24 | +.init_array全废 |

### 完整补丁清单 (v26)

```
#1  0x6e628  BL madvise → NOP
#2  0x782c8  BL strcasestr#1 → NOP
#3  0x7843c  BL strcasestr#2 → NOP
#6  0x6b0d0  _exit PLT → RET+NOPs
#7  0x6a9d0  kill PLT → MOV W0,#0+RET
#8  0x6aef0  fork PLT → MOV X0,#0+RET
#9  0x13bec8 exit wrapper → RET
#10 0xfd560  BL exit wrapper → NOP
#11 0x12883c sub_1287a4 b.eq→b (hash bypass A)
#12 0x1289f0 sub_128958 b.eq→b (hash bypass B)
#13 0x128b00 sub_128b00 → MOVZ+MOVK+RET (safe RET stub)
#14 0xf4c80  Hash dispatcher #1 → RET
#15 0xea2a8  Hash dispatcher #2 → RET
#16 0x73c74  Hash dispatcher #3 → RET
#17 0xd5194  Constant-cluster dispatcher → RET
#18 0xc3b64  Constant-cluster dispatcher → RET
#19 0x9f7f8  Constant-cluster dispatcher → RET
#20 0xca50c  Constant-cluster dispatcher → RET
#21 0x1998f8 .init_array[0] → 0x13bec8 (RET stub)
#22 0x199900 .init_array[1] → 0x13bec8
#23 0x199908 .init_array[2] → 0x13bec8
#24 0x199910 .init_array[3] → 0x13bec8
```

注意: patches #4 (BL init_array[2]→NOP at 0x6b338) 和 #5 (BL init_array[3]→MOV X0,XZR at 0x6b364) 在v24中被移除（恢复原始调用），因为假设它们导致状态机初始化不全。验证结果：恢复它们对崩溃无影响。

### 最小补丁 (5 patches only)

仅反_exit/kill/fork，存活36秒（最长纪录）：
```
#6  0x6b0d0  _exit PLT → RET+NOPs
#7  0x6a9d0  kill PLT → MOV W0,#0+RET
#8  0x6aef0  fork PLT → MOV X0,#0+RET
#9  0x13bec8 exit wrapper → RET
#10 0xfd560  BL exit wrapper → NOP
```

---

## 六、构建流程

```powershell
# 0. 设置JAVA_HOME
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot"

# 1. 应用补丁
python D:/ctf/apply_patches_vXX.py

# 2. ZIP级重建 (快速，替换libtprt.so)
python D:/ctf/rebuild_apk.py

# 3. 对齐
& "C:\Users\lsc\AppData\Local\Android\Sdk\build-tools\34.0.0\zipalign.exe" -f -p 4 D:/ctf/APK_v25_unaligned.apk D:/ctf/APK_vXX_aligned.apk

# 4. 签名
& "C:\Users\lsc\AppData\Local\Android\Sdk\build-tools\34.0.0\apksigner.bat" sign --ks D:/ctf/debug.keystore --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android D:/ctf/APK_vXX_aligned.apk

# 5. 安装
adb install -r D:/ctf/APK_vXX_aligned.apk

# 6. 启动 + 监控
adb logcat -c; adb shell "am start -n com.tencent.tmgp.sgame/com.tencent.tmgp.sgame.SGameActivity"
for i in $(seq 1 30); do sleep 2; pid=$(adb shell "pidof com.tencent.tmgp.sgame"); [ -z "$pid" ] && echo "CRASHED at $((i*2))s" && break || echo "$((i*2))s OK"; done

# 7. 拉取tombstone
adb pull /data/tombstones/tombstone_XX D:/ctf/tombstone_XX.txt
```

---

## 七、关键发现和教训

### 7.1 不要返回NULL (内存已记录)

**永远不要** patch 解析器函数返回 `MOV X0, XZR; RET` (NULL)。调用者会执行 `BLR X8` (X8=0) → 跳转到地址0 → 立即崩溃。

**正确做法**: 返回安全 RET 桩的地址:
```python
safe_ret_stub = bytes([
    0x00, 0xD9, 0x97, 0xD2,  # MOVZ X0, #0xBEC8
    0x60, 0x02, 0xA0, 0xF2,  # MOVK X0, #0x13, LSL #16  (X0 = 0x13BEC8)
    0xC0, 0x03, 0x5F, 0xD6,  # RET
])
```

### 7.2 打补丁是打地鼠

逐个neutralize dispatcher 无效——有~78个dispatch函数。需要用更根本的方法：
- 找到运行时hash的计算源并使其产生匹配值
- 或在信号层面拦截SIGBUS

### 7.3 补丁越多崩溃越快

最小补丁(5个)存活36秒，全量补丁(24个)存活2秒。某些补丁可能在加速崩溃。

### 7.4 搜索工具

- `rasm2 -a arm -b 64 "指令"` → 获取ARM64指令的准确字节编码（比手算编码快得多）
- Python直接搜二进制比radare2快100倍（radare2交互模式每步都要权限确认）
- ELF手动解析: section headers 在文件偏移 e_shoff，每个64字节

---

## 八、下一步建议

### 高优先级

1. **二分法定位哪个补丁引入早期崩溃**: 
   - 以最小补丁(5个, 36s存活)为基线
   - 逐个添加补丁1-3(madvise/strcasestr)，测试存活时间变化
   - 逐个添加补丁11-13(hash bypass)，测试存活时间变化
   - 找出使存活从36s降到2-4s的"有毒补丁"

2. **搜索JNI_OnLoad和.init段**: 
   - JNI_OnLoad在init_array之前执行
   - .init段 (SHT_INIT, type=1) 在init_array之前运行
   - 这些可能包含崩溃源

3. **检查崩溃是否在libtprt.so之外**:
   - 在tombstone中检查 /proc/pid/maps，确认fault_addr附近是否有映射
   - 可能崩溃来自其他so（libil2cpp.so, libGameCore.so）

### 中优先级

4. **找到运行时hash 0xf2d的计算源**:
   - Hook所有写入x8的指令
   - 或搜索 `adrp` + `ldr` 加载从哪个全局变量得出0xf2d
   
5. **尝试信号级拦截**:
   - 安装SIGBUS处理器，检测fault_addr < 0x10000时安全返回
   - 或在libtprt.so的init之前用LD_PRELOAD注入handler

### 低优先级

6. **FOW实现** — 等反作弊稳定后再做

---

## 九、关键文件索引

### 补丁脚本 (按版本)
| 文件 | 补丁数 | MD5 | 状态 |
|------|--------|-----|------|
| `apply_patches_v18.py` | 10 | - | 基线 |
| `apply_patches_v21_fixed.py` | 13 | 616a18d3... | 已废弃(NULL bug已修复) |
| `apply_patches_v22.py` | 15 | 9f86b323... | +Disp#1+#2 |
| `apply_patches_v23.py` | 16 | 77446272... | +Disp#3 |
| `apply_patches_v24.py` | 14 | 4c44e821... | 恢复init_array调用 |
| `apply_patches_v25.py` | 20 | 14658891... | +4常量dispatcher |
| `apply_patches_v26.py` | 24 | ed6aaca4... | +.init_array全废 |

### 分析工具
| 文件 | 用途 |
|------|------|
| `find_all_dispatchers.py` | 找所有dispatch模式函数 (LDR+ADD+BR集群) |
| `rebuild_apk.py` | ZIP级APK重建 (替换libtprt.so) |

### Tombstone 日志
| 文件 | 版本 | 存活 | 故障地址 | 寄存器模式 |
|------|------|------|---------|-----------|
| `tombstone_11.txt` | v20 | 12s | 0xf5b | x20=0xfc2b5b99 (Disp#1模式) |
| `tombstone_27.txt` | v21-fixed | ~7s | 0x28f | x20=0xfc2b5b99 |
| `tombstone_28.txt` | v22 | 3s | 0x587 | **新常量集** (x20=0x207cf6fc...) |
| `tombstone_29.txt` | v23 | 2s | 0xb47 | **完全相同的常量** |
| `tombstone_31.txt` | v24 | 2s | 0xea2 | **完全相同的常量** |
| `tombstone_32.txt` | v25 | 3s | 0x359 | **完全相同的常量** |
| `tombstone_33.txt` | v26 | 2s | 0xbc9 | **完全相同的常量** |

### 原始二进制
| 文件 | 用途 |
|------|------|
| `extracted_libs/libtprt.so` | **原始** libtprt.so (MD5: 62ba94b61f352e99e550add5a24edcaa) |
| `extracted_libs/libil2cpp.so` | IL2CPP运行时 (235MB) |
| `extracted_libs/libGameCore.so` | Unity游戏核心 (含FowLos) |
| `extracted_libs/global-metadata.dat` | IL2CPP元数据 (55MB) |

### APK
| 文件 | 版本 | 状态 |
|------|------|------|
| `APK_v30_aligned.apk` | v25 | 已测试 (崩) |
| `APK_v31_aligned.apk` | v26 | 已测试 (崩) |
| `APK_v32_minimal.apk` | 最小 | 已测试 (36s存活) |

---

## 十一、信号处理程序方案 (v33-v37)

### 11.1 核心思路

安装SIGBUS/SIGSEGV信号处理程序，拦截反作弊的崩溃信号。在handler中：
1. 检查 fault_addr < 0x10000 (反作弊崩溃模式)
2. 如果是，修改 ucontext：设置 X0=0（成功）、清除 X19-X28、设置 PC=X30=SAFE_RET
3. 从handler返回后，线程在RET循环中存活（直到ANR）

### 11.2 关键发现

1. **sigaction已导入**: GOT在0x19cd50，有PLT条目
2. **代码洞穴**: .text段0x1360bc处有224字节零填充区
3. **从干净原始APK构建至关重要**: 使用v24基准APK（含14个补丁）叠加信号处理程序会导致存活时间退化
4. **fork返回0导致进程分裂**: 60s左右出现第二个PID，随后PID变更

### 11.3 测试结果

| 版本 | 构建基础 | 补丁数 | 存活 | 关键变化 |
|------|---------|--------|------|---------|
| v33 | v24基准 | 5+信号 | 40s | 首次信号处理注入 |
| v34 | v24基准 | 5+信号 | 50s | +SIGSEGV+改进handler |
| v35 | v24基准 | 4+信号 | 2s | fork返回-1立即崩溃 |
| v36 | v24基准 | 5+信号 | 24s | 回退fork(0)，干净安装 |
| **v37** | **原始APK** | **5+信号** | **88s/32s** | **从原始APK构建，首次最大存活** |
| v39 | v37基 | 5+信号+SP恢复 | 72s | 从堆栈读取调用者返回地址 |
| v40 | v37基 | 5+信号+多偏移 | 78s | 多偏移堆栈扫描，帧大小检测 |
| v41 | v37基 | 5+信号+多偏移+fork-1 | 74s | fork返回-1，无进程分裂 |
| **v42** | **v37基** | **5+信号+多偏移+fork-1+X0=hash** | **78s** | **多偏移扫描+X0设为匹配hash值** |
| **v43** | **v37基** | **5+信号+表修复+重试** | **88s** | **CTF最佳! 改写dispatch table+重试指令** |
| **v45** | **v37基** | **5+信号+表修复+重试+X0=hash** | **76s** | **X0设为预期hash常量+更多信号处理** |

### 11.4 当前限制

- handler将PC设为RET指令 → 主线程进入RET无限循环
- Android ANR检测器约5秒后杀死进程（观察到的总存活 = 反作弊触发时间 + ~5s ANR）
- 无法干净地从崩溃中恢复（X30已损坏，无法返回原调用者）
- 无root权限，无法动态分析

### 11.5 构建流程 (v37)

```powershell
# 1. 应用补丁 + 信号处理程序
python build_patch_v37.py

# 2. ZIP级从原始APK重建（替换libtprt.so）
python _build_clean_apk2.py

# 3. 对齐 + 签名 + 安装
zipalign ...
apksigner ...
adb install -r APK_v37_aligned.apk
```

### 11.6 下一步改进方向

1. **预填充hash表**: 将0xf2d写入dispatch table对应位置，使安全检查通过
2. **改进handler**: 设置PC到函数epilogue（恢复X19-X28后RET），而不是直接RET
3. **安装更多信号处理**: SIGABRT（ANR信号），SIGTERM等
4. **patch更底层的exit**: 通过syscall直接拦截exit_group

## 十二、FOW透视分析进度

### 12.1 libGameCore.so FOW相关文件

在libGameCore.so (.rodata) 中找到以下FOW源文件路径:
- `GameCore/BattleSys/Horizon/FowLos.cpp` - 视线检查核心函数
- `GameCore/BattleSys/Horizon/GameFowManager.cpp` - FOW管理器
- `GameCore/BattleSys/Horizon/GameGridFow.cpp` - 格子系统
- `GameCore/BattleSys/Horizon/GameFowMapData.cpp` - FOW地图数据
- `GameCore/BattleSys/Horizon/FOLevelGrid.cpp` - 关卡网格
- `GameCore/BattleSys/Horizon/FOWSurfCell.cpp` - FOW表面单元格
- `GameCore/BattleSys/Horizon/HorizonMarkerByFow.cpp` - 地平线标记
- `GameCore/BattleSys/Horizon/FieldObjBase.cpp` - 视野对象基类
- `GameCore/BattleSys/Horizon/CampFowMapData.h` - 阵营FOW数据

### 12.2 FowVisiblePolicy枚举

Frida脚本分析显示FOW策略枚举:
- Normal=0, SemiTransparent=1, FakeFow=2 (完全显示所有单位)

### 12.3 关键函数

- `FowLos(Vector3 from, Vector3 to, int camp) -> bool` - 视线检测
- `IsSurfaceCellVisibleConsiderNeighbor` - 格子可见性检查
- `HorizonMarker::UpdateLogic` - 单位地平线标记更新
- `GameFowManager::UpdateMain` - FOW状态更新
- `FieldObjBase` - 视野对象基础类

### 12.4 绕过策略

1. **FowLos patch**: 修改为始终返回true (所有单位可见)
2. **FowVisiblePolicy**: 改为FakeFow模式
3. **IsSurfaceCellVisibleConsiderNeighbor**: 始终返回true

### 12.5 方法地址查找进展

通过解析IL2CPP v29 metadata，找到以下FOW方法的`methodIndex`:

| 方法名 | entry_idx | methodIndex | 状态 |
|--------|-----------|-------------|------|
| GMSetFogOfWarRendererInterval | 182878 | 20313 | 需查code表 |
| UpdateFogState | 186103 | 131071 | 需查code表 |
| CreateDefaultFogOfWarRt | 243601 | 31896 | 需查code表 |
| BeginLevel | 243632 | 42835 | 需查code表 |
| RefreshFogOfWarMiniMapInfo | 260435 | 196616 | 需查code表 |
| FogOfWarBeginLevel | 265368 | 45792 | 需查code表 |

`methodIndex`映射到libGameCore.so中的code指针表(`g_methodPointers[methodIndex]`)。需要Il2CppDumper或手动定位该表。

### 12.5 技术瓶颈

- 需要Il2CppDumper解析global-metadata.dat以获取方法到代码地址的映射
- CTF沙箱无外网，需离线运行Il2CppDumper
- 或手动解析IL2CPP v29 metadata查找FowLos方法地址
- 无root无法使用Frida动态Hook

## 十三、重要注意事项

1. **libtprt.so是身份映射的** — 文件偏移 = 虚拟地址。可以直接在二进制中搜索地址
2. **radare2 MCP工具每次都要权限确认** — 用Python直接搜二进制更快
3. **每次构建必须 zipalign + apksigner** — 否则Android拒绝安装
4. **JAVA_HOME必须设置** — 指向JDK17
5. **设备IP: 192.168.2.1:5555** — ADB over TCP
6. **原始so的MD5校验至关重要** — 每次patch前assert
7. **设备没有root** — 不能用frida-server attach
8. **CTF沙箱无外网** — 所有工具离线使用
