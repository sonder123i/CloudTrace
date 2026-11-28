package com.zcshou.gogogo.run.ui.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.button.MaterialButton
import com.zcshou.gogogo.R

/**
 * V5.0 黑曜石 / 纯白镜面主按钮。
 *
 * - 纵向微光泽渐变（浅色 `#2C2C2E → #141416` / 深色 `#FFFFFF → #E5E5EA`）；
 * - 顶部 1dp 内高光边；
 * - 每 4s 一道 720ms 斜向玻璃流光（Specular Shimmer），只在本 View 内绘制；
 * - 按下 0.96x 弹簧微缩 + CLOCK_TICK 触觉；可选长按 0.8s 向父容器扩散冰青声呐波。
 *
 * 所有动画在 detached / 不可见 / 巡航或被系统抑制时立即暂停，0fps 零重绘。
 */
class SpecularButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle
) : MaterialButton(context, attrs, defStyleAttr) {

    /** 长按 0.8s 时向父容器扩散声呐光波（首页「开始跑圈」专用）。 */
    var sonarOnLongPress: Boolean = false

    private val density = resources.displayMetrics.density
    private val shapePath = Path()
    private val shapeRect = RectF()

    private val mirrorTop = context.getColor(R.color.dr_mirror_top)
    private val mirrorBottom = context.getColor(R.color.dr_mirror_bottom)
    private val shimmerColor = context.getColor(R.color.dr_mirror_shimmer)
    private val shimmerCore = context.getColor(R.color.dr_mirror_shimmer_core)
    private val topEdgeColor = context.getColor(R.color.dr_mirror_top_edge)

    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val shimmerColors = intArrayOf(0x00FFFFFF, shimmerColor, shimmerCore, shimmerColor, 0x00FFFFFF)
    private val shimmerStops = floatArrayOf(0f, 0.32f, 0.5f, 0.68f, 1f)
    private val shimmerMatrix = android.graphics.Matrix()
    private var unitShimmerShader: LinearGradient? = null
    private var topEdgeShader: LinearGradient? = null
    private val suppressionListener: () -> Unit = {
        post {
            if (!com.zcshou.gogogo.run.ui.glass.DeepRunGlassManager.isRenderSuppressed &&
                isAttachedToWindow && isShown
            ) {
                startShimmer()
            }
        }
    }

    private var shimmerPhase = 0f
    private var shimmerAnimator: android.animation.ValueAnimator? = null
    private var pressAnimX: SpringAnimation? = null
    private var pressAnimY: SpringAnimation? = null

    private var sonarFired = false
    private var sonarRunnable: Runnable? = null

    init {
        // 覆盖 Material 主题给 background 叠加的 colorSecondary 着色
        backgroundTintList = null
        background = buildMirrorBackground()
        isClickable = true
        isFocusable = true
    }

    private fun buildMirrorBackground(): RippleDrawable {
        val gradient = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(mirrorTop, mirrorBottom)
        ).apply {
            cornerRadius = 100f * density
        }
        val rippleColor = context.getColor(R.color.dr_mirror_ripple)
        return RippleDrawable(ColorStateList.valueOf(rippleColor), gradient, null)
    }

    // ---------------------------------------------------------------- 流光

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        com.zcshou.gogogo.run.ui.glass.DeepRunGlassManager.addSuppressionListener(suppressionListener)
        startShimmer()
    }

    override fun onDetachedFromWindow() {
        com.zcshou.gogogo.run.ui.glass.DeepRunGlassManager.removeSuppressionListener(suppressionListener)
        stopShimmer()
        sonarRunnable?.let { removeCallbacks(it) }
        pressAnimX?.cancel()
        pressAnimY?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE && isAttachedToWindow && isShown) startShimmer() else stopShimmer()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE && isAttachedToWindow && isShown) startShimmer() else stopShimmer()
    }

    private fun startShimmer() {
        if (shimmerAnimator != null) return
        if (com.zcshou.gogogo.run.data.prefs.DisplayPrefs.quality(context) ==
            com.zcshou.gogogo.run.data.prefs.DisplayPrefs.RenderQuality.BALANCED
        ) {
            return
        }
        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4_000L
            repeatCount = android.animation.ValueAnimator.INFINITE
            addUpdateListener {
                // 巡航 / 锁屏期间全局抑制：立即休眠，避免后台持续重绘
                if (com.zcshou.gogogo.run.ui.glass.DeepRunGlassManager.isRenderSuppressed || !isShown) {
                    shimmerAnimator?.cancel()
                    shimmerAnimator = null
                    return@addUpdateListener
                }
                shimmerPhase = it.animatedValue as Float
                // 只在每轮前 18%（约 720ms）扫过，其余时间静止
                if (shimmerPhase <= 0.18f) invalidate() else if (shimmerPhase <= 0.24f) invalidate()
            }
            start()
        }
        shimmerAnimator = animator
    }

    private fun stopShimmer() {
        shimmerAnimator?.cancel()
        shimmerAnimator = null
    }

    // ---------------------------------------------------------------- 绘制

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shapeRect.set(0f, 0f, w.toFloat(), h.toFloat())
        shapePath.reset()
        shapePath.addRoundRect(shapeRect, h / 2f, h / 2f, Path.Direction.CW)
        if (w > 0 && h > 0) {
            val band = w * 0.14f
            unitShimmerShader = LinearGradient(
                0f, 0f, band, h.toFloat(),
                shimmerColors, shimmerStops, Shader.TileMode.CLAMP
            )
            topEdgeShader = LinearGradient(
                0f, 0f, w.toFloat(), 0f,
                intArrayOf(0x00FFFFFF, topEdgeColor, topEdgeColor, 0x00FFFFFF),
                floatArrayOf(0f, 0.12f, 0.88f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        // 先画流光，再交给 MaterialButton 绘制文字/图标，避免光带盖住文字
        if (width > 0 && height > 0) {
            drawSpecularShimmer(canvas)
            drawTopEdge(canvas)
        }
        super.onDraw(canvas)
    }

    private fun drawSpecularShimmer(canvas: Canvas) {
        if (shimmerPhase > 0.18f) return
        val shader = unitShimmerShader ?: return
        val progress = shimmerPhase / 0.18f
        val w = width.toFloat()
        val h = height.toFloat()
        val band = w * 0.14f
        val x = -band + (w + band * 2f) * progress

        shimmerMatrix.setTranslate(x, 0f)
        shader.setLocalMatrix(shimmerMatrix)
        shimmerPaint.shader = shader
        canvas.save()
        canvas.clipPath(shapePath)
        canvas.drawRect(0f, 0f, w, h, shimmerPaint)
        canvas.restore()
    }

    private fun drawTopEdge(canvas: Canvas) {
        edgePaint.shader = topEdgeShader
        edgePaint.color = topEdgeColor
        val y = 0.75f * density
        canvas.save()
        canvas.clipPath(shapePath)
        canvas.drawLine(0f, y, width.toFloat(), y, edgePaint)
        canvas.restore()
        edgePaint.shader = null
    }

    // ---------------------------------------------------------------- 按压 / 长按

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressScale(0.96f)
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                if (sonarOnLongPress) startSonarCharge()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressScale(1f)
                cancelSonarCharge()
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        if (sonarFired) {
            sonarFired = false
            return true
        }
        return super.performClick()
    }

    private fun pressScale(target: Float) {
        pressAnimX?.cancel()
        pressAnimY?.cancel()
        val spring = SpringForce(target).apply {
            dampingRatio = 0.55f
            stiffness = 900f
        }
        pressAnimX = SpringAnimation(this, SpringAnimation.SCALE_X).apply {
            setSpring(spring)
            start()
        }
        pressAnimY = SpringAnimation(this, SpringAnimation.SCALE_Y).apply {
            setSpring(spring)
            start()
        }
    }

    private fun startSonarCharge() {
        cancelSonarCharge()
        val runnable = Runnable {
            sonarFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            findSonarHost()?.let { SonarRipple.emit(this, it) }
        }
        sonarRunnable = runnable
        postDelayed(runnable, 800L)
    }

    /** 向上寻找整张主卡（液态玻璃容器）作为声呐扩散画布，而不是按钮自身的小父布局。 */
    private fun findSonarHost(): android.view.ViewGroup? {
        var node: android.view.View? = this
        repeat(5) {
            val parent = node?.parent
            when {
                parent is com.zcshou.gogogo.run.ui.glass.LiquidGlassContainer -> return parent
                parent is android.widget.FrameLayout && parent !is android.widget.LinearLayout -> {
                    if (parent.width > width && parent.height > height) return parent
                }
            }
            node = parent as? android.view.View
        }
        return this.parent as? android.view.ViewGroup
    }

    private fun cancelSonarCharge() {
        sonarRunnable?.let { removeCallbacks(it) }
        sonarRunnable = null
    }
}
