package com.zcshou.gogogo.run.ui.splash

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.core.graphics.ColorUtils
import com.zcshou.gogogo.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 云迹徽记（CloudTrace Emblem）—— 纯 Canvas 雕塑级分层矢量图标。
 *
 * 与 `res/drawable/ic_splash_emblem.xml` 和 `ic_launcher.png` 保持 1:1 造型与配色对齐：
 * 1. 天蓝 → 冰青 → 薄荷液态玻璃圆角方框 + 右上空气柔光；
 * 2. 底部两重交叠渐变柔波（青绿薄荷波 + 冰蓝雾白波）；
 * 3. 云迹丝带弧线（无缝承接系统首帧白丝带，并叠加 FiberStyle 双层光纤扫光）；
 * 4. 雕塑流线跑者剪影（非火柴人线条，采用闭合渐变玻璃轮廓 + 独立悬浮头圆）；
 * 5. 右上悬浮镂空玻璃泪滴 Pin（平滑贝塞尔泪滴 + 镜面描边 + 弹跳涟漪）+ 绕边流光。
 *
 * ## 零裁切破壳设计
 *
 * 当嵌入全屏 `LaunchSplashOverlayView` 时，外部会把本 View 设为 `MATCH_PARENT`，
 * 同时把 [emblemTargetSizePx] 设为 `96dp`：徽记视觉上严格居中在 `96dp` 区域内，
 * 但破壳放大（[shellExpand] → 4.5x）时不会被 96dp 子 View 边界裁死，可丝滑扩满整屏。
 *
 * ## 零 DrawAllocation
 *
 * `onDraw` 内不创建任何 Path / Paint / Shader / RectF / Matrix / PathMeasure。
 */
