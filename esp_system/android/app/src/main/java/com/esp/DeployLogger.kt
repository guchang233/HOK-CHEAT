package com.esp

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 部署日志 — 内存环形缓冲 + 文件持久化双写。
 *
 * 用于分析部署失败原因: Root 授权、资产解压、ABI 切换、
 * 每条 root 命令的输出、进程存活检查等全部留痕。
 * 文件落在应用外部私有目录 (无需存储权限, 可 adb pull / 文件管理器取出):
 *   /storage/emulated/0/Android/data/com.esp.overlay/files/deploy_log.txt
 */
object DeployLogger {
    private const val TAG = "ESP-Deploy"
    private const val MAX_ENTRIES = 800
    private const val MAX_FILE_BYTES = 512 * 1024L

    private val buffer = ArrayDeque<String>(MAX_ENTRIES)
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var logFile: File? = null

    fun init(ctx: Context) {
        if (logFile != null) return
        synchronized(this) {
            if (logFile != null) return
            logFile = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "deploy_log.txt")
        }
    }

    fun i(tag: String, msg: String) = append("I", tag, msg)
    fun w(tag: String, msg: String) = append("W", tag, msg)
    fun e(tag: String, msg: String) = append("E", tag, msg)

    private fun append(level: String, tag: String, msg: String) {
        val line = "${fmt.format(Date())} $level/$tag: $msg"
        synchronized(this) {
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(line)
        }
        Log.println(
            when (level) {
                "E" -> Log.ERROR
                "W" -> Log.WARN
                else -> Log.INFO
            }, TAG, "$tag: $msg"
        )
        // 文件追加 (超限截断, 失败不影响主流程)
        try {
            val f = logFile ?: return
            if (f.length() > MAX_FILE_BYTES) f.delete()
            f.appendText(line + "\n")
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun get(): String = buffer.joinToString("\n")

    @Synchronized
    fun clear() {
        buffer.clear()
        try { logFile?.delete() } catch (_: Exception) {}
    }
}
