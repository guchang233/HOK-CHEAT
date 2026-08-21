package com.esp;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * tv_reader TCP 客户端 — 连接 127.0.0.1:47291 接收 TVEF 帧。
 */
public final class ReaderClient {

    public interface Listener {
        void onStatus(boolean connected, EspFrame frame, String msg);
    }

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 47291;
    private static final long RECONNECT_DELAY_MS = 1000L;
    private static final int SOCKET_TIMEOUT_MS = 3000;

    private static volatile boolean running = false;
    private static volatile Listener listener;
    private static Thread thread;

    private ReaderClient() {}

    public static synchronized void start(Listener cb) {
        if (running) return;
        running = true;
        listener = cb;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "ESPReaderClient");
        thread.setDaemon(true);
        thread.start();
    }

    public static synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        listener = null;
    }

    private static void loop() {
        while (running) {
            Socket socket = null;
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(HOST, PORT), SOCKET_TIMEOUT_MS);
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(5000);
                InputStream input = socket.getInputStream();
                notify(false, null, "connected");
                while (running) {
                    byte[] lenBuf = new byte[4];
                    if (!readFully(input, lenBuf)) return;
                    int len = ((lenBuf[0] & 0xFF) << 24)
                            | ((lenBuf[1] & 0xFF) << 16)
                            | ((lenBuf[2] & 0xFF) << 8)
                            | (lenBuf[3] & 0xFF);
                    if (len <= 0 || len > 65536) return;
                    byte[] data = new byte[len];
                    if (!readFully(input, data)) return;
                    try {
                        EspFrame frame = EspFrame.parse(data);
                        notify(true, frame, "f" + frame.frameId + " n=" + frame.actors.size());
                    } catch (Exception ignore) {
                        // 帧解析失败，跳过
                    }
                }
            } catch (Exception e) {
                String msg = e.getMessage();
                notify(false, null, msg != null ? msg : e.getClass().getSimpleName());
                if (running) {
                    try { Thread.sleep(RECONNECT_DELAY_MS); } catch (InterruptedException ignore) {}
                }
            } finally {
                if (socket != null) {
                    try { socket.close(); } catch (IOException ignore) {}
                }
            }
        }
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r <= 0) return false;
            off += r;
        }
        return true;
    }

    private static void notify(boolean connected, EspFrame frame, String msg) {
        Listener l = listener;
        if (l != null) {
            try {
                l.onStatus(connected, frame, msg);
            } catch (Exception ignore) {}
        }
    }
}
