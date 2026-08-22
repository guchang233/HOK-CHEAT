package com.esp

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

object RootHelper {
    private const val TAG = "ESP-Root"
    private const val TARGET_DIR = "/data/adb/esp"
    private const val TARGET_BIN = "$TARGET_DIR/tv_reader"
    private const val LOG_FILE = "$TARGET_DIR/tv_reader.log"

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

    fun isRootAvailable(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            out.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    fun execute(command: String): RootResult {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val stdout = proc.inputStream.bufferedReader().readText().trim()
            val stderr = proc.errorStream.bufferedReader().readText().trim()
            val exit = proc.waitFor()
            RootResult(exit == 0, stdout, stderr)
        } catch (e: Exception) {
            RootResult(false, "", e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 验证已部署二进制能否在当前环境执行 (非 x86 设备上执行 x86 ELF 会失败)。
     * tv_reader 无参运行打印用法后退出, 退出码 0/1 均代表"可执行"。
     */
    fun launchTest(): Boolean {
        val r = execute("$TARGET_BIN 2>&1; echo EXIT:$?")
        val out = r.output
        // 可执行: 自身产出输出 (用法/日志); 不可执行: shell 报 "not executable"/"Exec format error"
        return !out.contains("Exec format error", ignoreCase = true) &&
               !out.contains("not executable", ignoreCase = true) &&
               out.contains("EXIT:")
    }

    fun extractAndDeployReader(context: Context, assetName: String): RootResult {
        val mkdirResult = execute("mkdir -p $TARGET_DIR")
        if (!mkdirResult.success) {
            Log.e(TAG, "mkdir failed: ${mkdirResult.error}")
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
                Log.e(TAG, "cp failed: ${copyResult.error}")
                return copyResult
            }

            execute("chmod 755 $TARGET_BIN")
            execute("chmod 755 $TARGET_DIR")

            tmpFile.delete()

            val file = File(TARGET_BIN)
            if (file.exists()) {
                val size = file.length()
                Log.i(TAG, "tv_reader deployed: $size bytes → $TARGET_BIN")
                return RootResult(true, "deployed $size bytes", "")
            }

            Log.e(TAG, "Binary not found after copy")
            return RootResult(false, "", "binary not found after copy")
        } catch (e: Exception) {
            Log.e(TAG, "Extract failed", e)
            return RootResult(false, "", e.message ?: "Extract failed")
        }
    }

    fun launchReader(gamePkg: String, port: Int): RootResult {
        execute("killall tv_reader 2>/dev/null || true")
        Thread.sleep(200)

        val cmd = "$TARGET_BIN --game-pkg $gamePkg --port $port"
        val launchResult = execute("nohup $cmd > $LOG_FILE 2>&1 &")

        Thread.sleep(500)

        val checkResult = execute("ps -A | grep tv_reader || echo NOT_RUNNING")
        val running = checkResult.output.contains("tv_reader")

        Log.i(TAG, "Reader launch: running=$running")

        return if (running) {
            RootResult(true, "reader running", "")
        } else {
            val logResult = execute("tail -20 $LOG_FILE 2>/dev/null || echo no_log")
            Log.w(TAG, "Reader not running. Log: ${logResult.output}")
            RootResult(false, "", "not running: ${logResult.output.take(200)}")
        }
    }

    fun stopReader(): RootResult {
        return execute("killall tv_reader 2>/dev/null; echo done")
    }

    fun getReaderLog(lines: Int = 30): String {
        val result = execute("tail -$lines $LOG_FILE 2>/dev/null || echo no_log")
        return result.output.ifEmpty { result.error }
    }

    fun verifyBinary(): String {
        val file = File(TARGET_BIN)
        if (!file.exists()) return "NOT_DEPLOYED"
        val size = file.length()
        val head = try { file.readBytes().copyOfRange(0, minOf(4, size.toInt())) } catch (_: Exception) { byteArrayOf() }
        val isElf = head.size >= 4 && head[0] == 0x7f.toByte() &&
                     head[1] == 'E'.code.toByte() && head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
        val typeResult = execute("file $TARGET_BIN 2>/dev/null || echo unknown")
        return "size=${size}B, elf=$isElf, ${typeResult.output.take(80)}"
    }
}
