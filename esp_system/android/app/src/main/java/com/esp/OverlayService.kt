package com.esp

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var espView: EspCanvasView? = null
    private var params: WindowManager.LayoutParams? = null
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("esp_overlay", Context.MODE_PRIVATE)
    }

    private var toolbarView: View? = null
    private var toolbarParams: WindowManager.LayoutParams? = null

    // HUD 引用
    private var toolbarRoot: FrameLayout? = null
    private var panelScroll: ScrollView? = null
    private var miniPillView: TextView? = null
    private var collapsed = false
    private var radarSize = 300
    private var mainHandler: Handler? = null
    private var statusRunnable: Runnable? = null
    private var pulseAnim: ValueAnimator? = null
    private var statusDotDrawable: GradientDrawable? = null

    private val TAG = "ESP-Overlay"
    private val CHANNEL_ID = "esp_overlay"
    private val NOTIF_ID = 1

    companion object {
        const val ACTION_STOP = "com.esp.STOP_OVERLAY"
        const val ACTION_CENTER = "com.esp.CENTER"
        const val ACTION_DEPLOY_READER = "com.esp.DEPLOY_READER"
        const val ACTION_STOP_READER = "com.esp.STOP_READER"
        const val ACTION_LOG_READER = "com.esp.LOG_READER"

        const val GAME_PKG_DEFAULT = "com.tencent.tmgp.sgame"
        const val READER_PORT_DEFAULT = 47291

        @Volatile var isRunning = false

        // 显示开关
        @Volatile var showRadar = true
        @Volatile var showLines = true
        @Volatile var showHp = true
        @Volatile var showSkills = true
        @Volatile var showUlt = true
        @Volatile var showTimers = true
        @Volatile var showLevels = true
        @Volatile var showDist = true
        @Volatile var showFacing = true

        @Volatile var lastLog = ""
        @Volatile var readerStatus = "未部署"

        // 实时状态 (读取线程写, UI 线程读)
        @Volatile var readerConnected = false
        @Volatile var fps = 0
        @Volatile var enemyAlive = 0
        @Volatile var allyAlive = 0
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startInForeground()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val view = EspCanvasView(this)
        espView = view

        // 雷达尺寸以 dp 为存储单位, 换算 px (高密度屏不至于过小)
        val radarDp = prefs.getInt("radar", 300).coerceIn(200, 640)
        radarSize = HudUi.dp(this, radarDp.toFloat()).toInt()
        view.setMapSize(radarSize)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

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
        windowManager.addView(view, lp)

        // 读取器数据回调 + 实时统计
        var frames = 0
        var lastMs = 0L
        ReaderClient.start { status ->
            view.updateFrame(status.frame)
            readerConnected = status.connected
            val f = status.frame
            if (f != null) {
                enemyAlive = f.actors.count { !it.ally && it.isHero }
                allyAlive = f.actors.count { it.ally && it.isHero }
                frames++
                val now = SystemClock.elapsedRealtime()
                if (lastMs == 0L) lastMs = now
                val dt = now - lastMs
                if (dt >= 1000) {
                    fps = (frames * 1000f / dt).toInt()
                    frames = 0
                    lastMs = now
                }
            }
        }

        showToolbar()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CENTER -> centerRadar()
            ACTION_DEPLOY_READER -> deployAndStartReader()
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

    private fun centerRadar() {
        val p = params ?: return
        val v = espView ?: return
        val dm = resources.displayMetrics
        p.x = (dm.widthPixels - v.width) / 2
        p.y = (dm.heightPixels - v.height) / 2
        prefs.edit().putInt("x", p.x).putInt("y", p.y).apply()
        try {
            windowManager.updateViewLayout(v, p)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        isRunning = false
        statusRunnable?.let { mainHandler?.removeCallbacks(it) }
        pulseAnim?.cancel()
        ReaderClient.stop()
        RootHelper.killShell()
        espView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        toolbarView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        espView = null
        toolbarView = null
        toolbarRoot = null
        panelScroll = null
        miniPillView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, OverlayService::class.java)
        intent.action = action
        startService(intent)
    }

    // ==================== 工具栏 (战术玻璃 HUD) ====================
    private fun showToolbar() {
        if (toolbarView != null) return
        val ctx = this
        mainHandler = Handler(Looper.getMainLooper())

        val root = FrameLayout(ctx)

        // ---------- 完整面板 ----------
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = HudUi.panelBg(ctx, 16f)
            setPadding(
                HudUi.dp(ctx, 12f).toInt(), HudUi.dp(ctx, 10f).toInt(),
                HudUi.dp(ctx, 12f).toInt(), HudUi.dp(ctx, 12f).toInt()
            )
            minimumWidth = HudUi.dp(ctx, 244f).toInt()
        }

        // 头部: 拖拽手柄 + 状态点 + 标题 + 折叠
        val dotDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(255, 120, 140, 160))
        }
        statusDotDrawable = dotDrawable

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(ctx).apply {
            text = "≡"
            textSize = 14f
            setTextColor(HudUi.TEXT_DIM)
        })
        val dotSize = HudUi.dp(ctx, 8f).toInt()
        header.addView(View(ctx).apply {
            background = dotDrawable
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                marginStart = HudUi.dp(ctx, 8f).toInt()
            }
        })
        header.addView(TextView(ctx).apply {
            text = "ESP 透视"
            textSize = 14f
            setTextColor(HudUi.TEXT_MAIN)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = HudUi.dp(ctx, 8f).toInt() }
        })
        header.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(HudUi.actionButton(ctx, "—") { setCollapsed(!collapsed) })
        panel.addView(header)

        // 分隔线
        panel.addView(View(ctx).apply {
            setBackgroundColor(HudUi.STROKE_DIM)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, HudUi.dp(ctx, 1f).toInt()
            ).apply { topMargin = HudUi.dp(ctx, 8f).toInt() }
        })

        // 状态芯片行
        fun chip(initial: String): TextView = TextView(ctx).apply {
            text = initial
            textSize = 10f
            setTextColor(HudUi.TEXT_MAIN)
            typeface = Typeface.DEFAULT_BOLD
            background = HudUi.chipBg(ctx)
            setPadding(
                HudUi.dp(ctx, 8f).toInt(), HudUi.dp(ctx, 3f).toInt(),
                HudUi.dp(ctx, 8f).toInt(), HudUi.dp(ctx, 3f).toInt()
            )
        }
        fun chipLp() = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = HudUi.dp(ctx, 6f).toInt() }

        val tvReader = chip("读取器 --")
        val tvFps = chip("FPS --")
        val tvCount = chip("敌 - / 我 -")
        val chipRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, HudUi.dp(ctx, 8f).toInt(), 0, 0)
        }
        chipRow.addView(tvReader, chipLp())
        chipRow.addView(tvFps, chipLp())
        chipRow.addView(tvCount, chipLp())
        panel.addView(chipRow)

        val tvStatusFull = TextView(ctx).apply {
            text = readerStatus
            textSize = 10f
            setTextColor(HudUi.TEXT_DIM)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, HudUi.dp(ctx, 4f).toInt(), 0, 0)
        }
        panel.addView(tvStatusFull)

        // 动作按钮行
        fun actionRow(): LinearLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, HudUi.dp(ctx, 8f).toInt(), 0, 0)
        }
        fun weightLp() = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginEnd = HudUi.dp(ctx, 6f).toInt() }
        fun lastWeightLp() = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        )

        val rowA = actionRow()
        rowA.addView(HudUi.actionButton(ctx, "部署") {
            readerStatus = "部署中..."
            sendServiceAction(ACTION_DEPLOY_READER)
        }, weightLp())
        rowA.addView(HudUi.actionButton(ctx, "日志") {
            sendServiceAction(ACTION_LOG_READER)
        }, weightLp())
        rowA.addView(HudUi.actionButton(ctx, "居中") {
            sendServiceAction(ACTION_CENTER)
        }, lastWeightLp())
        panel.addView(rowA)

        val rowB = actionRow()
        rowB.addView(HudUi.actionButton(ctx, "雷达 −") {
            adjustRadar(-40)
        }, weightLp())
        rowB.addView(HudUi.actionButton(ctx, "雷达 ＋") {
            adjustRadar(40)
        }, weightLp())
        rowB.addView(HudUi.dangerButton(ctx, "停止读取器") {
            sendServiceAction(ACTION_STOP_READER)
        }, lastWeightLp())
        panel.addView(rowB)

        // 开关区
        panel.addView(TextView(ctx).apply {
            text = "战场显示"
            textSize = 10f
            setTextColor(HudUi.TEXT_DIM)
            letterSpacing = 0.12f
            setPadding(0, HudUi.dp(ctx, 10f).toInt(), 0, HudUi.dp(ctx, 4f).toInt())
        })

        fun toggleRow(a: PillToggle, b: PillToggle?): LinearLayout {
            val r = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, HudUi.dp(ctx, 2f).toInt(), 0, HudUi.dp(ctx, 2f).toInt())
            }
            r.addView(a, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (b != null) {
                r.addView(b, LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            return r
        }

        panel.addView(toggleRow(
            PillToggle(ctx, "雷达", showRadar) { v -> showRadar = v; espView?.setRadar(v) },
            PillToggle(ctx, "连线", showLines) { v -> showLines = v; espView?.setLines(v) }
        ))
        panel.addView(toggleRow(
            PillToggle(ctx, "血量环", showHp) { v -> showHp = v; espView?.setHp(v) },
            PillToggle(ctx, "技能", showSkills) { v -> showSkills = v; espView?.setSkills(v) }
        ))
        panel.addView(toggleRow(
            PillToggle(ctx, "大招", showUlt) { v -> showUlt = v; espView?.setUlt(v) },
            PillToggle(ctx, "计时", showTimers) { v -> showTimers = v; espView?.setTimers(v) }
        ))
        panel.addView(toggleRow(
            PillToggle(ctx, "等级", showLevels) { v -> showLevels = v; espView?.setLevels(v) },
            PillToggle(ctx, "距离", showDist) { v -> showDist = v; espView?.setDist(v) }
        ))
        panel.addView(toggleRow(
            PillToggle(ctx, "朝向", showFacing) { v -> showFacing = v; espView?.setFacing(v) },
            null
        ))

        // 停止按钮
        panel.addView(HudUi.dangerButton(ctx, "⏹ 停止透视") {
            stopSelf()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = HudUi.dp(ctx, 12f).toInt() }
        })

        val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(panel)
        root.addView(scroll)

        // ---------- 迷你胶囊 ----------
        val mini = TextView(ctx).apply {
            text = "ESP"
            textSize = 12f
            setTextColor(HudUi.TEXT_MAIN)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(HudUi.BG_PANEL)
                cornerRadius = HudUi.dp(ctx, 18f)
                setStroke(HudUi.dp(ctx, 1f).toInt(), HudUi.STROKE_HOT)
            }
            setPadding(
                HudUi.dp(ctx, 14f).toInt(), HudUi.dp(ctx, 6f).toInt(),
                HudUi.dp(ctx, 14f).toInt(), HudUi.dp(ctx, 6f).toInt()
            )
            visibility = View.GONE
        }
        root.addView(mini, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
        miniPillView = mini
        panelScroll = scroll

        // ---------- 挂载窗口 ----------
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val dm = resources.displayMetrics
        val tbLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val sx = prefs.getInt("tb_x", -1)
            val sy = prefs.getInt("tb_y", -1)
            if (sx >= 0 && sy >= 0) {
                x = sx.coerceIn(0, dm.widthPixels)
                y = sy.coerceIn(0, dm.heightPixels)
            } else {
                // 默认停靠右侧、垂直 1/3 处
                x = (dm.widthPixels - HudUi.dp(ctx, 250f)).toInt()
                y = dm.heightPixels / 4
            }
        }
        toolbarParams = tbLp
        toolbarRoot = root
        try {
            windowManager.addView(root, tbLp)
            toolbarView = root
        } catch (e: Exception) {
            Log.w(TAG, "工具栏挂载失败", e)
            return
        }

        // 头部拖拽移动 (≡ 手柄区域) + 双击折叠/展开
        var downRawX = 0f
        var downRawY = 0f
        var lpStartX = 0
        var lpStartY = 0
        var dragMoved = false
        var lastTapMs = 0L
        val maxTbX = dm.widthPixels - HudUi.dp(ctx, 60f).toInt()
        val maxTbY = dm.heightPixels - HudUi.dp(ctx, 40f).toInt()
        header.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    lpStartX = tbLp.x
                    lpStartY = tbLp.y
                    dragMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downRawX).toInt()
                    val dy = (ev.rawY - downRawY).toInt()
                    if (!dragMoved && (abs(dx) > 5 || abs(dy) > 5)) dragMoved = true
                    if (dragMoved) {
                        tbLp.x = (lpStartX + dx).coerceIn(0, maxTbX)
                        tbLp.y = (lpStartY + dy).coerceIn(0, maxTbY)
                        try {
                            windowManager.updateViewLayout(root, tbLp)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragMoved) {
                        prefs.edit().putInt("tb_x", tbLp.x).putInt("tb_y", tbLp.y).apply()
                    } else {
                        // 双击标题区折叠/展开
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastTapMs < 300) {
                            setCollapsed(!collapsed)
                            lastTapMs = 0L
                        } else {
                            lastTapMs = now
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        // 迷你胶囊: 点按展开, 拖拽移动
        var miniDownX = 0f
        var miniDownY = 0f
        var miniLpX = 0
        var miniLpY = 0
        var miniMoved = false
        mini.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    miniDownX = ev.rawX
                    miniDownY = ev.rawY
                    miniLpX = tbLp.x
                    miniLpY = tbLp.y
                    miniMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - miniDownX).toInt()
                    val dy = (ev.rawY - miniDownY).toInt()
                    if (!miniMoved && (abs(dx) > 5 || abs(dy) > 5)) miniMoved = true
                    if (miniMoved) {
                        tbLp.x = (miniLpX + dx).coerceIn(0, maxTbX)
                        tbLp.y = (miniLpY + dy).coerceIn(0, maxTbY)
                        try {
                            windowManager.updateViewLayout(root, tbLp)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (miniMoved) {
                        prefs.edit().putInt("tb_x", tbLp.x).putInt("tb_y", tbLp.y).apply()
                    } else {
                        setCollapsed(false)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        // 状态点呼吸动画
        pulseAnim = ValueAnimator.ofFloat(0.35f, 1f).apply {
            duration = 650
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                dotDrawable.alpha = (255 * (it.animatedValue as Float)).toInt()
            }
            start()
        }

        // 周期刷新状态芯片
        val runnable = object : Runnable {
            override fun run() {
                tvReader.text = if (readerConnected) "读取器 ✓" else "读取器 ×"
                tvReader.setTextColor(if (readerConnected) HudUi.ACCENT else HudUi.TEXT_DIM)
                tvFps.text = "FPS $fps"
                tvFps.setTextColor(if (fps > 0) HudUi.TEXT_MAIN else HudUi.TEXT_DIM)
                tvCount.text = "敌 $enemyAlive / 我 $allyAlive"
                tvStatusFull.text = readerStatus
                statusDotDrawable?.setColor(
                    when {
                        readerConnected -> HudUi.ACCENT
                        readerStatus.contains("失败") || readerStatus.contains("错误") -> HudUi.ENEMY
                        else -> Color.argb(255, 120, 140, 160)
                    }
                )
                mainHandler?.postDelayed(this, 500)
            }
        }
        statusRunnable = runnable
        mainHandler?.post(runnable)
    }

    private fun adjustRadar(deltaDp: Int) {
        // radarSize 为 px; 存储与步进以 dp 为单位
        val curDp = (radarSize / resources.displayMetrics.density).toInt()
        val newDp = (curDp + deltaDp).coerceIn(200, 640)
        radarSize = HudUi.dp(this, newDp.toFloat()).toInt()
        espView?.setMapSize(radarSize)
        prefs.edit().putInt("radar", newDp).apply()
    }

    private fun setCollapsed(v: Boolean) {
        collapsed = v
        panelScroll?.visibility = if (v) View.GONE else View.VISIBLE
        miniPillView?.visibility = if (v) View.VISIBLE else View.GONE
        try {
            val r = toolbarRoot ?: return
            val lp = toolbarParams ?: return
            windowManager.updateViewLayout(r, lp)
        } catch (_: Exception) {
        }
    }

    // ==================== 读取器部署 ====================
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

    // ==================== 前台服务 ====================
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
        startForeground(NOTIF_ID, notif)
    }
}


