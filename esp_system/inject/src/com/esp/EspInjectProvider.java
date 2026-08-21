package com.esp;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

/**
 * ESP 注入入口 — ContentProvider 在应用进程启动时自动实例化 (先于 Application.onCreate)。
 * 延迟数秒后拉起 OverlayService，使 ESP 随宿主游戏启动。
 */
public class EspInjectProvider extends ContentProvider {

    private static final String TAG = "ESP-Inject";
    private static final long START_DELAY_MS = 4000L;

    @Override
    public boolean onCreate() {
        Log.i(TAG, "ESP inject provider created, scheduling overlay start");
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                tryStartService();
            }
        }, START_DELAY_MS);
        return true;
    }

    private void tryStartService() {
        try {
            android.content.Context ctx = getContext();
            if (ctx == null) return;
            android.content.Intent intent = new android.content.Intent(ctx, OverlayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }
            Log.i(TAG, "overlay service start requested");
        } catch (Exception e) {
            // 前台服务启动可能被系统拒绝 (后台限制)，稍后重试一次
            Log.w(TAG, "start service failed: " + e);
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
