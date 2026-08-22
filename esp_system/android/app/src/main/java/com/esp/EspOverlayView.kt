package com.esp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * 全屏 ESP 绘制层 — 直接在游戏画面上绘制 (悬浮窗全屏覆盖, 不拦截触摸)。
 *
 * 数据来源: tv_reader TVEF v3 (含 actor y 坐标)。
 * 投影模型: MOBA 固定俯视角相机 (跟随自身英雄), 参数可调,
 *           世界 (x,y,z) → 相机空间 → 透视除法 → 屏幕像素。
 */
class EspOverlayView(context: Context) : View(context) {

    /** 绘制项开关 */
    class Config {
        var espOn = true          // 透视总开关
        var showBox = true        // 方框
        var showHp = true         // 血条
        var showSkills = true     // 召唤师技能 CD
        var showUlt = true        // 大招就绪高亮
        var showLevel = true      // 等级
        var showDist = true       // 距离
        var showFacing = false    // 朝向线
        var showLines = false     // 自身→敌人连线
        var showHidden = true     // 未视野单位 (虚线框)
        var showAlly = false      // 友方 (默认只画敌方, 减少干扰)
        var showMinions = false   // 小兵/野怪 (默认关, 太多)
    }

    // ---- 相机模型参数 (世界单位, 地图 ±10000) ----
    private val camH = 9500f        // 相机高度
    private val camBack = 6200f     // 相机在英雄后方的距离 (+z 方向)
    private val lookAhead = 1800f   // 注视点在英雄前方 (-z 方向) — 英雄位于屏幕中下方
    private val vFovDeg = 46f       // 垂直视场角
    private val heroWorldH = 1250f  // 英雄碰撞盒世界高度
    private val nearZ = 500f        // 近平面

    val config = Config()

    @Volatile private var frame: EspFrame? = null

