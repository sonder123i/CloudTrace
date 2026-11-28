package com.zcshou.gogogo.run.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.widget.SeekBar
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.core.graphics.ColorUtils
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.ui.theme.FiberStyle

/**
 * V5.0 游标卡尺刻度滑块（Vernier Scale SeekBar）。
 *
 * 原生 SeekBar 的触摸、键盘与无障碍逻辑全部保留，只替换视觉：
 * - 细密垂直刻度线，经过的刻度随进度点亮；
 * - 光纤渐变已走轨道（靛蓝 → 冰青 → 紫罗兰）；
 * - 圆钮按下/滑动时每跨过 5% 触发一次 CLOCK_TICK 机械微震。
 */
class VernierSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.seekBarStyle
) : AppCompatSeekBar(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val dp = { v: Float -> v * density }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f * density
    }
    /** 轨道顶部 1dp 内凹阴影：让滑轨像刻进玻璃里。 */
    private val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x14000000
        maskFilter = android.graphics.BlurMaskFilter(0.8f * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1f * density
    }
    private val thumbGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = 0xCCFFFFFF.toInt()
    }
    private val thumbCore = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbRect = RectF()

    /** 复用矩阵：渐变按局部坐标构建一次，每帧只做平移。 */
    private val localMatrix = Matrix()
    private var trackShader: Shader? = null
    private var thumbShader: Shader? = null
    private var fiberColors: IntArray? = null
    private var primaryFiberColor = 0

    private val baseTickColor = context.getColor(R.color.pf_text_tertiary)
    private val activeTickColor = context.getColor(R.color.dr_fiber_indigo)
    private val trackBaseColor = context.getColor(R.color.pf_stroke)

    private var delegate: OnSeekBarChangeListener? = null
    private var lastTickBucket = Int.MIN_VALUE
    private var showThumb = true

    init {
        progressDrawable = ColorDrawable(Color.TRANSPARENT)
        thumb = ColorDrawable(Color.TRANSPARENT)
        splitTrack = false
        setPadding(dp(12f).toInt(), paddingTop, dp(12f).toInt(), paddingBottom)
        refreshPalette()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshPalette()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildShaders()
    }

    /** 主题色只在挂载与尺寸变化时读取：`FiberStyle` 内部要走一次 Prefs 判昼夜。 */
    private fun refreshPalette() {
        fiberColors = FiberStyle.colors(context)
        primaryFiberColor = FiberStyle.primaryColor(context)
        buildShaders()
    }

    private fun buildShaders() {
        val colors = fiberColors ?: return
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val left = paddingLeft.toFloat()
        val right = (w - paddingRight).toFloat()
        val lineWidth = (right - left).coerceAtLeast(1f)
        val r = dp(10f)
        // 局部坐标：(0,0)→(lineWidth,0) / (-r,-r)→(r,r)，绘制时按实际位置平移
        trackShader = LinearGradient(0f, 0f, lineWidth, 0f, colors, null, Shader.TileMode.CLAMP)
        thumbShader = LinearGradient(-r, -r, r, r, colors, null, Shader.TileMode.CLAMP)
    }

    override fun setOnSeekBarChangeListener(listener: OnSeekBarChangeListener?) {
        delegate = listener
        super.setOnSeekBarChangeListener(if (listener == null) null else forwarder)
    }

    private val forwarder = object : OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) tick(progress)
            invalidate()
            delegate?.onProgressChanged(seekBar, progress, fromUser)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {
            showThumb = true
            delegate?.onStartTrackingTouch(seekBar)
        }

        override fun onStopTrackingTouch(seekBar: SeekBar?) {
            delegate?.onStopTrackingTouch(seekBar)
        }
    }

    private fun tick(progress: Int) {
        val bucket = progress / 5
        if (bucket == lastTickBucket) return
        lastTickBucket = bucket
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        lastTickBucket = Int.MIN_VALUE
    }

    override fun onDraw(canvas: Canvas) {
        val maxValue = max.coerceAtLeast(1)
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val cy = height / 2f
        val progressRatio = progress.toFloat() / maxValue

        // 1) 刻度尺：41 根细密竖线，跨过的刻度点亮
        val tickCount = 40
        for (i in 0..tickCount) {
            val ratio = i / tickCount.toFloat()
            val x = left + (right - left) * ratio
            val major = i % 5 == 0
            val half = if (major) dp(6f) else dp(3.5f)
            val passed = ratio <= progressRatio + 0.001f
            tickPaint.color = if (passed) {
                ColorUtils.blendARGB(activeTickColor, Color.WHITE, ratio * 0.35f)
            } else {
                baseTickColor
            }
            tickPaint.alpha = if (passed) 190 else 70
            canvas.drawLine(x, cy - half, x, cy + half, tickPaint)
        }

        // 2) 轨道：暗底 + 已走光纤渐变
        trackPaint.color = trackBaseColor
        trackPaint.alpha = 120
        canvas.drawLine(left, cy, right, cy, trackPaint)
        // 顶部 1dp 内凹阴影，形成雕刻进玻璃的凹槽
        canvas.drawLine(left, cy - dp(2.2f), right, cy - dp(2.2f), groovePaint)

        val thumbX = left + (right - left) * progressRatio
        val track = trackShader
        if (thumbX > left && track != null) {
            trackPaint.alpha = 255
            localMatrix.setTranslate(left, cy)
            track.setLocalMatrix(localMatrix)
            trackPaint.shader = track
            canvas.drawLine(left, cy, thumbX, cy, trackPaint)
            trackPaint.shader = null
        }

        // 3) 圆钮：22% 光晕 + 光纤渐变核心 + 冷白细边
        if (showThumb) {
            val r = dp(10f)
            thumbGlow.color = primaryFiberColor
            thumbGlow.alpha = 46
            canvas.drawCircle(thumbX, cy, r * 1.9f, thumbGlow)

            thumbRect.set(thumbX - r, cy - r, thumbX + r, cy + r)
            val thumbGradient = thumbShader
            if (thumbGradient != null) {
                localMatrix.setTranslate(thumbX, cy)
                thumbGradient.setLocalMatrix(localMatrix)
                thumbPaint.shader = thumbGradient
            }
            canvas.drawCircle(thumbX, cy, r, thumbPaint)
            thumbPaint.shader = null
            canvas.drawCircle(thumbX, cy, r, thumbRim)

            thumbCore.color = Color.WHITE
            thumbCore.alpha = 220
            canvas.drawCircle(thumbX, cy, dp(1.6f), thumbCore)
        }
    }

    /** 供外部在程序化 setProgress 时避免触发机械震动（编辑器批量同步用）。 */
    fun setProgressSilently(value: Int) {
        val previous = lastTickBucket
        setProgress(value)
        lastTickBucket = previous
    }
}
