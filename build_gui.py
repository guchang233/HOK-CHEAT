#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
APK直装 + ESP 一键构建器 - GUI 版本
选择官方 APK → 配置私服 IP → 自动构建直装 APK + ESP 系统

依赖: pip install cryptography
可选: Android SDK + NDK (编译 ESP 原生二进制)

使用: python3 build_gui.py
"""

import os
import sys
import subprocess
import threading
import tkinter as tk
from tkinter import ttk, filedialog, messagebox, scrolledtext
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent.resolve()
ULTIMATE_BUILDER = SCRIPT_DIR / "ultimate_builder.py"
ESP_SOURCE = SCRIPT_DIR / "esp_system" / "tv_reader.c"


class BuildApp:
    def __init__(self, root):
        self.root = root
        root.title("王者荣耀 直装APK + ESP 一键构建器")
        root.geometry("720x680")
        root.minsize(640, 580)

        self._build_ui()
        self._load_defaults()

    def _build_ui(self):
        style = ttk.Style()
        style.configure("Title.TLabel", font=("Helvetica", 14, "bold"))
        style.configure("Section.TLabelframe.Label", font=("Helvetica", 10, "bold"))
        style.configure("Status.TLabel", font=("Helvetica", 9))

        title = ttk.Label(self.root, text="🎮 王者荣耀 直装APK + ESP 构建器",
                          style="Title.TLabel")
        title.pack(pady=(12, 4))

        subtitle = ttk.Label(self.root,
                             text="选择官方 APK → 配置私服 → 一键生成直装包",
                             foreground="#666")
        subtitle.pack(pady=(0, 8))

        notebook = ttk.Notebook(self.root)
        notebook.pack(fill=tk.BOTH, expand=True, padx=12, pady=4)

        self._build_game_tab(notebook)
        self._build_esp_tab(notebook)
        self._build_output_tab(notebook)

        self._build_action_bar()
        self._build_log_panel()

    def _build_game_tab(self, parent):
        frame = ttk.Frame(parent)
        parent.add(frame, text="  ① 游戏APK  ")

        apkgroup = ttk.LabelFrame(frame, text=" 官方 APK 文件 ",
                                   style="Section.TLabelframe", padding=10)
        apkgroup.pack(fill=tk.X, padx=8, pady=8)

        row = ttk.Frame(apkgroup)
        row.pack(fill=tk.X)
        self.apk_path_var = tk.StringVar()
        ttk.Entry(row, textvariable=self.apk_path_var).pack(
            side=tk.LEFT, fill=tk.X, expand=True, padx=(0, 8))
        ttk.Button(row, text="浏览...", command=self._pick_apk).pack(side=tk.RIGHT)

        self.apk_info_label = ttk.Label(apkgroup, text="", foreground="#666")
        self.apk_info_label.pack(anchor=tk.W, pady=(6, 0))

        servergroup = ttk.LabelFrame(frame, text=" 服务器配置 ",
                                      style="Section.TLabelframe", padding=10)
        servergroup.pack(fill=tk.X, padx=8, pady=8)

        self.use_private_var = tk.BooleanVar(value=True)
        self.use_private_var.trace_add("write", self._on_toggle_private)
        ttk.Checkbutton(servergroup, text="填入私服IP (取消则直连官方，不替换服务器)",
                         variable=self.use_private_var).pack(anchor=tk.W)

        self.server_frame = ttk.Frame(servergroup)
        self.server_frame.pack(fill=tk.X, pady=(8, 0))

        grid = ttk.Frame(self.server_frame)
        grid.pack(fill=tk.X)

        ttk.Label(grid, text="服务器 IP / 端口:").grid(row=0, column=0, sticky=tk.W, padx=(0, 8))
        self.server_var = tk.StringVar(value="127.0.0.1:6645")
        self.server_entry = ttk.Entry(grid, textvariable=self.server_var)
        self.server_entry.grid(row=0, column=1, sticky=tk.EW, padx=(0, 4))

        ttk.Label(grid, text="游戏包名:").grid(row=1, column=0, sticky=tk.W, padx=(0, 8), pady=(6, 0))
        self.pkg_var = tk.StringVar(value="com.tencent.tmgp.sgame")
        ttk.Entry(grid, textvariable=self.pkg_var).grid(
            row=1, column=1, sticky=tk.EW, pady=(6, 0))

        ttk.Label(grid, text="端口 (ESP通信):").grid(row=2, column=0, sticky=tk.W, padx=(0, 8), pady=(6, 0))
        self.port_var = tk.StringVar(value="18388")
        ttk.Entry(grid, textvariable=self.port_var, width=12).grid(
            row=2, column=1, sticky=tk.W, pady=(6, 0))

        grid.columnconfigure(1, weight=1)

        self.server_hint = ttk.Label(self.server_frame,
                  text="服务器地址格式: ip:port 或 hostname:port",
                  foreground="#888")
        self.server_hint.pack(anchor=tk.W, pady=(6, 0))

        self.detect_frame = ttk.Frame(self.server_frame)
        self.detect_frame.pack(fill=tk.X, padx=8, pady=4)
        self.scan_btn = ttk.Button(self.detect_frame, text="🔍 扫描 APK 中的服务器地址",
                    command=self._scan_apk)
        self.scan_btn.pack(side=tk.LEFT)

        self.scan_result = tk.Text(frame, height=5, state=tk.DISABLED,
                                    font=("Courier", 9))
        self.scan_result.pack(fill=tk.X, padx=8, pady=4)

        self._on_toggle_private()

    def _build_esp_tab(self, parent):
        frame = ttk.Frame(parent)
        parent.add(frame, text="  ② ESP 配置  ")

        esp_enabled = ttk.LabelFrame(frame, text=" ESP 功能 ",
                                      style="Section.TLabelframe", padding=10)
        esp_enabled.pack(fill=tk.X, padx=8, pady=8)

        self.esp_enabled_var = tk.BooleanVar(value=True)
        ttk.Checkbutton(esp_enabled, text="构建 ESP Overlay APK (悬浮窗 + 透视)",
                         variable=self.esp_enabled_var).pack(anchor=tk.W)

        ttk.Label(esp_enabled,
                  text="ESP 功能需要 Root 权限和 KPM 内核模块支持",
                  foreground="#888").pack(anchor=tk.W, pady=(4, 0))

        kpmgroup = ttk.LabelFrame(frame, text=" KPM 内核模块 ",
                                   style="Section.TLabelframe", padding=10)
        kpmgroup.pack(fill=tk.X, padx=8, pady=8)

        self.kpm_check_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(kpmgroup, text="检查 KPM 内核模块状态",
                         variable=self.kpm_check_var).pack(anchor=tk.W)

        ttk.Button(kpmgroup, text="🔌 检查设备连接状态",
                    command=self._check_device).pack(anchor=tk.W, pady=(6, 0))

        self.device_status = ttk.Label(kpmgroup, text="未连接设备",
                                        foreground="#888")
        self.device_status.pack(anchor=tk.W, pady=(4, 0))

        compgroup = ttk.LabelFrame(frame, text=" 编译环境 ",
                                    style="Section.TLabelframe", padding=10)
        compgroup.pack(fill=tk.X, padx=8, pady=8)

        self.ndk_path_var = tk.StringVar()
        ndk_row = ttk.Frame(compgroup)
        ndk_row.pack(fill=tk.X)
        ttk.Label(ndk_row, text="NDK 路径 (可选):").pack(side=tk.LEFT)
        ttk.Entry(ndk_row, textvariable=self.ndk_path_var).pack(
            side=tk.LEFT, fill=tk.X, expand=True, padx=(8, 8))
        ttk.Button(ndk_row, text="浏览...", command=self._pick_ndk).pack(side=tk.LEFT)

        ttk.Label(compgroup,
                  text="未配置 NDK 时跳过原生二进制编译，仅构建直装 APK",
                  foreground="#888").pack(anchor=tk.W, pady=(4, 0))

    def _build_output_tab(self, parent):
        frame = ttk.Frame(parent)
        parent.add(frame, text="  ③ 输出  ")

        outgroup = ttk.LabelFrame(frame, text=" 输出设置 ",
                                   style="Section.TLabelframe", padding=10)
        outgroup.pack(fill=tk.X, padx=8, pady=8)

        out_row = ttk.Frame(outgroup)
        out_row.pack(fill=tk.X)
        ttk.Label(out_row, text="输出目录:").pack(side=tk.LEFT)
        self.output_var = tk.StringVar()
        ttk.Entry(out_row, textvariable=self.output_var).pack(
            side=tk.LEFT, fill=tk.X, expand=True, padx=(8, 8))
        ttk.Button(out_row, text="浏览...", command=self._pick_output).pack(side=tk.LEFT)

        self.open_output_var = tk.BooleanVar(value=True)
        ttk.Checkbutton(outgroup, text="构建完成后打开输出目录",
                         variable=self.open_output_var).pack(anchor=tk.W, pady=(8, 0))

        advgroup = ttk.LabelFrame(frame, text=" 高级选项 ",
                                   style="Section.TLabelframe", padding=10)
        advgroup.pack(fill=tk.X, padx=8, pady=8)

        self.force_var = tk.BooleanVar(value=True)
        ttk.Checkbutton(advgroup, text="强制覆盖已存在的输出文件",
                         variable=self.force_var).pack(anchor=tk.W)

        self.skip_verify_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(advgroup, text="跳过 APK 结构验证 (不推荐)",
                         variable=self.skip_verify_var).pack(anchor=tk.W)

        ttk.Label(advgroup,
                  text="构建产物:\n"
                       "  • game_private.apk — 直装私服 APK\n"
                       "  • esp_overlay.apk  — ESP 悬浮窗 APK (可选)\n"
                       "  • tv_reader_arm64   — 原生内存读取器 (可选)\n"
                       "  • install.sh       — 一键部署脚本",
                  foreground="#666", justify=tk.LEFT).pack(anchor=tk.W, pady=(8, 0))

    def _build_action_bar(self):
        bar = ttk.Frame(self.root)
        bar.pack(fill=tk.X, padx=12, pady=(0, 4))

        self.build_btn = ttk.Button(bar, text="🚀 开始构建",
                                     command=self._start_build)
        self.build_btn.pack(side=tk.LEFT, ipadx=8, ipady=4)

        ttk.Button(bar, text="清除日志",
                    command=self._clear_log).pack(side=tk.LEFT, padx=4)

        ttk.Button(bar, text="打开输出目录",
                    command=self._open_output).pack(side=tk.LEFT, padx=4)

        self.progress = ttk.Progressbar(bar, mode="indeterminate", length=200)
        self.progress.pack(side=tk.RIGHT, padx=4)

        self.status_label = ttk.Label(bar, text="就绪",
                                       style="Status.TLabel", foreground="#666")
        self.status_label.pack(side=tk.RIGHT, padx=8)

    def _build_log_panel(self):
        logframe = ttk.LabelFrame(self.root, text=" 构建日志 ",
                                   style="Section.TLabelframe", padding=4)
        logframe.pack(fill=tk.BOTH, expand=True, padx=12, pady=(0, 10))

        self.log_text = scrolledtext.ScrolledText(
            logframe, height=8, state=tk.DISABLED,
            font=("Consolas", 9), wrap=tk.WORD)
        self.log_text.pack(fill=tk.BOTH, expand=True)

        self.log_text.tag_configure("info", foreground="#333")
        self.log_text.tag_configure("ok", foreground="#2a7")
        self.log_text.tag_configure("err", foreground="#c33")
        self.log_text.tag_configure("warn", foreground="#d80")
        self.log_text.tag_configure("dim", foreground="#888")

    def _load_defaults(self):
        desktop = Path.home() / "Desktop"
        if not desktop.exists():
            desktop = Path.home()
        self.output_var.set(str(desktop / "hok_build"))

    def _pick_apk(self):
        path = filedialog.askopenfilename(
            title="选择王者荣耀官方 APK",
            filetypes=[("APK files", "*.apk"), ("All files", "*.*")])
        if path:
            self.apk_path_var.set(path)
            self._update_apk_info()

    def _pick_output(self):
        path = filedialog.askdirectory(title="选择输出目录")
        if path:
            self.output_var.set(path)

    def _pick_ndk(self):
        path = filedialog.askdirectory(title="选择 Android NDK 目录")
        if path:
            self.ndk_path_var.set(path)

    def _update_apk_info(self):
        apk = self.apk_path_var.get()
        if not apk or not os.path.exists(apk):
            self.apk_info_label.config(text="")
            return
        size_mb = os.path.getsize(apk) / (1024 * 1024)
        self.apk_info_label.config(
            text=f"📦 {os.path.basename(apk)}  |  {size_mb:.1f} MB")

    def _on_toggle_private(self, *args):
        """当勾选切换时，禁用/启用私服输入区"""
        enabled = self.use_private_var.get()
        state = tk.NORMAL if enabled else tk.DISABLED
        self.server_entry.config(state=state)
        self.server_hint.config(state=state)
        self.scan_btn.config(state=state)
        if enabled:
            self.server_frame.pack(fill=tk.X, pady=(8, 0))
        else:
            self.server_frame.pack_forget()

    def _scan_apk(self):
        apk = self.apk_path_var.get()
        if not apk or not os.path.exists(apk):
            messagebox.showwarning("提示", "请先选择 APK 文件")
            return
        self._log("扫描 APK 中的服务器地址...", "info")
        self.progress.start(10)
        self.status_label.config(text="扫描中...")

        def do_scan():
            try:
                result = subprocess.run(
                    [sys.executable, str(SCRIPT_DIR / "hokstrap.py"),
                     "scan", apk],
                    capture_output=True, text=True, timeout=60, cwd=str(SCRIPT_DIR))
                self.root.after(0, self._on_scan_done, result)
            except Exception as e:
                self.root.after(0, self._log, f"扫描失败: {e}", "err")
                self.root.after(0, self.progress.stop)

        threading.Thread(target=do_scan, daemon=True).start()

    def _on_scan_done(self, result):
        self.progress.stop()
        output = result.stdout or result.stderr
        self.scan_result.config(state=tk.NORMAL)
        self.scan_result.delete("1.0", tk.END)
        self.scan_result.insert(tk.END, output)
        self.scan_result.config(state=tk.DISABLED)
        self._log(f"扫描完成 (退出码: {result.returncode})",
                   "ok" if result.returncode == 0 else "warn")
        self.status_label.config(text="扫描完成")

    def _check_device(self):
        self._log("检查 ADB 设备连接...", "info")

        def do_check():
            try:
                result = subprocess.run(
                    ["adb", "devices"], capture_output=True, text=True, timeout=10)
                self.root.after(0, self._on_device_checked, result)
            except FileNotFoundError:
                self.root.after(0, self._on_device_checked, None)
            except Exception as e:
                self.root.after(0, self._on_device_checked, str(e))

        threading.Thread(target=do_check, daemon=True).start()

    def _on_device_checked(self, result):
        if result is None:
            self.device_status.config(text="ADB 未找到，请安装 Android SDK", foreground="#c33")
            self._log("ADB 未找到", "err")
            return
        if isinstance(result, str):
            self.device_status.config(text=f"错误: {result}", foreground="#c33")
            return
        output = result.stdout
        lines = [l for l in output.strip().split('\n') if l.strip()]
        devices = [l for l in lines[1:] if 'device' in l and 'unauthorized' not in l]
        if devices:
            self.device_status.config(text=f"已连接: {devices[0].split()[0]}", foreground="#2a7")
            self._log(f"设备已连接: {devices[0]}", "ok")
        else:
            self.device_status.config(text="未检测到设备", foreground="#d80")
            self._log("未检测到 ADB 设备", "warn")

    def _start_build(self):
        apk = self.apk_path_var.get()
        if not apk or not os.path.exists(apk):
            messagebox.showerror("错误", "请选择有效的 APK 文件")
            return

        use_private = self.use_private_var.get()
        server = self.server_var.get().strip()

        if use_private and not server:
            messagebox.showerror("错误", "请输入私服服务器地址，\n或取消勾选'填入私服IP'以直连官方")
            return

        output = self.output_var.get().strip()
        if not output:
            messagebox.showerror("错误", "请选择输出目录")
            return

        os.makedirs(output, exist_ok=True)

        cmd = [
            sys.executable, str(ULTIMATE_BUILDER),
            apk,
        ]

        if use_private:
            cmd.extend(["--server", server])
        else:
            cmd.append("--official")

        cmd.extend(["--output", output,
                     "--game-pkg", self.pkg_var.get().strip()])

        if self.force_var.get():
            cmd.append("--force")
        if not self.esp_enabled_var.get():
            cmd.append("--skip-esp")
        if self.ndk_path_var.get().strip():
            cmd.extend(["--ndk", self.ndk_path_var.get().strip()])

        self.build_btn.config(state=tk.DISABLED)
        self.progress.start(10)
        self.status_label.config(text="构建中...")
        self._log("开始构建...", "ok")
        self._log(f"命令: {' '.join(cmd)}", "dim")

        def do_build():
            try:
                proc = subprocess.Popen(
                    cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                    text=True, cwd=str(SCRIPT_DIR),
                    bufsize=1, universal_newlines=True)

                for line in proc.stdout:
                    self.root.after(0, self._log, line.rstrip(), "info")

                proc.wait()
                self.root.after(0, self._on_build_done, proc.returncode, output)
            except Exception as e:
                self.root.after(0, self._log, f"构建失败: {e}", "err")
                self.root.after(0, self._on_build_done, -1, output)

        threading.Thread(target=do_build, daemon=True).start()

    def _on_build_done(self, returncode, output_dir):
        self.progress.stop()
        self.build_btn.config(state=tk.NORMAL)

        if returncode == 0:
            self.status_label.config(text="构建完成 ✓", foreground="#2a7")
            self._log("━━━ 构建成功 ━━━", "ok")
            deploy_dir = os.path.join(output_dir, "deploy")
            if os.path.isdir(deploy_dir):
                self._log(f"产物目录: {deploy_dir}", "ok")
                for f in os.listdir(deploy_dir):
                    fpath = os.path.join(deploy_dir, f)
                    if os.path.isfile(fpath):
                        size = os.path.getsize(fpath)
                        self._log(f"  📄 {f}  ({size:,} bytes)", "dim")

            if self.open_output_var.get():
                self._open_dir(output_dir)

            game_apk_name = "game_official.apk" if not self.use_private_var.get() else "game_private.apk"
            esp_line = "2. 安装 deploy/esp_overlay.apk (如启用ESP)\n" if self.esp_enabled_var.get() else ""
            messagebox.showinfo(
                "构建完成",
                f"✅ 构建成功！\n\n"
                f"输出目录: {output_dir}\n\n"
                f"部署步骤:\n"
                f"1. 将 deploy/{game_apk_name} 安装到手机\n"
                f"{esp_line}"
                f"{'3' if esp_line else '2'}. 运行 bash deploy/install.sh 一键部署")
        else:
            self.status_label.config(text="构建失败 ✗", foreground="#c33")
            self._log(f"━━━ 构建失败 (exit={returncode}) ━━━", "err")
            messagebox.showerror("构建失败",
                                  f"构建过程中出现错误 (退出码: {returncode})\n\n请查看日志了解详情")

    def _log(self, msg, level="info"):
        self.log_text.config(state=tk.NORMAL)
        self.log_text.insert(tk.END, msg + "\n", level)
        self.log_text.see(tk.END)
        self.log_text.config(state=tk.DISABLED)

    def _clear_log(self):
        self.log_text.config(state=tk.NORMAL)
        self.log_text.delete("1.0", tk.END)
        self.log_text.config(state=tk.DISABLED)

    def _open_output(self):
        output = self.output_var.get().strip()
        if output and os.path.isdir(output):
            self._open_dir(output)
        else:
            messagebox.showinfo("提示", "输出目录不存在，请先执行构建")

    def _open_dir(self, path):
        try:
            if sys.platform == "darwin":
                subprocess.Popen(["open", path])
            elif sys.platform == "win32":
                subprocess.Popen(["explorer", path])
            else:
                subprocess.Popen(["xdg-open", path])
        except Exception:
            pass


def main():
    root = tk.Tk()
    app = BuildApp(root)

    if len(sys.argv) > 1:
        apk_path = sys.argv[1]
        if os.path.exists(apk_path):
            app.apk_path_var.set(apk_path)
            app._update_apk_info()

    root.mainloop()


if __name__ == "__main__":
    main()
