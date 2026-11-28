package com.zcshou.gogogo.run.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.engine.TrackMathUtils
import com.zcshou.gogogo.run.ui.theme.FiberStyle
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Apple 单色光纤轨迹缩略图：
 * - 双层光纤：9dp 10% 漫反射光晕 + 1.5dp 锐利核心线（浅色深炭黑 / 深色纯白微光）；
 * - 声呐预热动效：PRE_START_SETTLE 期间从起点扩散同心单色光波，逐段点亮路线。
 */
class RouteThumbnailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var points: List<TrackMathUtils.LatLng> = emptyList()

    private val density = resources.displayMetrics.density
    private val dp = { v: Float -> v * density }

    /** 底层 9dp 漫反射光晕（10%，克制不发光）。 */
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 9f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = context.getColor(R.color.pf_volt)
        alpha = FiberStyle.GLOW_ALPHA
        maskFilter = android.graphics.BlurMaskFilter(4f * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    /** 顶层 1.5dp 锐利核心线。 */
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = context.getColor(R.color.pf_volt)
    }
    /** 声呐尚未扫过的"暗"段。 */
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = context.getColor(R.color.pf_volt)
        alpha = 42
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.pf_card)
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = context.getColor(R.color.pf_volt)
    }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.pf_volt)
    }

    // ---------------------------------------------------------------- 声呐状态

    private var sonarRunning = false
    private var sonarProgress = 0f
    private var ripplePhase = 0f
    private var progressAnimator: ValueAnimator? = null
    private var rippleAnimator: ValueAnimator? = null

    // V5.2：沿跑道匀速巡航的冰川青白光点（6s / 圈），带柔和光尾
    private var cometRunning = false
    private var cometPhase = 0f
    private var cometAnimator: ValueAnimator? = null
    private val cometGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.dr_comet_glow)
        alpha = 60
        maskFilter = android.graphics.BlurMaskFilter(5f * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val cometCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.dr_comet_core)
    }
    private val cometBaseColor = context.getColor(R.color.dr_comet_core)

    private var screenX = FloatArray(0)
    private var screenY = FloatArray(0)
    private var segmentLengths = FloatArray(0)
    private var totalSegmentLength = 0f
    private val cachedLitPath = Path()
    private val cachedDimPath = Path()
    private var lastLitCount = -1
    private var cachedCoreShader: android.graphics.Shader? = null

    fun setRoute(routePoints: List<TrackMathUtils.LatLng>) {
        points = routePoints
        rebuildGeometry()
        invalidate()
        // 路线数据晚于 onResume 到达时，补启动巡航光点
        if (cometRunning && cometAnimator == null) {
            cometRunning = false
            setCometRunning(true)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            cachedCoreShader = FiberStyle.linear(context, w, h)
        }
        rebuildGeometry()
    }

    private fun rebuildGeometry() {
        lastLitCount = -1
        if (points.size < 3 || width <= 0 || height <= 0) {
            screenX = FloatArray(0)
            screenY = FloatArray(0)
            segmentLengths = FloatArray(0)
            totalSegmentLength = 0f
            return
        }
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
        val pad = 6f * density
        val scale = min((width - 2 * pad) / spanX, (height - 2 * pad) / spanY)
        val cx = width / 2f
        val cy = height / 2f
        val midX = (minX + maxX) / 2f
        val midY = (minY + maxY) / 2f
        val n = points.size
        val sx = FloatArray(n)
        val sy = FloatArray(n)
        for (i in 0 until n) {
            sx[i] = cx + (lx[i] - midX) * scale
            sy[i] = cy - (ly[i] - midY) * scale
        }
        val lens = FloatArray(n)
        var total = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            val len = kotlin.math.hypot(sx[j] - sx[i], sy[j] - sy[i])
            lens[i] = len
            total += len
        }
        screenX = sx
        screenY = sy
        segmentLengths = lens
        totalSegmentLength = total
    }

    /** 6s 一圈的巡航光点；[running] 为 false 或省电平衡模式时立即 cancel，保持 0fps。 */
    fun setCometRunning(running: Boolean) {
        val effective = running &&
            com.zcshou.gogogo.run.data.prefs.DisplayPrefs.quality(context) !=
            com.zcshou.gogogo.run.data.prefs.DisplayPrefs.RenderQuality.BALANCED
        if (cometRunning == effective) return
        cometRunning = effective
        if (!effective) {
            cometAnimator?.cancel()
            cometAnimator = null
            invalidate()
            return
        }
        if (points.size < 3 || !isShown) return
        cometAnimator?.cancel()
        cometAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 6_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                cometPhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE || !isShown) {
            cometAnimator?.pause()
        } else if (cometRunning) {
            val anim = cometAnimator
            if (anim != null && anim.isPaused) anim.resume() else if (anim == null) {
                cometRunning = false
                setCometRunning(true)
            }
        }
    }

    /**
     * 5s 预热：声呐光波 + 逐段点亮。
     * 进入 RUNNING 前一直播放；[stopSonar] 后路线保持全亮。
     */
    fun startSonar(durationMs: Long = 5000) {
        if (points.size < 3) return
        sonarRunning = true
        sonarProgress = 0f
        progressAnimator?.cancel()
        rippleAnimator?.cancel()
        progressAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            addUpdateListener {
                sonarProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                ripplePhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopSonar() {
        progressAnimator?.cancel()
        rippleAnimator?.cancel()
        progressAnimator = null
        rippleAnimator = null
        sonarRunning = false
        sonarProgress = 1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = min(width, height) / 2f
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, radius, bgPaint)
        val n = screenX.size
        if (n < 3) return

        // 声呐光波：从起点向外扩散的同心圆
        if (sonarRunning) {
            val ox = screenX[0]
            val oy = screenY[0]
            val maxRipple = radius * 0.95f
            repeat(2) { k ->
                var phase = ripplePhase - k * 0.5f
                if (phase < 0f) phase += 1f
                val r = maxRipple * phase
                if (r > dp(8f)) {
                    ripplePaint.alpha = ((1f - phase) * 70).toInt().coerceIn(0, 70)
                    canvas.drawCircle(ox, oy, r, ripplePaint)
                }
            }
        }

        val litCount = (n * sonarProgress).toInt().coerceIn(0, n)
        if (litCount != lastLitCount) {
            lastLitCount = litCount
            cachedDimPath.reset()
            if (litCount < n) {
                for (index in litCount until n) {
                    if (index == litCount) cachedDimPath.moveTo(screenX[index], screenY[index])
                    else cachedDimPath.lineTo(screenX[index], screenY[index])
                }
                cachedDimPath.lineTo(screenX[0], screenY[0])
                cachedDimPath.close()
            }
            cachedLitPath.reset()
            if (litCount > 0) {
                for (i in 0 until litCount) {
                    if (i == 0) cachedLitPath.moveTo(screenX[i], screenY[i])
                    else cachedLitPath.lineTo(screenX[i], screenY[i])
                }
                if (litCount == n) cachedLitPath.close()
            }
        }

        if (litCount < n) {
            canvas.drawPath(cachedDimPath, dimPaint)
        }
        if (litCount > 0) {
            canvas.drawPath(cachedLitPath, glowPaint)
            corePaint.shader = cachedCoreShader
            canvas.drawPath(cachedLitPath, corePaint)
        }

        // 起点星标
        canvas.drawCircle(screenX[0], screenY[0], dp(2.5f), startPaint)

        if (cometRunning) drawComet(canvas)
    }

    /** 沿闭环折线按累计边长插值，绘制带柔和光尾的冰川青白巡航光点。 */
    private fun drawComet(canvas: Canvas) {
        val n = screenX.size
        val total = totalSegmentLength
        if (n < 3 || total <= 0.01f) return

        // 光尾：沿跑道向后均匀取 10 个采样点，半径与透明度递减
        val tailSteps = 10
        for (i in tailSteps downTo 1) {
            val strength = 1f - (i - 1) / tailSteps.toFloat()
            val phase = ((cometPhase - i * 0.0055f) % 1f + 1f) % 1f
            paintCometSample(
                canvas, total, phase,
                glowRadius = dp(5.4f) * strength.coerceAtLeast(0.35f),
                glowAlpha = (78 * strength * strength).toInt().coerceIn(0, 78),
                coreRadius = dp(1.7f) * strength.coerceAtLeast(0.4f),
                coreAlpha = (150 * strength).toInt().coerceIn(0, 150)
            )
        }

        paintCometSample(
            canvas, total, cometPhase,
            glowRadius = dp(6.5f),
            glowAlpha = 90,
            coreRadius = dp(2.6f),
            coreAlpha = 255
        )
        // 白热核心
        paintCometWhiteCore(canvas, total, cometPhase)
        cometCorePaint.color = cometBaseColor
    }

    private fun paintCometSample(
        canvas: Canvas,
        total: Float,
        phase: Float,
        glowRadius: Float,
        glowAlpha: Int,
        coreRadius: Float,
        coreAlpha: Int
    ) {
        val n = screenX.size
        var target = phase * total
        var index = 0
        while (index < segmentLengths.size && target > segmentLengths[index]) {
            target -= segmentLengths[index]
            index++
        }
        if (index >= n) index = n - 1
        val next = (index + 1) % n
        val ratio = if (segmentLengths[index] > 0f) target / segmentLengths[index] else 0f
        val x = screenX[index] + (screenX[next] - screenX[index]) * ratio
        val y = screenY[index] + (screenY[next] - screenY[index]) * ratio
        cometGlowPaint.color = cometBaseColor
        cometGlowPaint.alpha = glowAlpha
        canvas.drawCircle(x, y, glowRadius, cometGlowPaint)
        cometCorePaint.color = cometBaseColor
        cometCorePaint.alpha = coreAlpha
        canvas.drawCircle(x, y, coreRadius, cometCorePaint)
    }

    private fun paintCometWhiteCore(canvas: Canvas, total: Float, phase: Float) {
        val n = screenX.size
        var headTarget = phase * total
        var headIndex = 0
        while (headIndex < segmentLengths.size && headTarget > segmentLengths[headIndex]) {
            headTarget -= segmentLengths[headIndex]
            headIndex++
        }
        if (headIndex >= n) headIndex = n - 1
        val next = (headIndex + 1) % n
        val hr = if (segmentLengths[headIndex] > 0f) headTarget / segmentLengths[headIndex] else 0f
        cometCorePaint.color = 0xFFFFFFFF.toInt()
        cometCorePaint.alpha = 255
        canvas.drawCircle(
            screenX[headIndex] + (screenX[next] - screenX[headIndex]) * hr,
            screenY[headIndex] + (screenY[next] - screenY[headIndex]) * hr,
            dp(1.1f),
            cometCorePaint
        )
    }

    override fun onDetachedFromWindow() {
        progressAnimator?.cancel()
        rippleAnimator?.cancel()
        progressAnimator = null
        rippleAnimator = null
        cometAnimator?.cancel()
        cometAnimator = null
        super.onDetachedFromWindow()
    }
}