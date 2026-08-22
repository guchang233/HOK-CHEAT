package com.esp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.Button

/**
 * HUD 设计系统 — "战术玻璃" 风格
 * 暗色烟玻璃面板 · 单一青色强调 · 敌方红 · 琥珀警示
 */
object HudUi {
    const val ACCENT = 0xFF00E5FF.toInt()      // 青色强调
    const val ENEMY = 0xFFFF3B47.toInt()       // 敌方红
    const val ALLY = 0xFF3DDC84.toInt()        // 友方绿
    const val WARN = 0xFFFFB300.toInt()        // 琥珀警示
    const val BG_APP = 0xFF080B10.toInt()      // 应用底色

    val BG_PANEL = Color.argb(216, 10, 14, 20)
    val BG_CHIP = Color.argb(170, 20, 28, 38)
    val STROKE_DIM = Color.argb(70, 0, 229, 255)
    val STROKE_HOT = Color.argb(190, 0, 229, 255)
    val TEXT_MAIN = Color.argb(242, 226, 240, 255)
    val TEXT_DIM = Color.argb(150, 148, 170, 190)

    fun dp(ctx: Context, v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    /** 圆角玻璃面板背景 */
    fun panelBg(ctx: Context, radiusDp: Float = 14f, stroke: Int = STROKE_DIM): GradientDrawable =
        GradientDrawable().apply {
            setColor(BG_PANEL)
            cornerRadius = dp(ctx, radiusDp)
            setStroke(dp(ctx, 1f).toInt(), stroke)
        }

    /** 深色小芯片背景 */
    fun chipBg(ctx: Context, stroke: Int = Color.TRANSPARENT): GradientDrawable =
        GradientDrawable().apply {
            setColor(BG_CHIP)
            cornerRadius = dp(ctx, 8f)
            if (stroke != Color.TRANSPARENT) setStroke(dp(ctx, 1f).toInt(), stroke)
        }

    /** 小动作按钮 (描边胶囊) */
    fun actionButton(ctx: Context, label: String, stroke: Int = STROKE_HOT, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setTextColor(TEXT_MAIN)
            minWidth = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(ctx, 10f).toInt(), dp(ctx, 6f).toInt(),
                dp(ctx, 10f).toInt(), dp(ctx, 6f).toInt())
            background = GradientDrawable().apply {
                setColor(Color.argb(140, 16, 24, 34))
                cornerRadius = dp(ctx, 9f)
                setStroke(dp(ctx, 1f).toInt(), stroke)
            }
            setOnClickListener { onClick() }
        }

    /** 主按钮 (青色实底) */
    fun primaryButton(ctx: Context, label: String, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            setTextColor(Color.argb(255, 4, 10, 14))
            minWidth = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(ctx, 16f).toInt(), dp(ctx, 12f).toInt(),
                dp(ctx, 16f).toInt(), dp(ctx, 12f).toInt())
            background = GradientDrawable().apply {
                setColor(ACCENT)
                cornerRadius = dp(ctx, 12f)
            }
            setOnClickListener { onClick() }
        }

    /** 次按钮 (弱描边) */
    fun ghostButton(ctx: Context, label: String, onClick: () -> Unit): Button =
        actionButton(ctx, label, STROKE_DIM, onClick).apply {
            textSize = 14f
            setPadding(dp(ctx, 16f).toInt(), dp(ctx, 12f).toInt(),
                dp(ctx, 16f).toInt(), dp(ctx, 12f).toInt())
        }

    /** 危险按钮 (红描边) */
    fun dangerButton(ctx: Context, label: String, onClick: () -> Unit): Button =
        actionButton(ctx, label, Color.argb(190, 255, 59, 71), onClick)
}

/**
 * 胶囊开关 — 自绘 HUD 风格 toggle, 替代系统 Button 的 "地图:ON" 丑样式
 */
class PillToggle(
    ctx: Context,
    private val label: String,
    initial: Boolean,
    private val onChange: (Boolean) -> Unit
) : View(ctx) {

    var state = initial
        private set

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HudUi.TEXT_MAIN
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 11f, ctx.resources.displayMetrics)
        isFakeBoldText = true
    }
    private val trackRect = RectF()
    private val density = ctx.resources.displayMetrics.density
    private val trackW = 34f * density
    private val trackH = 16f * density
    private val knobR = 6.5f * density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wm = View.MeasureSpec.getMode(widthMeasureSpec)
        val ws = View.MeasureSpec.getSize(widthMeasureSpec)
        val hm = View.MeasureSpec.getMode(heightMeasureSpec)
        val hs = View.MeasureSpec.getSize(heightMeasureSpec)
        val textW = textPaint.measureText(label)
        val contentW = trackW + 10f * density + textW + 8f * density
        val w = when (wm) {
            View.MeasureSpec.EXACTLY -> ws
            View.MeasureSpec.AT_MOST -> minOf(contentW.toInt(), ws)
            else -> contentW.toInt()
        }
        val h = if (hm == View.MeasureSpec.EXACTLY) hs else (30f * density).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val top = cy - trackH / 2f
        trackRect.set(0f, top, trackW, top + trackH)
        val on = state
        // 轨道
        trackPaint.style = Paint.Style.FILL
        trackPaint.color = if (on) Color.argb(70, 0, 229, 255) else Color.argb(120, 40, 52, 66)
        canvas.drawRoundRect(trackRect, trackH / 2f, trackH / 2f, trackPaint)
        trackPaint.style = Paint.Style.STROKE
        trackPaint.strokeWidth = 1f * density
        trackPaint.color = if (on) HudUi.STROKE_HOT else Color.argb(80, 90, 110, 130)
        canvas.drawRoundRect(trackRect, trackH / 2f, trackH / 2f, trackPaint)
        // 滑块
        knobPaint.style = Paint.Style.FILL
        knobPaint.color = if (on) HudUi.ACCENT else Color.argb(220, 110, 128, 148)
        val kx = if (on) trackW - trackH / 2f else trackH / 2f
        canvas.drawCircle(kx, cy, knobR, knobPaint)
        // 标签
        textPaint.color = if (on) HudUi.TEXT_MAIN else HudUi.TEXT_DIM
        canvas.drawText(label, trackW + 10f * density,
            cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            performClick()
            return true
        }
        return event.actionMasked == MotionEvent.ACTION_DOWN
    }

    override fun performClick(): Boolean {
        super.performClick()
        state = !state
        invalidate()
        onChange(state)
        return true
    }

    fun set(v: Boolean) {
        if (state != v) {
            state = v
            invalidate()
        }
    }
}