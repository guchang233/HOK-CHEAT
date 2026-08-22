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
 * Root 操作助手 — 持久 root shell 模式。
 *
 * 关键设计:
 *  1. 整个进程生命周期只拉起一次交互式 `su` — 授权弹窗最多出现一次;
 *  2. 专用读泵线程持续读 shell stdout → 行队列, 主逻辑轮询消费
 *     (readLine 阻塞且无超时, 必须与探测逻辑解耦);
 *  3. 授权探测采用「marker + 周期重发」, 不设 banner 行数上限:
 *     - su 等待授权期间, 先发的探测命令可能被 su 吞掉 → 需重发;
 *     - root 管理器授权后输出的 banner 行数不定 (旧版固定 12 行上限,
 *       授权成功却误判失败 → 杀 shell 重建 → 反复弹授权);
 *     - 只认 `uid=0` / marker 行, 总超时 45s (覆盖用户慢慢点弹窗),
 *       每 1s 重发一次探测命令。
 */
object RootHelper {
    private const val TAG = "ESP-Root"
    private const val TARGET_DIR = "/data/adb/esp"
    private const val TARGET_BIN = "$TARGET_DIR/tv_reader"
    private const val LOG_FILE = "$TARGET_DIR/tv_reader.log"

    // ---- 探测协议 ----
    private const val PROBE_MARK = "__ESP_PROBE__"
    private const val EOF_MARK = "__ESP_EOF____"

    /** 授权等待总时长: 覆盖用户看到弹窗并点击的时间 */
    private const val SHELL_WAIT_MS = 45_000L
    /** 无响应时探测命令重发间隔 */
    private const val PROBE_RESEND_MS = 1_000L
    /** 单条命令执行超时 */
    private const val CMD_TIMEOUT_MS = 30_000L

    // ---- 持久 root shell ----
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

    /**
     * 启动 (或复用) 持久 su shell。首次调用时 root 管理器弹一次授权;
     * 授权后 shell 存活期间不再有任何弹窗。
     *
     * 探测协议: `id; echo __ESP_PROBE__` —
     *   - 读到含 uid=0 的行 → 成功;
     *   - banner/prompt/回显行一律跳过 (不设行数上限!);
     *   - 每 1s 无有效响应就重发探测 (授权期间首条命令可能被 su 吞掉);
     *   - su 进程退出 (拒绝授权) → 立即失败。
     */
    @Synchronized
    private fun ensureShell(): Boolean {
        if (shellReady && suProcess != null) return true
        killShell()
        return try {
            DeployLogger.i("Root", "拉起 su shell (弹授权框请点允许, 最长等待 ${SHELL_WAIT_MS / 1000}s)...")
            val p = ProcessBuilder("su")
                .redirectErrorStream(true)   // 合并 stderr, 防止缓冲区涨满死锁
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
                    DeployLogger.e("Root", "su 进程已退出 — 授权被拒绝 / su 异常崩溃")
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
                        DeployLogger.i("Root", "Root 授权成功: ${line.trim()}")
                        return true
                    }
                    line.contains("uid=") -> {
                        DeployLogger.e("Root", "su 返回非 root 身份: $line")
                        return false
                    }
                    line.contains(PROBE_MARK) -> {
                        // 探测命令执行完却没见到 uid 行 (id 输出被吞/粘连) → 重发
                        sendProbe()
                        lastSend = SystemClock.elapsedRealtime()
                    }
                    line.isNotBlank() -> {
                        bannerCount++
                        if (bannerCount <= 6) bannerSample.appendLine("  | ${line.take(80)}")
                    }
                }
            }
            DeployLogger.e("Root", "su 探测失败: ${SHELL_WAIT_MS / 1000}s 内未读到 uid=0 (banner 样例: ${bannerSample.toString().trim()})")
            killShell()
            false
        } catch (e: Exception) {
            DeployLogger.e("Root", "su shell 启动失败: ${e.message} (su 可能不存在)")
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

    /** 关闭并重置 shell (下次 execute 会重新拉起, 若已授权则静默复权)。 */
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

    /**
     * 在持久 root shell 中执行命令 (marker 协议, 从行队列消费输出)。
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
        // (126=Exec format error/ABI 不匹配, 127=not found, 旧版只查前缀会误判)
        val ok = r.output.contains("TEST_OK:0")
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
