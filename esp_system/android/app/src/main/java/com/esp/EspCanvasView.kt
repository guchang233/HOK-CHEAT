package com.esp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class EspCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val WORLD_MIN_X = -10000f
        const val WORLD_MAX_X = 10000f
        const val WORLD_MIN_Z = -10000f
        const val WORLD_MAX_Z = 10000f

        const val MAP_PADDING = 8f
        const val CIRCLE_RADIUS = 6f

        // Color.* 是函数/字段调用, 非编译期常量, 不能用 const
        val BOX_COLOR_ALLY = Color.argb(200, 0, 220, 100)
        val BOX_COLOR_ENEMY = Color.argb(200, 255, 60, 60)
        val BOX_COLOR_TOWER = Color.argb(180, 255, 200, 50)
        val BOX_COLOR_MONSTER = Color.argb(150, 180, 120, 80)
        val LINE_COLOR = Color.argb(120, 255, 100, 100)
        val ULT_READY_COLOR = Color.argb(220, 255, 200, 0)
        val ULT_CD_COLOR = Color.argb(220, 100, 100, 100)
        val TIMER_COLOR = Color.argb(200, 255, 180, 50)
        val TIMER_BG = Color.argb(120, 20, 20, 20)
        val TEXT_COLOR = Color.WHITE
        val HP_BG_COLOR = Color.argb(100, 60, 60, 60)

        val SKILL_NAMES = mapOf(
            105 to "闪现", 106 to "治疗", 107 to "斩杀", 108 to "惩戒",
            109 to "加速", 110 to "净化", 111 to "闪现", 112 to "眩晕"
        )
        val SKILL_COLORS = mapOf(
            105 to Color.argb(220, 100, 200, 255),
            106 to Color.argb(220, 80, 220, 80),
            107 to Color.argb(220, 220, 80, 60),
            108 to Color.argb(220, 160, 100, 60),
            109 to Color.argb(220, 100, 255, 200),
            110 to Color.argb(220, 200, 220, 100),
            111 to Color.argb(220, 100, 200, 255),
            112 to Color.argb(220, 255, 150, 50)
        )
    }

    private var currentFrame: EspFrame? = null
    private var mapSize = 300
    private var showMinimap = true
    private var showBoxes = true
    private var showDistance = true
    private var showHPRatio = true
    private var showSkills = true
    private var showUltimate = true
    private var showLines = true
    private var showTimers = true
    private var showFacing = true
    private var showNameLevel = true
    private var worldOffsetX = 0f
    private var worldOffsetZ = 0f

    private val paintDot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintBox = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_COLOR
        textSize = 28f
        isFakeBoldText = true
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }
    private val paintSmallText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_COLOR
        textSize = 20f
        isFakeBoldText = true
        setShadowLayer(1f, 1f, 1f, Color.BLACK)
    }
    private val paintHpBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HP_BG_COLOR }
    private val paintHp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintCenterDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 150, 150, 150)
        style = Paint.Style.FILL
    }
    private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val paintBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LINE_COLOR
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        strokeCap = Paint.Cap.ROUND
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }
    private val paintSkillBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 30, 30, 30)
        style = Paint.Style.FILL
    }
    private val paintSkillCd = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 40, 40, 40)
        style = Paint.Style.FILL
    }
    private val paintUltBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 30, 30, 30)
        style = Paint.Style.FILL
    }
    private val paintTimersBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TIMER_BG
        style = Paint.Style.FILL
    }
    private val paintTimerBar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintFacing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
    }

    fun updateFrame(frame: EspFrame?) {
        currentFrame = frame
        postInvalidate()
    }

    fun setMapSize(size: Int) { mapSize = size.coerceIn(140, 600); requestLayout() }
    fun setWorldOffsets(ox: Float, oz: Float) { worldOffsetX = ox; worldOffsetZ = oz; postInvalidate() }
    fun setShowMinimap(v: Boolean) { showMinimap = v; postInvalidate() }
    fun setShowBoxes(v: Boolean) { showBoxes = v; postInvalidate() }
    fun setShowDistance(v: Boolean) { showDistance = v; postInvalidate() }
    fun setShowHPRatio(v: Boolean) { showHPRatio = v; postInvalidate() }
    fun setShowSkills(v: Boolean) { showSkills = v; postInvalidate() }
    fun setShowUltimate(v: Boolean) { showUltimate = v; postInvalidate() }
    fun setShowLines(v: Boolean) { showLines = v; postInvalidate() }
    fun setShowTimers(v: Boolean) { showTimers = v; postInvalidate() }
    fun setShowFacing(v: Boolean) { showFacing = v; postInvalidate() }
    fun setShowNameLevel(v: Boolean) { showNameLevel = v; postInvalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = currentFrame ?: return
        val actors = frame.actors.filter { it.drawable && it.visible }
        if (actors.isEmpty() && frame.timers.isEmpty()) return

        val enemies = actors.filter { !it.ally }
        val allies = actors.filter { it.ally }

        if (showLines) drawLines(canvas, enemies)
        if (showMinimap) drawMinimap(canvas, actors)
        if (showBoxes) drawEnhancedBoxes(canvas, actors)
        if (showTimers) drawTimerPanel(canvas, frame.timers, frame.gameTime)
    }

    private fun worldToScreen(wx: Float, wz: Float): Pair<Float, Float> {
        val w = width.toFloat()
        val h = height.toFloat()
        val worldRange = 20000f
        val scale = minOf(w, h) / worldRange
        return Pair(w / 2f + (wx - worldOffsetX) * scale, h / 2f + (wz - worldOffsetZ) * scale)
    }

    private fun drawLines(canvas: Canvas, enemies: List<EspActor>) {
        val frame = currentFrame ?: return
        if (!showLines || enemies.isEmpty()) return

        val (selfX, selfZ) = frame.selfX to frame.selfZ

        paintLine.strokeWidth = 1.5f
        for (e in enemies) {
            if (!e.isHero) continue
            val (sx, sy) = worldToScreen(e.x, e.z)
            if (sx < 0 || sx > width || sy < 0 || sy > height) continue

            val color = when {
                e.hpRatio < 0.3f -> Color.argb(180, 255, 40, 40)
                e.hpRatio < 0.6f -> Color.argb(150, 255, 160, 50)
                else -> Color.argb(120, 255, 100, 100)
            }
            paintLine.color = color

            val centerX = width / 2f
            val centerY = height / 2f
            canvas.drawLine(centerX, centerY, sx, sy, paintLine)
        }
    }

    private fun drawEnhancedBoxes(canvas: Canvas, actors: List<EspActor>) {
        for (a in actors) {
            val (sx, sy) = worldToScreen(a.x, a.z)
            if (sx < -150f || sx > width + 150f || sy < -150f || sy > height + 150f) continue

            val baseColor = when {
                a.isTower -> BOX_COLOR_TOWER
                a.isMonster -> BOX_COLOR_MONSTER
                a.ally -> BOX_COLOR_ALLY
                else -> BOX_COLOR_ENEMY
            }

            val boxW = when {
                a.isTower -> 18f
                a.isHero -> 26f
                a.isMonster -> 20f
                else -> 14f
            }
            val boxH = boxW * 2.5f

            val rect = RectF(sx - boxW / 2f, sy - boxH / 2f, sx + boxW / 2f, sy + boxH / 2f)

            paintBox.style = Paint.Style.STROKE
            paintBox.strokeWidth = 2f
            paintBox.color = baseColor
            canvas.drawRect(rect, paintBox)

            if (showHPRatio && a.maxHp > 0) {
                val hpW = boxW
                val hpH = 3f
                val hpTop = rect.top - hpH - 2f

                paintHpBg.color = HP_BG_COLOR
                canvas.drawRect(sx - hpW / 2f, hpTop, sx + hpW / 2f, hpTop + hpH, paintHpBg)

                val hpColor = when {
                    a.hpRatio > 0.6f -> Color.argb(220, 0, 220, 0)
                    a.hpRatio > 0.3f -> Color.argb(220, 220, 220, 0)
                    else -> Color.argb(220, 220, 40, 40)
                }
                paintHp.color = hpColor
                canvas.drawRect(sx - hpW / 2f, hpTop, sx - hpW / 2f + hpW * a.hpRatio, hpTop + hpH, paintHp)
            }

            if (showDistance) {
                val dist = sqrt(
                    (a.x - worldOffsetX) * (a.x - worldOffsetX) +
                    (a.z - worldOffsetZ) * (a.z - worldOffsetZ)
                )
                val label = buildString {
                    append(dist.toInt()).append("m")
                    if (a.isHero && showNameLevel) {
                        append(" Lv").append(a.level)
                    }
                }
                paintSmallText.color = baseColor
                paintSmallText.textSize = 20f
                canvas.drawText(label, rect.left, rect.top - 12f, paintSmallText)
            }

            if (a.isHero && showUltimate) {
                drawUltimateBar(canvas, sx, rect.bottom + 4f, boxW, a)
            }

            if (a.isHero && showSkills && a.summonerSpells.isNotEmpty()) {
                drawSkillIcons(canvas, sx, rect.bottom + (if (showUltimate) 18f else 0f) + 6f, boxW, a)
            }

            if (a.isHero && showFacing && a.facingAngle != 0f) {
                drawFacingIndicator(canvas, sx, sy, boxH, a.facingAngle, baseColor)
            }
        }
    }

    private fun drawUltimateBar(canvas: Canvas, cx: Float, topY: Float, width: Float, actor: EspActor) {
        val barH = 4f
        val left = cx - width / 2f
        val rect = RectF(left, topY, left + width, topY + barH)

        paintUltBg.color = Color.argb(180, 20, 20, 20)
        canvas.drawRoundRect(rect, 2f, 2f, paintUltBg)

        if (actor.ultimateReady || actor.ultimateCooldown <= 0f) {
            val readyColor = when {
                !actor.ally -> Color.argb(230, 255, 200, 0)
                else -> Color.argb(200, 100, 220, 100)
            }
            paintHp.color = readyColor
            canvas.drawRoundRect(rect, 2f, 2f, paintHp)

            paintSmallText.textSize = 14f
            paintSmallText.color = Color.argb(220, 255, 220, 50)
            canvas.drawText("R", cx - 3f, topY + barH + 12f, paintSmallText)
        } else {
            val ratio = actor.ultimateRatio.coerceIn(0f, 1f)
            val cdColor = when {
                ratio > 0.5f -> Color.argb(220, 220, 180, 0)
                else -> Color.argb(200, 180, 60, 60)
            }
            paintHp.color = cdColor
            val inner = RectF(left, topY, left + width * ratio, topY + barH)
            canvas.drawRoundRect(inner, 2f, 2f, paintHp)

            paintSmallText.textSize = 14f
            paintSmallText.color = Color.argb(200, 255, 200, 100)
            val cdText = "${actor.ultimateCooldown.toInt()}s"
            paintSmallText.textAlign = Paint.Align.CENTER
            canvas.drawText(cdText, cx, topY + barH + 12f, paintSmallText)
            paintSmallText.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawSkillIcons(canvas: Canvas, cx: Float, topY: Float, width: Float, actor: EspActor) {
        val size = 12f
        val gap = 2f
        val skills = actor.summonerSpells.take(4)
        val totalW = skills.size * size + (skills.size - 1) * gap
        var x = cx - totalW / 2f

        for (sk in skills) {
            val rect = RectF(x, topY, x + size, topY + size)
            paintSkillBg.color = Color.argb(180, 30, 30, 30)
            canvas.drawRoundRect(rect, 2f, 2f, paintSkillBg)

            val skillColor = SKILL_COLORS[sk.spellId] ?: Color.argb(180, 150, 150, 150)

            if (sk.ready || sk.cooldownRemaining <= 0f) {
                paintHp.color = skillColor
                canvas.drawRoundRect(rect, 2f, 2f, paintHp)
                paintSmallText.textSize = 10f
                paintSmallText.color = Color.BLACK
                paintSmallText.textAlign = Paint.Align.CENTER
                val name = SKILL_NAMES[sk.spellId] ?: "?"
                canvas.drawText(name.take(1), x + size / 2f, topY + size * 0.7f, paintSmallText)
                paintSmallText.textAlign = Paint.Align.LEFT
            } else {
                val ratio = (1f - sk.cooldownRemaining / sk.cooldownTotal.coerceAtLeast(0.01f)).coerceIn(0f, 1f)
                paintSkillCd.color = Color.argb(180, 40, 40, 40)
                canvas.drawRoundRect(rect, 2f, 2f, paintSkillCd)

                val cdH = size * ratio
                val cdRect = RectF(x, topY + size - cdH, x + size, topY + size)
                paintHp.color = skillColor
                canvas.drawRoundRect(cdRect, 2f, 2f, paintHp)

                paintSmallText.textSize = 9f
                paintSmallText.color = Color.WHITE
                paintSmallText.textAlign = Paint.Align.CENTER
                canvas.drawText("${sk.cooldownRemaining.toInt()}", x + size / 2f, topY + size * 0.7f, paintSmallText)
                paintSmallText.textAlign = Paint.Align.LEFT
            }

            x += size + gap
        }
    }

    private fun drawFacingIndicator(canvas: Canvas, cx: Float, cy: Float, boxH: Float, angle: Float, color: Int) {
        val len = boxH * 0.15f
        val rad = Math.toRadians(angle.toDouble())
        val endX = cx + (len * sin(rad)).toFloat()
        val endY = cy + (len * cos(rad)).toFloat()

        paintFacing.color = color
        canvas.drawLine(cx, cy, endX, endY, paintFacing)
    }

    private fun drawMinimap(canvas: Canvas, actors: List<EspActor>) {
        val size = mapSize.toFloat()
        val pad = MAP_PADDING
        val left = width - size - pad
        val top = pad

        val worldRangeX = WORLD_MAX_X - WORLD_MIN_X
        val worldRangeZ = WORLD_MAX_Z - WORLD_MIN_Z
        val scaleX = (size - 2 * pad) / worldRangeX
        val scaleZ = (size - 2 * pad) / worldRangeZ
        val cx = left + size / 2f
        val cy = top + size / 2f

        val rect = RectF(left, top, left + size, top + size)
        paintDot.style = Paint.Style.FILL
        paintDot.color = Color.argb(30, 20, 20, 20)
        canvas.drawRoundRect(rect, 8f, 8f, paintDot)
        canvas.drawRoundRect(rect, 8f, 8f, paintBorder)

        canvas.drawLine(cx, top + pad, cx, top + size - pad, paintGrid)
        canvas.drawLine(left + pad, cy, left + size - pad, cy, paintGrid)

        val frame = currentFrame
        if (frame != null) {
            val selfX = frame.selfX
            val selfZ = frame.selfZ
            val selfSX = cx + (selfX - worldOffsetX) * scaleX
            val selfSZ = cy + (selfZ - worldOffsetZ) * scaleZ
            paintCenterDot.color = Color.argb(180, 255, 255, 255)
            canvas.drawCircle(selfSX, selfSZ, 4f, paintCenterDot)

            val dirLen = 6f
            val rad = Math.toRadians(0.0)
            canvas.drawLine(selfSX, selfSZ,
                selfSX + (dirLen * sin(rad)).toFloat(),
                selfSZ + (dirLen * cos(rad)).toFloat(),
                paintCenterDot)
        }

        for (a in actors) {
            val sx = cx + (a.x - worldOffsetX) * scaleX
            val sz = cy + (a.z - worldOffsetZ) * scaleZ

            val dotColor = when {
                a.isTower -> BOX_COLOR_TOWER
                a.isMonster -> BOX_COLOR_MONSTER
                a.ally -> BOX_COLOR_ALLY
                else -> BOX_COLOR_ENEMY
            }
            paintDot.color = dotColor
            val r = when {
                a.isTower -> 4f
                a.isHero -> CIRCLE_RADIUS
                a.isMonster -> 5f
                else -> 3f
            }
            canvas.drawCircle(sx, sz, r, paintDot)

            if (!a.ally && a.isHero) {
                val enemyColor = when {
                    a.hpRatio < 0.3f -> Color.argb(220, 255, 60, 60)
                    a.hpRatio < 0.6f -> Color.argb(220, 255, 180, 50)
                    else -> Color.argb(180, 255, 100, 100)
                }
                paintDot.color = enemyColor
                canvas.drawCircle(sx, sz, r + 2f, paintDot)
            }

            if (showDistance) {
                val dist = sqrt(
                    (a.x - worldOffsetX) * (a.x - worldOffsetX) +
                    (a.z - worldOffsetZ) * (a.z - worldOffsetZ)
                )
                paintText.color = dotColor
                paintText.textSize = 18f
                canvas.drawText("${dist.toInt()}", sx + r + 3f, sz + 5f, paintText)
            }
        }

        val enemies = actors.count { !it.ally && it.isHero }
        val allies = actors.count { it.ally && it.isHero }
        val monsters = actors.count { it.isMonster }

        paintText.color = Color.argb(200, 200, 200, 200)
        paintText.textSize = 22f
        canvas.drawText("ESP", left + pad, top + pad + 20f, paintText)

        paintText.textSize = 16f
        paintText.color = Color.argb(180, 255, 100, 100)
        canvas.drawText("敌:$enemies", left + pad + 50f, top + pad + 20f, paintText)
        paintText.color = Color.argb(180, 100, 255, 100)
        canvas.drawText("我:$allies", left + pad + 95f, top + pad + 20f, paintText)
        paintText.color = Color.argb(180, 200, 150, 100)
        canvas.drawText("怪:$monsters", left + pad + 135f, top + pad + 20f, paintText)
    }

    private fun drawTimerPanel(canvas: Canvas, timers: List<EspGlobalTimer>, gameTime: Float) {
        if (timers.isEmpty()) return

        val pad = 12f
        val right = width - mapSize - mapSize / 2f - 20f
        if (right < 100f) return
        var top = mapSize + 2 * pad

        val panelW = 180f
        val panelH = timers.size * 32f + 28f
        val panelRect = RectF(right - panelW, top, right, top + panelH)

        paintTimersBg.color = Color.argb(160, 15, 15, 20)
        canvas.drawRoundRect(panelRect, 8f, 8f, paintTimersBg)
        canvas.drawRoundRect(panelRect, 8f, 8f, paintBorder)

        paintText.color = Color.argb(200, 255, 200, 50)
        paintText.textSize = 18f
        canvas.drawText("⚡ 野区 / Boss 计时", panelRect.left + 8f, panelRect.top + 20f, paintText)

        paintSmallText.textSize = 18f
        var y = panelRect.top + 40f

        for (t in timers) {
            val barLeft = panelRect.left + 8f
            val barRight = panelRect.right - 8f
            val barW = barRight - barLeft
            val barH = 6f

            paintSmallText.color = Color.argb(220, 220, 220, 220)
            val timeStr = if (t.active) {
                val s = t.respawnSeconds.toInt()
                val m = s / 60
                val sec = s % 60
                "${m}:${sec.toString().padStart(2, '0')}"
            } else {
                "已就绪"
            }
            paintSmallText.textAlign = Paint.Align.LEFT
            canvas.drawText(t.label, barLeft, y + 8f, paintSmallText)
            paintSmallText.textAlign = Paint.Align.RIGHT
            paintSmallText.color = if (t.active) Color.argb(220, 255, 180, 100)
                else Color.argb(220, 100, 255, 100)
            canvas.drawText(timeStr, barRight, y + 8f, paintSmallText)
            paintSmallText.textAlign = Paint.Align.LEFT

            val barTop = y + 14f
            val barRect = RectF(barLeft, barTop, barRight, barTop + barH)
            paintHpBg.color = Color.argb(150, 30, 30, 30)
            canvas.drawRoundRect(barRect, 3f, 3f, paintHpBg)

            if (t.active) {
                val ratio = t.ratio.coerceIn(0f, 1f)
                val fillRect = RectF(barLeft, barTop, barLeft + barW * ratio, barTop + barH)
                val barColor = when {
                    t.id == 1 -> Color.argb(220, 255, 180, 0)
                    t.id == 2 -> Color.argb(220, 180, 80, 255)
                    t.id <= 4 -> Color.argb(200, 100, 180, 255)
                    else -> Color.argb(180, 150, 200, 100)
                }
                paintTimerBar.color = barColor
                canvas.drawRoundRect(fillRect, 3f, 3f, paintTimerBar)
            } else {
                paintTimerBar.color = Color.argb(180, 60, 220, 100)
                canvas.drawRoundRect(barRect, 3f, 3f, paintTimerBar)
            }

            y += 32f
        }

        if (gameTime > 0f) {
            val gm = gameTime.toInt()
            val mins = gm / 60
            val secs = gm % 60
            paintSmallText.textAlign = Paint.Align.LEFT
            paintSmallText.textSize = 14f
            paintSmallText.color = Color.argb(150, 200, 200, 200)
            canvas.drawText(
                "⏱ 游戏时间 ${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}",
                panelRect.left + 8f, panelRect.bottom - 6f, paintSmallText
            )
        }
    }
}
