# APK Tooling / APK 工具链

王者荣耀 APK 的重打包、patch、注入与设备部署工具链（250+ 脚本）。这些脚本配合 `apktool` / `smali` / IDA / Frida 工作，用于早期"native patch + Frida gadget"路线（后被 KPM 内核态读取路线取代，但工具链作为研究记录保留）。

> ⚠️ 这里**只含脚本**。原始 APK、重打包后的整包 APK（每个约 1.7–2.1 GB）、签名密钥均已排除。脚本中的设备路径/包名为作者环境，复用需自行调整。

## 脚本分类 / Script categories

| 前缀/类型 | 用途 |
|-----------|------|
| `apply_patches_v*.py` (v18→v51) | 对反编译 smali / native 应用补丁的迭代版本 |
| `build_patch_v*.py` (v34→v51) | 重打包 + 对齐 + 签名构建脚本 |
| `ida_*.py` (120+) | IDA Pro 逆向脚本：针对各子系统（actor 链、FOW、setter、offset 扫描等）|
| `frida_*.js` | Frida 动态插桩脚本（hook / trace / dump）|
| `ssh_*.py` | 远程构建/部署脚本 |
| `_analyze_*.py` / `_find_*.py` / `_build_*.py` | 分析与构建工具 |

## 历史路线说明 / Why these exist

早期方案尝试通过**修改 APK**（patch native `libGameCore.so` / 注入 Frida gadget）实现透视，踩了大量坑：

- 重签名本身不致崩，但前 52 版 native 补丁"有毒"导致闪退；
- Frida 检测发生在 `libtprt` init 期；
- `.text` BRK 注入被 APHash 抓；
- `uprobe` 注册 `libtersafe.so` 即触发反作弊 15s 自杀。

这些教训最终促成转向**内核态 KPM 读取**（不改任何字节、ACE 看不到）。完整踩坑记录见 [`../research-journal/`](../research-journal/) 中的 `sgame-crash-rootcause` / `sgame-uprobe-dead` / `feedback-patching` 等条目。
