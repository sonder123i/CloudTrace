package com.zcshou.gogogo.run.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.zcshou.gogogo.R

/**
 * 首页周目标迷你进度环（30dp 级别）：柔灰底环 + 单色进度弧。
 *
 * `onDraw` 零分配：RectF 与 Paint 都是字段，只做 drawArc；
 * 进度色跟随 pf_volt 昼夜翻转（浅色曜石黑 / 深色纯白），与其它卡片无色化一致。
 */
class WeeklyGoalRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val rect = RectF()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.pf_stroke)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.pf_volt)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.pf_volt)
    }

    private var ratio = 0f

    /** @param value 达成率（可 >1，视觉上封顶 100%） */
    fun setProgress(value: Float) {
        val next = value.coerceIn(0f, 1f)
        contentDescription = if (value > 0f) {
            "周目标已完成 ${(value * 100).toInt()}%"
        } else {
            "周目标尚未开始"
        }
        if (next == ratio) return
        ratio = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = 3f * density
        val inset = stroke / 2f + 1f * density
        rect.set(inset, inset, width - inset, height - inset)
        if (rect.width() <= 0f || rect.height() <= 0f) return
        canvas.drawArc(rect, 0f, 360f, false, trackPaint)
        if (ratio <= 0f) return
        val sweep = 360f * ratio
        canvas.drawArc(rect, -90f, sweep, false, progressPaint)
        // 弧末端一个小亮点，弱化「进度条」感、更像仪表盘
        val radius = rect.width() / 2f
        val cx = rect.centerX()
        val cy = rect.centerY()
        val angle = Math.toRadians((-90f + sweep).toDouble())
        canvas.drawCircle(
            cx + (radius * Math.cos(angle)).toFloat(),
            cy + (radius * Math.sin(angle)).toFloat(),
            stroke * 0.45f,
            dotPaint
        )
    }
}
