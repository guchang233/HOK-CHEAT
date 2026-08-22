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
            val p = ProcessBuilder("su")
                .redirectErrorStream(true)   // 合并 stderr, 防止缓冲区涨满死锁
                .start()
            suProcess = p
            suStdin = DataOutputStream(p.outputStream)
            suStdout = BufferedReader(InputStreamReader(p.inputStream))

            // 探测: 等 shell 就绪并确认 uid=0 (授权弹窗发生在这里, 仅此一次)
            suStdin!!.writeBytes("id\n")
            suStdin!!.flush()
            val first = suStdout!!.readLine()
            shellReady = first != null && first.contains("uid=0")
            if (!shellReady) killShell()
            shellReady
        } catch (e: Exception) {
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
        // 无 --help 时退出码非 0 也无妨, 只要能产出 TEST_OK 即代表 ELF 可执行
        return r.output.contains("TEST_OK:")
    }

    fun extractAndDeployReader(context: Context, assetName: String): RootResult {
        val mkdirResult = execute("mkdir -p $TARGET_DIR")
        if (!mkdirResult.success) {
            Log.e(TAG, "mkdir 失败: ${mkdirResult.error}")
            return mkdirResult
        }

        try {
            val tmpFile = File(context.cacheDir, "tv_reader_tmp")
            context.assets.open("native/$assetName").use { input ->
                tmpFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val copyResult = execute("cp ${tmpFile.absolutePath} $TARGET_BIN")
            if (!copyResult.success) {
                Log.e(TAG, "cp 失败: ${copyResult.error}")
                return copyResult
            }

            execute("chmod 755 $TARGET_BIN")
            execute("chmod 755 $TARGET_DIR")

            tmpFile.delete()

            val check = execute("ls -la $TARGET_BIN")
            if (check.output.contains(TARGET_BIN)) {
                Log.i(TAG, "tv_reader 已部署: $assetName → $TARGET_BIN")
                return RootResult(true, check.output, "")
            }

            Log.e(TAG, "部署后未找到二进制")
            return RootResult(false, "", "部署后未找到二进制")
        } catch (e: Exception) {
            Log.e(TAG, "解压失败", e)
            return RootResult(false, "", e.message ?: "解压失败")
        }
    }

    fun launchReader(gamePkg: String, port: Int): RootResult {
        execute("killall tv_reader 2>/dev/null || true")
        Thread.sleep(200)

        val cmd = "$TARGET_BIN --game-pkg $gamePkg --port $port"
        execute("nohup $cmd > $LOG_FILE 2>&1 &")

        Thread.sleep(500)

        val checkResult = execute("ps -A | grep tv_reader || echo NOT_RUNNING")
        val running = checkResult.output.contains("tv_reader")

        Log.i(TAG, "读取器启动: running=$running")

        return if (running) {
            RootResult(true, "读取器运行中", "")
        } else {
            val logResult = execute("tail -20 $LOG_FILE 2>/dev/null || echo no_log")
            Log.w(TAG, "读取器未运行。日志: ${logResult.output}")
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
}
