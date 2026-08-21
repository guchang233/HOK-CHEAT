package com.truevision

import android.util.Log
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket

object ReaderClient {
    private const val TAG = "TV-Reader"
    private const val HOST = "127.0.0.1"
    private const val PORT = 47291

    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var listener: ((Status) -> Unit)? = null

    data class Status(
        val connected: Boolean,
        val frame: TvFrame? = null,
        val msg: String = ""
    )

    fun start(callback: (Status) -> Unit) {
        if (running) return
        running = true
        listener = callback

        thread = Thread {
            while (running) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(HOST, PORT), 3000)
                        socket.tcpNoDelay = true
                        // 5 秒收不到数据 = 断了 (reader 卡死 / 关进程都会触发)
                        socket.soTimeout = 5000
                        val input = DataInputStream(socket.getInputStream())
                        notify(Status(true, null, "connected"))

                        while (running) {
                            val frame = TvFrame.parse(input)
                            notify(Status(true, frame, "f${frame.frameId} n=${frame.count}"))
                        }
                    }
                } catch (e: Exception) {
                    notify(Status(false, null, e.message ?: e.javaClass.simpleName))
                    Log.w(TAG, "socket error", e)
                    try { Thread.sleep(500) } catch (_: InterruptedException) {}
                }
            }
        }.apply {
            isDaemon = true
            name = "TVReaderClient"
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        listener = null
    }

    private fun notify(s: Status) {
        listener?.invoke(s)
    }
}
