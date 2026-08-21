package com.truevision

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
    private var minimapView: MinimapView? = null
    private var params: WindowManager.LayoutParams? = null
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("tv_overlay", Context.MODE_PRIVATE)
    }

    // toolbar (debug mode only)
    private var toolbarView: View? = null
    private var toolbarParams: WindowManager.LayoutParams? = null
    private var tbSizeText: TextView? = null
    private var tbAlphaText: TextView? = null

    private val TAG = "TV-Overlay"
    private val CHANNEL_ID = "tv_overlay"
    private val NOTIF_ID = 1

    companion object {
        const val ACTION_STOP = "com.truevision.STOP_OVERLAY"
        const val ACTION_SET_DEBUG = "com.truevision.SET_DEBUG"
        const val MIN_SIZE = 140
        const val MAX_SIZE = 900
        const val MIN_ALPHA = 0.3f
        const val MAX_ALPHA = 1.0f
        @Volatile var isRunning = false
        @Volatile var debugMode = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        debugMode = false  // 默认锁定模式
        startInForeground()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val view = MinimapView(this)
        minimapView = view

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val savedSize = prefs.getInt("size", 260).coerceIn(MIN_SIZE, MAX_SIZE)
        val savedX = prefs.getInt("x", 32)
        val savedY = prefs.getInt("y", 120)
        val savedAlpha = prefs.getFloat("alpha", 1.0f).coerceIn(MIN_ALPHA, MAX_ALPHA)

        val lp = WindowManager.LayoutParams(
            savedSize,
            savedSize,
            type,
            // 默认 NOT_TOUCHABLE = 锁定状态 (触摸穿透到 sgame)
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
            alpha = savedAlpha
        }
        params = lp

        // 调试模式下生效: 单指拖动改位置 (long-press/double-tap 都移除了, 通过 toolbar 控制)
        view.setOnTouchListener(buildDragListener(view, lp))
        windowManager.addView(view, lp)

        // 启动 reader socket
        ReaderClient.start { status ->
            view.setStatus(status)
        }
        Log.i(TAG, "overlay started, size=$savedSize alpha=$savedAlpha")
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
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    lp.x = initX + dx
                    lp.y = initY + dy
                    try {
                        windowManager.updateViewLayout(view, lp)
                    } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_SET_DEBUG -> {
                val on = intent.getBooleanExtra("debug", false)
                applyDebugMode(on)
            }
        }
        return START_STICKY
    }

    private fun applyDebugMode(on: Boolean) {
        debugMode = on
        val view = minimapView ?: return
        val lp = params ?: return

        if (on) {
            // 解锁: 接受 touch (拖动)
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            showToolbar()
        } else {
            // 锁定: 触摸穿透
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            hideToolbar()
        }
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (e: Exception) {
            Log.w(TAG, "updateLayout flags", e)
        }
        Log.i(TAG, "debugMode=$on")
    }

    // ===== toolbar (调试模式浮窗) =====

    private fun showToolbar() {
        if (toolbarView != null) return
        val ctx = this

        val ll = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(230, 25, 25, 25))
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }

        // Size row
        val sizeRow = horizontalRow()
        val sizeText = labelText("Size: ${params?.width ?: 0}", dp(120))
        tbSizeText = sizeText
        sizeRow.addView(sizeText)
        for ((label, delta) in listOf("-50" to -50, "-10" to -10, "+10" to 10, "+50" to 50)) {
            sizeRow.addView(toolbarButton(label) { adjustSize(delta) })
        }
        ll.addView(sizeRow)

        // Alpha row
        val alphaRow = horizontalRow()
        val initAlphaPct = ((params?.alpha ?: 1f) * 100).toInt()
        val alphaText = labelText("Alpha: ${initAlphaPct}%", dp(120))
        tbAlphaText = alphaText
        alphaRow.addView(alphaText)
        alphaRow.addView(toolbarButton("-10") { adjustAlpha(-0.1f) })
        alphaRow.addView(toolbarButton("+10") { adjustAlpha(+0.1f) })
        ll.addView(alphaRow)

        // Hide row
        val hideRow = horizontalRow().apply { gravity = Gravity.CENTER }
        hideRow.addView(toolbarButton("Hide Overlay") {
            stopSelf()
        })
        ll.addView(hideRow)

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
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(60)
        }
        toolbarParams = tbLp

        try {
            windowManager.addView(ll, tbLp)
            toolbarView = ll
        } catch (e: Exception) {
            Log.w(TAG, "showToolbar", e)
        }
    }

    private fun horizontalRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2); bottomMargin = dp(2) }
    }

    private fun labelText(text: String, minWidth: Int): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 13f
        this.minWidth = minWidth
    }

    private fun toolbarButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 12f
        minWidth = 0
        minimumWidth = dp(44)
        setPadding(dp(8), dp(2), dp(8), dp(2))
        setOnClickListener { onClick() }
    }

    private fun hideToolbar() {
        toolbarView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        toolbarView = null
        toolbarParams = null
        tbSizeText = null
        tbAlphaText = null
    }

    private fun adjustSize(delta: Int) {
        val lp = params ?: return
        val view = minimapView ?: return
        val newSize = (lp.width + delta).coerceIn(MIN_SIZE, MAX_SIZE)
        if (newSize == lp.width) return
        lp.width = newSize
        lp.height = newSize
        try { windowManager.updateViewLayout(view, lp) } catch (_: Exception) {}
        tbSizeText?.text = "Size: $newSize"
    }

    private fun adjustAlpha(delta: Float) {
        val lp = params ?: return
        val view = minimapView ?: return
        val newAlpha = (lp.alpha + delta).coerceIn(MIN_ALPHA, MAX_ALPHA)
        if (kotlin.math.abs(newAlpha - lp.alpha) < 0.001f) return
        lp.alpha = newAlpha
        try { windowManager.updateViewLayout(view, lp) } catch (_: Exception) {}
        tbAlphaText?.text = "Alpha: ${(newAlpha * 100).toInt()}%"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        // 持久化
        params?.let { lp ->
            prefs.edit().apply {
                putInt("size", lp.width)
                putInt("x", lp.x)
                putInt("y", lp.y)
                putFloat("alpha", lp.alpha)
                apply()
            }
        }
        ReaderClient.stop()
        hideToolbar()
        try {
            minimapView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.w(TAG, "removeView", e)
        }
        minimapView = null
        isRunning = false
        debugMode = false
        Log.i(TAG, "overlay stopped, size + pos + alpha saved")
        super.onDestroy()
    }

    private fun startInForeground() {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "TrueVision Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            mgr.createNotificationChannel(ch)
        }
        val notif: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("TrueVision")
                .setContentText("Minimap overlay running (从主 app 控制)")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("TrueVision")
                .setContentText("Minimap overlay running")
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
