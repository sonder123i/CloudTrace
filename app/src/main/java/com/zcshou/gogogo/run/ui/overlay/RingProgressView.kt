package com.zcshou.gogogo.run.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.zcshou.gogogo.R

/**
 * 环形进度（胶囊内嵌）：柔灰底环 + 谷歌多色光谱进度弧 + 四芒星呼吸核心。
 */
class RingProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var progress = 0f
    private var pulseAlpha = 255

    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        strokeCap = Paint.Cap.ROUND
        color = context.getColor(R.color.dr_overlay_recess)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        strokeCap = Paint.Cap.ROUND
        color = context.getColor(R.color.dr_overlay_ice)
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val sparklePath = Path()
    private val beamBlue = context.getColor(R.color.dr_beam_blue)
    private val beamIndigo = context.getColor(R.color.dr_beam_indigo)
    private val beamTeal = context.getColor(R.color.dr_beam_teal)
    private var ringShader: LinearGradient? = null
    private var haloShader: RadialGradient? = null
    private var sparkleRadius = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            val shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(beamBlue, beamIndigo, beamTeal),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
            ringShader = shader
            progressPaint.shader = shader
            dotPaint.shader = shader

            val cx = w / 2f
            val cy = h / 2f
            val r = minOf(w, h) * 0.22f
            sparkleRadius = r
            buildSparklePath(cx, cy, r)

            val hRadius = r * 1.85f
            val hShader = RadialGradient(
                cx, cy, hRadius,
                intArrayOf(
                    ColorUtils.setAlphaComponent(beamBlue, 110),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            haloShader = hShader
            haloPaint.shader = hShader
        }
    }

    /** 在中心构建平滑凹曲四芒星路径（仅在尺寸变化时调用）。 */
    private fun buildSparklePath(cx: Float, cy: Float, r: Float) {
        sparklePath.reset()
        val k = r * 0.28f
        sparklePath.moveTo(cx, cy - r)
        sparklePath.quadTo(cx + k, cy - k, cx + r, cy)
        sparklePath.quadTo(cx + k, cy + k, cx, cy + r)
        sparklePath.quadTo(cx - k, cy + k, cx - r, cy)
        sparklePath.quadTo(cx - k, cy - k, cx, cy - r)
        sparklePath.close()
    }

    fun setProgress(value: Float) {
        val next = value.coerceIn(0f, 1f)
        if (next == progress) return
        progress = next
        invalidate()
    }

    /** 呼吸效果由外部驱动（0..255） */
    fun setPulseAlpha(alpha: Int) {
        val next = alpha.coerceIn(40, 255)
        if (next == pulseAlpha) return
        pulseAlpha = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = 2.4f * density
        val inset = stroke / 2 + 1f * density
        rect.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(rect, 0f, 360f, false, trackPaint)
        if (progress > 0f) {
            canvas.drawArc(rect, -90f, 360f * progress, false, progressPaint)
        }
        val cx = width / 2f
        val cy = height / 2f
        if (sparkleRadius > 0f) {
            haloPaint.alpha = (pulseAlpha * 0.65f).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, sparkleRadius * 1.85f, haloPaint)
            dotPaint.alpha = pulseAlpha
            canvas.drawPath(sparklePath, dotPaint)
        }
    }
}