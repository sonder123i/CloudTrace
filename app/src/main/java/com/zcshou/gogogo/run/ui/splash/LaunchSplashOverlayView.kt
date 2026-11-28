package com.zcshou.gogogo.run.ui.splash

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import com.zcshou.gogogo.R
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 「云迹破壳 · 跑者入画」全屏启动遮罩（1050ms 无断档三段式）。
 *
 * ```
 *  0ms ────────── 240ms ────────────── 660ms ─────────────── 1050ms
 *  │ 玻璃苏醒·呼吸流光 │ 跑者起势·光纤引路 │ 破壳融屏·首页落位 │
 *  │ 蓄力 1.0→0.93→1.04 │ 前倾 +8° / 光纤接力出画 │ 无裁切扩满全屏 + 折射环 │
 * ```
 */
class LaunchSplashOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var onRevealStart: (() -> Unit)? = null
    var onFinished: (() -> Unit)? = null

    private val density = resources.displayMetrics.density

    private val emblem = CloudTraceEmblemView(context)
    private val wordmark = TextView(context)
    private val wordmarkSub = TextView(context)

    private val bgPaint = Paint()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.6f * density
    }
    private val contourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.7f * density
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handoffPaint = Paint()

    private val gridPath = Path()
    private val contourPath = Path()
    private val trailPath = Path()
    private val trailSegPath = Path()
    private val trailMeasure = PathMeasure()
    private val ringMatrix = Matrix()
    private val posTan = FloatArray(2)

    private var bgShader: LinearGradient? = null
    private var iceGlowShader: RadialGradient? = null
    private var trailShader: LinearGradient? = null
    private var headDotShader: RadialGradient? = null
    private var fallbackRingShader: RadialGradient? = null

    private val ringRenderer: SplashRingRenderer? = SplashRing.create()

    private var gridBaseAlpha = 0
    private var contourBaseAlpha = 0
    private var prismRed = 0
    private var prismCyan = 0

    private var bgAlpha = 1f
    private var handoffAlpha = 0f
    private var handoffNeeded = false
    private var trailHead = 0f
    private var trailAlpha = 0f
    private var ringProgress = 0f
    private var ringAlpha = 0f

    private var centerX = 0f
    private var centerY = 0f
    private var wordBaseY = 0f
    private var wordSubBaseY = 0f

    private var started = false
    private var finished = false
    private var revealSent = false

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = LaunchPolicy.FULL_SPLASH_DURATION_MS
        interpolator = LinearInterpolator()
        addUpdateListener { animation ->
            applyTimeline(
                (animation.animatedValue as Float) * LaunchPolicy.FULL_SPLASH_DURATION_MS
            )
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                complete()
            }
        })
    }

    init {
        isClickable = true
        clipChildren = false
        clipToPadding = false
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO

        // 铺满全屏但以 96dp 居中绘制徽记：破壳放大 4.6x 时不会被 96dp 边界裁死
        emblem.mode = CloudTraceEmblemView.Mode.SPLASH_DRIVEN
        emblem.emblemTargetSizePx = dpF(96f)
        addView(
            emblem,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        wordmark.apply {
            setText(R.string.app_name)
            setTextColor(context.getColor(R.color.pf_text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            letterSpacing = 0.08f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            alpha = 0f
        }
        addView(wordmark, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        wordmarkSub.apply {
            setText(R.string.splash_wordmark_subtitle)
            setTextColor(context.getColor(R.color.pf_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            letterSpacing = 0.42f
            gravity = Gravity.CENTER
            alpha = 0f
        }
        addView(
            wordmarkSub,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )

        setOnClickListener { finishNow() }
        loadPalette()
    }

    fun onThemeChanged() {
        loadPalette()
        emblem.onThemeChanged()
        rebuildShaders()
        invalidate()
    }

    private fun loadPalette() {
        val grid = context.getColor(R.color.dr_l0_grid)
        val contour = context.getColor(R.color.dr_l0_contour)
        gridBaseAlpha = Color.alpha(grid)
        contourBaseAlpha = Color.alpha(contour)
        gridPaint.color = grid
        contourPaint.color = contour
        prismRed = context.getColor(R.color.dr_prism_red)
        prismCyan = context.getColor(R.color.dr_prism_cyan)

        headDotShader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(0xFFFFFFFF.toInt(), 0x995CE1E6.toInt(), 0x00FFFFFF),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )

        val appDark = com.zcshou.gogogo.run.ui.theme.ThemeHelper.isDark(context)
        val systemDark = com.zcshou.gogogo.run.ui.theme.ThemeHelper.isSystemDark(context)
        handoffNeeded = appDark != systemDark
        handoffPaint.color = context.getColor(
            if (systemDark) R.color.dr_launch_canvas_dark else R.color.dr_launch_canvas_light
        )

        fallbackRingShader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(
                Color.TRANSPARENT,
                withAlpha(prismRed, 150),
                withAlpha(prismCyan, 195),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.86f, 0.965f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w * 0.5f
        centerY = h * 0.5f
        wordBaseY = dp(72).toFloat()
        wordSubBaseY = dp(100).toFloat()
        wordmark.translationY = wordBaseY
        wordmarkSub.translationY = wordSubBaseY
        buildBackdrop(w, h)
        rebuildShaders()
        buildTrailPath(w.toFloat(), h.toFloat())
    }

    private fun rebuildShaders() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        bgShader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                context.getColor(R.color.dr_launch_canvas_top),
                context.getColor(R.color.dr_launch_canvas_bottom)
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        iceGlowShader = RadialGradient(
            w * 0.92f, -h * 0.04f, w * 0.92f,
            intArrayOf(context.getColor(R.color.dr_l0_ice_glow), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP
        )
    }

    private fun buildBackdrop(w: Int, h: Int) {
        gridPath.reset()
        val step = 72f * density
        var x = step
        while (x < w) {
            gridPath.moveTo(x, 0f)
            gridPath.lineTo(x, h.toFloat())
            x += step
        }
        var y = step
        while (y < h) {
            gridPath.moveTo(0f, y)
            gridPath.lineTo(w.toFloat(), y)
            y += step
        }

        contourPath.reset()
        val contourStep = 64f * density
        var cy = h * 0.16f
        var index = 0
        while (cy < h * 1.02f) {
            val amp = (9f + (index % 3) * 5f) * density
            contourPath.moveTo(-w * 0.05f, cy)
            var cx = -w * 0.05f
            while (cx < w * 1.05f) {
                val nx = cx + w * 0.24f
                val midY = cy + sin(index * 0.7f + cx / (w * 0.30f)) * amp
                val endY = cy + sin(index * 0.7f + nx / (w * 0.30f)) * amp * 0.55f
                contourPath.quadTo((cx + nx) * 0.5f, midY.toFloat(), nx, endY.toFloat())
                cx = nx
            }
            cy += contourStep
            index++
        }
    }

    /**
     * 屏幕级彗星拖尾：严格从图标内部丝带出口（Pin 正下方）接出，
     * 飞向右上象限安全区（不越出屏幕右边界）。
     */
    private fun buildTrailPath(w: Float, h: Float) {
        val size = dpF(96f)
        val scale = size / 96f
        val startX = centerX + (73.5f - 48f) * scale
        val startY = centerY + (42.7f - 48f) * scale
        val endX = (centerX + dpF(128f)).coerceAtMost(w - dpF(26f))
        val endY = (centerY - dpF(164f)).coerceAtLeast(dpF(64f))

        trailPath.reset()
        trailPath.moveTo(startX, startY)
        trailPath.cubicTo(
            startX + dpF(42f), startY - dpF(20f),
            endX - dpF(10f), endY + dpF(76f),
            endX, endY
        )
        trailMeasure.setPath(trailPath, false)
        trailShader = LinearGradient(
            startX, startY, endX, endY,
            intArrayOf(
                context.getColor(R.color.dr_fiber_indigo),
                context.getColor(R.color.dr_fiber_cyan),
                context.getColor(R.color.dr_fiber_violet)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || bgAlpha <= 0.002f) return
        val alpha = (255f * bgAlpha).toInt()
        bgPaint.shader = bgShader
        bgPaint.alpha = alpha
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        bgPaint.shader = null

        glowPaint.shader = iceGlowShader
        glowPaint.alpha = (alpha * 0.9f).toInt()
        canvas.drawRect(0f, 0f, w, h, glowPaint)
        glowPaint.shader = null

        if (handoffAlpha > 0.004f) {
            handoffPaint.alpha = (255f * handoffAlpha).toInt()
            canvas.drawRect(0f, 0f, w, h, handoffPaint)
        }

        gridPaint.alpha = (gridBaseAlpha * bgAlpha).toInt()
        canvas.drawPath(gridPath, gridPaint)
        contourPaint.alpha = (contourBaseAlpha * bgAlpha).toInt()
        canvas.drawPath(contourPath, contourPaint)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawCometTrail(canvas)
        drawRefractionRing(canvas)
    }

    private fun drawCometTrail(canvas: Canvas) {
        if (trailHead <= 0.004f || trailAlpha <= 0.004f) return
        val length = trailMeasure.length
        if (length <= 1f) return
        trailSegPath.reset()
        trailMeasure.getSegment(0f, length * trailHead, trailSegPath, true)

        trailPaint.shader = trailShader
        trailPaint.strokeWidth = dpF(8.5f)
        trailPaint.alpha = (56f * trailAlpha).toInt()
        canvas.drawPath(trailSegPath, trailPaint)
        trailPaint.strokeWidth = dpF(2.5f)
        trailPaint.alpha = (235f * trailAlpha).toInt()
        canvas.drawPath(trailSegPath, trailPaint)
        trailPaint.shader = null

        if (trailMeasure.getPosTan(length * trailHead, posTan, null)) {
            val headR = dpF(6.5f)
            ringMatrix.setScale(headR, headR)
            ringMatrix.postTranslate(posTan[0], posTan[1])
            headDotShader?.setLocalMatrix(ringMatrix)
            trailPaint.shader = headDotShader
            trailPaint.style = Paint.Style.FILL
            trailPaint.alpha = (245f * trailAlpha).toInt()
            canvas.drawCircle(posTan[0], posTan[1], headR, trailPaint)
            trailPaint.style = Paint.Style.STROKE
            trailPaint.shader = null
        }
    }

    private fun drawRefractionRing(canvas: Canvas) {
        if (ringAlpha <= 0.004f) return
        val w = width.toFloat()
        val h = height.toFloat()
        val diagonal = hypot(w.toDouble(), h.toDouble()).toFloat()
        val radius = dpF(56f) + (diagonal * 0.62f - dpF(56f)) * easeOutCubic(ringProgress)
        val thickness = dpF(20f) * (1f - 0.55f * ringProgress)

        val renderer = ringRenderer
        if (renderer != null) {
            renderer.draw(
                canvas, ringPaint, w, h, centerX, centerY, radius, thickness,
                ringAlpha, prismRed, prismCyan
            )
        } else {
            ringMatrix.setScale(radius, radius)
            ringMatrix.postTranslate(centerX, centerY)
            fallbackRingShader?.setLocalMatrix(ringMatrix)
            ringPaint.shader = fallbackRingShader
            ringPaint.alpha = (255f * ringAlpha).toInt()
            canvas.drawRect(0f, 0f, w, h, ringPaint)
            ringPaint.shader = null
        }
    }

    // ---------------------------------------------------------------- 时间轴（无断档连续转场）

    private fun applyTimeline(elapsedMs: Float) {
        val t = elapsedMs.coerceIn(0f, LaunchPolicy.FULL_SPLASH_DURATION_MS.toFloat())
        val s1 = LaunchPolicy.STAGE_ONE_END_MS.toFloat() // 240ms
        val s2 = LaunchPolicy.STAGE_TWO_END_MS.toFloat() // 660ms

        handoffAlpha = if (handoffNeeded) 1f - clamp01(t / 180f) else 0f

        // 字标：0..240ms 柔和浮现，640..840ms 先于外壳淡出
        val wordIn = easeOutCubic(clamp01(t / s1))
        val wordOut = 1f - easeOutCubic(clamp01((t - 640f) / 200f))
        val wordOffset = (1f - wordIn) * dp(6)
        wordmark.alpha = wordIn * wordOut
        wordmark.translationY = wordBaseY + wordOffset
        wordmarkSub.alpha = wordIn * wordOut
        wordmarkSub.translationY = wordSubBaseY + wordOffset

        // Stage 1：呼吸蓄力 + 彗星绕边
        val breathe = when {
            t <= 140f -> 1f - 0.075f * easeInOutCubic(t / 140f)
            t <= s1 -> 0.925f + 0.115f * easeOutCubic((t - 140f) / 100f)
            else -> 1.04f - 0.04f * easeOutCubic(clamp01((t - s1) / 200f))
        }
        emblem.breatheScale = breathe
        emblem.beamPhase = (t / 920f) % 1f
        emblem.beamAlpha = clamp01(t / 80f) * (1f - clamp01((t - s2) / 120f))

        // Stage 2：跑者起势 + 白色原丝带无缝衔接 + FiberStyle 光纤扫过接出屏幕右上
        val leanT = easeOutCubic(clamp01((t - s1) / 180f))
        emblem.runnerLean = 8f * leanT
        emblem.runnerScale = 1f + 0.08f * leanT
        emblem.trailProgress = 1f
        emblem.fiberProgress = easeOutCubic(clamp01((t - s1) / 240f))
        emblem.fiberMix = clamp01((t - s1) / 90f)

        // 光纤扫抵 Pin（440ms）：轻弹 + 涟漪
        val pinPhase = (t - 440f) / 220f
        val pinActive = pinPhase in 0f..1f
        emblem.pinBounce = if (pinActive) sin(pinPhase * PI).toFloat() else 0f
        emblem.pinRipple = if (pinActive) pinPhase else -1f

        // 屏幕级彗星从 Pin 出口接棒飞向右上（410..700ms），640..860ms 淡出
        trailHead = easeOutCubic(clamp01((t - 410f) / 290f))
        trailAlpha = clamp01((t - 400f) / 60f) * (1f - easeOutCubic(clamp01((t - 640f) / 220f)))

        // Stage 3（660..1050ms）：无裁切破壳扩放冲出全屏 + 画布同步透出首页，零空白停顿
        val shellP = clamp01((t - s2) / 390f)
        emblem.shellExpand = 1.04f + (10.5f - 1.04f) * easeInOutCubic(shellP)
        emblem.shellCornerScale = 1f - 0.65f * easeOutCubic(shellP)
        emblem.innerAlpha = 1f - easeOutCubic(clamp01((t - s2) / 190f))
        emblem.shellAlpha = 1f - easeOutCubic(clamp01((t - 680f) / 310f))

        ringProgress = shellP
        ringAlpha = sin(PI * shellP).toFloat().coerceAtLeast(0f) * 0.46f

        // 画布必须最后化开：字标 840ms 就退完了，如果 scrim 从 680ms 就开始 easeOut 淡出，
        // 680~840 这段会出现「黑字压在已经露出来的首页卡片上」。改到 800ms 起、
        // 用 easeInOut 收尾，等于把首页的露出压进最后 250ms，和注释里的「先于外壳淡出」一致。
        bgAlpha = 1f - easeInOutCubic(clamp01((t - 800f) / 250f))

        if (!revealSent && t >= s2) {
            revealSent = true
            onRevealStart?.invoke()
        }

        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (finished) {
            post(removeSelf)
            return
        }
        if (!started) {
            started = true
            animator.start()
        }
    }

    override fun onDetachedFromWindow() {
        if (!finished) {
            finished = true
            started = false
            if (animator.isStarted) animator.cancel()
            onFinished = null
            onRevealStart = null
        }
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE && !finished) finishNow()
    }

    fun finishNow() {
        if (finished) return
        started = false
        if (animator.isStarted) {
            animator.cancel()
            return
        }
        complete()
    }

    private fun complete() {
        if (finished) return
        finished = true
        started = false
        bgAlpha = 0f
        trailAlpha = 0f
        ringAlpha = 0f
        emblem.shellAlpha = 0f
        emblem.innerAlpha = 0f
        alpha = 0f
        onFinished?.invoke()
        post(removeSelf)
        onFinished = null
        onRevealStart = null
    }

    private val removeSelf = Runnable {
        (parent as? ViewGroup)?.removeView(this)
    }

    private fun dp(value: Int): Int = (value * density).toInt()
    private fun dpF(value: Float): Float = value * density

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

    private fun easeOutCubic(t: Float): Float {
        val inv = 1f - clamp01(t)
        return 1f - inv * inv * inv
    }

    private fun easeInOutCubic(t: Float): Float {
        val v = clamp01(t)
        return if (v < 0.5f) 4f * v * v * v else 1f - (-2f * v + 2f).let { it * it * it } / 2f
    }
}
