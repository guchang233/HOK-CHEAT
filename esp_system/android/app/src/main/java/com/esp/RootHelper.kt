package com.esp

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader

/**
 * Root 操作助手 — 持久 root shell 模式。
 *
 * 关键设计: 整个应用生命周期只启动一次 `su` 交互式进程,
 * 后续所有命令通过 stdin 写入、stdout 读取 (marker 协议)。
 * 好处: root 管理器只弹一次授权框, 不再每条命令都询问。
 */
object RootHelper {
    private const val TAG = "ESP-Root"
    private const val TARGET_DIR = "/data/adb/esp"
    private const val TARGET_BIN = "$TARGET_DIR/tv_reader"
    private const val LOG_FILE = "$TARGET_DIR/tv_reader.log"

    // ---- 持久 root shell ----
    private var suProcess: Process? = null
    private var suStdin: DataOutputStream? = null
    private var suStdout: BufferedReader? = null
    @Volatile private var shellReady = false

    /**
     * 按 CPU ABI 选择要部署的 tv_reader 资产名。
     * 雷电/MuMu 等模拟器是 x86_64 (ARM 转译仅覆盖应用进程,
     * root shell 下裸执行 ARM ELF 会失败), 必须用原生 x86_64。
     */
    fun pickReaderAsset(): String {
        val abi = Build.SUPPORTED_ABIS?.firstOrNull() ?: Build.CPU_ABI
        return when {
            abi.startsWith("x86_64") -> "tv_reader_x64"
            abi.startsWith("x86") -> "tv_reader_x64"   // 32 位 x86 暂无, 先试 x64
            abi.startsWith("arm64") -> "tv_reader_arm64"
            abi.startsWith("armeabi") -> "tv_reader_arm64"
            else -> "tv_reader_arm64"
        }
    }

    data class RootResult(
        val success: Boolean,
        val output: String,
        val error: String
    )

    /**
     * 启动 (或复用) 持久 su shell。首次调用时 root 管理器弹一次授权;
     * 授权后 shell 存活期间不再有任何弹窗。
     */
    @Synchronized
    private fun ensureShell(): Boolean {
        if (shellReady && suProcess != null) return true
        killShell()
        return try {
            DeployLogger.i("Root", "拉起 su shell (首次会弹授权框)...")
            val p = ProcessBuilder("su")
                .redirectErrorStream(true)   // 合并 stderr, 防止缓冲区涨满死锁
                .start()
            suProcess = p
            suStdin = DataOutputStream(p.outputStream)
            suStdout = BufferedReader(InputStreamReader(p.inputStream))

            // 探测: 等 shell 就绪并确认 uid=0 (授权弹窗发生在这里, 仅此一次)。
            // 注意: 部分 root 管理器 (Magisk/KernelSU/SuperSU) 会在 uid 行之前
            // 先输出 banner 行, 只读一行会误判失败 → 杀 shell → 下次重新拉起
            // su → 再次弹授权窗。这里循环读取直到出现 uid 行 (或 su 退出)。
            suStdin!!.writeBytes("id\n")
            suStdin!!.flush()
            var uidLine: String? = null
            var bannerLines = 0
            while (bannerLines < 12) {
                val line = suStdout!!.readLine() ?: break   // su 退出 (被拒绝)
                if (line.contains("uid=")) { uidLine = line; break }
                if (line.isNotBlank()) bannerLines++
            }
            shellReady = uidLine != null && uidLine.contains("uid=0")
            DeployLogger.i("Root", "su 响应: ${uidLine ?: "(无 uid 输出)"} → ${if (shellReady) "授权成功" else "未授权"}")
            if (!shellReady) killShell()
            shellReady
        } catch (e: Exception) {
            DeployLogger.e("Root", "su shell 启动失败: ${e.message} (su 可能不存在)")
            Log.w(TAG, "su shell 启动失败: ${e.message}")
            killShell()
            false
        }
    }

