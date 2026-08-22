package com.esp

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Root 操作助手 — 多策略 su 探测 + 命令执行。
 *
 * 背景: 不同 root 实现的 su 行为差异巨大 —
 *  - Magisk/KernelSU/SuperSU: 交互式 shell 与 `su -c` 都支持;
 *  - 雷电/MuMu 等模拟器自带 root: 常只支持 `su -c` (或 AOSP 语法 `su 0`),
 *    交互式模式可能既无输出也不报错, 直到内部超时 (~30s) 退出
 *    (表现为日志 "su 进程已退出 + banner 样例为空");
 *  - userdebug 系统 su: AOSP 语法 `su 0 <cmd>`。
 *
 * 因此按序探测三种模式, 首个返回 uid=0 的生效:
 *  1. `su -c id`     — 一次性命令模式, 兼容面最广, 优先;
 *  2. `su 0 id`      — AOSP 原生语法;
 *  3. 交互式持久 shell — 读泵线程 + 行队列, 授权后常驻复用。
 * 每一步的输出 / 退出码全部写入部署日志, 失败可直接定位。
 */
object RootHelper {
    private const val TAG = "ESP-Root"
    private const val TARGET_DIR = "/data/adb/esp"
    private const val TARGET_BIN = "$TARGET_DIR/tv_reader"
    private const val LOG_FILE = "$TARGET_DIR/tv_reader.log"

    // ---- 探测协议 ----
    private const val PROBE_MARK = "__ESP_PROBE__"
    private const val EOF_MARK = "__ESP_EOF____"

    /** 单次 su 探测超时 (覆盖授权弹窗出现 + 用户点击) */
    private const val PROBE_TIMEOUT_MS = 20_000L
    /** 交互式 shell 授权等待总时长 */
    private const val SHELL_WAIT_MS = 45_000L
    /** 无响应时探测命令重发间隔 */
    private const val PROBE_RESEND_MS = 1_000L
    /** 单条命令执行超时 */
    private const val CMD_TIMEOUT_MS = 30_000L

    // ---- su 模式 ----
    private enum class SuMode { UNKNOWN, ONESHOT_C, ONESHOT_UID, PERSISTENT }

    @Volatile private var suMode = SuMode.UNKNOWN

    // ---- 持久 root shell (仅 PERSISTENT 模式使用) ----
    private var suProcess: Process? = null
    private var suStdin: DataOutputStream? = null
    private var suStdout: BufferedReader? = null
    private var pumpThread: Thread? = null
    private val lineQueue = ConcurrentLinkedQueue<String>()
    @Volatile private var shellReady = false
    @Volatile private var streamEof = false

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

    // ==================== su 模式探测 ====================

    /**
     * 确保 root 可用: 已有生效模式直接复用, 否则按序探测多种 su 语法。
     * 针对雷电/MuMu 等模拟器的 Su 实现缺陷, 优先使用 sh -c 包装命令,
     * 并引入 "退出码兜底" 机制 (即使 stdout 被吞, exit=0 也视为授权)。
     */
    @Synchronized
    private fun ensureShell(): Boolean {
        when (suMode) {
            SuMode.ONESHOT_C, SuMode.ONESHOT_UID -> return true
            SuMode.PERSISTENT -> if (shellReady && suProcess != null) return true
            SuMode.UNKNOWN -> {}
        }
        killShell()
        suMode = SuMode.UNKNOWN
        DeployLogger.i("Root", "--- su 模式探测 (针对模拟器兼容优化) ---")

        // 策略 1: su -c sh -c 'id' (Magisk/模拟器 root 均支持, 强制 shell 执行)
        if (probeOneshot(arrayOf("su", "-c", "sh -c 'id'"), "su -c sh -c 'id'", SuMode.ONESHOT_C)) return true
        // 策略 2: su 0 sh -c 'id' (AOSP 原生语法, 雷电模拟器特化路径)
        if (probeOneshot(arrayOf("su", "0", "sh", "-c", "id"), "su 0 sh -c id", SuMode.ONESHOT_UID)) return true
        // 策略 3: su -c id (简单命令, 兜底)
        if (probeOneshot(arrayOf("su", "-c", "id"), "su -c id", SuMode.ONESHOT_C)) return true
        // 策略 4: 交互式持久 shell
        if (probePersistent()) {
            suMode = SuMode.PERSISTENT
            return true
        }

        DeployLogger.e("Root", "--- 四种 su 模式均未获得 uid=0 ---")
        DeployLogger.e("Root", "排查建议: ① 模拟器需在设置中开启 ROOT 并重启模拟器 " +
                "(雷电: 设置→其他设置→ROOT权限) ② Magisk/KernelSU 的超级用户列表里确认已授权本应用")
        return false
    }

