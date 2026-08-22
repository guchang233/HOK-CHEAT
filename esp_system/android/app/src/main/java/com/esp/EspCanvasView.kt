package com.esp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 战术雷达 — 玻璃质感自绘 HUD
 * 自上而下: 雷达区 (方形) + 资源计时区 (动态高度)
 */
class EspCanvasView(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val WORLD_MIN = -10000f
        const val WORLD_MAX = 10000f
        const val WORLD_RANGE = 20000f

        val C_ENEMY = Color.argb(255, 255, 59, 71)
        val C_ALLY = Color.argb(255, 61, 220, 132)
        val C_TOWER = Color.argb(255, 255, 179, 0)
        val C_MONSTER = Color.argb(235, 190, 150, 110)
        val C_MINION = Color.argb(200, 150, 165, 185)
        val C_ACCENT = Color.argb(255, 0, 229, 255)
        val C_SELF = Color.argb(255, 242, 248, 255)
        val C_GRID = Color.argb(26, 140, 190, 220)
        val C_GLASS = Color.argb(198, 10, 14, 20)
        val C_GOLD = Color.argb(255, 255, 200, 60)
        val C_TEXT = Color.argb(242, 226, 240, 255)
        val C_DIM = Color.argb(160, 150, 170, 190)

        private val TAU = (2.0 * PI).toFloat()
    }

    // ---- 显示开关 ----
    private var showRadar = true
    private var showLines = true
    private var showHp = true
    private var showSkills = true
    private var showUlt = true
    private var showTimers = true
    private var showLevels = true
    private var showDist = true
    private var showFacing = true

    private var mapSize = 300
    private var currentFrame: EspFrame? = null
    private var hadTimers = false

    private val density = resources.displayMetrics.density

    // ---- 画笔 ----
    private val paintBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f
        color = Color.argb(110, 0, 229, 255)
    }
    private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f; color = C_GRID
    }
    private val paintTick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f; color = C_ACCENT
    }
    private val paintDot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintArc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val paintBracket = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f
    }
    private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val paintSweep = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = C_ACCENT
    }
    private val paintChipBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintChipStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C_TEXT; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
    }
    private val paintTextDim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C_DIM; textSize = 12f
    }
    private val paintTextSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C_TEXT; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
    }

    private val rectTmp = RectF()
    private val chipRect = RectF()

    // ---- 对外接口 ----
    fun updateFrame(frame: EspFrame?) {
        val had = hadTimers
        currentFrame = frame
        val has = frame?.timers?.isNotEmpty() == true
        if (had != has) {
            hadTimers = has
            requestLayout()
        }
        postInvalidate()
    }

    fun setMapSize(size: Int) {
        mapSize = size.coerceIn(200, 640)
        requestLayout()
        postInvalidate()
    }

    fun setRadar(v: Boolean) { showRadar = v; requestLayout(); postInvalidate() }
    fun setLines(v: Boolean) { showLines = v; postInvalidate() }
    fun setHp(v: Boolean) { showHp = v; postInvalidate() }
    fun setSkills(v: Boolean) { showSkills = v; postInvalidate() }
    fun setUlt(v: Boolean) { showUlt = v; postInvalidate() }
    fun setTimers(v: Boolean) { showTimers = v; requestLayout(); postInvalidate() }
    fun setLevels(v: Boolean) { showLevels = v; postInvalidate() }
    fun setDist(v: Boolean) { showDist = v; postInvalidate() }
    fun setFacing(v: Boolean) { showFacing = v; postInvalidate() }

    private fun timerPanelHeight(rows: Int): Int = 42 + rows * 36 + 10

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val frame = currentFrame
        val radarPart = if (frame == null || showRadar) mapSize else 0
        val timersPart = if (frame != null && showTimers && frame.timers.isNotEmpty()) {
            timerPanelHeight(frame.timers.size)
        } else 0
        setMeasuredDimension(
            View.resolveSize(mapSize, widthMeasureSpec),
            View.resolveSize(radarPart + timersPart, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = currentFrame
        if (frame == null) {
            drawStandby(canvas)
            return
        }
        var y = 0f
        if (showRadar) {
            drawRadar(canvas, frame)
            y = mapSize.toFloat()
        }
        if (showTimers && frame.timers.isNotEmpty()) {
            drawTimers(canvas, frame.timers, y)
        }
    }

    private fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a shl 24)

    // ==================== 雷达 ====================
    private fun drawRadar(canvas: Canvas, frame: EspFrame) {
        val size = mapSize.toFloat()
        val pad = 8f
        val inner = size - pad * 2f

        // 玻璃底
        paintBg.color = C_GLASS
        rectTmp.set(0f, 0f, size, size)
        canvas.drawRoundRect(rectTmp, 12f, 12f, paintBg)
        canvas.drawRoundRect(rectTmp, 12f, 12f, paintBorder)

        // 网格 (4x4 内线)
        for (i in 1..3) {
            val t = pad + inner * i / 4f
            canvas.drawLine(t, pad, t, size - pad, paintGrid)
            canvas.drawLine(pad, t, size - pad, t, paintGrid)
        }

        // 角标
        drawCornerTicks(canvas, size)

        // 头部统计芯片
        val enemies = frame.actors.count { !it.ally && it.isHero }
        val allies = frame.actors.count { it.ally && it.isHero }
        val monsters = frame.actors.count { it.isMonster }
        paintTextSmall.color = C_TEXT
        drawChip(canvas, "敌 $enemies", pad + 28f, pad + 16f,
            paintTextSmall, Color.argb(150, 120, 20, 28), C_ENEMY)
        drawChip(canvas, "我 $allies", pad + 82f, pad + 16f,
            paintTextSmall, Color.argb(140, 16, 70, 40), C_ALLY)
        drawChip(canvas, "怪 $monsters", pad + 136f, pad + 16f,
            paintTextSmall, Color.argb(140, 40, 40, 52), C_DIM)
        if (frame.gameTime > 0f) {
            val gm = frame.gameTime.toInt()
            val mm = (gm / 60).toString().padStart(2, '0')
            val ss = (gm % 60).toString().padStart(2, '0')
            val t = "$mm:$ss"
            val w = paintTextSmall.measureText(t) + 20f
            drawChip(canvas, t, size - pad - w / 2f, pad + 16f,
                paintTextSmall, Color.argb(150, 6, 40, 52), C_ACCENT)
        }

        // 世界坐标 → 雷达
        val mapX = { wx: Float -> pad + (wx - WORLD_MIN) / WORLD_RANGE * inner }
        val mapY = { wz: Float -> pad + (wz - WORLD_MIN) / WORLD_RANGE * inner }
        val cx = mapX(frame.selfX)
        val cy = mapY(frame.selfZ)

        // 扫描线
        drawSweep(canvas, cx, cy, inner / 2f - 6f)

        // 敌我连线 + 距离标签
        if (showLines) {
            for (a in frame.actors) {
                if (a.ally || !a.isHero) continue
                val ax = mapX(a.x)
                val ay = mapY(a.z)
                paintLine.color = Color.argb(150, 255, 70, 80)
                canvas.drawLine(cx, cy, ax, ay, paintLine)
                if (showDist) {
                    val dx = a.x - frame.selfX
                    val dz = a.z - frame.selfZ
                    val dist = sqrt(dx * dx + dz * dz)
                    val label = "${(dist / 100f).toInt()}m"
                    paintTextSmall.color = Color.argb(230, 255, 120, 128)
                    drawChip(canvas, label, (cx + ax) / 2f, (cy + ay) / 2f,
                        paintTextSmall, Color.argb(170, 30, 10, 14), 0)
                }
            }
        }

        // 单位
        val now = SystemClock.elapsedRealtime()
        var heroIdx = 0
        for (a in frame.actors) {
            val ax = mapX(a.x)
            val ay = mapY(a.z)
            if (ax < pad - 4f || ax > size - pad + 4f) continue
            if (ay < pad - 4f || ay > size - pad + 4f) continue
            when {
                a.isHero -> {
                    drawHero(canvas, a, ax, ay, now, heroIdx)
                    heroIdx++
                }
                a.isTower -> drawTower(canvas, ax, ay, a.visible)
                a.isMonster -> drawDot(canvas, ax, ay, C_MONSTER, 5f, a.visible)
                else -> drawDot(canvas, ax, ay, C_MINION, 3f, a.visible)
            }
        }

        // 自己
        paintDot.style = Paint.Style.FILL
        paintDot.color = C_SELF
        paintDot.setShadowLayer(10f, 0f, 0f, C_SELF)
        canvas.drawCircle(cx, cy, 5.5f, paintDot)
        paintDot.clearShadowLayer()
        paintArc.strokeWidth = 2f
        paintArc.color = C_ACCENT
        rectTmp.set(cx - 10f, cy - 10f, cx + 10f, cy + 10f)
        canvas.drawArc(rectTmp, 0f, 360f, false, paintArc)
    }

    private fun drawHero(canvas: Canvas, a: EspActor, x: Float, y: Float, now: Long, idx: Int) {
        val color = if (a.ally) C_ALLY else C_ENEMY
        val alpha = if (a.visible) 255 else 110
        val r = 7f

        // 敌方脉冲圈
        if (!a.ally && a.visible) {
            val phase = (now % 1200f) / 1200f
            val pr = r + 6f + 5f * sin(phase * TAU + idx)
            paintArc.strokeWidth = 2f
            paintArc.color = Color.argb(120, 255, 59, 71)
            rectTmp.set(x - pr, y - pr, x + pr, y + pr)
            canvas.drawArc(rectTmp, 0f, 360f, false, paintArc)
        }

        // 本体 (辉光)
        paintDot.style = Paint.Style.FILL
        paintDot.color = withAlpha(color, alpha)
        if (a.visible) paintDot.setShadowLayer(9f, 0f, 0f, color)
        canvas.drawCircle(x, y, r, paintDot)
        paintDot.clearShadowLayer()

        // 血量弧
        if (showHp && a.maxHp > 0) {
            val ratio = a.hpRatio.coerceIn(0f, 1f)
            val arcR = r + 4.5f
            paintArc.strokeWidth = 3f
            paintArc.color = when {
                ratio > 0.55f -> Color.argb(230, 61, 220, 132)
                ratio > 0.28f -> Color.argb(230, 255, 179, 0)
                else -> Color.argb(230, 255, 59, 71)
            }
            rectTmp.set(x - arcR, y - arcR, x + arcR, y + arcR)
            canvas.drawArc(rectTmp, -90f, 360f * ratio, false, paintArc)
        }

        // 大招环
        if (showUlt) {
            val ultR = r + 9.5f
            paintArc.strokeWidth = 2f
            if (a.ultimateReady || a.ultimateCooldown <= 0f) {
                paintArc.color = C_GOLD
                rectTmp.set(x - ultR, y - ultR, x + ultR, y + ultR)
                canvas.drawArc(rectTmp, 0f, 360f, false, paintArc)
            } else if (a.ultimateTotal > 0f) {
                val ur = a.ultimateRatio.coerceIn(0f, 1f)
                paintArc.color = Color.argb(160, 200, 200, 210)
                rectTmp.set(x - ultR, y - ultR, x + ultR, y + ultR)
                canvas.drawArc(rectTmp, -90f, 360f * ur, false, paintArc)
            }
        }

        // 召唤师技能
        if (showSkills && a.summonerSpells.isNotEmpty()) {
            val spells = a.summonerSpells.take(2)
            val pipY = y + r + 10f
            var px = x - (spells.size - 1) * 6f
            for (s in spells) {
                paintDot.color = if (s.ready || s.cooldownRemaining <= 0f) {
                    C_ACCENT
                } else {
                    Color.argb(160, 110, 125, 145)
                }
                canvas.drawCircle(px, pipY, 3f, paintDot)
                px += 12f
            }
        }

        // 朝向
        if (showFacing && a.facingAngle != 0f) {
            val rad = Math.toRadians(a.facingAngle.toDouble()).toFloat()
            val len = 15f
            paintArc.strokeWidth = 2f
            paintArc.color = withAlpha(color, 190)
            canvas.drawLine(x, y, x + len * sin(rad), y - len * cos(rad), paintArc)
        }

        // 等级 (仅敌方, 减少视觉噪音)
        if (showLevels && !a.ally) {
            paintTextSmall.color = Color.argb(235, 255, 200, 205)
            drawChip(canvas, "Lv${a.level}", x + r + 16f, y - r - 10f,
                paintTextSmall, Color.argb(170, 40, 12, 16), 0)
        }
    }

    private fun drawTower(canvas: Canvas, x: Float, y: Float, visible: Boolean) {
        paintBracket.strokeWidth = 2f
        paintBracket.color = withAlpha(C_TOWER, if (visible) 235 else 110)
        rectTmp.set(x - 5.5f, y - 5.5f, x + 5.5f, y + 5.5f)
        canvas.drawRect(rectTmp, paintBracket)
    }

    private fun drawDot(canvas: Canvas, x: Float, y: Float, color: Int, r: Float, visible: Boolean) {
        paintDot.style = Paint.Style.FILL
        paintDot.color = withAlpha(color, if (visible) 255 else 100)
        canvas.drawCircle(x, y, r, paintDot)
    }

    private fun drawSweep(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val cycle = 2800f
        val ang = ((SystemClock.elapsedRealtime() % cycle) / cycle) * TAU
        val alphas = intArrayOf(200, 90, 40)
        for (i in 0..2) {
            val a2 = ang - i * 0.14f
            paintSweep.alpha = alphas[i]
            canvas.drawLine(cx, cy, cx + radius * sin(a2), cy + radius * cos(a2), paintSweep)
        }
        paintSweep.alpha = 255
    }

    private fun drawCornerTicks(canvas: Canvas, size: Float) {
        val len = 14f
        val o = 2f
        val s = size - o
        paintTick.color = C_ACCENT
        canvas.drawLine(o, o + len, o, o, paintTick)
        canvas.drawLine(o, o, o + len, o, paintTick)
        canvas.drawLine(s - len, o, s, o, paintTick)
        canvas.drawLine(s, o, s, o + len, paintTick)
        canvas.drawLine(o, s - len, o, s, paintTick)
        canvas.drawLine(o, s, o + len, s, paintTick)
        canvas.drawLine(s - len, s, s, s, paintTick)
        canvas.drawLine(s, s, s, s - len, paintTick)
    }

    // ==================== 资源计时 ====================
    private fun drawTimers(canvas: Canvas, timers: List<EspGlobalTimer>, top: Float) {
        val w = mapSize.toFloat()
        val h = timerPanelHeight(timers.size).toFloat()
        paintBg.color = C_GLASS
        rectTmp.set(0f, top + 6f, w, top + 6f + h)
        canvas.drawRoundRect(rectTmp, 12f, 12f, paintBg)
        canvas.drawRoundRect(rectTmp, 12f, 12f, paintBorder)

        val padX = 12f
        paintText.color = C_GOLD
        paintText.textSize = 14f
        canvas.drawText("⚡ 资源计时", padX, top + 30f, paintText)

        var y = top + 48f
        for (t in timers) {
            paintTextDim.textSize = 12f
            paintTextDim.color = C_TEXT
            canvas.drawText(t.label, padX, y + 10f, paintTextDim)

            paintTextSmall.textSize = 12f
            val timeStr: String
            val timeColor: Int
            if (t.active) {
                val s = t.respawnSeconds.toInt().coerceAtLeast(0)
                timeStr = "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
                timeColor = Color.argb(235, 255, 179, 0)
            } else {
                timeStr = "已就绪"
                timeColor = C_ALLY
            }
            paintTextSmall.color = timeColor
            canvas.drawText(timeStr, w - padX - paintTextSmall.measureText(timeStr), y + 10f, paintTextSmall)

            // 进度条
            val barL = padX
            val barR = w - padX
            val barW = barR - barL
            paintChipBg.style = Paint.Style.FILL
            paintChipBg.color = Color.argb(120, 24, 32, 44)
            rectTmp.set(barL, y + 16f, barR, y + 21f)
            canvas.drawRoundRect(rectTmp, 2.5f, 2.5f, paintChipBg)
            val ratio = t.ratio.coerceIn(0f, 1f)
            if (ratio > 0f) {
                paintChipBg.color = Color.argb(220, 255, 179, 0)
                rectTmp.set(barL, y + 16f, barL + barW * ratio, y + 21f)
                canvas.drawRoundRect(rectTmp, 2.5f, 2.5f, paintChipBg)
            }
            y += 36f
        }
    }

    // ==================== 待机态 ====================
    private fun drawStandby(canvas: Canvas) {
        val size = mapSize.toFloat()
        paintBg.color = C_GLASS
        rectTmp.set(0f, 0f, size, size)
        canvas.drawRoundRect(rectTmp, 12f, 12f, paintBg)
        canvas.drawRoundRect(rectTmp, 12f, 12f, paintBorder)
        drawCornerTicks(canvas, size)

        val cx = size / 2f
        val cy = size / 2f - 14f
        val phase = (SystemClock.elapsedRealtime() % 1400f) / 1400f
        val pr = 10f + 8f * sin(phase * TAU)
        paintArc.strokeWidth = 2.5f
        paintArc.color = C_ACCENT
        rectTmp.set(cx - pr, cy - pr, cx + pr, cy + pr)
        canvas.drawArc(rectTmp, 0f, 360f, false, paintArc)
        paintDot.style = Paint.Style.FILL
        paintDot.color = C_ACCENT
        canvas.drawCircle(cx, cy, 4f, paintDot)

        paintText.color = C_TEXT
        paintText.textSize = 16f
        val t1 = "等待对局数据"
        canvas.drawText(t1, cx - paintText.measureText(t1) / 2f, cy + 44f, paintText)
        paintTextDim.textSize = 12f
        val t2 = "部署读取器 · 进入对局后生效"
        canvas.drawText(t2, cx - paintTextDim.measureText(t2) / 2f, cy + 66f, paintTextDim)
    }

    // ==================== 芯片 ====================
    private fun drawChip(
        canvas: Canvas, text: String, cx: Float, cy: Float,
        tPaint: Paint, bg: Int, stroke: Int = 0
    ) {
        val tw = tPaint.measureText(text)
        val ph = 5f
        val pv = 2.5f
        val h = tPaint.textSize + pv * 2f
        val w = tw + ph * 2f
        chipRect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        paintChipBg.style = Paint.Style.FILL
        paintChipBg.color = bg
        canvas.drawRoundRect(chipRect, h / 2f, h / 2f, paintChipBg)
        if (stroke != 0) {
            paintChipStroke.color = stroke
            canvas.drawRoundRect(chipRect, h / 2f, h / 2f, paintChipStroke)
        }
        val fm = tPaint.fontMetrics
        canvas.drawText(text, cx - tw / 2f, cy - (fm.ascent + fm.descent) / 2f, tPaint)
    }
}

