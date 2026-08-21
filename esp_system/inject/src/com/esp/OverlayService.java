package com.esp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * ESP 前台服务 — 悬浮窗 + 工具栏 + tv_reader 生命周期管理。
 * 由 Kotlin 版 OverlayService.kt 移植 (注入 dex 版)。
 */
public class OverlayService extends Service {

    public static final String ACTION_STOP = "com.esp.STOP_OVERLAY";
    public static final String ACTION_TOGGLE_MAP = "com.esp.TOGGLE_MAP";
    public static final String ACTION_TOGGLE_BOX = "com.esp.TOGGLE_BOX";
    public static final String ACTION_CENTER = "com.esp.CENTER";
    public static final String ACTION_DEPLOY_READER = "com.esp.DEPLOY_READER";
    public static final String ACTION_STOP_READER = "com.esp.STOP_READER";
    public static final String ACTION_LOG_READER = "com.esp.LOG_READER";
    public static final String ACTION_START = "com.esp.START";

    private static final String TAG = "ESP-Overlay";
    private static final String CHANNEL_ID = "esp_overlay";
    private static final int NOTIF_ID = 1;
    private static final long OVERLAY_RETRY_MS = 8000L;

    private static volatile boolean isRunning = false;
    public static volatile String lastLog = "";
    public static volatile String readerStatus = "idle";

