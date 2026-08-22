package com.esp

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket

object ReaderClient {
    private const val TAG = "ESP-Reader"
    private const val HOST = "127.0.0.1"
    private const val PORT = 47291
    private const val RECONNECT_DELAY_MS = 1000L
    private const val SOCKET_TIMEOUT_MS = 3000

    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var listener: ((EspStatus) -> Unit)? = null

    /** 读满整个 buf (TCP 短读安全); 返回 false = 流断开 */
    private fun readFully(input: java.io.InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r <= 0) return false
            off += r
        }
        return true
    }

    fun start(callback: (EspStatus) -> Unit) {
        if (running) return
        running = true
        listener = callback
        thread = Thread {
            while (running) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(HOST, PORT), SOCKET_TIMEOUT_MS)
                        socket.tcpNoDelay = true
                        socket.soTimeout = 5000
                        val input = socket.getInputStream()
                        notify(EspStatus(true, null, "connected"))
                        while (running) {
                            val lenBuf = ByteArray(4)
                            if (!readFully(input, lenBuf)) break
                            val len = ((lenBuf[0].toInt() and 0xFF) shl 24) or
                                      ((lenBuf[1].toInt() and 0xFF) shl 16) or
                                      ((lenBuf[2].toInt() and 0xFF) shl 8) or
                                      (lenBuf[3].toInt() and 0xFF)
                            if (len <= 0 || len > 65536) break
                            val data = ByteArray(len)
                            if (!readFully(input, data)) break
                            val frame = EspFrame.parse(data)
                            notify(EspStatus(true, frame, "f${frame.frameId} n=${frame.actors.size}"))
                        }
                    }
                } catch (e: Exception) {
                    notify(EspStatus(false, null, e.message ?: e.javaClass.simpleName))
                    if (running) {
                        try { Thread.sleep(RECONNECT_DELAY_MS) } catch (_: InterruptedException) {}
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "ESPReaderClient"
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        listener = null
    }

    private fun notify(s: EspStatus) {
        try { listener?.invoke(s) } catch (_: Exception) {}
    }
}