class CloudTraceEmblemView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode {
        STATIC_IDLE,
        WELCOME_BREATHE,
        SPLASH_DRIVEN
    }

    // ---------------------------------------------------------------- 逻辑几何

    private val shellPath = Path()
    private val beamPath = Path()
    private val waveHighPath = Path()
    private val waveLowPath = Path()
    private val ribbonPath = Path()
    private val ribbonSegPath = Path()
    private val runnerBodyPath = Path()
    private val pinPath = Path()
    private val pinOuterPath = Path()

    private val pinArcRect = RectF()
    private val ribbonMeasure = PathMeasure()
    private val beamMeasure = PathMeasure()
    private val dotMatrix = Matrix()
    private val posTan = FloatArray(2)

    private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val waveHighPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val waveLowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ribbonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.1f
    }
    private val fiberGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 5.4f
    }
    private val fiberCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.5f
    }
    private val runnerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val runnerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.65f
    }
    private val pinFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pinRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.9f
    }
    private val pinRipplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.25f
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.55f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var shellShader: LinearGradient? = null
    private var glowShader: RadialGradient? = null
    private var waveHighShader: LinearGradient? = null
    private var waveLowShader: LinearGradient? = null
    private var runnerGlassShader: LinearGradient? = null
    private var pinGlassShader: LinearGradient? = null
    private var rimShader: LinearGradient? = null
    private var fiberShader: LinearGradient? = null
    private var dotShader: RadialGradient? = null

    private val beamTints = arrayOfNulls<ColorFilter>(BEAM_TINTS)

    private var trailBaseAlpha = 224
    private var runnerBaseAlpha = 250
    private var pinBaseAlpha = 250
    private var fiberGlowBaseAlpha = 115

    // ---------------------------------------------------------------- 外部状态

    var mode: Mode = Mode.STATIC_IDLE
        set(value) {
            if (field == value) return
            field = value
            when (value) {
                Mode.WELCOME_BREATHE -> startBreathLoop()
                Mode.SPLASH_DRIVEN -> {
                    stopBreathLoop()
                    resetForSplash()
                }
                Mode.STATIC_IDLE -> {
                    stopBreathLoop()
                    resetIdle()
                }
            }
            invalidate()
        }

    /**
     * 当外部把本 View 铺满全屏以便无裁切扩壳时，指定徽记基础直径（px，如 96dp）；
     * 为 0f 时退化为填满自身宽高（如欢迎页里的 96dp 控件）。
     */
    var emblemTargetSizePx: Float = 0f
        set(value) {
            val v = value.coerceAtLeast(0f)
            if (abs(field - v) < 0.5f) return
            field = v
            invalidate()
        }

    var breatheScale: Float = 1f
        set(value) {
            val v = value.coerceIn(0.5f, 1.6f)
            if (abs(field - v) < 0.0004f) return
            field = v
            invalidate()
        }

    var beamPhase: Float = 0f
        set(value) {
            val v = value - floor(value)
            if (abs(field - v) < 0.0006f) return
            field = v
            invalidate()
        }

    var beamAlpha: Float = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (abs(field - v) < 0.004f) return
            field = v
            invalidate()
        }

    var runnerLean: Float = 0f
        set(value) {
            val v = value.coerceIn(-20f, 25f)
            if (abs(field - v) < 0.04f) return
            field = v
            invalidate()
        }

    var runnerScale: Float = 1f
        set(value) {
            val v = value.coerceIn(0.8f, 1.3f)
            if (abs(field - v) < 0.001f) return
            field = v
            invalidate()
        }

    /** 白色原丝带底纹可见度（0..1，SPLASH_DRIVEN 首帧默认 1f 以无缝承接系统 Splash）。 */
    var trailProgress: Float = 1f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (abs(field - v) < 0.002f) return
            field = v
            invalidate()
        }

    /** 双层光纤沿丝带扫过的进度（0..1）。 */
    var fiberProgress: Float = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (abs(field - v) < 0.002f) return
            field = v
            invalidate()
        }

    /** 光纤强度（0..1）。 */
    var fiberMix: Float = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (abs(field - v) < 0.004f) return
            field = v
            invalidate()
        }

    var pinBounce: Float = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (abs(field - v) < 0.004f) return
            field = v
            invalidate()
        }

    var pinRipple: Float = -1f
        set(value) {
            val v = if (value < 0f) -1f else value.coerceIn(0f, 1f)
            if (abs(field - v) < 0.004f) return
            field = v
            invalidate()
        }

    /** 破壳阶段玻璃外壳扩放倍数（1.0 → 10.5，足以覆盖全屏高度）。 */
    var shellExpand: Float = 1f
        set(value) {
            val v = value.coerceIn(1f, 12f)
            if (abs(field - v) < 0.0008f) return
            field = v
            invalidate()
        }

    /** 破壳阶段圆角收敛比例（1.0 = 标准方框圆角 21，0.15 = 接近铺满全屏）。 */
    var shellCornerScale: Float = 1f
        set(value) {
            val v = value.coerceIn(0.1f, 1f)
            if (abs(field - v) < 0.003f) return
            field = v
            invalidate()
        }

    /** 玻璃外框（底色+高光+双浪）透明度（0..1）。 */
    var shellAlpha: Float = 1f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (abs(field - v) < 0.003f) return
            field = v
            invalidate()
        }

    /** 内部跑者 / 丝带 / Pin 透明度（0..1，可略早于外壳消融）。 */
    var innerAlpha: Float = 1f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (abs(field - v) < 0.003f) return
            field = v
            invalidate()
        }

    /** 兼容单参数透明度调节（如整体淡入/淡出）。 */
    var contentAlpha: Float = 1f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (abs(field - v) < 0.003f) return
            field = v
            shellAlpha = v
            innerAlpha = v
        }

    // ---------------------------------------------------------------- 帧循环

    private var loopScheduled = false
    private var loopStartMs = 0L

    private val breathCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            loopScheduled = false
            if (mode != Mode.WELCOME_BREATHE) return
            if (!isAttachedToWindow || windowVisibility != VISIBLE || !isShown) return
            val now = frameTimeNanos / 1_000_000L
            if (loopStartMs == 0L) loopStartMs = now
            applyBreath((now - loopStartMs) / 1000f)
            scheduleBreathFrame()
        }
    }

    init {
        buildGeometry()
        loadPalette()
    }

    fun onThemeChanged() {
        loadPalette()
        invalidate()
    }

    /** 返回「丝带终点（Pin 下方出口）」在当前 View 坐标系中的像素位置，供外部彗星轨迹无缝衔接。 */
    fun getTrailExitPoint(outPoint: FloatArray) {
        val w = width.toFloat()
        val h = height.toFloat()
        val size = emblemBaseSize(w, h)
        val scale = size / LOGICAL_SIZE
        val offsetX = (w - size) * 0.5f
        val offsetY = (h - size) * 0.5f
        outPoint[0] = offsetX + (CENTER_X + (TRAIL_EXIT_X - CENTER_X) * breatheScale) * scale
        outPoint[1] = offsetY + (CENTER_Y + (TRAIL_EXIT_Y - CENTER_Y) * breatheScale) * scale
    }

    private fun emblemBaseSize(w: Float, h: Float): Float =
        if (emblemTargetSizePx > 0f) emblemTargetSizePx else min(w, h)

    // ---------------------------------------------------------------- 几何预建

    private fun buildGeometry() {
        shellPath.reset()
        shellPath.addRoundRect(
            SHELL_LEFT, SHELL_TOP, SHELL_RIGHT, SHELL_BOTTOM,
            SHELL_RADIUS, SHELL_RADIUS, Path.Direction.CW
        )

        beamPath.reset()
        beamPath.addRoundRect(
            SHELL_LEFT + BEAM_INSET, SHELL_TOP + BEAM_INSET,
            SHELL_RIGHT - BEAM_INSET, SHELL_BOTTOM - BEAM_INSET,
            SHELL_RADIUS - BEAM_INSET, SHELL_RADIUS - BEAM_INSET, Path.Direction.CW
        )
        beamMeasure.setPath(beamPath, false)

        // 后层青绿薄荷波浪：从左下底部抬升至右缘
        waveHighPath.reset()
        waveHighPath.moveTo(26f, SHELL_BOTTOM)
        waveHighPath.quadTo(67.4f, 53.3f, SHELL_RIGHT, 65.6f)
        waveHighPath.lineTo(SHELL_RIGHT, SHELL_BOTTOM)
        waveHighPath.close()

        // 前层冰蓝雾白波浪：横跨底部形成双峰层次
        waveLowPath.reset()
        waveLowPath.moveTo(SHELL_LEFT, 78.8f)
        waveLowPath.quadTo(43.6f, 62.1f, SHELL_RIGHT, 86.7f)
        waveLowPath.lineTo(SHELL_RIGHT, SHELL_BOTTOM)
        waveLowPath.lineTo(SHELL_LEFT, SHELL_BOTTOM)
        waveLowPath.close()

        // 云迹丝带：与 ic_splash_emblem.xml 完全同路径
        ribbonPath.reset()
        ribbonPath.moveTo(32.2f, 69.1f)
        ribbonPath.cubicTo(60.3f, 65.6f, 85.0f, 51.5f, TRAIL_EXIT_X, TRAIL_EXIT_Y)
        ribbonMeasure.setPath(ribbonPath, false)

        // 雕塑流线跑者剪影：闭合二次贝塞尔（与 demo / ic_launcher 原画轮廓一致）
        runnerBodyPath.reset()
        runnerBodyPath.moveTo(23.8f, 44.0f)
        runnerBodyPath.quadTo(35.2f, 34.4f, 49.3f, 43.2f)
        runnerBodyPath.quadTo(55.5f, 46.2f, 61.6f, 40.1f)
        runnerBodyPath.quadTo(60.8f, 49.3f, 48.9f, 51.1f)
        runnerBodyPath.quadTo(55.9f, 58.6f, 48.9f, 69.6f)
        runnerBodyPath.quadTo(43.2f, 77.0f, 38.8f, 78.8f)
        runnerBodyPath.quadTo(44.5f, 67.4f, 42.7f, 60.3f)
        runnerBodyPath.quadTo(28.6f, 72.2f, 14.1f, 75.7f)
        runnerBodyPath.quadTo(29.1f, 64.3f, 38.8f, 50.2f)
        runnerBodyPath.quadTo(32.6f, 44.5f, 23.8f, 44.0f)
        runnerBodyPath.close()

        // 悬浮镂空玻璃泪滴 Pin
        pinArcRect.set(
            PIN_X - PIN_RADIUS, PIN_Y - PIN_RADIUS,
            PIN_X + PIN_RADIUS, PIN_Y + PIN_RADIUS
        )
        pinOuterPath.reset()
        pinOuterPath.moveTo(PIN_X, PIN_TIP_Y)
        pinOuterPath.cubicTo(63.4f, 32.6f, PIN_X - PIN_RADIUS, 26.44f, PIN_X - PIN_RADIUS, PIN_Y)
        pinOuterPath.arcTo(pinArcRect, 180f, 180f, false)
        pinOuterPath.cubicTo(PIN_X + PIN_RADIUS, 26.44f, 80.12f, 32.6f, PIN_X, PIN_TIP_Y)
        pinOuterPath.close()

        pinPath.reset()
        pinPath.fillType = Path.FillType.EVEN_ODD
        pinPath.addPath(pinOuterPath)
        pinPath.addCircle(PIN_X, PIN_Y, PIN_HOLE_RADIUS, Path.Direction.CW)
    }

    // ---------------------------------------------------------------- 主题色

    private fun loadPalette() {
        val c = context
        shellShader = LinearGradient(
            SHELL_LEFT, SHELL_TOP, SHELL_RIGHT, SHELL_BOTTOM,
            intArrayOf(
                c.getColor(R.color.dr_emblem_shell_top),
                c.getColor(R.color.dr_emblem_shell_mid),
                c.getColor(R.color.dr_emblem_shell_bottom)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        glowShader = RadialGradient(
            78.8f, 19.8f, 58f,
            intArrayOf(c.getColor(R.color.dr_emblem_glow), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP
        )
        waveHighShader = LinearGradient(
            30f, 61f, SHELL_RIGHT, SHELL_BOTTOM,
            intArrayOf(
                c.getColor(R.color.dr_emblem_wave_high),
                c.getColor(R.color.dr_emblem_wave_high_end)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        waveLowShader = LinearGradient(
            SHELL_LEFT, 72f, SHELL_RIGHT, SHELL_BOTTOM,
            intArrayOf(
                c.getColor(R.color.dr_emblem_wave_low),
                c.getColor(R.color.dr_emblem_wave_low_end)
            ),
            null,
            Shader.TileMode.CLAMP
        )

        val runnerTop = c.getColor(R.color.dr_emblem_runner)
        val glassEnd = c.getColor(R.color.dr_emblem_glass_tint_end)
        val pinTop = c.getColor(R.color.dr_emblem_pin)
        runnerBaseAlpha = Color.alpha(runnerTop)
        pinBaseAlpha = Color.alpha(pinTop)

        runnerGlassShader = LinearGradient(
            17f, 26f, 61f, 76f,
            intArrayOf(runnerTop, glassEnd),
            null,
            Shader.TileMode.CLAMP
        )
        pinGlassShader = LinearGradient(
            63f, 12f, 81f, 42f,
            intArrayOf(pinTop, glassEnd),
            null,
            Shader.TileMode.CLAMP
        )

        val rim = c.getColor(R.color.dr_emblem_shell_rim)
        val rimDim = (rim and 0x00FFFFFF) or ((Color.alpha(rim) * 0.30f).toInt() shl 24)
        rimShader = LinearGradient(
            SHELL_LEFT, SHELL_TOP, SHELL_RIGHT, SHELL_BOTTOM,
            intArrayOf(rim, rimDim, rim),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        val fiberIndigo = c.getColor(R.color.dr_fiber_indigo)
        val fiberCyan = c.getColor(R.color.dr_fiber_cyan)
        val fiberViolet = c.getColor(R.color.dr_fiber_violet)
        for (i in 0 until BEAM_TINTS) {
            val t = i / (BEAM_TINTS - 1).toFloat()
            val tint = ColorUtils.blendARGB(fiberIndigo, fiberCyan, t)
            beamTints[i] = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
        }
        fiberShader = LinearGradient(
            32f, 69f, 80f, 42f,
            intArrayOf(fiberIndigo, fiberCyan, fiberViolet),
            null,
            Shader.TileMode.CLAMP
        )
        fiberGlowPaint.color = fiberCyan
        fiberGlowBaseAlpha = 115

        dotShader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xF2FFFFFF.toInt(), 0x00FFFFFF),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )

        val trail = c.getColor(R.color.dr_emblem_trail)
        trailBaseAlpha = Color.alpha(trail)
        ribbonPaint.color = trail
        runnerRimPaint.color = 0xE6FFFFFF.toInt()
        pinRimPaint.color = 0xF2FFFFFF.toInt()
        pinRipplePaint.color = fiberCyan
    }

    // ---------------------------------------------------------------- 绘制

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val size = emblemBaseSize(w, h)
        val scale = size / LOGICAL_SIZE
        val offsetX = (w - size) * 0.5f
        val offsetY = (h - size) * 0.5f

        val outerSave = canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        if (breatheScale != 1f) {
            canvas.scale(breatheScale, breatheScale, CENTER_X, CENTER_Y)
        }

        drawShellAndWaves(canvas)
        drawInnerMotion(canvas)
        drawPin(canvas)
        drawRimAndBeam(canvas)

        canvas.restoreToCount(outerSave)
    }

    /** 第 1~2 层：液态玻璃方框底色 + 柔光 + 交叠波浪（破壳时整体无裁切扩放）。 */
    private fun drawShellAndWaves(canvas: Canvas) {
        if (shellAlpha <= 0.002f) return
        val save = canvas.save()
        if (shellExpand != 1f) {
            canvas.scale(shellExpand, shellExpand, CENTER_X, CENTER_Y)
        }
        val cornerR = SHELL_RADIUS * shellCornerScale

        shellPaint.shader = shellShader
        shellPaint.alpha = (255f * shellAlpha).toInt()
        canvas.drawRoundRect(
            SHELL_LEFT, SHELL_TOP, SHELL_RIGHT, SHELL_BOTTOM,
            cornerR, cornerR, shellPaint
        )
        shellPaint.shader = null

        glowPaint.shader = glowShader
        glowPaint.alpha = (255f * shellAlpha).toInt()
        canvas.drawRoundRect(
            SHELL_LEFT, SHELL_TOP, SHELL_RIGHT, SHELL_BOTTOM,
            cornerR, cornerR, glowPaint
        )
        glowPaint.shader = null

        // 波浪只在破壳前保留在方框内；扩壳时随内层淡出，避免放大后锯齿
        val waveAlpha = shellAlpha * innerAlpha
        if (waveAlpha > 0.004f) {
            val clipSave = canvas.save()
            canvas.clipPath(shellPath)
            waveHighPaint.shader = waveHighShader
            waveHighPaint.alpha = (255f * waveAlpha).toInt()
            canvas.drawPath(waveHighPath, waveHighPaint)
            waveHighPaint.shader = null

            waveLowPaint.shader = waveLowShader
            waveLowPaint.alpha = (255f * waveAlpha).toInt()
            canvas.drawPath(waveLowPath, waveLowPaint)
            waveLowPaint.shader = null
            canvas.restoreToCount(clipSave)
        }

        canvas.restoreToCount(save)
    }

    /** 第 3~4 层：云迹丝带 + 光纤扫光 + 雕塑跑者。 */
    private fun drawInnerMotion(canvas: Canvas) {
        if (innerAlpha <= 0.002f) return
        val save = canvas.save()
        if (shellExpand <= 1.05f) {
            canvas.clipPath(shellPath)
        }
        drawTrail(canvas)
        drawRunner(canvas)
        canvas.restoreToCount(save)
    }

    private fun drawTrail(canvas: Canvas) {
        val length = ribbonMeasure.length
        if (length <= 1f) return

        // 白色丝带底纹（首帧与系统 Splash 一致）
        if (trailProgress > 0.001f) {
            ribbonSegPath.reset()
            ribbonMeasure.getSegment(0f, length * trailProgress, ribbonSegPath, true)
            ribbonPaint.alpha = (trailBaseAlpha * innerAlpha).toInt()
            canvas.drawPath(ribbonSegPath, ribbonPaint)
        }

        // Stage 2 叠加 FiberStyle 双层光纤扫光
        if (fiberMix > 0.004f && fiberProgress > 0.001f) {
            ribbonSegPath.reset()
            ribbonMeasure.getSegment(0f, length * fiberProgress, ribbonSegPath, true)
            fiberGlowPaint.alpha = (fiberGlowBaseAlpha * fiberMix * innerAlpha).toInt()
            canvas.drawPath(ribbonSegPath, fiberGlowPaint)

            fiberCorePaint.shader = fiberShader
            fiberCorePaint.alpha = (230f * fiberMix * innerAlpha).toInt()
            canvas.drawPath(ribbonSegPath, fiberCorePaint)
            fiberCorePaint.shader = null
        }
    }

    private fun drawRunner(canvas: Canvas) {
        val save = canvas.save()
        if (runnerScale != 1f) {
            canvas.scale(runnerScale, runnerScale, RUNNER_PIVOT_X, RUNNER_PIVOT_Y)
        }
        if (runnerLean != 0f) {
            canvas.rotate(runnerLean, RUNNER_PIVOT_X, RUNNER_PIVOT_Y)
        }

        val alpha = (runnerBaseAlpha * innerAlpha).toInt()
        runnerFillPaint.shader = runnerGlassShader
        runnerFillPaint.alpha = alpha
        canvas.drawPath(runnerBodyPath, runnerFillPaint)
        canvas.drawCircle(RUNNER_HEAD_X, RUNNER_HEAD_Y, RUNNER_HEAD_R, runnerFillPaint)
        runnerFillPaint.shader = null

        runnerRimPaint.alpha = (180f * innerAlpha).toInt()
        canvas.drawPath(runnerBodyPath, runnerRimPaint)
        canvas.drawCircle(RUNNER_HEAD_X, RUNNER_HEAD_Y, RUNNER_HEAD_R, runnerRimPaint)

        canvas.restoreToCount(save)
    }

    /** 第 5 层：右上悬浮镂空玻璃泪滴 Pin（弹跳 + 光环涟漪）。 */
    private fun drawPin(canvas: Canvas) {
        if (innerAlpha <= 0.002f) return
        val ripple = pinRipple
        if (ripple in 0f..1f) {
            pinRipplePaint.alpha = ((1f - ripple) * 190f * innerAlpha).toInt()
            canvas.drawCircle(PIN_X, PIN_Y, PIN_RADIUS + 13f * ripple, pinRipplePaint)
        }
        val save = canvas.save()
        if (pinBounce > 0.004f) {
            canvas.translate(0f, -4.5f * pinBounce)
            val s = 1f + 0.22f * pinBounce
            canvas.scale(s, s, PIN_X, PIN_Y)
        }
        pinFillPaint.shader = pinGlassShader
        pinFillPaint.alpha = (pinBaseAlpha * innerAlpha).toInt()
        canvas.drawPath(pinPath, pinFillPaint)
        pinFillPaint.shader = null

        pinRimPaint.alpha = (230f * innerAlpha).toInt()
        canvas.drawPath(pinOuterPath, pinRimPaint)
        canvas.drawCircle(PIN_X, PIN_Y, PIN_HOLE_RADIUS, pinRimPaint)
        canvas.restoreToCount(save)
    }

    private fun drawRimAndBeam(canvas: Canvas) {
        if (shellAlpha <= 0.002f) return
        val save = canvas.save()
        if (shellExpand != 1f) {
            canvas.scale(shellExpand, shellExpand, CENTER_X, CENTER_Y)
        }
        val cornerR = SHELL_RADIUS * shellCornerScale
        rimPaint.shader = rimShader
        rimPaint.alpha = (255f * shellAlpha).toInt()
        canvas.drawRoundRect(
            SHELL_LEFT, SHELL_TOP, SHELL_RIGHT, SHELL_BOTTOM,
            cornerR, cornerR, rimPaint
        )
        rimPaint.shader = null

        if (beamAlpha > 0.004f && shellExpand <= 1.25f) {
            drawBeam(canvas)
        }
        canvas.restoreToCount(save)
    }

    private fun drawBeam(canvas: Canvas) {
        val length = beamMeasure.length
        if (length <= 1f) return
        val head = beamPhase * length
        val tailLen = length * BEAM_TAIL_RATIO

        for (i in 0..BEAM_STAMPS) {
            val t = i / BEAM_STAMPS.toFloat()
            val dist = normalizeDistance(head - tailLen * (1f - t))
            if (!beamMeasure.getPosTan(dist, posTan, null)) continue
            val energy = t * sqrt(t)
            val alpha = (230f * energy * beamAlpha * shellAlpha).toInt()
            if (alpha <= 2) continue
            val r = 1.25f + 1.85f * t
            dotMatrix.setScale(r, r)
            dotMatrix.postTranslate(posTan[0], posTan[1])
            dotShader?.setLocalMatrix(dotMatrix)
            dotPaint.shader = dotShader
            dotPaint.colorFilter =
                beamTints[(t * (BEAM_TINTS - 1)).toInt().coerceIn(0, BEAM_TINTS - 1)]
            dotPaint.alpha = alpha
            canvas.drawCircle(posTan[0], posTan[1], r, dotPaint)
        }

        if (beamMeasure.getPosTan(head, posTan, null)) {
            val headR = 2.4f
            dotMatrix.setScale(headR, headR)
            dotMatrix.postTranslate(posTan[0], posTan[1])
            dotShader?.setLocalMatrix(dotMatrix)
            dotPaint.shader = dotShader
            dotPaint.colorFilter = null
            dotPaint.alpha = (245f * beamAlpha * shellAlpha).toInt()
            canvas.drawCircle(posTan[0], posTan[1], headR, dotPaint)
        }

        dotPaint.shader = null
        dotPaint.colorFilter = null
        dotPaint.alpha = 255
    }

    private fun normalizeDistance(distance: Float): Float {
        val length = beamMeasure.length
        if (length <= 0f) return 0f
        var d = distance % length
        if (d < 0f) d += length
        return d
    }

    // ---------------------------------------------------------------- 模式实现

    private fun applyBreath(seconds: Float) {
        val a = sin(seconds * 2.0 * PI / 3.6)
        val b = sin(seconds * 2.0 * PI / 2.3 + 1.1)
        val mixed = (0.72 * a + 0.28 * b).toFloat()
        breatheScale = 1f + 0.022f * mixed
        runnerLean = 2.2f + 1.6f * sin(seconds * 2.0 * PI / 4.4).toFloat()
        runnerScale = 1f + 0.012f * ((mixed + 1f) * 0.5f)
        trailProgress = 1f
        fiberProgress = 1f
        fiberMix = 0.22f + 0.14f * ((mixed + 1f) * 0.5f)
        beamAlpha = 0.88f

        val phase = seconds / 3.2f
        beamPhase = phase - floor(phase)
        val window = (beamPhase - 0.62f) / 0.16f
        val inWindow = window in 0f..1f
        pinBounce = if (inWindow) sin(window * PI).toFloat() * 0.72f else 0f
        pinRipple = if (inWindow) window else -1f
        invalidate()
    }

    private fun resetIdle() {
        breatheScale = 1f
        beamPhase = 0.18f
        beamAlpha = 0.35f
        runnerLean = 0f
        runnerScale = 1f
        trailProgress = 1f
        fiberProgress = 1f
        fiberMix = 0.28f
        pinBounce = 0f
        pinRipple = -1f
        shellExpand = 1f
        shellCornerScale = 1f
        shellAlpha = 1f
        innerAlpha = 1f
    }

    private fun resetForSplash() {
        breatheScale = 1f
        beamPhase = 0f
        beamAlpha = 0f
        runnerLean = 0f
        runnerScale = 1f
        // 首帧保留白色原丝带，与 Android 12+ 系统 Splash 图标零跳变接棒
        trailProgress = 1f
        fiberProgress = 0f
        fiberMix = 0f
        pinBounce = 0f
        pinRipple = -1f
        shellExpand = 1f
        shellCornerScale = 1f
        shellAlpha = 1f
        innerAlpha = 1f
    }

    private fun startBreathLoop() {
        if (loopScheduled) return
        loopStartMs = 0L
        if (!isAttachedToWindow) return
        loopScheduled = true
        Choreographer.getInstance().postFrameCallback(breathCallback)
    }

    private fun scheduleBreathFrame() {
        if (loopScheduled) return
        loopScheduled = true
        Choreographer.getInstance().postFrameCallback(breathCallback)
    }

    private fun stopBreathLoop() {
        loopScheduled = false
        loopStartMs = 0L
        Choreographer.getInstance().removeFrameCallback(breathCallback)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (mode == Mode.WELCOME_BREATHE) startBreathLoop()
    }

    override fun onDetachedFromWindow() {
        stopBreathLoop()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            stopBreathLoop()
        } else if (mode == Mode.WELCOME_BREATHE) {
            startBreathLoop()
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) {
            stopBreathLoop()
        } else if (mode == Mode.WELCOME_BREATHE && isAttachedToWindow) {
            startBreathLoop()
        }
    }

    private companion object {
        const val LOGICAL_SIZE = 96f
        const val SHELL_LEFT = 4f
        const val SHELL_TOP = 4f
        const val SHELL_RIGHT = 92f
        const val SHELL_BOTTOM = 92f
        const val SHELL_RADIUS = 21f
        const val CENTER_X = 48f
        const val CENTER_Y = 48f

        const val BEAM_INSET = 1.6f
        const val BEAM_TAIL_RATIO = 0.22f
        const val BEAM_STAMPS = 36
        const val BEAM_TINTS = 8

        const val RUNNER_PIVOT_X = 42f
        const val RUNNER_PIVOT_Y = 56f
        const val RUNNER_HEAD_X = 52.0f
        const val RUNNER_HEAD_Y = 31.7f
        const val RUNNER_HEAD_R = 5.46f

        const val PIN_X = 71.76f
        const val PIN_Y = 22.48f
        const val PIN_RADIUS = 10.12f
        const val PIN_HOLE_RADIUS = 4.05f
        const val PIN_TIP_Y = 41.84f

        const val TRAIL_EXIT_X = 73.5f
        const val TRAIL_EXIT_Y = 42.7f
    }
}
