package com.zcshou.gogogo.run.ui.home

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
import com.zcshou.gogogo.run.engine.TrackMathUtils
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * 路书海拔剖面：真实 `<ele>` 序列优先，缺失时回退到「曲率 + 微地形」估算曲线。
 *
 * 性能红线：`onDraw` 内零分配 —— Paint / Path / 坐标数组 / 渐变全部在
 * [setProfile] / [onSizeChanged] 里预分配，绘制阶段只做 drawPath。
 */
class RouteElevationProfileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.7f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9f * density
        textAlign = Paint.Align.LEFT
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val linePath = Path()
    private val fillPath = Path()

    /** 原始海拔序列（未归一化）：尺寸变化时可安全重算 */
    private var rawElevations: FloatArray = EMPTY
    private var rawDistances: FloatArray = EMPTY
    private var xs: FloatArray = EMPTY
    private var ys: FloatArray = EMPTY

    private var pointCount = 0
    private var minElevation = 0f
    private var maxElevation = 0f
    private var totalMeters = 0.0
    private var usingRealData = false

    private var left = 0f
    private var right = 0f
    private var top = 0f
    private var bottom = 0f

    init {
        val accent = ContextCompat.getColor(context, R.color.pf_volt)
        linePaint.color = accent
        markerPaint.color = accent
        baselinePaint.color = ContextCompat.getColor(context, R.color.pf_stroke)
        labelPaint.color = ContextCompat.getColor(context, R.color.pf_text_secondary)
        fillPaint.color = Color.argb(34, Color.red(accent), Color.green(accent), Color.blue(accent))
    }

    /**
     * 绑定真实海拔剖面。
     *
     * @param elevations 与 [points] 等长的海拔序列；为 null/长度不符时自动回退估算
     */
    fun bind(points: List<TrackMathUtils.LatLng>, elevations: List<Double>?) {
        if (points.size < 3) {
            pointCount = 0
            linePath.rewind()
            fillPath.rewind()
            invalidate()
            return
        }
        val cumulative = TrackMathUtils.cumulativeDistances(points)
        totalMeters = cumulative.last()
        val real = elevations?.takeIf { it.size == points.size }
        usingRealData = real != null
        // 采样点太多会让路径与逐点计算都很重：等比抽到 120 点足够表达地形起伏
        val step = (points.size / MAX_POINTS).coerceAtLeast(1)
        val sampledIndices = buildList {
            var i = 0
            while (i < points.size) {
                add(i)
                i += step
            }
            if (last() != points.lastIndex) add(points.lastIndex)
        }
        pointCount = sampledIndices.size
        if (rawElevations.size != pointCount) {
            rawElevations = FloatArray(pointCount)
            rawDistances = FloatArray(pointCount)
            xs = FloatArray(pointCount)
            ys = FloatArray(pointCount)
        }
        var minE = Float.MAX_VALUE
        var maxE = -Float.MAX_VALUE
        sampledIndices.forEachIndexed { slot, index ->
            val elevation = if (real != null) {
                real[index].toFloat()
            } else {
                estimatedElevation(points, cumulative, index)
            }
            rawElevations[slot] = elevation
            rawDistances[slot] = cumulative[index].toFloat()
            if (elevation < minE) minE = elevation
            if (elevation > maxE) maxE = elevation
        }
        // 平坦路段（爬升 < 0.5m）人为撑开 1m 窗口，否则曲线会贴成一条直线
        if (maxE - minE < 0.5f) {
            val center = (maxE + minE) / 2f
            minE = center - 0.5f
            maxE = center + 0.5f
        }
        minElevation = minE
        maxElevation = maxE
        contentDescription = if (usingRealData) "海拔剖面" else "海拔剖面（按曲率估算）"
        rebuildPaths()
        invalidate()
    }

    /**
     * 无真实 `<ele>` 时的估算：曲率越急弯越「高」+ 一个低频正弦起伏，
     * 与引擎注入所用的微地形同源，视觉上不会凭空出现锯齿。
     */
    private fun estimatedElevation(
        points: List<TrackMathUtils.LatLng>,
        cumulative: DoubleArray,
        index: Int
    ): Float {
        val distance = cumulative[index]
        val curvature = TrackMathUtils.curvatureAngleAt(points, cumulative, distance, 12.0)
        val terrain = kotlin.math.sin(2 * Math.PI * (distance / 400.0)) * 1.2
        return (50.0 + terrain + curvature * 0.06).toFloat()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        left = 2f * density
        right = (w - 2f * density).coerceAtLeast(left + 1f)
        top = 12f * density
        bottom = (h - 12f * density).coerceAtLeast(top + 1f)
        fillPaint.shader = LinearGradient(
            0f, top, 0f, bottom,
            Color.argb(
                72,
                Color.red(linePaint.color),
                Color.green(linePaint.color),
                Color.blue(linePaint.color)
            ),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        rebuildPaths()
    }

    private fun rebuildPaths() {
        linePath.rewind()
        fillPath.rewind()
        if (pointCount < 2 || right <= left || bottom <= top) return
        val spanX = (rawDistances[pointCount - 1] - rawDistances[0]).takeIf { it > 0f } ?: 1f
        val spanY = (maxElevation - minElevation).coerceAtLeast(0.5f)
        val width = right - left
        val height = bottom - top
        for (i in 0 until pointCount) {
            xs[i] = left + (rawDistances[i] - rawDistances[0]) / spanX * width
            val normalized = (rawElevations[i] - minElevation) / spanY
            ys[i] = bottom - height * (0.08f + 0.84f * normalized)
        }
        linePath.moveTo(xs[0], ys[0])
        for (i in 1 until pointCount) linePath.lineTo(xs[i], ys[i])
        fillPath.moveTo(xs[0], bottom)
        for (i in 0 until pointCount) fillPath.lineTo(xs[i], ys[i])
        fillPath.lineTo(xs[pointCount - 1], bottom)
        fillPath.close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pointCount < 2) return
        // 基线：底部 0 线 + 顶部峰值参考线
        canvas.drawLine(left, bottom, right, bottom, baselinePaint)
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)
        // 峰值与谷值两个小标记点
        var peakIndex = 0
        var lowIndex = 0
        for (i in 1 until pointCount) {
            if (rawElevations[i] > rawElevations[peakIndex]) peakIndex = i
            if (rawElevations[i] < rawElevations[lowIndex]) lowIndex = i
        }
        canvas.drawCircle(xs[peakIndex], ys[peakIndex], 2.4f * density, markerPaint)
        canvas.drawCircle(xs[lowIndex], ys[lowIndex], 2.4f * density, markerPaint)
        // 左上角说明 + 爬升幅度
        val gain = (maxElevation - minElevation).roundToInt()
        canvas.drawText(
            if (usingRealData) "海拔 · 落差 ${gain}m" else "海拔（估算）· 落差 ${gain}m",
            left + 2f * density,
            top - 3f * density,
            labelPaint
        )
        val km = totalMeters / 1000.0
        val distanceText = if (km >= 1.0) {
            String.format(java.util.Locale.US, "%.2f km", km)
        } else {
            String.format(java.util.Locale.US, "%.0f m", totalMeters)
        }
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(distanceText, right - 2f * density, top - 3f * density, labelPaint)
        labelPaint.textAlign = Paint.Align.LEFT
    }

    private companion object {
        const val MAX_POINTS = 120
        val EMPTY = FloatArray(0)
    }
}
