package com.zcshou.gogogo.run.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.engine.PacePreviewPoint

/**
 * 配速策略预演微曲线：把 [PacePreviewPoint] 序列画成一条呼吸感的速度曲线。
 *
 * 性能红线：`onDraw` 内零分配 —— Paint / Path / Shader / 坐标数组全部在
 * [onSizeChanged] / [setProfile] 里预分配复用，绘制阶段只做 drawPath。
 * 曲线纵轴 = 速度（越快越高），横轴 = 里程。
 */
class PaceStrategySparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.6f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val linePath = Path()
    private val fillPath = Path()

    // 原始数据（里程 / 配速）与屏幕坐标分离：尺寸变化时可安全重算，不会被二次变换
    private var rawDistance: FloatArray = EMPTY
    private var rawPace: FloatArray = EMPTY
    private var xs: FloatArray = EMPTY
    private var ys: FloatArray = EMPTY

    private var pointCount = 0
    private var minPace = 0
    private var maxPace = 0

    private var strokeTop = 0f
    private var strokeBottom = 0f
    private var strokeLeft = 0f
    private var strokeRight = 0f

    private val dotRadius = dp(2.6f)

    init {
        val accent = ContextCompat.getColor(context, R.color.pf_volt)
        linePaint.color = accent
        dotPaint.color = accent
        gridPaint.color = Color.argb(
            34,
            Color.red(accent),
            Color.green(accent),
            Color.blue(accent)
        )
        fillPaint.color = Color.argb(30, Color.red(accent), Color.green(accent), Color.blue(accent))
        contentDescription = "配速策略预演曲线"
    }

    /**
     * 绑定预演结果。数据只在主线程状态变更时进入，绘制阶段不再读取 List。
     */
    fun setProfile(points: List<PacePreviewPoint>) {
        pointCount = points.size
        if (pointCount < 2) {
            pointCount = 0
            linePath.rewind()
            fillPath.rewind()
            invalidate()
            return
        }
        if (rawDistance.size != pointCount) {
            rawDistance = FloatArray(pointCount)
            rawPace = FloatArray(pointCount)
            xs = FloatArray(pointCount)
            ys = FloatArray(pointCount)
        }
        var fastest = Int.MAX_VALUE
        var slowest = 0
        points.forEachIndexed { index, point ->
            rawDistance[index] = point.distanceMeters.toFloat()
            rawPace[index] = point.paceSecPerKm.toFloat()
            if (point.paceSecPerKm < fastest) fastest = point.paceSecPerKm
            if (point.paceSecPerKm > slowest) slowest = point.paceSecPerKm
        }
        minPace = fastest
        maxPace = slowest
        rebuildPaths()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        strokeLeft = dp(1f)
        strokeRight = (w - dp(1f)).coerceAtLeast(strokeLeft + 1f)
        strokeTop = dp(3f)
        strokeBottom = (h - dp(3f)).coerceAtLeast(strokeTop + 1f)
        fillPaint.shader = LinearGradient(
            0f,
            strokeTop,
            0f,
            strokeBottom,
            Color.argb(
                70,
                Color.red(linePaint.color),
                Color.green(linePaint.color),
                Color.blue(linePaint.color)
            ),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        rebuildPaths()
    }

    /**
     * 预分配阶段完成全部几何计算：里程归一化 + 配速反转成屏幕坐标，
     * 再一次性写进复用的 Path。`onDraw` 只负责 draw。
     */
    private fun rebuildPaths() {
        linePath.rewind()
        fillPath.rewind()
        if (pointCount < 2 || strokeRight <= strokeLeft || strokeBottom <= strokeTop) return
        val spanX = (rawDistance[pointCount - 1] - rawDistance[0]).takeIf { it > 0f } ?: 1f
        val spanPace = (maxPace - minPace).coerceAtLeast(1).toFloat()
        val width = strokeRight - strokeLeft
        val height = strokeBottom - strokeTop
        for (i in 0 until pointCount) {
            xs[i] = strokeLeft + (rawDistance[i] - rawDistance[0]) / spanX * width
            // 配速越小越快：映射到更小的 y（更高）
            val normalized = (rawPace[i] - minPace) / spanPace
            ys[i] = strokeTop + height * (0.10f + 0.80f * normalized)
        }
        linePath.moveTo(xs[0], ys[0])
        for (i in 1 until pointCount) linePath.lineTo(xs[i], ys[i])

        fillPath.moveTo(xs[0], strokeBottom)
        for (i in 0 until pointCount) fillPath.lineTo(xs[i], ys[i])
        fillPath.lineTo(xs[pointCount - 1], strokeBottom)
        fillPath.close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pointCount < 2) return
        val midY = (strokeTop + strokeBottom) * 0.5f
        canvas.drawLine(strokeLeft, midY, strokeRight, midY, gridPaint)
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)
        canvas.drawCircle(xs[pointCount - 1], ys[pointCount - 1], dotRadius, dotPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        val EMPTY = FloatArray(0)
    }
}
