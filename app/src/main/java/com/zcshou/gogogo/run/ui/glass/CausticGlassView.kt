package com.zcshou.gogogo.run.ui.glass

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import com.example.liquidglass.LiquidGlassView
import kotlin.math.min

/**
 * V6.4 触摸随动焦散液态玻璃。
 *
 * 在 QWEA0 的 SDF 折射玻璃之上叠加一层 **生成式 AGSL 焦散**：
 * - 手指位置渲染柔和内部亮斑 + 一圈极细的焦散环；
 * - 同时提亮距离指尖最近的玻璃边缘高光；
 * - 按下 120ms 平滑点亮，松手 420ms 衰减归零；
 * - 只有触摸与衰减动画期间 invalidate，静止 0 重绘。
 *
 * ## V6.4 传感器联动
 *
 * 光学档且未冻结时，把 `LightSourceController` 的倾角方向注入焦散着色器：
 * 光斑与边缘光束朝重力方向轻微偏移。省电档 / 锁屏 / 切后台时
 * 由 [setSensorLightEnabled] 注销传感器监听（**严格 0 唤醒**）。
 *
 * API < 33 退化为 RadialGradient 光斑（渐变同样只建一次，靠局部矩阵平移）。
 */
class CausticGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LiquidGlassView(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val lensPath = Path()
    private val lensRect = RectF()
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fallbackMatrix = Matrix()

    private var fallbackShader: Shader? = null

    private var touchX = 0f
    private var touchY = 0f
    private var intensity = 0f
    private var fadeAnimator: ValueAnimator? = null
    private var shader33: CausticShader? = null
    private var shader33Failed = false
    private var lastDownEventTime = 0L

    /** 是否允许读取重力/倾角传感器（省电档、后台、锁屏时为 false）。 */
    private var sensorLightEnabled = true

    /** XML `sensorHighlight` 的原始意图：程序化重新启用时不能违背它。 */
    private val sensorHighlightRequested: Boolean

    /** 缓存的倾斜方向：避免每帧都走一次跨模块静态读。 */
    private var lightX = 0f
    private var lightY = 0f

    init {
        setWillNotDraw(false)
        sensorHighlightRequested = enableSensorHighlight
        sensorLightEnabled = enableSensorHighlight
        // 用 outline 让 elevation 真正投影：iOS 26 玻璃下方的大半径柔和阴影
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val radius = min(cornerRadius, min(view.width, view.height) / 2f)
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            outlineAmbientShadowColor = 0x140B1220.toInt()
            outlineSpotShadowColor = 0x2E0B1220.toInt()
        }
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (sensorLightEnabled) TiltLightSource.register(this)
    }

    override fun onDetachedFromWindow() {
        TiltLightSource.unregister(this)
        cancelCaustics()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != View.VISIBLE) cancelCaustics()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fallbackShader = null
    }

    /**
     * 传感器光源开关。省电平衡档 / 巡航冻结 / 切后台时传 false：
     * 立即注销倾角监听（连同基类内置的 `LightSourceController`）并熄灭焦散，
     * **严格 0 帧、0 唤醒**。
     */
    fun setSensorLightEnabled(enabled: Boolean) {
        val want = enabled && sensorHighlightRequested
        if (want == sensorLightEnabled) return
        sensorLightEnabled = want
        // 基类 setter 内部会同步 register/unregister 它自己的重力监听
        enableSensorHighlight = want
        if (want) {
            TiltLightSource.register(this)
        } else {
            lightX = 0f
            lightY = 0f
            TiltLightSource.unregister(this)
            cancelCaustics()
        }
    }

    // ---------------------------------------------------------------- 触摸追踪

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        trackTouch(event)
        return super.dispatchTouchEvent(event)
    }

    /**
     * 导航 Tab 的触摸会由宿主直接转发到玻璃的 onTouchEvent，这里也要接住。
     * 与 dispatchTouchEvent 的重复 DOWN 用 eventTime 去重。
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        trackTouch(event)
        return super.onTouchEvent(event)
    }

    private fun trackTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.eventTime - lastDownEventTime < 20L) return
                lastDownEventTime = event.eventTime
                touchX = event.x
                touchY = event.y
                animateIntensity(1f, 120L)
            }

            MotionEvent.ACTION_MOVE -> {
                touchX = event.x
                touchY = event.y
                if (intensity > 0.01f) invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                animateIntensity(0f, 420L)
            }
        }
    }

    private fun animateIntensity(target: Float, durationMs: Long) {
        fadeAnimator?.cancel()
        val animator = ValueAnimator.ofFloat(intensity, target).apply {
            duration = durationMs
            interpolator = if (target > intensity) {
                DecelerateInterpolator(1.6f)
            } else {
                AccelerateDecelerateInterpolator()
            }
            addUpdateListener {
                intensity = it.animatedValue as Float
                invalidate()
            }
        }
        fadeAnimator = animator
        animator.start()
    }

    /** 后台 / 锁屏时由 GlassRenderGate 调用：立即熄灭焦散。 */
    fun cancelCaustics() {
        fadeAnimator?.cancel()
        fadeAnimator = null
        intensity = 0f
        invalidate()
    }

    // ---------------------------------------------------------------- 绘制

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (intensity <= 0.003f || width <= 0 || height <= 0) return

        val radius = min(width, height) * 0.58f
        val corner = min(cornerRadius, min(width, height) / 2f)
        lensRect.set(0f, 0f, width.toFloat(), height.toFloat())
        lensPath.reset()
        lensPath.addRoundRect(lensRect, corner, corner, Path.Direction.CW)

        if (sensorLightEnabled) {
            lightX = TiltLightSource.dirX
            lightY = TiltLightSource.dirY
        }

        val save = canvas.save()
        canvas.clipPath(lensPath)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val shader = if (shader33Failed) {
                null
            } else {
                shader33 ?: CausticShaderFactory.create()?.also { shader33 = it }
            }
            var drawn = false
            if (shader != null) {
                drawn = try {
                    shader.draw(
                        canvas = canvas,
                        width = width,
                        height = height,
                        corner = corner,
                        touchX = touchX,
                        touchY = touchY,
                        intensity = intensity,
                        radius = radius,
                        lightX = lightX,
                        lightY = lightY
                    )
                    true
                } catch (t: Throwable) {
                    shader33Failed = true
                    false
                }
            }
            if (!drawn) drawFallback(canvas, radius)
        } else {
            drawFallback(canvas, radius)
        }
        canvas.restoreToCount(save)
    }

    /**
     * API < 33 的冷白光斑兜底：没有生成式 AGSL，只保留触摸位置的柔光。
     *
     * 渐变按 (0,0) 为圆心建一次，逐帧只平移局部矩阵 + 用 `paint.alpha` 控制衰减，
     * 避免每个动画帧 new 一个 RadialGradient。
     */
    private fun drawFallback(canvas: Canvas, radius: Float) {
        val shader = fallbackShader ?: RadialGradient(
            0f, 0f, radius,
            intArrayOf(0x40DCEBFF, 0x14BFD9FF, 0x00DCEBFF),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        ).also { fallbackShader = it }
        fallbackMatrix.setTranslate(touchX, touchY)
        shader.setLocalMatrix(fallbackMatrix)
        fallbackPaint.shader = shader
        fallbackPaint.alpha = (255 * intensity).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fallbackPaint)
        fallbackPaint.shader = null
        fallbackPaint.alpha = 255
    }
}
