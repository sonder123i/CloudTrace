package com.zcshou.gogogo.run.ui.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.engine.TrackMathUtils
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * 无地图路线预览：WGS-84 轨迹自绘 + 网格 + 起点标记（不依赖任何地图 SDK）。
 */
class RoutePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var points: List<TrackMathUtils.LatLng> = emptyList()
    private var closed = true

    private val density = resources.displayMetrics.density
    /** 底层柔光：让细线在玻璃卡片上仍有光纤质感。 */
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = context.getColor(R.color.pf_volt)
        alpha = 30
        maskFilter = android.graphics.BlurMaskFilter(4f * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = context.getColor(R.color.pf_volt)
    }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.pf_volt)
    }
    private val gridPaint = Paint().apply {
        color = context.getColor(R.color.pf_stroke)
        strokeWidth = density
        alpha = 46
    }

    private val cachedPath = Path()
    private var cachedStartX = 0f
    private var cachedStartY = 0f
    private var hasCachedPath = false

    fun setRoute(points: List<TrackMathUtils.LatLng>, closed: Boolean) {
        this.points = points
        this.closed = closed
        rebuildPath()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildPath()
    }

    private fun rebuildPath() {
        cachedPath.reset()
        hasCachedPath = false
        if (points.size < 2 || width <= 0 || height <= 0) return

        val lat0 = points.sumOf { it.lat } / points.size
        val lng0 = points.sumOf { it.lng } / points.size
        val metersPerLng = 111320.0 * cos(Math.toRadians(lat0))
        val lx = FloatArray(points.size)
        val ly = FloatArray(points.size)
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in points.indices) {
            val x = ((points[i].lng - lng0) * metersPerLng).toFloat()
            val y = ((points[i].lat - lat0) * 110574.0).toFloat()
            lx[i] = x
            ly[i] = y
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        val spanX = max(maxX - minX, 1f)
        val spanY = max(maxY - minY, 1f)
        val pad = 24f * density
        val scale = min((width - 2 * pad) / spanX, (height - 2 * pad) / spanY)
        val centerX = width / 2f
        val centerY = height / 2f
        val midX = (minX + maxX) / 2f
        val midY = (minY + maxY) / 2f

        for (i in points.indices) {
            val vx = centerX + (lx[i] - midX) * scale
            val vy = centerY - (ly[i] - midY) * scale
            if (i == 0) {
                cachedStartX = vx
                cachedStartY = vy
                cachedPath.moveTo(vx, vy)
            } else {
                cachedPath.lineTo(vx, vy)
            }
        }
        if (closed) cachedPath.close()
        hasCachedPath = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val step = 32f * density
        var gx = step
        while (gx < width) {
            canvas.drawLine(gx, 0f, gx, height.toFloat(), gridPaint)
            gx += step
        }
        var gy = step
        while (gy < height) {
            canvas.drawLine(0f, gy, width.toFloat(), gy, gridPaint)
            gy += step
        }
        if (!hasCachedPath) return
        canvas.drawPath(cachedPath, glowPaint)
        canvas.drawPath(cachedPath, pathPaint)
        canvas.drawCircle(cachedStartX, cachedStartY, 4f * density, startPaint)
    }
}