    /** 关闭并重置 shell (下次 execute 会重新拉起, 若已授权则静默复权)。 */
    @Synchronized
    fun killShell() {
        try { suStdin?.writeBytes("exit\n"); suStdin?.flush() } catch (_: Exception) {}
        try { suStdin?.close() } catch (_: Exception) {}
        try { suStdout?.close() } catch (_: Exception) {}
        try { suProcess?.destroy() } catch (_: Exception) {}
        suProcess = null; suStdin = null; suStdout = null
        shellReady = false
    }

    /**
     * 在持久 root shell 中执行命令 (marker 协议)。
     * 与旧版 su -c 每次新进程不同: 不再反复触发授权弹窗。
     */
    @Synchronized
    fun execute(command: String): RootResult {
        if (!ensureShell()) return RootResult(false, "", "未获得 Root 授权")
        val marker = "CMD_DONE_${System.nanoTime()}"
        return try {
            // 2>&1 合并错误输出; $? 取 (command) 的退出码
            suStdin!!.writeBytes("($command) 2>&1; echo ${marker}:\$?\n")
            suStdin!!.flush()
            val sb = StringBuilder()
            while (true) {
                val line = suStdout!!.readLine()
                if (line == null) {
                    // shell 已退出 (被系统杀掉等) — 重置, 下次重建
                    killShell()
                    return RootResult(false, sb.toString().trim(), "Root 会话已结束")
                }
                if (line.startsWith("$marker:")) {
                    val code = line.substring(marker.length + 1).trim().toIntOrNull() ?: -1
                    return RootResult(code == 0, sb.toString().trim(), "")
                }
                sb.appendLine(line)
            }
            @Suppress("UNREACHABLE_CODE")
            RootResult(false, "", "")  // 不可达, 让编译器满意
        } catch (e: Exception) {
            killShell()
            RootResult(false, "", "Root 会话错误: ${e.message}")
        }
    }

    fun isRootAvailable(): Boolean = ensureShell()

    /**
     * 验证已部署二进制能否在当前环境执行 (非 x86 设备上执行 x86 ELF 会失败)。
     */
    fun launchTest(): Boolean {
        val r = execute("$TARGET_BIN --help >/dev/null 2>&1; echo TEST_OK:\$?")
        val ok = r.output.contains("TEST_OK:")
        // 注意: 输出含 "not executable"/"Exec format error" 时说明 ELF 架构不匹配
        DeployLogger.i("Deploy", "ELF 可执行性测试 → ${if (ok) "通过" else "不可执行: ${r.output.take(120)}"}")
        if (!ok && r.output.contains("Format error", true)) {
            DeployLogger.w("Deploy", "Exec format error = ABI 不匹配 (如 ARM 设备上执行 x86 二进制)")
        }
        return ok
    }

