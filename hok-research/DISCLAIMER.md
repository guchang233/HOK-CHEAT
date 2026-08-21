# Disclaimer / 免责声明

## 中文

本仓库（"TrueVision"）是一个**安全研究与逆向工程教育项目**。它记录并演示了对一款主流移动游戏（王者荣耀 / *Honor of Kings* / 内部代号 *sgame*）的内存安全分析过程，涉及：

- Android 内核态跨进程内存读取（基于 KernelPatch / KPM）；
- 腾讯 ACE 反作弊（`libtersafe.so` / `libtprt.so`）的逆向分析；
- Unity / IL2CPP 与 native 游戏引擎（`libGameCore.so`）的数据流逆向；
- 一个真实研究项目的完整日志（含大量被推翻的错误假设）。

### 用途限制

1. **本项目仅供安全研究、反作弊对抗研究、逆向工程方法论学习与教育使用。**
2. **请勿在任何真实的在线对战游戏中使用本项目的代码或衍生物进行作弊。** 在联机游戏中使用透视/外挂违反游戏的服务条款（ToS），可能导致账号封禁，并破坏其他玩家的游戏体验。
3. 你需要对自己使用本仓库代码的行为**完全负责**，并自行确保遵守你所在司法辖区的法律法规。
4. 作者与贡献者**不对**任何因使用、误用本仓库内容而产生的后果承担责任。

### 关于第三方知识产权

- 本仓库**不分发**任何第三方专有二进制文件（游戏 `.so`、APK、商业外挂二进制、反作弊库二进制、`global-metadata.dat` 等）。
- `re/` 与 `research/` 下的反编译笔记、分析脚本与文档系基于对**合法获取**的软件进行逆向分析的研究记录。相关底层软件的版权归各自所有者。
- 如果你是相关权利人并认为本仓库的某部分内容不当，请通过 Issue 联系，我们会配合处理。

### 概率与确定性标注约定

遵循本项目的研究规范，研究日志中的**间接结论均带概率标注**（例如"~0.7"），**直接且确定的结论会附逻辑链**。读者应将带概率的结论视为假设而非定论。

---

## English

This repository ("TrueVision") is a **security-research and reverse-engineering educational project**. It documents the process of analysing the memory safety of a major mobile game (*Honor of Kings*, internal codename *sgame*), covering Android kernel-mode cross-process memory reading (via KernelPatch/KPM), reverse engineering of Tencent's ACE anti-cheat, and Unity/IL2CPP + native engine dataflow analysis, along with a complete journal of a real research effort (including many disproven hypotheses).

### Usage restrictions

1. This project is provided **for security research, anti-cheat research, reverse-engineering methodology study, and education only.**
2. **Do not use this code or any derivative to cheat in any live online game.** Doing so violates the game's Terms of Service, may result in account bans, and harms other players.
3. You are **solely responsible** for your use of this code and for complying with the laws of your jurisdiction.
4. The authors and contributors accept **no liability** for any consequences arising from use or misuse of this repository.

### Third-party intellectual property

- This repository **does not redistribute** any proprietary third-party binaries (game `.so` files, APKs, commercial-cheat binaries, anti-cheat library binaries, `global-metadata.dat`, etc.).
- Reverse-engineering notes, analysis scripts, and documentation under `re/` and `research/` are research records produced from analysis of **legitimately obtained** software. Copyright in the underlying software belongs to its respective owners.
- If you are a rights holder and believe any part of this repository is inappropriate, please open an issue and we will cooperate.

By using this repository you acknowledge and accept the above.