    private WindowManager windowManager;
    private EspCanvasView espView;
    private WindowManager.LayoutParams params;
    private SharedPreferences prefs;
    private View toolbarView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable overlayRetry = new Runnable() {
        @Override
        public void run() {
            if (isRunning && espView == null) {
                tryAddOverlay();
            }
            if (isRunning && espView == null) {
                handler.postDelayed(this, OVERLAY_RETRY_MS);
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        startInForeground();
        prefs = getSharedPreferences("esp_overlay", Context.MODE_PRIVATE);
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        tryAddOverlay();
        if (espView == null) {
            readerStatus = "waiting overlay permission...";
            handler.postDelayed(overlayRetry, OVERLAY_RETRY_MS);
        }

        ReaderClient.start(new ReaderClient.Listener() {
            @Override
            public void onStatus(boolean connected, EspFrame frame, String msg) {
                if (frame != null && espView != null) {
                    espView.updateFrame(frame);
                }
            }
        });

        // 首次运行: 自动部署并启动 tv_reader (root)
        if (!prefs.getBoolean("reader_auto_started", false)) {
            prefs.edit().putBoolean("reader_auto_started", true).apply();
            deployAndStartReader();
        }
        showToolbar();
    }

    private void tryAddOverlay() {
        if (espView != null) return;
        if (!Settings.canDrawOverlays(this)) {
            android.util.Log.w(TAG, "overlay permission not granted yet");
            return;
        }
        try {
            EspCanvasView view = new EspCanvasView(this);
            espView = view;

            int type;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                type = WindowManager.LayoutParams.TYPE_PHONE;
            }

            int savedSize = Math.max(140, Math.min(600, prefs.getInt("size", 300)));
            int savedX = prefs.getInt("x", 32);
            int savedY = prefs.getInt("y", 100);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = savedX;
            lp.y = savedY;
            params = lp;

            windowManager.addView(view, lp);
            readerStatus = "overlay ready";
        } catch (Exception e) {
            android.util.Log.e(TAG, "addView failed", e);
            espView = null;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        } else if (ACTION_TOGGLE_MAP.equals(action)) {
            if (espView != null) espView.setShowMinimap(!prefsGetShow("map", true));
        } else if (ACTION_TOGGLE_BOX.equals(action)) {
            if (espView != null) espView.setShowBoxes(!prefsGetShow("box", true));
        } else if (ACTION_CENTER.equals(action)) {
            if (params != null && espView != null) {
                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                params.x = dm.widthPixels / 2 - 150;
                params.y = dm.heightPixels / 2 - 150;
                try { windowManager.updateViewLayout(espView, params); } catch (Exception ignore) {}
            }
        } else if (ACTION_DEPLOY_READER.equals(action)) {
            deployAndStartReader();
        } else if (ACTION_STOP_READER.equals(action)) {
            RootHelper.stopReader();
            readerStatus = "stopped";
        } else if (ACTION_LOG_READER.equals(action)) {
            lastLog = RootHelper.getReaderLog(30);
            readerStatus = "log: " + lastLog.substring(0, Math.min(60, lastLog.length()));
        }
        return START_STICKY;
    }

    private boolean prefsGetShow(String key, boolean def) {
        return prefs.getBoolean("show_" + key, def);
    }

    private void deployAndStartReader() {
        readerStatus = "deploying...";
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!RootHelper.isRootAvailable()) {
                        readerStatus = "ERROR: no root access";
                        return;
                    }
                    RootHelper.Result deployResult = RootHelper.extractAndDeployReader(OverlayService.this);
                    if (!deployResult.success) {
                        readerStatus = "DEPLOY FAIL: " + deployResult.error;
                        return;
                    }
                    RootHelper.Result launchResult = RootHelper.launchReader(
                            RootHelper.GAME_PKG_DEFAULT, RootHelper.READER_PORT_DEFAULT);
                    if (launchResult.success) {
                        readerStatus = "reader running";
                    } else {
                        readerStatus = "LAUNCH FAIL: " + launchResult.error;
                        lastLog = RootHelper.getReaderLog(30);
                    }
                } catch (Exception e) {
                    readerStatus = "ERROR: " + e.getMessage();
                }
            }
        }, "ESP-Deploy").start();
    }

    // ---------------- 工具栏 ----------------

    private void showToolbar() {
        if (toolbarView != null || !Settings.canDrawOverlays(this)) return;
        final Context ctx = this;

        ScrollView scrollView = new ScrollView(ctx);
        scrollView.setVerticalScrollBarEnabled(false);

        LinearLayout ll = new LinearLayout(ctx);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setBackgroundColor(Color.argb(230, 15, 15, 20));
        ll.setPadding(dp(12), dp(8), dp(12), dp(8));
        scrollView.addView(ll, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(ctx);
        title.setText("ESP Pro (内置)");
        title.setTextColor(Color.argb(220, 100, 220, 255));
        title.setTextSize(14f);
        title.setPadding(0, dp(2), 0, dp(4));
        ll.addView(title);

        final TextView statusText = new TextView(ctx);
        statusText.setText(readerStatus);
        statusText.setTextColor(Color.argb(180, 180, 220, 180));
        statusText.setTextSize(10f);
        statusText.setPadding(0, 0, 0, dp(4));
        ll.addView(statusText);

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (statusText != null) statusText.setText(readerStatus);
                if (toolbarView != null) handler.postDelayed(this, 2000);
            }
        }, 2000);

        LinearLayout row1 = new LinearLayout(ctx);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setPadding(0, dp(2), 0, dp(2));
        ll.addView(row1);
        row1.addView(toolbarButton("Deploy", new Runnable() {
            @Override public void run() { sendServiceAction(ACTION_DEPLOY_READER); }
        }));
        row1.addView(toolbarButton("Stop Reader", new Runnable() {
            @Override public void run() { sendServiceAction(ACTION_STOP_READER); }
        }));

        LinearLayout row2 = new LinearLayout(ctx);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(2), 0, dp(2));
        ll.addView(row2);
        row2.addView(toolbarButton("Log", new Runnable() {
            @Override public void run() { sendServiceAction(ACTION_LOG_READER); }
        }));
        row2.addView(toolbarButton("Recenter", new Runnable() {
            @Override public void run() { sendServiceAction(ACTION_CENTER); }
        }));

        addSectionLabel(ll, "── ESP 显示 ──");

        LinearLayout row3 = new LinearLayout(ctx);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        ll.addView(row3);
        row3.addView(toggleButton("地图", "map", true));
        row3.addView(toggleButton("方框", "box", true));
        row3.addView(toggleButton("连线", "lines", true));

        LinearLayout row4 = new LinearLayout(ctx);
        row4.setOrientation(LinearLayout.HORIZONTAL);
        ll.addView(row4);
        row4.addView(toggleButton("技能", "skills", true));
        row4.addView(toggleButton("大招", "ult", true));
        row4.addView(toggleButton("计时", "timers", true));

        LinearLayout row5 = new LinearLayout(ctx);
        row5.setOrientation(LinearLayout.HORIZONTAL);
        ll.addView(row5);
        row5.addView(toggleButton("朝向", "facing", true));
        row5.addView(toggleButton("等级", "level", true));
        row5.addView(toggleButton("距离", "dist", true));

        LinearLayout row6 = new LinearLayout(ctx);
        row6.setOrientation(LinearLayout.HORIZONTAL);
        ll.addView(row6);
        row6.addView(toggleButton("血条", "hp", true));

        addSectionLabel(ll, "──────────────");

        Button stopBtn = toolbarButton("停止 ESP", new Runnable() {
            @Override public void run() { stopSelf(); }
        });
        ll.addView(stopBtn);

        int type;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            type = WindowManager.LayoutParams.TYPE_PHONE;
        }
        WindowManager.LayoutParams tbLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        tbLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        tbLp.x = -dp(10);
        tbLp.width = dp(200);

        try {
            windowManager.addView(scrollView, tbLp);
            toolbarView = scrollView;
        } catch (Exception e) {
            android.util.Log.e(TAG, "toolbar addView failed", e);
        }
    }

    private void addSectionLabel(LinearLayout parent, String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.argb(140, 150, 150, 180));
        label.setTextSize(10f);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(4), 0, dp(2));
        parent.addView(label);
    }

    private Button toggleButton(final String label, final String key, boolean initial) {
        final Button btn = new Button(this);
        final boolean[] state = {prefs.getBoolean("show_" + key, initial)};
        btn.setText(label + ":" + (state[0] ? "ON" : "OFF"));
        btn.setTextSize(11f);
        btn.setMinWidth(0);
        btn.setMinimumWidth(dp(50));
        btn.setPadding(dp(4), dp(2), dp(4), dp(2));
        applyToggleColor(btn, state[0]);
        btn.setTextColor(Color.WHITE);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state[0] = !state[0];
                prefs.edit().putBoolean("show_" + key, state[0]).apply();
                btn.setText(label + ":" + (state[0] ? "ON" : "OFF"));
                applyToggleColor(btn, state[0]);
                if (espView == null) return;
                if (key.equals("map")) espView.setShowMinimap(state[0]);
                else if (key.equals("box")) espView.setShowBoxes(state[0]);
                else if (key.equals("lines")) espView.setShowLines(state[0]);
                else if (key.equals("skills")) espView.setShowSkills(state[0]);
                else if (key.equals("ult")) espView.setShowUltimate(state[0]);
                else if (key.equals("timers")) espView.setShowTimers(state[0]);
                else if (key.equals("facing")) espView.setShowFacing(state[0]);
                else if (key.equals("level")) espView.setShowNameLevel(state[0]);
                else if (key.equals("dist")) espView.setShowDistance(state[0]);
                else if (key.equals("hp")) espView.setShowHPRatio(state[0]);
            }
        });
        return btn;
    }

    private void applyToggleColor(Button btn, boolean on) {
        btn.setBackgroundColor(Color.argb(
                on ? 200 : 100, on ? 50 : 100, on ? 180 : 100, on ? 100 : 100));
    }

    private Button toolbarButton(String label, final Runnable onClick) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextSize(12f);
        btn.setMinWidth(0);
        btn.setMinimumWidth(dp(56));
        btn.setPadding(dp(6), dp(2), dp(6), dp(2));
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { onClick.run(); }
        });
        return btn;
    }

    private void sendServiceAction(String action) {
        Intent intent = new Intent(this, OverlayService.class);
        intent.setAction(action);
        startService(intent);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onDestroy() {
        if (params != null) {
            prefs.edit().putInt("x", params.x).putInt("y", params.y).apply();
        }
        ReaderClient.stop();
        handler.removeCallbacksAndMessages(null);
        if (toolbarView != null) {
            try { windowManager.removeView(toolbarView); } catch (Exception ignore) {}
        }
        toolbarView = null;
        if (espView != null) {
            try { windowManager.removeView(espView); } catch (Exception ignore) {}
        }
        espView = null;
        isRunning = false;
        super.onDestroy();
    }

    private void startInForeground() {
        NotificationManager mgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "ESP Overlay", NotificationManager.IMPORTANCE_LOW);
            mgr.createNotificationChannel(ch);
        }
        Notification notif;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notif = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("ESP Reader")
                    .setContentText("ESP overlay running (embedded)")
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .setOngoing(true)
                    .build();
        } else {
            notif = new Notification.Builder(this)
                    .setContentTitle("ESP Reader")
                    .setContentText("ESP overlay running (embedded)")
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .setOngoing(true)
                    .build();
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, notif);
        }
    }
}
