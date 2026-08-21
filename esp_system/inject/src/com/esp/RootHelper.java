package com.esp;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Root 辅助 — 通过 su 部署并启动 tv_reader。
 * 注入版: 二进制位于 APK assets/esp_native/tv_reader_arm64。
 */
public final class RootHelper {

    private static final String TAG = "ESP-Root";
    private static final String TARGET_DIR = "/data/adb/esp";
    private static final String TARGET_BIN = TARGET_DIR + "/tv_reader";
    private static final String LOG_FILE = TARGET_DIR + "/tv_reader.log";
    private static final String ASSET_READER = "esp_native/tv_reader_arm64";

    public static final String GAME_PKG_DEFAULT = "com.tencent.tmgp.sgame";
    public static final int READER_PORT_DEFAULT = 47291;

    public static class Result {
        public final boolean success;
        public final String output;
        public final String error;

        public Result(boolean success, String output, String error) {
            this.success = success;
            this.output = output != null ? output : "";
            this.error = error != null ? error : "";
        }
    }

    private RootHelper() {}

    public static boolean isRootAvailable() {
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            String out = readStream(proc.getInputStream());
            proc.waitFor();
            return out.contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }

    public static Result execute(String command) {
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            String stdout = readStream(proc.getInputStream()).trim();
            String stderr = readStream(proc.getErrorStream()).trim();
            int exit = proc.waitFor();
            return new Result(exit == 0, stdout, stderr);
        } catch (Exception e) {
            String msg = e.getMessage();
            return new Result(false, "", msg != null ? msg : e.getClass().getSimpleName());
        }
    }

    public static Result extractAndDeployReader(Context context) {
        Result mkdirResult = execute("mkdir -p " + TARGET_DIR);
        if (!mkdirResult.success) {
            Log.e(TAG, "mkdir failed: " + mkdirResult.error);
            return mkdirResult;
        }
        try {
            File tmpFile = new File(context.getCacheDir(), "tv_reader_tmp");
            InputStream in = context.getAssets().open(ASSET_READER);
            OutputStream out = new FileOutputStream(tmpFile);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.close();
            in.close();

            Result copyResult = execute("cp " + tmpFile.getAbsolutePath() + " " + TARGET_BIN);
            if (!copyResult.success) {
                Log.e(TAG, "cp failed: " + copyResult.error);
                return copyResult;
            }
            execute("chmod 755 " + TARGET_BIN);
            execute("chmod 755 " + TARGET_DIR);
            tmpFile.delete();

            File file = new File(TARGET_BIN);
            if (file.exists()) {
                Log.i(TAG, "tv_reader deployed: " + file.length() + " bytes -> " + TARGET_BIN);
                return new Result(true, "deployed " + file.length() + " bytes", "");
            }
            return new Result(false, "", "binary not found after copy");
        } catch (Exception e) {
            Log.e(TAG, "Extract failed", e);
            return new Result(false, "", "Extract failed");
        }
    }

    public static Result launchReader(String gamePkg, int port) {
        execute("killall tv_reader 2>/dev/null || true");
        try { Thread.sleep(200); } catch (InterruptedException ignore) {}

        String cmd = TARGET_BIN + " --game-pkg " + gamePkg + " --port " + port;
        execute("nohup " + cmd + " > " + LOG_FILE + " 2>&1 &");
        try { Thread.sleep(500); } catch (InterruptedException ignore) {}

        Result check = execute("ps -A | grep tv_reader || echo NOT_RUNNING");
        boolean runningNow = check.output.contains("tv_reader");
        Log.i(TAG, "Reader launch: running=" + runningNow);
        if (runningNow) {
            return new Result(true, "reader running", "");
        }
        Result logResult = execute("tail -20 " + LOG_FILE + " 2>/dev/null || echo no_log");
        String err = logResult.output;
        return new Result(false, "", "not running: " + err.substring(0, Math.min(200, err.length())));
    }

    public static Result stopReader() {
        return execute("killall tv_reader 2>/dev/null; echo done");
    }

    public static String getReaderLog(int lines) {
        Result result = execute("tail -" + lines + " " + LOG_FILE + " 2>/dev/null || echo no_log");
        return result.output.isEmpty() ? result.error : result.output;
    }

    private static String readStream(InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) {
            sb.append(new String(buf, 0, n, "UTF-8"));
        }
        return sb.toString();
    }
}