    /**
     * 一次性命令探测: 跑一条 `id`, 看输出是否 uid=0。
     * 针对模拟器缺陷, 增加退出码兜底: 若 stdout 为空但 exit=0, 视为授权成功
     * (部分 Su 实现会吞掉 stdout, 导致读取线程阻塞或无输出)。
     */
    private fun probeOneshot(args: Array<String>, desc: String, mode: SuMode): Boolean {
        return try {
            DeployLogger.i("Root", "探测 [$desc] (最长 ${PROBE_TIMEOUT_MS / 1000}s, 若弹授权框请点允许)...")
            val p = ProcessBuilder(*args)
                .redirectErrorStream(true)
                .start()
            val sb = StringBuilder()
            val t = Thread {
                try {
                    BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                        while (true) {
                            val l = r.readLine() ?: break
                            sb.appendLine(l)
                        }
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }

            // 轮询等待退出
            val start = SystemClock.elapsedRealtime()
            var exited = false
            while (SystemClock.elapsedRealtime() - start < PROBE_TIMEOUT_MS) {
                try { p.exitValue(); exited = true; break } catch (_: IllegalThreadStateException) {}
                Thread.sleep(50)
            }
            if (!exited) {
                p.destroyForcibly()
                DeployLogger.e("Root", "[$desc] ✗ 超时无响应 (进程挂起, 可能 Su 实现缺陷)")
                return false
            }
            t.join(300)
            val out = sb.toString().trim()
            val exitCode = p.exitValue()
            
            // 判定逻辑:
            // 1. 明确成功: stdout 包含 uid=0
            // 2. 兜底成功: stdout 为空 + exit=0 + 无拒绝关键字 (针对 Su 吞 stdout 的情况)
            // 3. 明确失败: 包含 denied 或 exit != 0
            val hasUid0 = out.contains("uid=0")
            val hasDenied = out.contains("denied", true) || out.contains("not allowed", true)
            val fallbackOk = out.isEmpty() && exitCode == 0 && !hasDenied
            
            val ok = hasUid0 || fallbackOk
            if (ok) {
                suMode = mode
                DeployLogger.i("Root", "[$desc] ✓ 授权成功 (exit=$exitCode, stdout='${out.take(60)}')" + 
                    if (fallbackOk) " [退出码兜底: Su 吞掉了 stdout]" else "")
            } else {
                DeployLogger.e("Root", "[$desc] ✗ exit=$exitCode 输出: ${out.ifEmpty { "(无输出, 可能 Su 实现缺陷或权限被拒)" }.take(150)}")
            }
            ok
        } catch (e: Exception) {
            DeployLogger.e("Root", "[$desc] ✗ 异常: ${e.message}")
            false
        }
    }

    /**
     * 交互式持久 shell 探测。
     *
     * 协议: `id; echo __ESP_PROBE__` —
     *   - 读到含 uid=0 的行 → 成功;
     *   - banner/prompt/回显行一律跳过 (不设行数上限);
     *   - 每 1s 无有效响应就重发探测 (授权期间首条命令可能被 su 吞掉);
     *   - su 进程退出 (拒绝授权) → 立即失败。
     */
    private fun probePersistent(): Boolean {
        return try {
            DeployLogger.i("Root", "探测 [交互式 su] (授权框出现请点允许, 最长 ${SHELL_WAIT_MS / 1000}s)...")
            val p = ProcessBuilder("su")
                .redirectErrorStream(true)
                .start()
            suProcess = p
            suStdin = DataOutputStream(p.outputStream)
            suStdout = BufferedReader(InputStreamReader(p.inputStream))
            startPump()
            sendProbe()

            val start = SystemClock.elapsedRealtime()
            var lastSend = start
            var bannerCount = 0
            val bannerSample = StringBuilder()

            while (SystemClock.elapsedRealtime() - start < SHELL_WAIT_MS) {
                if (streamEof) {
                    DeployLogger.e("Root", "[交互式 su] ✗ su 进程已退出 — 授权被拒绝 / 不支持交互模式")
                    break
                }
                val line = lineQueue.poll()
                if (line == null) {
                    if (SystemClock.elapsedRealtime() - lastSend >= PROBE_RESEND_MS) {
                        sendProbe()
                        lastSend = SystemClock.elapsedRealtime()
                    } else {
                        Thread.sleep(50)
                    }
                    continue
                }
                when {
                    line.contains("uid=0") -> {
                        drainQueue()
                        shellReady = true
                        DeployLogger.i("Root", "[交互式 su] ✓ 授权成功: ${line.trim()}")
                        return true
                    }
                    line.contains("uid=") -> {
                        DeployLogger.e("Root", "[交互式 su] ✗ 非 root 身份: $line")
                        return false
                    }
                    line.contains(PROBE_MARK) -> {
                        sendProbe()
                        lastSend = SystemClock.elapsedRealtime()
                    }
                    line.isNotBlank() -> {
                        bannerCount++
                        if (bannerCount <= 6) bannerSample.appendLine("  | ${line.take(80)}")
                    }
                }
            }
            DeployLogger.e("Root", "[交互式 su] ✗ 超时未读到 uid=0 (banner 样例: ${bannerSample.toString().trim()})")
            killShell()
            false
        } catch (e: Exception) {
            DeployLogger.e("Root", "[交互式 su] ✗ 启动失败: ${e.message} (su 可能不存在)")
            Log.w(TAG, "su shell 启动失败: ${e.message}")
            killShell()
            false
        }
    }

    /** 发送探测命令 (id + marker)。 */
    private fun sendProbe() {
        try {
            suStdin!!.writeBytes("id; echo $PROBE_MARK\n")
            suStdin!!.flush()
        } catch (_: Exception) {
            streamEof = true
        }
    }

    /** 读泵线程: 独占 suStdout 持续 readLine → 行队列。流关闭时置 EOF 标记。 */
    private fun startPump() {
        pumpThread = Thread {
            try {
                val reader = suStdout ?: return@Thread
                while (true) {
                    val line = reader.readLine() ?: break
                    lineQueue.offer(line)
                }
            } catch (_: Exception) {
            } finally {
                streamEof = true
                lineQueue.offer(EOF_MARK)
            }
        }.apply {
            isDaemon = true
            name = "ESP-SuPump"
            start()
        }
    }

    /** 丢弃队列中探测阶段的残留行 (banner/marker/回显)。
     *  管道缓冲里可能还有在途行, 等 150ms 再清一次。 */
    private fun drainQueue() {
        while (lineQueue.poll() != null) { /* drain */ }
        try { Thread.sleep(150) } catch (_: InterruptedException) {}
        while (lineQueue.poll() != null) { /* drain */ }
    }

    /** 关闭并重置持久 shell。 */
    @Synchronized
    fun killShell() {
        try { suStdin?.writeBytes("exit\n"); suStdin?.flush() } catch (_: Exception) {}
        try { suStdin?.close() } catch (_: Exception) {}
        try { suStdout?.close() } catch (_: Exception) {}
        try { suProcess?.destroy() } catch (_: Exception) {}
        suProcess = null; suStdin = null; suStdout = null
        pumpThread = null
        lineQueue.clear()
        shellReady = false
        streamEof = false
    }

    // ==================== 命令执行 ====================

    /**
     * 执行 root 命令。自动路由到已探测的 su 模式:
     *  - ONESHOT_*: 每条命令独立 `su -c "..."` (已授权则零弹窗);
     *  - PERSISTENT: 复用常驻 shell (marker 协议)。
     */
    @Synchronized
    fun execute(command: String): RootResult {
        if (!ensureShell()) return RootResult(false, "", "未获得 Root 授权")
        return when (suMode) {
            SuMode.ONESHOT_C -> executeOneshot(arrayOf("su", "-c"), command)
            SuMode.ONESHOT_UID -> executeOneshot(arrayOf("su", "0", "sh", "-c"), command)
            SuMode.PERSISTENT -> executePersistent(command)
            SuMode.UNKNOWN -> RootResult(false, "", "su 模式未初始化")
        }
    }

    /** 一次性模式: `su -c "sh -c '(cmd) 2>&1; echo MARK:$?'"`, 收全部输出后解析 marker。
     *  使用 sh -c 包装是为了兼容模拟器 Su 实现 (直接传命令可能被吞)。 */
    private fun executeOneshot(suPrefix: Array<String>, command: String): RootResult {
        val marker = "CMD_DONE_${System.nanoTime()}"
        val innerCmd = "($command) 2>&1; echo ${marker}:\$?"
        val full = "sh -c '$innerCmd'"
        val args = suPrefix + full
        return try {
            val p = ProcessBuilder(*args)
                .redirectErrorStream(true)
                .start()
            val sb = StringBuilder()
            val t = Thread {
                try {
                    BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                        while (true) {
                            val l = r.readLine() ?: break
                            sb.appendLine(l)
                        }
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }

            val start = SystemClock.elapsedRealtime()
            var exited = false
            while (SystemClock.elapsedRealtime() - start < CMD_TIMEOUT_MS) {
                try { p.exitValue(); exited = true; break } catch (_: IllegalThreadStateException) {}
                Thread.sleep(25)
            }
            if (!exited) {
                p.destroyForcibly()
                return RootResult(false, "", "命令超时 (${CMD_TIMEOUT_MS / 1000}s): ${command.take(60)}")
            }
            t.join(300)
            val out = sb.toString()
            val exitCode = p.exitValue()

            var code: Int? = null
            val outLines = StringBuilder()
            for (l in out.lines()) {
                if (l.startsWith("$marker:")) {
                    code = l.substring(marker.length + 1).trim().toIntOrNull()
                } else if (l.isNotBlank() || outLines.isNotEmpty()) {
                    outLines.appendLine(l)
                }
            }

            // 兜底: 如果没有 marker (被 Su 吞) 但 exit=0, 视为成功
            if (code == null) {
                if (exitCode == 0) {
                    return RootResult(true, outLines.toString().trim(), "")
                }
                return RootResult(false, outLines.toString().trim(),
                    "su 未返回结果 (可能 Su 吞掉 stdout): exit=$exitCode")
            }
            RootResult(code == 0, outLines.toString().trim(), "")
        } catch (e: Exception) {
            RootResult(false, "", "Root 会话错误: ${e.message}")
        }
    }

    /** 持久模式: marker 协议, 从行队列消费输出。 */
    private fun executePersistent(command: String): RootResult {
        val marker = "CMD_DONE_${System.nanoTime()}"
        return try {
            suStdin!!.writeBytes("($command) 2>&1; echo ${marker}:\$?\n")
            suStdin!!.flush()
            val sb = StringBuilder()
            val deadline = SystemClock.elapsedRealtime() + CMD_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                val line = lineQueue.poll()
                if (line == null) {
                    if (streamEof) {
                        killShell()
                        return RootResult(false, sb.toString().trim(), "Root 会话已结束")
                    }
                    Thread.sleep(25)
                    continue
                }
                when {
                    line == EOF_MARK -> {
                        killShell()
                        return RootResult(false, sb.toString().trim(), "Root 会话已结束")
                    }
                    line.startsWith("$marker:") -> {
                        val code = line.substring(marker.length + 1).trim().toIntOrNull() ?: -1
                        return RootResult(code == 0, sb.toString().trim(), "")
                    }
                    // 探测残留行混入 — 跳过不污染命令输出
                    // (只跳 marker, 不跳 uid 行 — diagnose 的 id 命令输出需要保留)
                    line.contains(PROBE_MARK) -> { /* skip */ }
                    else -> sb.appendLine(line)
                }
            }
            killShell()
            RootResult(false, sb.toString().trim(), "命令超时 (${CMD_TIMEOUT_MS / 1000}s): ${command.take(60)}")
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
        // 输出形如 "TEST_OK:126" — 只有退出码 0 才证明二进制真的可执行
        // (126=Exec format error/ABI 不匹配, 127=not found, 只查前缀会误判)
        val ok = r.output.contains("TEST_OK:0")
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
        execute("lsof $TARGET_BIN 2>/dev/null || true")
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

            execute("rm -f $TARGET_BIN")
            Thread.sleep(50)

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
     * 覆盖: 设备 ABI / su 位置与版本 / SELinux / 挂载 noexec /
     *       游戏安装与进程 / 读取器进程 / 端口监听 / 磁盘空间。
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
        run("su 位置", "command -v su; ls -la /system/bin/su /system/xbin/su /sbin/su 2>/dev/null")
        run("su 版本", "su -v 2>/dev/null || su --version 2>/dev/null || echo 无版本输出")
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
        if (diag.contains("su 位置") && diag.substringAfter("su 位置", "").substringBefore("\n").contains("(无输出)")) {
            hints.add("su 不存在: 设备未 Root 或 root 管理器异常 (模拟器需在设置中开启 ROOT 并重启)")
        }
        if (diag.contains("Exec format error", true)) {
            hints.add("ABI 不匹配: 二进制架构与设备 CPU 不符 → 检查是否在 ARM 设备上跑了 x86 模拟器资产 (或反之)")
        }
        if (hints.isEmpty()) hints.add("未识别到明确原因, 请结合上方原始诊断输出与 tv_reader 日志尾部判断")
        return hints
    }
}