    private val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f
    }
    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        color = Color.WHITE
        isFakeBoldText = true
    }
    private val paintDash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }
    private val pathTmp = Path()

    fun updateFrame(f: EspFrame?) {
        frame = f
        postInvalidateOnAnimation()
    }

    // ---- 投影 ----
    // 相机基向量缓存 (每帧计算一次)
    private var camX = 0f; private var camY = 0f; private var camZ = 0f
    private var fX = 0f; private var fY = 0f; private var fZ = 0f
    private var rX = 0f; private var rY = 0f; private var rZ = 0f
    private var uX = 0f; private var uY = 0f; private var uZ = 0f
    private var tanHalfV = 0f
    private var tanHalfH = 0f

    private fun setupCamera(selfX: Float, selfY: Float, selfZ: Float, w: Int, h: Int) {
        camX = selfX; camY = selfY + camH; camZ = selfZ + camBack
        // forward = normalize(target - cam)
        var fx = selfX - camX
        var fy = selfY - camY
        var fz = (selfZ - lookAhead) - camZ
        val fl = Math.sqrt((fx * fx + fy * fy + fz * fz).toDouble()).toFloat()
        fx /= fl; fy /= fl; fz /= fl
        // right = normalize(cross(forward, worldUp))
        val rx = fz; val ry = 0f; val rz = -fx
        val rl = Math.sqrt((rx * rx + rz * rz).toDouble()).toFloat()
        val rXn = rx / rl; val rZn = rz / rl
        // up = cross(right, forward)
        val ux = ry * fz - rZn * fy
        val uy = rZn * fx - rx * fz
        val uz = rx * fy - ry * fx
        fX = fx; fY = fy; fZ = fz
        rX = rXn; rY = 0f; rZ = rZn
        uX = ux; uY = uy; uZ = uz
        val aspect = w.toFloat() / h.toFloat()
        tanHalfV = Math.tan(Math.toRadians(vFovDeg / 2.0)).toFloat()
        tanHalfH = tanHalfV * aspect
    }

    /** 世界坐标 → 屏幕像素; 相机后方返回 null */
    private fun project(x: Float, y: Float, z: Float, w: Int, h: Int): FloatArray? {
        val dx = x - camX; val dy = y - camY; val dz = z - camZ
        val zc = dx * fX + dy * fY + dz * fZ
        if (zc <= nearZ) return null
        val xc = dx * rX + dy * rY + dz * rZ
        val yc = dx * uX + dy * uY + dz * uZ
        val sx = w * 0.5f + (xc / zc) * (w * 0.5f) / tanHalfH
        val sy = h * 0.5f - (yc / zc) * (h * 0.5f) / tanHalfV
        return floatArrayOf(sx, sy)
    }

    override fun onDraw(canvas: Canvas) {
        val f = frame
        if (f == null || !config.espOn) return
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val d = resources.displayMetrics.density
        if (paintText.textSize != 11f * d) {
            paintText.textSize = 11f * d
            paintStroke.strokeWidth = 1.6f * d
            paintDash.strokeWidth = 1.2f * d
        }

        setupCamera(f.selfX, f.selfY, f.selfZ, width, height)

        // 自身屏幕锚点 (连线用): 英雄头顶上方一点
        var selfAnchor: FloatArray? = null
        if (config.showLines) {
            selfAnchor = project(f.selfX, f.selfY + heroWorldH * 1.4f, f.selfZ, width, height)
        }

        val c = config
        for (a in f.actors) {
            // 过滤: 英雄必画; 小兵/野怪按开关; 友方按开关
            if (!a.isHero && !c.showMinions) continue
            if (a.ally && a.isHero && !c.showAlly && !c.showMinions) continue
            if (a.ally && !a.isHero && !c.showMinions) continue
            if (!a.visible && !c.showHidden && a.isHero) continue

            // 盒子投影: 脚点 + 头点
            val feet = project(a.x, a.y, a.z, width, height) ?: continue
            val head = project(a.x, a.y + heroWorldH, a.z, width, height) ?: continue

            val boxH = feet[1] - head[1]
            if (boxH < 6f) continue  // 太远/太小的单位跳过
            val boxW = boxH * 0.62f
            val left = feet[0] - boxW * 0.5f
            val right = feet[0] + boxW * 0.5f
            val top = head[1]
            val bottom = feet[1]

            val isEnemy = !a.ally
            val mainColor = when {
                isEnemy -> HudUi.ENEMY
                else -> HudUi.ALLY
            }

            // ---- 连线 (自身 → 敌人) ----
            if (c.showLines && isEnemy && selfAnchor != null) {
                paintDash.color = Color.argb(140, 255, 59, 71)
                pathTmp.reset()
                pathTmp.moveTo(selfAnchor[0], selfAnchor[1])
                pathTmp.lineTo(feet[0], top + boxH * 0.5f)
                canvas.drawPath(pathTmp, paintDash)
            }

            // ---- 方框 ----
            if (c.showBox) {
                if (a.visible) {
                    paintStroke.color = mainColor
                    canvas.drawRect(left, top, right, bottom, paintStroke)
                    // 大招就绪: 金色外发光框
                    if (c.showUlt && isEnemy && a.ultimateReady && a.isHero) {
                        paintStroke.color = HudUi.WARN
                        paintStroke.strokeWidth = paintStroke.strokeWidth + d
                        canvas.drawRect(left - d * 2, top - d * 2, right + d * 2, bottom + d * 2, paintStroke)
                        paintStroke.strokeWidth = 1.6f * d
                    }
                } else {
                    // 未视野: 虚线暗黄框
                    paintDash.color = Color.argb(180, 255, 179, 0)
                    canvas.drawRect(left, top, right, bottom, paintDash)
                }
            }

            var textY = top - d * 3

            // ---- 等级 (框上方左) ----
            if (c.showLevel && a.isHero && a.level > 0) {
                paintText.color = Color.argb(220, 190, 210, 235)
                paintText.textSize = 9f * d
                canvas.drawText("Lv${a.level}", left, textY, paintText)
                paintText.textSize = 11f * d
            }

            // ---- 血条 (框上方) ----
            if (c.showHp && a.maxHp > 0) {
                val barW = boxW
                val barH = 3.5f * d
                val barY = textY - barH - d * 1.5f
                paintFill.color = Color.argb(150, 0, 0, 0)
                canvas.drawRect(left, barY, left + barW, barY + barH, paintFill)
                val ratio = a.hpRatio.coerceIn(0f, 1f)
                paintFill.color = if (a.ally) HudUi.ALLY else HudUi.ENEMY
                if (ratio > 0f) {
                    canvas.drawRect(left, barY, left + barW * ratio, barY + barH, paintFill)
                }
            }

            // ---- 召唤师技能 CD (框下方圆点) ----
            if (c.showSkills && a.isHero && a.summonerSpells.isNotEmpty()) {
                val n = a.summonerSpells.size.coerceAtMost(4)
                val dotR = 2.6f * d
                val gap = dotR * 2 + d * 2
                var cx = feet[0] - (n - 1) * gap * 0.5f
                val cy = bottom + d * 5
                for (s in a.summonerSpells) {
                    if (s.ready) {
                        paintFill.color = Color.argb(230, 120, 230, 170)
                        canvas.drawCircle(cx, cy, dotR, paintFill)
                    } else {
                        paintFill.color = Color.argb(120, 90, 100, 115)
                        canvas.drawCircle(cx, cy, dotR, paintFill)
                        paintText.color = Color.argb(220, 200, 210, 225)
                        paintText.textSize = 7.5f * d
                        val txt = if (s.cooldownRemaining >= 10f) "${s.cooldownRemaining.toInt()}" else String.format("%.0f", s.cooldownRemaining)
                        val tw = paintText.measureText(txt)
                        canvas.drawText(txt, cx - tw * 0.5f, cy - dotR - d, paintText)
                        paintText.textSize = 11f * d
                    }
                    cx += gap
                }
            }

            // ---- 距离 (框下方) ----
            if (c.showDist && a.isHero) {
                val dx = a.x - f.selfX
                val dz = a.z - f.selfZ
                val dist = Math.sqrt((dx * dx + dz * dz).toDouble()).toFloat()
                val txt = String.format("%.0fm", dist / 100f)
                paintText.color = Color.argb(190, 160, 175, 195)
                paintText.textSize = 8.5f * d
                val tw = paintText.measureText(txt)
                canvas.drawText(txt, feet[0] - tw * 0.5f, bottom + d * 2.5f, paintText)
                paintText.textSize = 11f * d
            }

            // ---- 朝向线 ----
            if (c.showFacing && a.visible) {
                val len = boxH * 0.9f
                val rad = Math.toRadians(a.facingAngle.toDouble())
                // 世界朝向 (sin,cos) → 屏幕方向: 屏幕 x 对应世界 x, 屏幕 y 对应世界 -z (近似)
                val dirX = Math.sin(rad).toFloat()
                val dirZ = Math.cos(rad).toFloat()
                val tip = project(a.x + dirX * 900f, a.y + heroWorldH * 0.5f, a.z - dirZ * 900f, width, height)
                if (tip != null) {
                    paintStroke.color = mainColor
                    paintStroke.strokeWidth = 1.4f * d
                    canvas.drawLine(feet[0], top + boxH * 0.5f, tip[0], tip[1], paintStroke)
                    paintStroke.strokeWidth = 1.6f * d
                }
            }
        }
    }
}
