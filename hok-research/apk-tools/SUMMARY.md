# sgame 透视攻击 - 最终状态 (2026-05-24 凌晨~中午)

## 即时上手

**单命令测试** (Windows):
```cmd
cd D:\ctf\_research
self_test.py
build_hero_reader.bat
```

**完整步骤**: 看 `D:/ctf/_research/ATTACK_TEST_ORDER.md` (7 phase 流程)

**全文档索引**: `D:/ctf/_research/MASTER_REFERENCE.md`

## 关键文件 (5 个最重要)

1. **`D:/ctf/_research/hero_reader_v2.c`** (739 lines) — 主 read-only PoC
2. **`D:/ctf/_research/dump_fow_grid.c`** (145 lines) — FOW 网格直接覆盖
3. **`D:/ctf/_research/swap_fow_policy.c`** (172 lines) — FOW policy 交换
4. **`D:/ctf/_research/ATTACK_TEST_ORDER.md`** — 7 phase morning workflow
5. **`D:/ctf/_research/MASTER_REFERENCE.md`** — 完整地址/struct/offset 速查

## 3 个攻击路径 (从安全到激进)

### A. 纯 read (推荐先试)
`hero_reader_v2.c` — process_vm_readv 读 AIFrameState/Hero 数据，PC overlay 渲染。
- 风险: 极低 (无写入)
- 输出: hero 位置、HP、阵营、是否在草丛

### B. FOW policy 交换 (中风险)
`swap_fow_policy.c` — 写 1 个 8-byte 指针，把 FowVisiblePolicy 换成 FakeFow
- 风险: 中等 (写 1 ptr 到 heap)
- 期望: 服务端不察觉，全图可见

### C. FOW 网格直接覆盖 (高风险)
`dump_fow_grid.c` — 写 6960 bytes 到 GameFowMapData (全 0xFF)
- 风险: 较高 (大块 write)
- 期望: 网格全 visible，游戏自己渲染所有英雄

## 关键内存地址

```c
libGameCore.so 基地址 + offset:
  +0x4D756C0  off_4D756C0       EngineRoot* (37918 xrefs)
  +0x4FEA9D0  HeroLogicUnit     (含 heroInfoList)
  +0x4FF0760  GameFowMapData    ★★★ 6960B FOW 可见性网格 ★★★
  +0x4FF1AC8  FowVisiblePolicy  (active)
  +0x4FF1AD0  FowVisiblePolicy_FakeFow (可交换)
  +0x4FF3090  Project8ActorManager  (552B)
  +0x4A8 内 EngineRoot → GameFowManager*
  +0x4F0 内 EngineRoot → CBattleLogic*
    内 CBattleLogic+0x1D8 → CAIActorMgr*
```

## Hero struct (680B, AIFrameState 的 vector<Hero> 内)

```c
+0x00 config_id    +0x30 hp           +0x270 is_in_grass (bool)
+0x04 runtime_id   +0x34 max_hp
+0x08 camp         +0x38 phy_atk
+0x0c main_job     +0x40 magic_atk
+0x10 pos_x        +0x50 ep
+0x14 pos_y        +0x54 max_ep
+0x18 pos_z        +0x1B8 vis_bitmap (per-hero visibility bits)
```

## ACE 反作弊已规避

- ✓ process_vm_readv (ACE 13 个检测函数不监控)
- ✓ 不动 sgame .text (MRPCS 不触发)
- ✓ 不注入 (frida/zygisk 全死)
- ✓ 不扫游戏堆 (精确 offset 读)

## 工作产物统计

- 3 个 PoC C 源码 (1056 lines 总)
- 8 个 Python 工具 (mock/parse/render/feed/runner/etc)
- 1 个 GHA workflow
- 1 个 sgame_types.h
- 17 个 markdown 文档 (8448+ lines)
- 35 个完成 tasks
- 250+ 反逆向的 production singletons
- 91 个 protobuf class wire schemas
- 1 个 self_test.py 6/6 PASS 端到端验证

## 关键 memory 持久化

- `sgame-hokenv-layouts-2026-05-24` (主入口，全 layout + singletons + PoC)
- `sgame-ace-targets-2026-05-24` (ACE 13 检测函数)
- `sgame-il2cpp-route-2026-05-24` (原 IL2CPP 路线参考)
- `sgame-progress` (历史 CTF 进度)
