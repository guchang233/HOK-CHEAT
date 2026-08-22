package com.esp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var espView: EspCanvasView? = null
    private var params: WindowManager.LayoutParams? = null
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("esp_overlay", Context.MODE_PRIVATE)
    }

    private var toolbarView: View? = null
    private var toolbarParams: WindowManager.LayoutParams? = null

    private val TAG = "ESP-Overlay"
    private val CHANNEL_ID = "esp_overlay"
    private val NOTIF_ID = 1

    companion object {
        const val ACTION_STOP = "com.esp.STOP_OVERLAY"
        const val ACTION_TOGGLE_MAP = "com.esp.TOGGLE_MAP"
        const val ACTION_TOGGLE_BOX = "com.esp.TOGGLE_BOX"
        const val ACTION_CENTER = "com.esp.CENTER"
        const val ACTION_DEPLOY_READER = "com.esp.DEPLOY_READER"
        const val ACTION_STOP_READER = "com.esp.STOP_READER"
        const val ACTION_LOG_READER = "com.esp.LOG_READER"

        const val GAME_PKG_DEFAULT = "com.tencent.tmgp.sgame"
        const val READER_PORT_DEFAULT = 47291

        @Volatile var isRunning = false
        @Volatile var showMinimap = true
        @Volatile var showBoxes = true
        @Volatile var showSkills = true
        @Volatile var showUltimate = true
        @Volatile var showLines = true
        @Volatile var showTimers = true
        @Volatile var showFacing = true
        @Volatile var showNameLevel = true
        @Volatile var lastLog = ""
        @Volatile var readerStatus = "未部署"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startInForeground()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val view = EspCanvasView(this)
        espView = view

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val savedSize = prefs.getInt("size", 300).coerceIn(140, 600)
        val savedX = prefs.getInt("x", 32)
        val savedY = prefs.getInt("y", 100)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }
        params = lp

        view.setOnTouchListener(buildDragListener(view, lp))
        windowManager.addView(view, lp)

        ReaderClient.start { status ->
            view.updateFrame(status.frame)
        }

        showToolbar()
    }

    private fun buildDragListener(view: View, lp: WindowManager.LayoutParams): View.OnTouchListener {
        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f

        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initX = lp.x
                    initY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    enableTouch(lp, view)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    lp.x = initX + dx
                    lp.y = initY + dy
                    try { windowManager.updateViewLayout(view, lp) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    disableTouch(lp, view)
                    true
                }
                else -> false
            }
        }
    }

    private fun enableTouch(lp: WindowManager.LayoutParams, view: View) {
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        try { windowManager.updateViewLayout(view, lp) } catch (_: Exception) {}
    }

    private fun disableTouch(lp: WindowManager.LayoutParams, view: View) {
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try { windowManager.updateViewLayout(view, lp) } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_TOGGLE_MAP -> {
                showMinimap = !showMinimap
                espView?.setShowMinimap(showMinimap)
            }
            ACTION_TOGGLE_BOX -> {
                showBoxes = !showBoxes
                espView?.setShowBoxes(showBoxes)
            }
            ACTION_CENTER -> {
                params?.let { p ->
                    val dm = resources.displayMetrics
                    p.x = (dm.widthPixels / 2 - 150).toInt()
                    p.y = (dm.heightPixels / 2 - 150).toInt()
                    try { windowManager.updateViewLayout(espView, p) } catch (_: Exception) {}
                }
            }
            ACTION_DEPLOY_READER -> {
                deployAndStartReader()
            }
            ACTION_STOP_READER -> {
                RootHelper.stopReader()
                readerStatus = "读取器已停止"
            }
            ACTION_LOG_READER -> {
                lastLog = RootHelper.getReaderLog(30)
                readerStatus = "日志: ${lastLog.take(60)}"
            }
        }
        return START_STICKY
    }

    private fun deployAndStartReader() {
        readerStatus = "部署中..."
        Thread {
            try {
                if (!RootHelper.isRootAvailable()) {
                    readerStatus = "错误: 未获得 Root 授权"
                    return@Thread
                }

                val primary = RootHelper.pickReaderAsset()
                val fallback = if (primary == "tv_reader_x64") "tv_reader_arm64" else "tv_reader_x64"
                var deployResult = RootHelper.extractAndDeployReader(this, primary)
                if (!deployResult.success || !RootHelper.launchTest()) {
                    Log.w(TAG, "主资产 $primary 失败, 改试 $fallback")
                    deployResult = RootHelper.extractAndDeployReader(this, fallback)
                }
                if (!deployResult.success) {
                    readerStatus = "部署失败: ${deployResult.error}"
                    return@Thread
                }

                val binInfo = RootHelper.verifyBinary()
                Log.i(TAG, "二进制已部署: $binInfo")

                val launchResult = RootHelper.launchReader(GAME_PKG_DEFAULT, READER_PORT_DEFAULT)
                if (launchResult.success) {
                    readerStatus = "读取器运行中 ✓"
                } else {
                    readerStatus = "启动失败: ${launchResult.error}"
                    lastLog = RootHelper.getReaderLog(30)
                }
            } catch (e: Exception) {
                readerStatus = "错误: ${e.message}"
                Log.e(TAG, "deploy failed", e)
            }
        }.start()
    }

    private fun showToolbar() {
        if (toolbarView != null) return
        val ctx = this

        val scrollView = android.widget.ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
        }

        val ll = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(230, 15, 15, 20))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val title = TextView(ctx).apply {
            text = "🎮 ESP 透视"
            setTextColor(Color.argb(220, 100, 220, 255))
            textSize = 14f
            setPadding(0, dp(2), 0, dp(4))
        }
        ll.addView(title)

        val statusText = TextView(ctx).apply {
            text = readerStatus
            setTextColor(Color.argb(180, 180, 220, 180))
            textSize = 10f
            setPadding(0, 0, 0, dp(4))
        }
        ll.addView(statusText)

        val row1 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
        }
        ll.addView(row1)

        row1.addView(toolbarButton("部署") {
            readerStatus = "部署中..."
            statusText.text = readerStatus
            sendServiceAction(ACTION_DEPLOY_READER)
        })
        row1.addView(toolbarButton("停止读取器") {
            sendServiceAction(ACTION_STOP_READER)
        })

        val row2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
        }
        ll.addView(row2)

        row2.addView(toolbarButton("日志") {
            sendServiceAction(ACTION_LOG_READER)
            statusText.text = lastLog.take(60)
        })
        row2.addView(toolbarButton("居中") {
            sendServiceAction(ACTION_CENTER)
        })

        addSectionLabel(ll, "── ESP 显示 ──")

        val row3 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        ll.addView(row3)
        row3.addView(toggleButton("地图", showMinimap) { v ->
            showMinimap = v; espView?.setShowMinimap(v)
        })
        row3.addView(toggleButton("方框", showBoxes) { v ->
            showBoxes = v; espView?.setShowBoxes(v)
        })
        row3.addView(toggleButton("连线", showLines) { v ->
            showLines = v; espView?.setShowLines(v)
        })

        val row4 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        ll.addView(row4)
        row4.addView(toggleButton("技能", showSkills) { v ->
            showSkills = v; espView?.setShowSkills(v)
        })
        row4.addView(toggleButton("大招", showUltimate) { v ->
            showUltimate = v; espView?.setShowUltimate(v)
        })
        row4.addView(toggleButton("计时", showTimers) { v ->
            showTimers = v; espView?.setShowTimers(v)
        })

        val row5 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        ll.addView(row5)
        row5.addView(toggleButton("朝向", showFacing) { v ->
            showFacing = v; espView?.setShowFacing(v)
        })
        row5.addView(toggleButton("等级", showNameLevel) { v ->
            showNameLevel = v; espView?.setShowNameLevel(v)
        })
        row5.addView(toggleButton("距离", true) { v ->
            espView?.setShowDistance(v)
        })

        val row6 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        ll.addView(row6)
        row6.addView(toggleButton("血条", true) { v ->
            espView?.setShowHPRatio(v)
        })

        addSectionLabel(ll, "──────────────")

        val stopBtn = toolbarButton("⏹ 停止透视") {
            stopSelf()
        }
        ll.addView(stopBtn)

        scrollView.addView(ll)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val tbLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = -dp(10)
            width = dp(200)
        }
        toolbarParams = tbLp

        try {
            windowManager.addView(scrollView, tbLp)
            toolbarView = scrollView
        } catch (_: Exception) {}
    }

    private fun addSectionLabel(parent: LinearLayout, text: String) {
        val label = TextView(this).apply {
            this.text = text
            setTextColor(Color.argb(140, 150, 150, 180))
            textSize = 10f
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(4), 0, dp(2))
        }
        parent.addView(label)
    }

    private fun toggleButton(label: String, initial: Boolean, onChange: (Boolean) -> Unit): Button {
        var state = initial
        val btn = Button(this).apply {
            text = "$label:${if (state) "ON" else "OFF"}"
            textSize = 11f
            minWidth = 0
            minimumWidth = dp(50)
            setPadding(dp(4), dp(2), dp(4), dp(2))
            setBackgroundColor(Color.argb(
                if (state) 200 else 100,
                if (state) 50 else 100,
                if (state) 180 else 100,
                if (state) 100 else 100
            ))
            setTextColor(Color.WHITE)
            setOnClickListener {
                state = !state
                this.text = "$label:${if (state) "ON" else "OFF"}"
                this.setBackgroundColor(Color.argb(
                    if (state) 200 else 100,
                    if (state) 50 else 100,
                    if (state) 180 else 100,
                    if (state) 100 else 100
                ))
                onChange(state)
            }
        }
        return btn
    }

    private fun toolbarButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 12f
        minWidth = 0
        minimumWidth = dp(56)
        setPadding(dp(6), dp(2), dp(6), dp(2))
        setOnClickListener { onClick() }
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, OverlayService::class.java).apply { this.action = action }
        startService(intent)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        params?.let { lp ->
            prefs.edit().apply {
                putInt("x", lp.x)
                putInt("y", lp.y)
                apply()
            }
        }
        ReaderClient.stop()
        toolbarView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        toolbarView = null
        espView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        espView = null
        isRunning = false
        super.onDestroy()
    }

    private fun startInForeground() {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "ESP 悬浮窗",
                NotificationManager.IMPORTANCE_LOW
            )
            mgr.createNotificationChannel(ch)
        }
        val notif: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("ESP 透视")
                .setContentText("透视悬浮窗运行中")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("ESP 透视")
                .setContentText("透视悬浮窗运行中")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }
}
