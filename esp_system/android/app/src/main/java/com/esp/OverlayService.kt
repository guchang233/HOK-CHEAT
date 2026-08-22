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

    // ---- 悬浮窗组件 ----
    private var espFullScreen: EspOverlayView? = null     // 全屏 ESP (游戏画面直绘)
    private var espView: EspCanvasView? = null            // 小雷达
    private var params: WindowManager.LayoutParams? = null

    private var toolbarView: View? = null
    private var toolbarParams: WindowManager.LayoutParams? = null

    // 日志面板
    private var logPanelView: View? = null
    private var logPanelParams: WindowManager.LayoutParams? = null
    private var logTextView: TextView? = null
    private var logTabButtons: Array<TextView?> = arrayOfNulls(3)
    private var logCurrentTab = 0

    // HUD 引用
    private var toolbarRoot: FrameLayout? = null
    private var panelScroll: ScrollView? = null
    private var miniPillView: TextView? = null
    private var collapsed = false
    private var radarSize = 300
    private var radarShown = true
    private var mainHandler: Handler? = null
    private var statusRunnable: Runnable? = null
    private var pulseAnim: ValueAnimator? = null
    private var statusDotDrawable: GradientDrawable? = null
    private var tvStatusLine: TextView? = null
    private var tvDeployLine: TextView? = null

    private val TAG = "ESP-Overlay"
    private val CHANNEL_ID = "esp_overlay"
    private val NOTIF_ID = 1

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("esp_overlay", Context.MODE_PRIVATE)
    }

    companion object {
        const val ACTION_STOP = "com.esp.STOP_OVERLAY"
        const val ACTION_CENTER = "com.esp.CENTER"
        const val ACTION_DEPLOY_READER = "com.esp.DEPLOY_READER"
        const val ACTION_STOP_READER = "com.esp.STOP_READER"
        const val ACTION_LOG_READER = "com.esp.LOG_READER"

        const val LOG_TAB_DEPLOY = 0
        const val LOG_TAB_READER = 1
        const val LOG_TAB_DIAG = 2

        const val GAME_PKG_DEFAULT = "com.tencent.tmgp.sgame"
        const val READER_PORT_DEFAULT = 47291

        @Volatile var isRunning = false

        @Volatile var lastLog = ""
        @Volatile var readerStatus = "未部署"

        // 实时状态 (读取线程写, UI 线程读)
        @Volatile var readerConnected = false
        @Volatile var fps = 0
        @Volatile var enemyAlive = 0
        @Volatile var allyAlive = 0
    }

    // ==================== 开关持久化 ====================
    private fun boolPref(key: String, def: Boolean): Boolean =
        prefs.getBoolean(key, def)

    private fun saveBool(key: String, v: Boolean) {
        prefs.edit().putBoolean(key, v).apply()
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startInForeground()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mainHandler = Handler(Looper.getMainLooper())

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // ---------- 1. 全屏 ESP 层 (游戏画面直绘, 不拦截触摸) ----------
        val full = EspOverlayView(this).apply {
            config.espOn = boolPref("cfg_esp", true)
            config.showBox = boolPref("cfg_box", true)
            config.showHp = boolPref("cfg_hp", true)
            config.showSkills = boolPref("cfg_skills", true)
            config.showUlt = boolPref("cfg_ult", true)
            config.showLevel = boolPref("cfg_level", true)
            config.showDist = boolPref("cfg_dist", false)
            config.showFacing = boolPref("cfg_facing", false)
            config.showLines = boolPref("cfg_lines", false)
            config.showHidden = boolPref("cfg_hidden", true)
            config.showAlly = boolPref("cfg_ally", false)
            config.showMinions = boolPref("cfg_minions", false)
        }
        espFullScreen = full
        val fullLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        try {
            windowManager.addView(full, fullLp)
        } catch (e: Exception) {
            Log.w(TAG, "全屏 ESP 层挂载失败", e)
        }

        // ---------- 2. 小雷达 (独立窗口, 可开关/拖动) ----------
        val view = EspCanvasView(this)
        espView = view
        val radarDp = prefs.getInt("radar", 132).coerceIn(100, 320)
        radarSize = HudUi.dp(this, radarDp.toFloat()).toInt()
        view.setMapSize(radarSize)
        radarShown = boolPref("cfg_radar", true)

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
        if (radarShown) {
            try {
                windowManager.addView(view, lp)
            } catch (e: Exception) {
                Log.w(TAG, "雷达挂载失败", e)
            }
        }

        // ---------- 3. 读取器数据回调 ----------
        var frames = 0
        var lastMs = 0L
        ReaderClient.start { status ->
            full.updateFrame(status.frame)
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
            ACTION_LOG_READER -> showLogPanel(LOG_TAB_DEPLOY)
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

    /** 雷达大小调节 (dp 步进, 100-320) */
    private fun adjustRadar(deltaDp: Int) {
        val curDp = (radarSize / resources.displayMetrics.density).toInt()
        val newDp = (curDp + deltaDp).coerceIn(100, 320)
        radarSize = HudUi.dp(this, newDp.toFloat()).toInt()
        espView?.setMapSize(radarSize)
        prefs.edit().putInt("radar", newDp).apply()
    }

    /** 显示/隐藏雷达窗口 */
    private fun setRadarShown(v: Boolean) {
        radarShown = v
        saveBool("cfg_radar", v)
        val view = espView ?: return
        val lp = params ?: return
        try {
            if (v) {
                if (view.windowToken == null || view.parent == null) {
                    windowManager.addView(view, lp)
                } else {
                    view.visibility = View.VISIBLE
                }
            } else {
                if (view.parent != null) {
                    windowManager.removeView(view)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "雷达开关失败", e)
        }
    }

    override fun onDestroy() {
        isRunning = false
        statusRunnable?.let { mainHandler?.removeCallbacks(it) }
        pulseAnim?.cancel()
        ReaderClient.stop()
        // 注意: 不 kill Root shell — 服务重启时直接复用, su 授权弹窗只出现一次。
        // shell 是本 app 的子进程, app 进程被杀时系统会自动回收它。
        espFullScreen?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        espView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        toolbarView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        hideLogPanel()
        espFullScreen = null
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

    // ==================== 紧凑工具栏 ====================
    private fun showToolbar() {
        if (toolbarView != null) return
        val ctx = this

        val root = FrameLayout(ctx)

        // ---------- 完整面板 (紧凑) ----------
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = HudUi.panelBg(ctx, 14f)
            setPadding(
                HudUi.dp(ctx, 9f).toInt(), HudUi.dp(ctx, 7f).toInt(),
                HudUi.dp(ctx, 9f).toInt(), HudUi.dp(ctx, 9f).toInt()
            )
            minimumWidth = HudUi.dp(ctx, 208f).toInt()
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
            textSize = 13f
            setTextColor(HudUi.TEXT_DIM)
        })
        val dotSize = HudUi.dp(ctx, 7f).toInt()
        header.addView(View(ctx).apply {
            background = dotDrawable
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                marginStart = HudUi.dp(ctx, 7f).toInt()
            }
        })
        header.addView(TextView(ctx).apply {
            text = "ESP"
            textSize = 13f
            setTextColor(HudUi.TEXT_MAIN)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = HudUi.dp(ctx, 7f).toInt() }
        })
        header.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(HudUi.actionButton(ctx, "—") { setCollapsed(!collapsed) })
        panel.addView(header)

        // 状态行 (1 行小字: 连接 + FPS + 敌我)
        val statusLine = TextView(ctx).apply {
            text = "连接 --  FPS --  敌 - 我 -"
            textSize = 10f
            setTextColor(HudUi.TEXT_MAIN)
            maxLines = 1
            setPadding(0, HudUi.dp(ctx, 5f).toInt(), 0, 0)
        }
        tvStatusLine = statusLine
        panel.addView(statusLine)

        // 部署状态行
        val deployLine = TextView(ctx).apply {
            text = readerStatus
            textSize = 9f
            setTextColor(HudUi.TEXT_DIM)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        tvDeployLine = deployLine
        panel.addView(deployLine)

        // 主开关行: 透视总开关 + 雷达
        val cfg = espFullScreen?.config
        val mainRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, HudUi.dp(ctx, 6f).toInt(), 0, 0)
        }
        mainRow.addView(PillToggle(ctx, "透视", cfg?.espOn ?: true) { v ->
            espFullScreen?.config?.espOn = v
            espFullScreen?.postInvalidate()
            saveBool("cfg_esp", v)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.35f))
        mainRow.addView(PillToggle(ctx, "雷达", radarShown) { v -> setRadarShown(v) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(mainRow)

        // 按钮行: 部署 | 日志 | 停止
        fun weightLp() = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginEnd = HudUi.dp(ctx, 5f).toInt() }

        val rowA = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, HudUi.dp(ctx, 6f).toInt(), 0, 0)
        }
        rowA.addView(HudUi.actionButton(ctx, "部署") {
            readerStatus = "部署中..."
            sendServiceAction(ACTION_DEPLOY_READER)
        }, weightLp())
        rowA.addView(HudUi.actionButton(ctx, "日志") {
            sendServiceAction(ACTION_LOG_READER)
        }, weightLp())
        rowA.addView(HudUi.dangerButton(ctx, "停止") {
            sendServiceAction(ACTION_STOP_READER)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(rowA)

        // 绘制项标签
        panel.addView(TextView(ctx).apply {
            text = "屏幕绘制项"
            textSize = 9f
            setTextColor(HudUi.TEXT_DIM)
            letterSpacing = 0.12f
            setPadding(0, HudUi.dp(ctx, 8f).toInt(), 0, HudUi.dp(ctx, 2f).toInt()
            )
        })

        // 开关网格 (2 列) — 全部作用于全屏 ESP 层
        fun toggleRow(): LinearLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, HudUi.dp(ctx, 1f).toInt(), 0, HudUi.dp(ctx, 1f).toInt())
        }
        fun cell(parent: LinearLayout, weight: Float = 1f) = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, weight
        )

        fun espToggle(label: String, key: String, def: Boolean, setter: (Boolean) -> Unit): PillToggle =
            PillToggle(ctx, label, boolPref(key, def), compact = true) { v ->
                setter(v)
                saveBool(key, v)
                espFullScreen?.postInvalidate()
            }

        val r1 = toggleRow()
        r1.addView(espToggle("方框", "cfg_box", true) { espFullScreen?.config?.showBox = it }, cell(r1))
        r1.addView(espToggle("血条", "cfg_hp", true) { espFullScreen?.config?.showHp = it }, cell(r1))
        panel.addView(r1)

        val r2 = toggleRow()
        r2.addView(espToggle("技能", "cfg_skills", true) { espFullScreen?.config?.showSkills = it }, cell(r2))
        r2.addView(espToggle("大招", "cfg_ult", true) { espFullScreen?.config?.showUlt = it }, cell(r2))
        panel.addView(r2)

        val r3 = toggleRow()
        r3.addView(espToggle("等级", "cfg_level", true) { espFullScreen?.config?.showLevel = it }, cell(r3))
        r3.addView(espToggle("距离", "cfg_dist", false) { espFullScreen?.config?.showDist = it }, cell(r3))
        panel.addView(r3)

        val r4 = toggleRow()
        r4.addView(espToggle("朝向", "cfg_facing", false) { espFullScreen?.config?.showFacing = it }, cell(r4))
        r4.addView(espToggle("连线", "cfg_lines", false) { espFullScreen?.config?.showLines = it }, cell(r4))
        panel.addView(r4)

        val r5 = toggleRow()
        r5.addView(espToggle("友军", "cfg_ally", false) { espFullScreen?.config?.showAlly = it }, cell(r5))
        r5.addView(espToggle("野怪", "cfg_minions", false) { espFullScreen?.config?.showMinions = it }, cell(r5))
        panel.addView(r5)

        // 雷达大小行
        val r6 = toggleRow().apply { setPadding(0, HudUi.dp(ctx, 4f).toInt(), 0, 0) }
        r6.addView(HudUi.actionButton(ctx, "雷达 −") { adjustRadar(-32) }, weightLp())
        r6.addView(HudUi.actionButton(ctx, "雷达 ＋") { adjustRadar(32) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(r6)

        val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(panel)
        root.addView(scroll)

        // ---------- 迷你胶囊 ----------
        val mini = TextView(ctx).apply {
            text = "ESP"
            textSize = 11f
            setTextColor(HudUi.TEXT_MAIN)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(HudUi.BG_PANEL)
                cornerRadius = HudUi.dp(ctx, 16f)
                setStroke(HudUi.dp(ctx, 1f).toInt(), HudUi.STROKE_HOT)
            }
            setPadding(
                HudUi.dp(ctx, 12f).toInt(), HudUi.dp(ctx, 5f).toInt(),
                HudUi.dp(ctx, 12f).toInt(), HudUi.dp(ctx, 5f).toInt()
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
                // 默认停靠右侧
                x = (dm.widthPixels - HudUi.dp(ctx, 216f)).toInt()
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

        // 头部拖拽移动 + 双击折叠/展开
        var downRawX = 0f
        var downRawY = 0f
        var lpStartX = 0
        var lpStartY = 0
        var dragMoved = false
        var lastTapMs = 0L
        val maxTbX = dm.widthPixels - HudUi.dp(ctx, 48f).toInt()
        val maxTbY = dm.heightPixels - HudUi.dp(ctx, 36f).toInt()
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

        // 周期刷新状态行
        val runnable = object : Runnable {
            override fun run() {
                val conn = if (readerConnected) "连接✓" else "连接×"
                tvStatusLine?.text = "$conn  FPS $fps  敌 $enemyAlive 我 $allyAlive"
                tvStatusLine?.setTextColor(
                    if (readerConnected) HudUi.ACCENT else HudUi.TEXT_DIM
                )
                tvDeployLine?.text = readerStatus
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
        DeployLogger.init(this)
        val t0 = SystemClock.elapsedRealtime()
        DeployLogger.i("Deploy", "========== 部署开始 ==========")
        Thread {
            try {
                // ---- 阶段 1: Root 授权 ----
                DeployLogger.i("Deploy", "阶段1/3: 请求 Root 授权...")
                if (!RootHelper.isRootAvailable()) {
                    DeployLogger.e("Deploy", "Root 不可用 — su 未安装 / 未授权 / 授权被拒绝")
                    DeployLogger.e("Deploy", "对策: 确认设备已 Root, 且 Magisk/KernelSU 已对「ESP 透视」授予 Root 权限")
                    readerStatus = "失败: 未获得 Root (点「日志」查看分析)"
                    return@Thread
                }

                // ---- 阶段 2: 部署二进制 (首选 ABI, 失败自动切换) ----
                val primary = RootHelper.pickReaderAsset()
                val fallback = if (primary == "tv_reader_x64") "tv_reader_arm64" else "tv_reader_x64"
                DeployLogger.i("Deploy", "阶段2/3: 部署读取器 (首选 $primary / 备用 $fallback)")
                var deployResult = RootHelper.extractAndDeployReader(this, primary)
                if (!deployResult.success || !RootHelper.launchTest()) {
                    DeployLogger.w("Deploy", "首选 $primary 不可用, 切换备用 $fallback 重试")
                    deployResult = RootHelper.extractAndDeployReader(this, fallback)
                }
                if (!deployResult.success) {
                    DeployLogger.e("Deploy", "部署失败: ${deployResult.error}")
                    readerStatus = "部署失败 (点「日志」查看诊断)"
                    runDiagnosis()
                    return@Thread
                }

                // ---- 阶段 3: 启动 ----
                DeployLogger.i("Deploy", "阶段3/3: 启动读取器 (包名 $GAME_PKG_DEFAULT, 端口 $READER_PORT_DEFAULT)")
                val launchResult = RootHelper.launchReader(GAME_PKG_DEFAULT, READER_PORT_DEFAULT)
                if (launchResult.success) {
                    readerStatus = "读取器运行中 ✓"
                    DeployLogger.i("Deploy", "========== 部署成功, 总耗时 ${SystemClock.elapsedRealtime() - t0}ms ==========")
                } else {
                    DeployLogger.e("Deploy", "========== 启动失败 ==========")
                    readerStatus = "启动失败 (点「日志」查看诊断)"
                    lastLog = RootHelper.getReaderLog(30)
                    runDiagnosis()
                }
            } catch (e: Exception) {
                DeployLogger.e("Deploy", "部署异常: ${e.stackTraceToString()}")
                readerStatus = "错误: ${e.message?.take(40)} (点「日志」)"
                Log.e(TAG, "deploy failed", e)
            }
        }.start()
    }

    /** 失败后自动执行环境诊断并写入日志, 供「日志」面板查看。 */
    private fun runDiagnosis() {
        try {
            val diag = RootHelper.diagnose(GAME_PKG_DEFAULT, READER_PORT_DEFAULT)
            val hints = RootHelper.analyzeFailure(diag)
            DeployLogger.e("分析", "失败原因分析:")
            hints.forEachIndexed { i, h -> DeployLogger.e("分析", "${i + 1}. $h") }
        } catch (e: Exception) {
            DeployLogger.e("Diag", "诊断自身异常: ${e.message}")
        }
    }

    // ==================== 日志面板 ====================
    private fun showLogPanel(tab: Int) {
        if (logPanelView != null) {
            refreshLogPanel(tab)
            return
        }
        DeployLogger.init(this)
        val ctx = this
        val dm = resources.displayMetrics
        val panelW = HudUi.dp(ctx, 300f).toInt()
        val panelH = HudUi.dp(ctx, 400f).toInt()
        val pad = HudUi.dp(ctx, 9f).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = HudUi.panelBg(ctx, 14f)
            setPadding(pad, pad, pad, pad)
        }

        // ---- 头部: 标题 + 关闭 ----
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(ctx).apply {
            text = "诊断日志"
            textSize = 13f
            setTextColor(HudUi.ACCENT)
            typeface = Typeface.DEFAULT_BOLD
        })
        header.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(HudUi.actionButton(ctx, "× 关闭") { hideLogPanel() })
        root.addView(header)

        root.addView(View(ctx).apply {
            setBackgroundColor(HudUi.STROKE_DIM)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, HudUi.dp(ctx, 1f).toInt()
            ).apply { topMargin = HudUi.dp(ctx, 6f).toInt() }
        })

        // ---- Tab 行 ----
        fun tabButton(label: String, idx: Int): TextView =
            HudUi.actionButton(ctx, label) { refreshLogPanel(idx) }

        val tabRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, HudUi.dp(ctx, 6f).toInt(), 0, HudUi.dp(ctx, 2f).toInt())
        }
        logTabButtons = arrayOf<TextView?>(
            tabButton("部署", LOG_TAB_DEPLOY),
            tabButton("读取器", LOG_TAB_READER),
            tabButton("诊断", LOG_TAB_DIAG),
            HudUi.actionButton(ctx, "清除") {
                DeployLogger.clear()
                refreshLogPanel(logCurrentTab)
            }
        )
        fun tabLp(last: Boolean) = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, if (last) 0.6f else 1f
        ).apply { marginEnd = HudUi.dp(ctx, 4f).toInt() }
        logTabButtons.forEachIndexed { i, b ->
            tabRow.addView(b, if (i == logTabButtons.size - 1) tabLp(true) else tabLp(false))
        }
        root.addView(tabRow)

        // ---- 内容区 ----
        val text = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            textSize = 9.5f
            setTextColor(HudUi.TEXT_MAIN)
            setLineSpacing(HudUi.dp(ctx, 2f), 1f)
            setPadding(0, HudUi.dp(ctx, 4f).toInt(), 0, 0)
        }
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = true
            addView(text, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        logTextView = text

        // ---- 挂载 ----
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            panelW, panelH, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((dm.widthPixels - panelW) / 2).coerceAtLeast(0)
            y = ((dm.heightPixels - panelH) / 2).coerceAtLeast(0)
        }
        try {
            windowManager.addView(root, lp)
        } catch (e: Exception) {
            Log.w(TAG, "日志面板挂载失败", e)
            return
        }
        logPanelView = root
        logPanelParams = lp

        // 头部拖动移动
        var downX = 0f; var downY = 0f; var lpX = 0; var lpY = 0
        header.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    lpX = lp.x; lpY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (lpX + (ev.rawX - downX).toInt()).coerceIn(0, dm.widthPixels)
                    lp.y = (lpY + (ev.rawY - downY).toInt()).coerceIn(0, dm.heightPixels)
                    try { windowManager.updateViewLayout(root, lp) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> true
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        refreshLogPanel(tab)
    }

    private fun hideLogPanel() {
        logPanelView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        logPanelView = null
        logPanelParams = null
    }

    /** 切换 tab 并刷新内容 (诊断 tab 为即时采集, 后台执行)。 */
    private fun refreshLogPanel(tab: Int) {
        logCurrentTab = tab
        logTabButtons.forEachIndexed { i, b ->
            b ?: return@forEachIndexed
            val active = i == tab
            b.setTextColor(if (active) HudUi.ACCENT else HudUi.TEXT_DIM)
        }
        val tv = logTextView ?: return

        when (tab) {
            LOG_TAB_DEPLOY -> {
                tv.text = DeployLogger.get().ifEmpty { "暂无部署日志 — 点工具栏「部署」开始" }
                postScrollToBottom()
            }
            LOG_TAB_READER -> {
                tv.text = "(现场采集 tv_reader 日志...)"
                Thread {
                    val log = RootHelper.getReaderLog(100)
                    mainHandler?.post {
                        logTextView?.text = log.ifEmpty { "tv_reader 暂无日志输出" }
                        postScrollToBottom()
                    }
                }.start()
            }
            LOG_TAB_DIAG -> {
                tv.text = "诊断中... (采集 ABI / Root / SELinux / 挂载 / 游戏 / 端口)\n"
                Thread {
                    val diag = RootHelper.diagnose(GAME_PKG_DEFAULT, READER_PORT_DEFAULT)
                    val hints = RootHelper.analyzeFailure(diag)
                    val sb = StringBuilder()
                    sb.append("【失败原因分析】\n")
                    hints.forEach { sb.append(" • $it\n") }
                    sb.append("\n【原始诊断】\n")
                    sb.append(diag)
                    mainHandler?.post {
                        logTextView?.text = sb.toString()
                        postScrollToBottom()
                    }
                }.start()
            }
        }
    }

    private fun postScrollToBottom() {
        logPanelView?.post {
            val root = logPanelView as? LinearLayout ?: return@post
            for (i in root.childCount - 1 downTo 0) {
                val c = root.getChildAt(i)
                if (c is ScrollView) {
                    c.post { c.fullScroll(View.FOCUS_DOWN) }
                    break
                }
            }
        }
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
