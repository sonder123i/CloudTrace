package com.zcshou.gogogo.run.ui.home

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.engine.RunSplit
import com.zcshou.gogogo.run.engine.TrackMathUtils
import com.zcshou.gogogo.run.ui.glass.DeepRunGlassManager
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 本地历史战报复盘：多圈变道分层轨迹 + 动态回放光标 + 真实分段配速柱。
 *
 * 性能红线：
 * - `onDraw` 内零分配：所有 Paint / Path / 坐标 / 文案 / 柱状图数组都在
 *   [bind] / [onSizeChanged] 里预分配，绘制阶段只做 drawPath / drawRoundRect / drawText；
 * - 回放由 [Choreographer] 单回调驱动，切后台、视图不可见、退出省电档或
 *   [DeepRunGlassManager.isRenderSuppressed] 为真时立即注销回调（0fps）。
 */
class RunReplayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 回放进度回调：progress ∈ [0,1] */
    fun interface ProgressListener {
        fun onProgress(progress: Float, playing: Boolean)
    }

    private var points: List<TrackMathUtils.LatLng> = emptyList()
    private var laps = 1
    private var averagePaceSec = 0
    private var seed = 0L

    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.pf_text_primary)
        textSize = 12f * density
        textAlign = Paint.Align.LEFT
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.pf_text_secondary)
        textSize = 10f * density
        textAlign = Paint.Align.LEFT
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.pf_stroke)
        strokeWidth = 1f * density
    }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.pf_volt)
        style = Paint.Style.FILL
    }
    private val cursorGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.dr_beam_teal)
    }
    private val cursorCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.pf_volt)
    }
    private val progressPath = Path()

    /** 光晕滤波器按 dp 常量构造一次，避免每圈每帧重复分配。 */
    private val lapGlowFilter = BlurMaskFilter(5f * density, BlurMaskFilter.Blur.NORMAL)
    private val cursorGlowFilter = BlurMaskFilter(7f * density, BlurMaskFilter.Blur.NORMAL)

    private data class CachedLapLayer(
        val color: Int,
        val glowAlpha: Int,
        val coreAlpha: Int,
        val path: Path
    )

    private var cachedWidth = 0
    private var cachedHeight = 0
    private var cachedLayers: List<CachedLapLayer> = emptyList()
    private var cachedBasePath: Path? = null
    private var cachedStartX = 0f
    private var cachedStartY = 0f
    private val voltColor = context.getColor(R.color.pf_volt)
    private val cyanColor = context.getColor(R.color.pf_cyan)

    private val pathMeasure = PathMeasure()
    private val cursorPos = FloatArray(2)
    private val cursorTan = FloatArray(2)
    private var lapPathLength = 0f
    private var cursorX = 0f
    private var cursorY = 0f

    // --- 回放状态 ---
    private var replayProgress = 0f
    private var playing = false
    private var speedMultiplier = 1f
    private var lastFrameNanos = 0L
    private val choreographer = Choreographer.getInstance()
    private var progressListener: ProgressListener? = null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!playing) return
            if (DeepRunGlassManager.isRenderSuppressed || isBalancedQuality()) {
                // 省电/冻结：停在完整轨迹态并注销回调
                finishReplay()
                return
            }
            if (lastFrameNanos == 0L) lastFrameNanos = frameTimeNanos
            val deltaSec = ((frameTimeNanos - lastFrameNanos) / 1_000_000_000.0).toFloat()
            lastFrameNanos = frameTimeNanos
            replayProgress += deltaSec * speedMultiplier / REPLAY_DURATION_SEC
            if (replayProgress >= 1f) {
                replayProgress = 1f
                updateCursor()
                playing = false
                lastFrameNanos = 0L
                progressListener?.onProgress(replayProgress, false)
                invalidate()
                return
            }
            updateCursor()
            progressListener?.onProgress(replayProgress, true)
            invalidate()
            choreographer.postFrameCallback(this)
        }
    }

    // --- 分段柱状图（bind 时一次算好） ---
    private var barCount = 0
    private val barLevels = FloatArray(MAX_BARS)
    private val barColors = IntArray(MAX_BARS)
    private var barLabels: Array<String> = EMPTY_STRINGS
    private var paceTitle = ""
    private var paceSubtitle = ""
    private var lapLabel = ""
    private val tealColor = context.getColor(R.color.dr_beam_teal)
    private val indigoColor = context.getColor(R.color.dr_beam_indigo)

    fun setProgressListener(listener: ProgressListener?) {
        progressListener = listener
    }

    /**
     * @param splits 真实分段（旧记录为空 → 回退到确定性估算，并显式标注）
     */
    fun bind(
        points: List<TrackMathUtils.LatLng>,
        laps: Int,
        averagePaceSec: Int,
        seed: Long,
        splits: List<RunSplit> = emptyList()
    ) {
        this.points = points
        this.laps = laps.coerceAtLeast(1)
        this.averagePaceSec = averagePaceSec
        this.seed = seed
        replayProgress = 0f
        playing = false
        lastFrameNanos = 0L
        choreographer.removeFrameCallback(frameCallback)
        rebuildBars(splits)
        rebuildTrackCache()
        updateCursor()
        invalidate()
    }

    // ------------------------------------------------------------------ 回放控制

    fun startReplay() {
        if (points.size < 3) return
        if (isBalancedQuality() || DeepRunGlassManager.isRenderSuppressed) {
            // 省电档不启动动画，直接展示完整轨迹
            replayProgress = 1f
            updateCursor()
            progressListener?.onProgress(1f, false)
            invalidate()
            return
        }
        if (replayProgress >= 1f) replayProgress = 0f
        if (playing) return
        playing = true
        lastFrameNanos = 0L
        progressListener?.onProgress(replayProgress, true)
        choreographer.postFrameCallback(frameCallback)
    }

    fun pauseReplay() {
        if (!playing) return
        playing = false
        lastFrameNanos = 0L
        choreographer.removeFrameCallback(frameCallback)
        progressListener?.onProgress(replayProgress, false)
        invalidate()
    }

    fun toggleReplay() {
        if (playing) pauseReplay() else startReplay()
    }

    fun isPlaying(): Boolean = playing

    fun setSpeed(multiplier: Float) {
        speedMultiplier = multiplier.coerceIn(0.25f, 8f)
    }

    fun seekTo(progress: Float) {
        replayProgress = progress.coerceIn(0f, 1f)
        updateCursor()
        progressListener?.onProgress(replayProgress, playing)
        invalidate()
    }

    fun currentProgress(): Float = replayProgress

    /** 弹窗关闭 / 页面销毁时调用：注销帧回调，绝不让动画在后台空转 */
    fun release() {
        playing = false
        lastFrameNanos = 0L
        choreographer.removeFrameCallback(frameCallback)
    }

    private fun finishReplay() {
        playing = false
        lastFrameNanos = 0L
        choreographer.removeFrameCallback(frameCallback)
        replayProgress = 1f
        updateCursor()
        progressListener?.onProgress(1f, false)
        invalidate()
    }

    private fun isBalancedQuality(): Boolean =
        DeepRunGlassManager.tier(context) == DeepRunGlassManager.Tier.BALANCED_MATTE

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) pauseReplay()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) pauseReplay()
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildTrackCache()
        updateCursor()
    }

    /** 回放光标：PathMeasure 在 [rebuildTrackCache] 里预建路径，这里只取位置 */
    private fun updateCursor() {
        if (lapPathLength <= 0f) {
            cursorX = 0f
            cursorY = 0f
            progressPath.rewind()
            return
        }
        val lapsTotal = laps.coerceAtLeast(1)
        val overall = replayProgress * lapsTotal
        val lapFraction = (overall - kotlin.math.floor(overall)).coerceIn(0f, 1f)
        val distance = (if (replayProgress >= 1f) lapPathLength else lapFraction * lapPathLength)
        pathMeasure.getPosTan(distance, cursorPos, cursorTan)
        cursorX = cursorPos[0]
        cursorY = cursorPos[1]
        progressPath.rewind()
        pathMeasure.getSegment(0f, distance, progressPath, true)
    }

    // ------------------------------------------------------------------ 缓存构建

    private fun rebuildTrackCache() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || points.size < 3) {
            cachedLayers = emptyList()
            cachedBasePath = null
            lapPathLength = 0f
            progressPath.rewind()
            return
        }
        cachedWidth = width
        cachedHeight = height
        val mapBottom = h * MAP_HEIGHT_RATIO
        val lat0 = points.sumOf { it.lat } / points.size
        val lng0 = points.sumOf { it.lng } / points.size
        val mx = 111320.0 * cos(Math.toRadians(lat0))
        val my = 110574.0
        val xs = FloatArray(points.size)
        val ys = FloatArray(points.size)
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in points.indices) {
            val x = ((points[i].lng - lng0) * mx).toFloat()
            val y = ((points[i].lat - lat0) * my).toFloat()
            xs[i] = x
            ys[i] = y
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        val spanX = (maxX - minX).coerceAtLeast(1f)
        val spanY = (maxY - minY).coerceAtLeast(1f)
        val padding = dp(26f)
        val scale = min((w - padding * 2) / spanX, (mapBottom - padding * 2) / spanY)
        val offsetX = (w - spanX * scale) / 2f
        val offsetY = (mapBottom - spanY * scale) / 2f

        fun buildLapPath(lane: Float): Path {
            val path = Path()
            val n = xs.size
            for (i in 0 until n) {
                val next = (i + 1) % n
                val dx = xs[next] - xs[i]
                val dy = ys[next] - ys[i]
                val len = kotlin.math.hypot(dx, dy).coerceAtLeast(0.01f)
                val nx = -dy / len * lane
                val ny = dx / len * lane
                val px = offsetX + (xs[i] + nx - minX) * scale
                val py = offsetY + (maxY - (ys[i] + ny)) * scale
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            return path
        }

        val basePath = buildLapPath(0f)
        cachedBasePath = basePath
        pathMeasure.setPath(basePath, false)
        lapPathLength = pathMeasure.length
        val lapCount = laps.coerceIn(1, 8)
        cachedLayers = (0 until lapCount).map { lap ->
            val lane = ((lap - (lapCount - 1) / 2.0) * 0.9).toFloat()
            val fraction = if (lapCount <= 1) 0f else lap.toFloat() / (lapCount - 1)
            CachedLapLayer(
                color = blend(voltColor, cyanColor, fraction, 255),
                glowAlpha = 36,
                coreAlpha = if (lapCount == 1) 230 else 150 + lap * 10,
                path = buildLapPath(lane)
            )
        }
        cachedStartX = offsetX + (xs[0] - minX) * scale
        cachedStartY = offsetY + (maxY - ys[0]) * scale
        lapLabel = String.format(
            Locale.US,
            "%d 圈 · 自动变道分层",
            min(laps.coerceIn(1, 8), laps)
        )
        progressPath.rewind()
    }

    /**
     * 分段柱：优先真实 [RunSplit]，旧记录回退到确定性估算（只在这里算一次，
     * 绝不放进 onDraw）。
     */
    private fun rebuildBars(splits: List<RunSplit>) {
        val real = splits.isNotEmpty()
        val count = if (real) min(splits.size, MAX_BARS) else ESTIMATED_SEGMENTS
        val paces = IntArray(count)
        if (real) {
            // 段数超过柱数时按顺序等比合并，保持总距离与总时间不变
            for (bucket in 0 until count) {
                val start = bucket * splits.size / count
                val end = ((bucket + 1) * splits.size / count).coerceAtLeast(start + 1)
                var dist = 0
                var dur = 0
                for (index in start until end.coerceAtMost(splits.size)) {
                    dist += splits[index].distM
                    dur += splits[index].durSec
                }
                paces[bucket] = if (dist > 0) (dur * 1000.0 / dist).roundToInt() else 0
            }
        } else {
            val base = if (averagePaceSec > 0) averagePaceSec else 340
            for (i in 0 until count) {
                val wave = sin(seed * 0.7 + i * 1.6) * 0.06
                paces[i] = (base * (1.0 + wave)).toInt().coerceIn(220, 720)
            }
        }
        var fastest = Int.MAX_VALUE
        var slowest = 0
        for (pace in paces) {
            if (pace <= 0) continue
            if (pace < fastest) fastest = pace
            if (pace > slowest) slowest = pace
        }
        if (fastest == Int.MAX_VALUE) {
            fastest = 340
            slowest = 340
        }
        val span = (slowest - fastest).coerceAtLeast(1)
        val labels = Array(count) { "" }
        for (i in 0 until count) {
            val pace = paces[i].coerceAtLeast(1)
            // 越快柱越高；配速差被映射到 0.45..1.0，避免最慢段看起来消失
            val level = if (slowest == fastest) {
                0.8f
            } else {
                1.0f - 0.55f * ((pace - fastest).toFloat() / span)
            }
            barLevels[i] = level.coerceIn(0.3f, 1f)
            barColors[i] = blend(tealColor, indigoColor, (pace - fastest).toFloat() / span, 235)
            labels[i] = formatPace(pace)
        }
        barCount = count
        barLabels = labels
        val delta = RunStatsCalculator.negativeSplitDeltaSeconds(splits)
        paceTitle = if (real) "真实分段" else "历史估算分段"
        paceSubtitle = when {
            !real -> if (averagePaceSec > 0) "平均 ${formatPace(averagePaceSec)} /km" else "平均配速 --"
            delta == null -> "共 ${splits.size} 段"
            abs(delta) <= 2 -> "分段配速均衡"
            delta < 0 -> String.format(Locale.US, "前慢后快 %d″/km", delta)
            else -> String.format(Locale.US, "前快后慢 +%d″/km", delta)
        }
    }

    // ------------------------------------------------------------------ 绘制

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val mapBottom = h * MAP_HEIGHT_RATIO
        canvas.drawLine(
            dp(12f), mapBottom + dp(6f), w - dp(12f), mapBottom + dp(6f), dividerPaint
        )
        drawLayeredTrack(canvas, mapBottom)
        drawPaceBars(canvas, w, mapBottom)
    }

    private fun drawLayeredTrack(canvas: Canvas, mapBottom: Float) {
        val basePath = cachedBasePath
        if (points.size < 3 || basePath == null) {
            canvas.drawText("原路线已删除，无法还原轨迹", dp(16f), mapBottom / 2f, subPaint)
            return
        }
        trackPaint.color = voltColor
        trackPaint.alpha = 0x22
        trackPaint.strokeWidth = dp(1.5f)
        trackPaint.maskFilter = null
        canvas.drawPath(basePath, trackPaint)

        for (layer in cachedLayers) {
            trackPaint.color = layer.color
            trackPaint.strokeWidth = dp(10f)
            trackPaint.alpha = layer.glowAlpha
            trackPaint.maskFilter = lapGlowFilter
            canvas.drawPath(layer.path, trackPaint)

            trackPaint.strokeWidth = dp(2.2f)
            trackPaint.alpha = layer.coreAlpha
            trackPaint.maskFilter = null
            canvas.drawPath(layer.path, trackPaint)
        }

        // 已跑进度：沿同一条 PathMeasure 截取 [0, cursorDistance) 段，荧光冰青
        if (lapPathLength > 0f) {
            trackPaint.color = cyanColor
            trackPaint.strokeWidth = dp(2.8f)
            trackPaint.alpha = 200
            trackPaint.maskFilter = null
            canvas.drawPath(progressPath, trackPaint)
        }

        canvas.drawCircle(cachedStartX, cachedStartY, dp(4f), startPaint)
        canvas.drawText(START_LABEL, cachedStartX + dp(7f), cachedStartY + dp(3f), subPaint)
        canvas.drawText(lapLabel, dp(14f), dp(18f), labelPaint)

        // 回放光标：外圈光晕 + 内芯亮点
        if (lapPathLength > 0f && (playing || replayProgress > 0f)) {
            cursorGlowPaint.maskFilter = cursorGlowFilter
            cursorGlowPaint.alpha = 150
            canvas.drawCircle(cursorX, cursorY, dp(7f), cursorGlowPaint)
            cursorGlowPaint.maskFilter = null
            canvas.drawCircle(cursorX, cursorY, dp(3.4f), cursorCorePaint)
        }
    }

    private fun drawPaceBars(canvas: Canvas, w: Float, top: Float) {
        val left = dp(16f)
        val right = w - dp(16f)
        val barAreaTop = top + dp(44f)
        val barAreaBottom = height - dp(22f)
        val barHeight = (barAreaBottom - barAreaTop).coerceAtLeast(20f)
        canvas.drawText(paceTitle, left, top + dp(20f), labelPaint)
        canvas.drawText(paceSubtitle, left, top + dp(34f), subPaint)
        if (barCount <= 0) return
        val gap = dp(7f)
        val totalGap = gap * (barCount - 1)
        val barWidth = ((right - left) - totalGap) / barCount
        if (barWidth <= 0f) return
        for (i in 0 until barCount) {
            val x = left + i * (barWidth + gap)
            val heightPx = barHeight * barLevels[i]
            val barTop = barAreaBottom - heightPx
            barPaint.color = barColors[i]
            canvas.drawRoundRect(x, barTop, x + barWidth, barAreaBottom, dp(4f), dp(4f), barPaint)
            if (barWidth >= dp(26f)) {
                canvas.drawText(barLabels[i], x, barAreaBottom + dp(14f), subPaint)
            }
        }
    }

    private fun formatPace(secPerKm: Int): String =
        String.format(Locale.US, "%d'%02d\"", secPerKm / 60, secPerKm % 60)

    private fun blend(from: Int, to: Int, fraction: Float, alpha: Int): Int {
        val f = fraction.coerceIn(0f, 1f)
        return Color.argb(
            alpha,
            (Color.red(from) + (Color.red(to) - Color.red(from)) * f).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * f).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f).toInt()
        )
    }

    private fun dp(value: Float): Float = value * density

    private companion object {
        const val MAX_BARS = 8
        const val ESTIMATED_SEGMENTS = 6
        const val MAP_HEIGHT_RATIO = 0.64f
        const val REPLAY_DURATION_SEC = 12f
        const val START_LABEL = "起点"
        val EMPTY_STRINGS = emptyArray<String>()
    }
}
