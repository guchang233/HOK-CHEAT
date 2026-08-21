# Architecture & Glossary / 架构与术语

本文给出 TrueVision 的端到端架构、关键数据链、以及研究日志中反复出现的术语表。设计实现细节见 [`../truevision/docs/DESIGN.md`](../truevision/docs/DESIGN.md)。

---

## 1. 端到端数据流 / End-to-end dataflow

```
                       Android 设备 (rooted)
   ┌───────────────────────────────────────────────────────────┐
   │                                                             │
   │   sgame (王者荣耀)                  KPM (内核态)             │
   │   ┌─────────────────┐              ┌────────────────────┐  │
   │   │ libGameCore.so  │              │ sgame-fakemem.kpm  │  │
   │   │   .bss          │◀────read─────│  supercall(45)     │  │
   │   │   actor list    │  (kernel)    │  ctl0 'r pid a len'│  │
   │   │ libunity/IL2CPP │              │  sgame_tgid = 0    │  │
   │   │ libtersafe(ACE) │ ✗ 看不到读   └─────────┬──────────┘  │
   │   └─────────────────┘                        │             │
   │                                              ▼             │
   │                                   ┌────────────────────┐   │
   │                                   │ tv_reader (ELF)    │   │
   │                                   │  解析 actor 链      │   │
   │                                   │  4-follow pos chain│   │
   │                                   │  雾中追踪逻辑       │   │
   │                                   └─────────┬──────────┘   │
   └─────────────────────────────────────────────┼─────────────┘
                            TCP 47291 (TvFrame)   │
              ┌──────────────────────────┬────────┘
              ▼                          ▼
    ┌──────────────────┐      ┌────────────────────────┐
    │ Android Overlay  │      │  PC minimap.py         │
    │  (Kotlin)        │      │   (Python tkinter)     │
    │  全屏透明 Surface │      │   实时小地图 + 诊断面板 │
    └──────────────────┘      └────────────────────────┘
```

**核心反检测原理**：跨进程内存读取发生在**内核态**（KPM），不经过任何 syscall，ACE 反作弊在用户态观察不到。KPM 严格只读、定向读目标 so 的文件映射页与其 bss，`sgame_tgid=0` 表示绝不干预 sgame 自己的 syscall。

---

## 2. 关键数据链 / Key chains

### Actor list chain（与 baba RE 一致）
```c
gc_bss        = resolve_anon_bss(pid, "libGameCore.so")
actor_anchor  = follow(gc_bss + 0x17BB58)
actor_list    = follow(actor_anchor + 0x238)
for i in 0..10:
    actor[i]  = follow(actor_list + i*24) & 0xFFFFFFFFFFFF   // strip PAC tag
```

### 4-follow position chain（★ 11.3.1.1 实测，推翻 baba 文档）
```c
p1 = follow(actor + 0x268)   // hop 1
p2 = follow(p1 + 0x10)        // hop 2
p3 = follow(p2 + 0x00)        // hop 3 — 关键：deref p2
p4 = follow(p3 + 0x60)        // hop 4
// read 16 bytes @ p4:  x_raw=int32[0..3], z_raw=int32[8..11]
world_x = x_raw / 100.0f
world_z = z_raw / 100.0f
```

### Actor 内部字段
| Offset | 字段 | 备注 |
|--------|------|------|
| `+0x34` | objID | 跨帧稳定身份 |
| `+0x50` | hero_id | （baba 误标为 actor_type）|
| `+0x5C` | camp | 1=蓝, 2=红 |
| `+0x188 → +0xA8` | (cur_hp, ...) | （baba 误标为 pos）|
| `+0x268 → …` | 真 world (x,z) | 见 4-follow chain |
| `+0x340` | byteid | byte-id 管理器身份 |

### 迷雾旁路（多条，按可靠性）
- **DisplayInfoData[]**：`gc_bss + 0x1C4980` → begin/count，stride `0x34`，pos@`+0x10`（米）。
- **thin-sync proxy**：own=0 的瘦同步 proxy 在雾中持续 fire 真实移动（绑定它而非冻结的身份 proxy）。
- **FakeFow 记录数组**：`ViewManager::ModifyEnemyInvisibleHeroPosition` 读真位，stride `0x3b8`，objID@`+0x14`，X@`+0x1c`，Z@`+0x24`（需冷 BP 入口取未伪造副本）。

---

## 3. TvFrame 协议 / Wire protocol

```c
struct TvFrame {
    char     magic[4];      // "TVR3"
    uint32_t frame_id;
    float    mvp[16];       // 摄像头矩阵（world-to-screen 投影用）
    uint32_t our_camp;      // 1 or 2
    uint32_t count;         // 10
    struct TvActor {
        uint32_t type;      // hero_id
        uint32_t camp;      // 1=blue, 2=red
        float    x, z;      // world / 100
    } actors[10];
    // + 诊断字段（开发用）
};
```

---

## 4. 术语表 / Glossary

| 术语 | 含义 |
|------|------|
| **sgame** | 王者荣耀的内部代号（*Honor of Kings*）|
| **ACE** | 腾讯 Anti-Cheat Expert，运行于 `libtersafe.so` / `libtprt.so` |
| **KPM** | KernelPatch Module，内核态模块，提供跨进程读 ABI |
| **OOR** | Out-Of-Range，越界视野——看到本不该看到的（迷雾/草丛中的）敌人 |
| **FOW / fog** | Fog of War 战争迷雾 |
| **FakeFow** | 客户端本地伪造迷雾的机制（真值仍在客户端）|
| **actor** | 游戏中的实体（英雄/小兵/塔等）|
| **proxy** | actor 的模拟代理对象；身份 proxy(own≠0) vs 瘦同步 proxy(own=0) |
| **camp** | 阵营（1=蓝/2=红）|
| **DD** | DisplayData，渲染显示数据（迷雾旁路之一）|
| **setter** | 写入位置的函数（如 `sub_03c511a0`），HW-BP 探针目标 |
| **slotpatch** | 改 FOW 派发槽实现全图可见的早期手段（会软踢）|
| **camp-agnostic** | 不区分敌我两队都追踪，阵营只作颜色标签 |
| **lockstep** | 帧同步——每客户端本地模拟全部 10 人，故真值必在客户端 |
| **PJF110** | 测试设备 OnePlus 机型，Android 14, kernel 6.1.75 |

---

## 5. 为何"透视"在帧同步 MOBA 中难以根除

王者荣耀是**帧同步（lockstep）**架构：服务器只下发指令，每个客户端**本地模拟**全部 10 名玩家的完整状态。这意味着——

> **全图真实坐标必然存在于客户端内存中，腾讯只能"藏"（迷雾遮挡/数据伪造）不能"删"。**

这解释了为什么这类游戏十几年来每个版本都有人能做出透视：迷雾只是渲染层与查询层的本地屏蔽，底层模拟数据是完整的。反作弊的真正战场因此在于**检测读取行为**（读 `/proc/maps`、ptrace、inline-hook 自检、HW-BP 等），而非隐藏数据本身。本项目的反检测设计正是基于这一认知：把读取放到内核态，让用户态的 ACE 观察不到。