    fun extractAndDeployReader(context: Context, assetName: String): RootResult {
        val t0 = System.currentTimeMillis()
        DeployLogger.i("Deploy", "--- 部署资产 $assetName ---")

        val mkdirResult = execute("mkdir -p $TARGET_DIR")
        DeployLogger.i("Deploy", "mkdir -p $TARGET_DIR → ${mkdirResult.output.ifEmpty { "OK" }}")
        if (!mkdirResult.success) {
            DeployLogger.e("Deploy", "mkdir 失败: ${mkdirResult.error}")
            return mkdirResult
        }

        // ---- 部署前强制清理残留进程 (防止 File busy) ----
        DeployLogger.i("Deploy", "清理旧进程 + 释放文件句柄...")
        execute("killall -9 tv_reader 2>/dev/null || true")
        execute("pkill -9 -f tv_reader 2>/dev/null || true")
        Thread.sleep(350)
        execute("lsof $TARGET_BIN 2>/dev/null || true")  // 检查是否仍有占用
        // 强制卸载可能残留的挂载
        execute("umount $TARGET_DIR 2>/dev/null || true")

        try {
            val tmpFile = File(context.cacheDir, "tv_reader_tmp")
            context.assets.open("native/$assetName").use { input ->
                tmpFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            val size = tmpFile.length()
            DeployLogger.i("Deploy", "资产解压到缓存: $size bytes")

            // ---- 先 rm -f 旧文件 (若无残留进程占用则可删除, 否则 cp 报 File busy) ----
            execute("rm -f $TARGET_BIN")
            Thread.sleep(50)

            // cp + 重试 (部分设备在 killall 后仍需短暂等待)
            var lastCopy: RootResult? = null
            for (attempt in 1..3) {
                val copyResult = execute("cp ${tmpFile.absolutePath} $TARGET_BIN")
                lastCopy = copyResult
                if (copyResult.success) {
                    DeployLogger.i("Deploy", "cp → $TARGET_BIN OK (尝试 $attempt)")
                    break
                }
                DeployLogger.w("Deploy", "cp 失败 (尝试 $attempt): ${copyResult.output}")
                if (attempt < 3) {
                    Thread.sleep(200L * attempt)
                    // 重试前再次强制清理
                    execute("killall -9 tv_reader 2>/dev/null || true")
                    execute("rm -f $TARGET_BIN")
                }
            }
            if (lastCopy != null && !lastCopy.success) {
                DeployLogger.e("Deploy", "cp 三次重试均失败: ${lastCopy.output}")
                val lsofResult = execute("lsof $TARGET_BIN 2>/dev/null || echo (无占用信息)")
                DeployLogger.e("Deploy", "诊断: lsof → ${lsofResult.output}")
                return lastCopy
            }

            execute("chmod 755 $TARGET_BIN")
            execute("chmod 755 $TARGET_DIR")
            DeployLogger.i("Deploy", "chmod 755 完成")

            tmpFile.delete()

            val check = execute("ls -la $TARGET_BIN")
            if (check.output.contains(TARGET_BIN)) {
                DeployLogger.i("Deploy", "部署完成 ($assetName), 耗时 ${System.currentTimeMillis() - t0}ms: ${check.output.trim()}")
                return RootResult(true, check.output, "")
            }

            DeployLogger.e("Deploy", "部署后 ls 未找到二进制: ${check.output}")
            return RootResult(false, "", "部署后未找到二进制")
        } catch (e: Exception) {
            DeployLogger.e("Deploy", "资产解压异常: ${e.message}")
            Log.e(TAG, "解压失败", e)
            return RootResult(false, "", e.message ?: "解压失败")
        }
    }

    fun launchReader(gamePkg: String, port: Int): RootResult {
        DeployLogger.i("Launch", "清理旧进程 (killall + pkill)...")
        execute("killall -9 tv_reader 2>/dev/null || true")
        execute("pkill -9 -f tv_reader 2>/dev/null || true")
        Thread.sleep(400)

        val cmd = "$TARGET_BIN --game-pkg $gamePkg --port $port"
        DeployLogger.i("Launch", "启动: nohup $cmd > $LOG_FILE 2>&1 &")
        execute("nohup $cmd > $LOG_FILE 2>&1 &")

        // 启动后多次探测, 给进程初始化留时间 (找不到游戏时 tv_reader 可能先跑几百 ms)
        var running = false
        var psOut = ""
        for (i in 1..3) {
            Thread.sleep(500)
            val checkResult = execute("ps -A | grep tv_reader || echo NOT_RUNNING")
            psOut = checkResult.output
            running = psOut.contains("tv_reader")
            if (running) break
            DeployLogger.w("Launch", "第 $i 次探测未发现进程, 继续等待...")
        }

        return if (running) {
            DeployLogger.i("Launch", "读取器进程运行中: ${psOut.trim().take(120)}")
            RootResult(true, "读取器运行中", "")
        } else {
            val logResult = execute("tail -20 $LOG_FILE 2>/dev/null || echo no_log")
            DeployLogger.e("Launch", "读取器未存活! 启动后日志尾部:")
            DeployLogger.e("Launch", logResult.output.ifBlank { "(无输出 — 可能启动即闪退或 noexec 拒绝执行)" })
            RootResult(false, "", "未运行: ${logResult.output.take(200)}")
        }
    }

    fun stopReader(): RootResult {
        return execute("killall tv_reader 2>/dev/null; echo done")
    }

    fun getReaderLog(lines: Int = 30): String {
        val result = execute("tail -$lines $LOG_FILE 2>/dev/null || echo 无日志")
        return result.output.ifEmpty { result.error }
    }

    fun verifyBinary(): String {
        val result = execute("ls -la $TARGET_BIN 2>/dev/null || echo 未部署")
        return result.output.take(120)
    }

    /**
     * 环境诊断 — 收集部署失败分析所需的全部现场信息。
     * 覆盖: 设备 ABI / Root / SELinux / 挂载 noexec / 游戏安装与进程 /
     *       读取器进程 / 端口监听 / 磁盘空间。
     */
    fun diagnose(gamePkg: String, port: Int): String {
        val sb = StringBuilder()
        fun sec(title: String) = sb.append("\n===== $title =====\n")
        fun run(label: String, cmd: String) {
            val r = execute(cmd)
            sb.append("[$label] $cmd\n  → ${r.output.ifEmpty { "(无输出)" }}\n")
        }

        DeployLogger.i("Diag", "开始环境诊断...")
        sec("设备")
        run("ABI", "getprop ro.product.cpu.abi; getprop ro.product.cpu.abilist")
        run("Android", "getprop ro.build.version.release; getprop ro.build.version.sdk")
        run("机型", "getprop ro.product.model; getprop ro.product.manufacturer")
        sec("Root")
        run("su", "command -v su || which su")
        run("uid", "id")
        sec("SELinux")
        run("模式", "getenforce")
        sec("部署目标")
        run("/data 挂载", "mount 2>/dev/null | grep ' /data ' | head -2")
        run("目录", "ls -la $TARGET_DIR 2>/dev/null || echo 目录不存在")
        run("二进制", "ls -la $TARGET_BIN 2>/dev/null || echo 未部署")
        sec("游戏")
        run("安装检测", "pm path $gamePkg 2>&1 | head -1")
        run("游戏进程", "ps -A 2>/dev/null | grep $gamePkg | head -3 || echo 游戏未运行")
        sec("读取器")
        run("进程", "ps -A 2>/dev/null | grep tv_reader || echo 未运行")
        run("端口 $port", "netstat -tlnp 2>/dev/null | grep :$port || echo 端口未监听")
        run("日志尾部", "tail -30 $LOG_FILE 2>/dev/null || echo 无日志文件")
        sec("存储")
        run("空间", "df -h /data 2>/dev/null | tail -1")

        val out = sb.toString()
        DeployLogger.i("Diag", out)
        return out
    }

    /**
     * 基于诊断输出给出人类可读的失败原因分析。
     */
    fun analyzeFailure(diag: String): List<String> {
        val hints = mutableListOf<String>()
        if (diag.contains("未安装", true) || (diag.contains("pm path", true) &&
                    diag.substringAfter("pm path", "").substringBefore("\n").contains("No package", true))
        ) {
            hints.add("游戏未安装或包名不匹配: 读取器启动后找不到目标进程会立即退出 → 先安装游戏 (com.tencent.tmgp.sgame) 再部署")
        }
        if (diag.contains("游戏未运行") && !diag.contains("tv_reader")) {
            hints.add("游戏进程未运行: 部署成功但读取器可能因找不到游戏而退出 → 先进游戏再重新部署")
        }
        if (diag.contains("Enforcing")) {
            hints.add("SELinux 处于 Enforcing: 部分系统会拒绝执行 /data/adb 下的二进制 → root 下执行 setenforce 0 后重试")
        }
        if (Regex("noexec").containsMatchIn(diag.substringAfter("/data 挂载", "").substringBefore("====="))) {
            hints.add("/data 分区带 noexec 标志: 该分区下的二进制无法执行 → 需换无 noexec 的目录部署")
        }
        if (diag.contains("command -v su") && diag.substringAfter("command -v su", "").substringBefore("\n").contains("(无输出)")) {
            hints.add("su 不存在: 设备未 Root 或 root 管理器异常")
        }
        if (diag.contains("Exec format error", true)) {
            hints.add("ABI 不匹配: 二进制架构与设备 CPU 不符 → 检查是否在 ARM 设备上跑了 x86 模拟器资产 (或反之)")
        }
        if (hints.isEmpty()) hints.add("未识别到明确原因, 请结合上方原始诊断输出与 tv_reader 日志尾部判断")
        return hints
    }
}
