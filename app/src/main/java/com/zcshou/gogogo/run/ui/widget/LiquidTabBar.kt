package com.zcshou.gogogo.run.ui.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.example.liquidglass.LiquidGlassView
import com.zcshou.gogogo.run.ui.glass.GlassLensRenderer
import com.zcshou.gogogo.run.ui.glass.GlassRenderGate
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * 液态玻璃底栏：等宽格子 + 滑动透镜（几何与动效对齐 capybara 的 CapsuleNavigationBar）。
 *
 * 分层（自下而上）：玻璃轨道 → 抬起透镜 → Tab 内容 → 手势层。
 *
 * ## 几何
 *
 * 轨道 72dp / 内留白 6dp / 透镜 56dp / 按下 78dp。**轨道高与内容留白同源**
 * （[NavMetrics.trackHeightPx]），改一处必须改另一处 —— 曾经两者脱钩，末项被压住。
 *
 * ## 动效参数（逐条对齐）
 *
 * - 按下即跟手：透镜瞬间落到手指所在格，不再从旧位置滑过来；
 * - 松手吸附：`spring(0.60, 310)`；抬起 `spring(0.55, 290)`；
 * - 透镜按压：X/Y 同乘 `78/56`，两端 Tab 不会被硬钳回中间；
 * - 速度果冻：`v = velocity/6`，`sx /= 1 - clamp(v*0.75, ±0.22)`，
 *   `sy *= 1 - clamp(v*0.25, ±0.22)`；
 * - 整条轨道随拖动轻微侧移 4dp（EaseOut），松手果冻归零；
 * - 端点橡皮筋：Tab 行最大位移 3dp×0.8，松手 `spring(0.60, 400)`；
 * - 跨格触发 CLOCK_TICK 触觉；
 * - 点已选中的 Tab：重播图标动作，而不是静默无反应。
 *
 * 静止时不申请任何帧：只在触摸与弹簧运行期间 invalidate。
 */
class LiquidTabBar(
    private val container: ViewGroup,
    private val glass: View,
    private val tabHost: FrameLayout,
    private val tabs: List<NavTabView>,
    private val onSelected: (Int) -> Unit
) {

    /** 底栏几何。轨道高与内容留白必须同源。 */
    object NavMetrics {
        /** 轨道高。 */
        const val TRACK_DP = 72f
        /** 轨道内侧留白：透镜与轨道之间这一圈就是边缘折射带。 */
        const val PADDING_DP = 6f
        /** 透镜高。 */
        const val INDICATOR_DP = 56f
        /** 78/56：按压时透镜的放大倍数，与 capybara 的 NavPressedScale 同值。 */
        const val PRESSED_SCALE = 78f / 56f
        /** 轨道随按压最多加宽 16dp。 */
        const val PRESS_GAIN_DP = 16f
        /** 整条轨道的拖动侧移上限。 */
        const val PANEL_OFFSET_DP = 4f
        /** 端点橡皮筋上限。 */
        const val RUBBER_DP = 3f
        /** 图标动作时长：与 capybara 的 NavigationIconPlayback 一致。 */
        const val ICON_ACTION_MS = 480L
        /** 轨道到屏幕边缘的纵向留白。 */
        const val BLOCK_PADDING_DP = 8f
        /** 轨道到屏幕边缘的横向留白。 */
        const val SIDE_PADDING_DP = 16f

        /**
         * 各页内容要避开的底部高度。
         *
         * 各页自己的 `contentPadding` 还会再加 24dp，两者合起来正好在轨道上缘留
         * 8dp 的缝，所以这里减 16dp。20:9 手势条（6+72+24）算出来正好是原先写死的
         * 96dp，于是既有留白一个 dp 都不变；换到三键导航（48dp）它自己长到 120dp，
         * 末项才不会被底栏压住。
         */
        fun contentInsetDp(navigationBarDp: Float): Float =
            (BLOCK_PADDING_DP * 2 + TRACK_DP + navigationBarDp - 16f).coerceAtLeast(0f)
    }

    private val density = container.resources.displayMetrics.density
    private val dp = { v: Float -> v * density }
    private val dpInt = { v: Float -> (v * density).toInt() }

    private val lens = LensView(container.context)
    private val scrub = ScrubView(container.context)

    private val centers = FloatArray(tabs.size)
    private val widths = FloatArray(tabs.size)

    /** 透镜矩形（用于水银融合），两块 RectF 全程复用。 */
    private val lensRect = RectF()
    private var secondaryShapeActive = false
    private var lastShapeX = Float.NaN
    private var lastShapeY = Float.NaN
    private var lastShapeSmooth = Float.NaN

    private var selected = 0
    private var value = 0f
    private var pressProgress = 0f
    private var pressed = false
    private var dragging = false
    private var downX = 0f
    private var lastHapticIndex = 0
    private var metricsReady = false

    /** 整条轨道的拖动侧移（像素），松手归零。 */
    private var panelOffset = 0f
    /** Tab 行相对轨道的橡皮筋位移（像素）。 */
    private var rubberOffset = 0f

    private var valueSpring: SpringAnimation? = null
    private var pressSpring: SpringAnimation? = null
    private var rubberSpring: SpringAnimation? = null
    private var panelSpring: SpringAnimation? = null
    private var velocityTracker: VelocityTracker? = null

    private val iconAnims = arrayOfNulls<ValueAnimator>(tabs.size)

    private val touchSlop = ViewConfiguration.get(container.context).scaledTouchSlop

    init {
        container.clipChildren = false
        container.clipToPadding = false
        tabHost.clipChildren = false

        // 容器负责投影轮廓；玻璃本身不再抬 elevation，避免盖住上层透镜与文字
        container.elevation = 0f
        container.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val navHeight = if (glass.height > 0) glass.height else dpInt(NavMetrics.TRACK_DP)
                val top = (view.height - navHeight).coerceAtLeast(0)
                val radius = min(navHeight, view.width) / 2f
                outline.setRoundRect(0, top, view.width, view.height, radius)
            }
        }
        container.clipToOutline = false

        // 透镜夹在玻璃与 Tab 之间：glass -> lens -> tabHost -> scrub。
        // 垂直居中而不是贴底：tabHost 是 56dp 居中在 72dp 轨道里的，
        // 透镜贴底会比 Tab 内容低 8dp，抬起的着色副本就会和原字形错位。
        container.addView(
            lens,
            1,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dpInt(NavMetrics.INDICATOR_DP),
                android.view.Gravity.CENTER_VERTICAL
            )
        )
        container.addView(
            scrub,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        lens.translationZ = dp(1f)
        tabHost.translationZ = dp(2f)
        scrub.translationZ = dp(5f)
        // 静止不出现任何色散；按压抬起时再淡入
        (glass as? LiquidGlassView)?.dispersionStrength = 0f

        container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateMetrics()
            applyLens()
            if (!metricsReady) {
                metricsReady = true
                applyValue(selected.toFloat())
            }
        }
        container.post {
            updateMetrics()
            applyValue(selected.toFloat())
            applyLens()
            playIcon(selected)
        }
    }

    // ---------------------------------------------------------------- 对外 API

    fun select(index: Int, animate: Boolean = true) {
        if (index !in tabs.indices) return
        val changed = index != selected
        selected = index
        if (changed) playIcon(index)
        if (pressed) return
        if (!animate || !changed) {
            valueSpring?.cancel()
            applyValue(index.toFloat())
        } else {
            animateValue(index.toFloat(), SpringForce(index.toFloat()).apply {
                dampingRatio = 0.85f
                stiffness = 450f
            })
        }
    }

    /** 重播指定格子的图标动作（点已选中项时用）。 */
    fun replayIcon(index: Int) = playIcon(index)

    // ---------------------------------------------------------------- 手势

    private fun onDown(event: MotionEvent) {
        val x = event.x
        updateMetrics()
        pressed = true
        dragging = false
        downX = x
        lastHapticIndex = roundValue(rawValueAt(x))
        velocityTracker?.recycle()
        velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
        // 按下即跟手：透镜直接落到手指所在的格子，不从旧位置滑过来
        setValue(rawValueAt(x))
        setHiddenTab(roundValue(value))
        lens.translationZ = dp(4f)
        animatePress(1f, damping = 0.65f, stiffness = 300f)
        // 玻璃形状与位移都在变：顺延一次采样窗口，让折射跟上按压
        GlassRenderGate.requestRefresh(320L)
    }

    private fun onMove(event: MotionEvent) {
        if (!pressed) return
        val x = event.x
        if (!dragging && abs(x - downX) > touchSlop) dragging = true
        velocityTracker?.let {
            it.addMovement(event)
            it.computeCurrentVelocity(1000)
        }
        val raw = rawValueAt(x)
        setValue(raw)
        applyRubberBand(raw)
        setHiddenTab(roundValue(value))
        // 整条轨道在侧移，底下的页面内容相对玻璃也在动
        GlassRenderGate.requestRefresh(200L)
        val haptic = roundValue(value)
        if (haptic != lastHapticIndex) {
            lastHapticIndex = haptic
            container.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun onUp() {
        if (!pressed) return
        pressed = false
        val target = roundValue(value)
        selected = target
        lastHapticIndex = target
        container.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        playIcon(target)
        onSelected(target)
        animateValue(target.toFloat(), SpringForce(target.toFloat()).apply {
            dampingRatio = 0.8f
            stiffness = 550f
        })
        animatePress(0f, damping = 0.8f, stiffness = 550f)
        restoreHiddenTab()
        lens.translationZ = dp(1f)
        resetRubberBand()
        resetPanelOffset()
        velocityTracker?.recycle()
        velocityTracker = null
    }

    // ---------------------------------------------------------------- 位置 / 透镜

    private fun updateMetrics() {
        if (tabs.isEmpty()) return
        val containerLoc = IntArray(2)
        container.getLocationInWindow(containerLoc)
        tabs.forEachIndexed { index, tab ->
            if (tab.width <= 0) return@forEachIndexed
            val loc = IntArray(2)
            tab.getLocationInWindow(loc)
            centers[index] = (loc[0] - containerLoc[0] + tab.width / 2f)
            widths[index] = tab.width.toFloat()
        }
        metricsReady = tabs.all { it.width > 0 }
    }

    private fun rawValueAt(x: Float): Float {
        if (!metricsReady || tabs.size < 2) return 0f
        if (x <= centers.first()) return 0f
        if (x >= centers.last()) return (tabs.size - 1).toFloat()
        for (i in 0 until tabs.size - 1) {
            if (x <= centers[i + 1]) {
                val span = (centers[i + 1] - centers[i]).coerceAtLeast(1f)
                return i + ((x - centers[i]) / span).coerceIn(0f, 1f)
            }
        }
        return (tabs.size - 1).toFloat()
    }

    private fun setValue(raw: Float) {
        value = raw.coerceIn(0f, (tabs.size - 1).coerceAtLeast(0).toFloat())
        applyValue(value)
    }

    private fun applyValue(v: Float) {
        value = v.coerceIn(0f, (tabs.size - 1).coerceAtLeast(0).toFloat())
        if (!metricsReady) return
        val base = floor(value).toInt().coerceIn(0, tabs.size - 1)
        val next = (base + 1).coerceAtMost(tabs.size - 1)
        val t = (value - base).coerceIn(0f, 1f)
        val center = centers[base] + (centers[next] - centers[base]) * t
        val width = widths[base] + (widths[next] - widths[base]) * t
        lens.setBase(center, width, dp(NavMetrics.INDICATOR_DP))
        lens.setPress(pressProgress)
        lens.invalidate()
        // 权重跟着透镜连续变化：图标抬升、字重交叉淡入都读这一个值
        tabs.forEachIndexed { index, tab ->
            val weight = (1f - abs(value - index)).coerceIn(0f, 1f)
            tab.weight = weight
            tab.pressProgress = pressProgress * weight
        }
        applySecondaryShape()
    }

    // ---------------------------------------------------------------- 水银融合

    /**
     * 按压或横向拖拽时，把透镜矩形作为 `setSecondaryShape` 的第二个胶囊
     * 塞进底栏玻璃的一次折射 Pass：两块形状做 `smin` 平滑并集，
     * 透镜与轨道之间会拉出一段"水银粘连"的过渡脊。
     *
     * 静止（`pressProgress <= 0.01`）时传 null 恢复清澈双层轮廓。
     * 位移 / 平滑半径变化不足阈值时不下发，避免给基类制造无意义重绘。
     */
    private fun applySecondaryShape() {
        val glassView = glass as? LiquidGlassView ?: return
        if (pressProgress <= 0.01f || !metricsReady ||
            glass.width <= 0 || glass.height <= 0
        ) {
            if (secondaryShapeActive) {
                secondaryShapeActive = false
                lastShapeX = Float.NaN
                lastShapeY = Float.NaN
                lastShapeSmooth = Float.NaN
                glassView.setSecondaryShape(null, 0f, 0f)
            }
            return
        }

        lens.currentRect(lensRect)
        // 透镜与玻璃同为 container 的直接子 View，两者 translationX 相同，直接相减即可
        lensRect.offset(
            (lens.left - glass.left).toFloat(),
            (lens.top - glass.top).toFloat()
        )
        val smooth = 14f * density * pressProgress
        if (secondaryShapeActive &&
            abs(lensRect.centerX() - lastShapeX) < 0.5f &&
            abs(lensRect.centerY() - lastShapeY) < 0.5f &&
            abs(smooth - lastShapeSmooth) < 0.05f
        ) {
            return
        }
        secondaryShapeActive = true
        lastShapeX = lensRect.centerX()
        lastShapeY = lensRect.centerY()
        lastShapeSmooth = smooth
        glassView.setSecondaryShape(lensRect, lensRect.height() / 2f, smooth)
    }

    private fun applyLens() {
        applyValue(value)
        applyTrackOffsets()
    }

    private fun applyTrackOffsets() {
        glass.translationX = panelOffset
        tabHost.translationX = panelOffset + rubberOffset
        lens.translationX = panelOffset
        lens.invalidate()
    }

    private fun animateValue(target: Float, spring: SpringForce) {
        valueSpring?.cancel()
        valueSpring = SpringAnimation(FloatValueHolder(value)).apply {
            setSpring(spring)
            addUpdateListener { _, v, _ ->
                value = v
                applyValue(v)
            }
            start()
        }
    }

    private fun animatePress(target: Float, damping: Float, stiffness: Float) {
        pressSpring?.cancel()
        pressSpring = SpringAnimation(FloatValueHolder(pressProgress)).apply {
            setSpring(SpringForce(target).apply {
                dampingRatio = damping
                this.stiffness = stiffness
            })
            addUpdateListener { _, v, _ ->
                pressProgress = v.coerceIn(0f, 1f)
                (glass as? LiquidGlassView)?.dispersionStrength = 0.10f * pressProgress
                applyGlassPress()
                applyValue(value)
            }
            start()
        }
    }

    /**
     * 轨道随按压轻微呼吸：加宽最多 16dp，再叠加透镜形变的一小部分。
     * 与 capybara 的 `pressScale * (1 + indicatorBoost * 0.18)` 同式。
     */
    private fun applyGlassPress() {
        if (glass.width <= 0) return
        val gain = (dp(NavMetrics.PRESS_GAIN_DP) / glass.width).coerceIn(0f, 0.2f)
        val pressScale = 1f + gain * pressProgress
        val indicatorBoost = (lens.pressScaleAverage() - 1f).coerceIn(0f, 0.4f)
        val scale = pressScale * (1f + indicatorBoost * 0.18f)
        glass.pivotX = glass.width / 2f
        glass.pivotY = glass.height / 2f
        glass.scaleX = scale
        glass.scaleY = scale
    }

    private fun setHiddenTab(@Suppress("UNUSED_PARAMETER") index: Int) = Unit

    private fun restoreHiddenTab() = Unit

    private fun applyRubberBand(raw: Float) {
        val clamped = raw.coerceIn(0f, (tabs.size - 1).toFloat())
        val overshoot = (raw - clamped) * (widths.minOrNull() ?: 1f)
        rubberOffset =
            overshoot.coerceIn(-dp(NavMetrics.RUBBER_DP), dp(NavMetrics.RUBBER_DP)) * 0.8f
        // 拖动时整条轨道跟着手指侧移 4dp，用 EaseOut 收尾，读作"被拽动"
        val widthPx = container.width.coerceAtLeast(1).toFloat()
        val fraction = (overshoot / widthPx).coerceIn(-1f, 1f)
        panelOffset = dp(NavMetrics.PANEL_OFFSET_DP) * fraction.sign * easeOut(abs(fraction))
        applyTrackOffsets()
    }

    private fun resetRubberBand() {
        rubberSpring?.cancel()
        rubberSpring = SpringAnimation(FloatValueHolder(rubberOffset)).apply {
            setSpring(SpringForce(0f).apply {
                dampingRatio = 0.6f
                stiffness = 400f
            })
            addUpdateListener { _, v, _ ->
                rubberOffset = v
                applyTrackOffsets()
            }
            start()
        }
    }

    private fun resetPanelOffset() {
        panelSpring?.cancel()
        panelSpring = SpringAnimation(FloatValueHolder(panelOffset)).apply {
            setSpring(SpringForce(0f).apply {
                dampingRatio = 0.66f
                stiffness = 320f
            })
            addUpdateListener { _, v, _ ->
                panelOffset = v
                applyTrackOffsets()
            }
            start()
        }
    }

    private fun easeOut(x: Float): Float {
        val inv = 1f - x.coerceIn(0f, 1f)
        return 1f - inv * inv * inv
    }

    private fun roundValue(v: Float): Int =
        v.roundToInt().coerceIn(0, (tabs.size - 1).coerceAtLeast(0))

    // ---------------------------------------------------------------- 图标动作

    /**
     * 播放一格的图标动作。时钟 0→1 线性推进 480ms；渲染器在两端姿态一致、
     * 速度为零，所以中途重播不会跳帧。
     */
    private fun playIcon(index: Int) {
        if (index !in tabs.indices) return
        iconAnims[index]?.cancel()
        val tab = tabs[index]
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = NavMetrics.ICON_ACTION_MS
            addUpdateListener {
                tab.iconPhase = it.animatedValue as Float
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    tab.iconPhase = 1f
                    if (iconAnims[index] === animation) iconAnims[index] = null
                }
            })
        }
        iconAnims[index] = anim
        anim.start()
    }

    // ---------------------------------------------------------------- 透镜

    private inner class LensView(context: Context) : View(context) {

        private var centerX = 0f
        private var baseWidth = 0f
        private var baseHeight = 0f
        private var press = 0f
        private var jellyX = 0f
        private var jellyY = 0f

        /** 复用几何矩形：onDraw 与 setSecondaryShape 共用，避免每帧 new RectF。 */
        private val drawRect = RectF()

        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            setWillNotDraw(false)
        }

        fun setBase(centerX: Float, width: Float, height: Float) {
            this.centerX = centerX
            this.baseWidth = width
            this.baseHeight = height
        }

        fun setPress(value: Float) {
            press = value.coerceIn(0f, 1f)
            updateJelly()
        }

        fun pressScaleAverage(): Float = (scaleXOf() + scaleYOf()) / 2f

        /** 当前透镜绘制矩形（父容器坐标），与水银融合、绘制共用同一套几何。 */
        fun currentRect(out: RectF) {
            if (baseWidth <= 0f || width <= 0f) {
                out.setEmpty()
                return
            }
            val lensWidth = baseWidth * scaleXOf()
            val lensHeight = baseHeight * scaleYOf()
            // 放大必须**关于中心对称**：capybara 的 scale 作用在 graphicsLayer 上，
            // 56dp 的胶囊按下涨到 78dp 时上下各溢出 11dp。曾经这里锚在底边，
            // 按下时整块往下长，底部溢出轨道、顶部留空，观感是"沉下去"而不是"鼓起来"。
            val centerY = height / 2f
            val half = lensWidth / 2f
            // 布局未完成时 width 可能小于透镜宽度，coerceIn 的上下界会翻转直接抛异常
            val cx = if (width >= lensWidth) {
                centerX.coerceIn(half, width - half)
            } else {
                width / 2f
            }
            out.set(
                cx - half,
                centerY - lensHeight / 2f,
                cx + half,
                centerY + lensHeight / 2f
            )
        }

        private fun updateJelly() {
            val tracker = velocityTracker
            val vx = tracker?.xVelocity ?: 0f
            val tabWidth = (widths.minOrNull() ?: 1f).coerceAtLeast(1f)
            // ## 为什么除 tabWidth 之后还要再除一次
            //
            // `VelocityTracker` 给的是**像素/秒**（拖动时常见 2000~6000），
            // 除 tabWidth（≈300px）之后仍有 7~20。capybara 的公式写的是
            // `velocity / tabWidth / 10`，那里的 velocity 已经是"格/秒"
            // （DampedDragAnimation 内部除以了 tabWidth），所以它只除一次。
            //
            // 照抄时漏了这一层，结果 v 永远是 12 上下，`v*0.75` 直接顶满 ±0.22
            // 的限幅，横向拉伸恒为 1/(1-0.22) ≈ 1.28 —— 拖动时透镜被拉成一格半宽，
            // 盖住旁边两格。这里补上 ×1000ms 的换算，让"一格/秒"对应 v≈1。
            val v = (vx / tabWidth) * (16f / 1000f)
            jellyX = (v * 0.75f).coerceIn(-0.22f, 0.22f)
            jellyY = (v * 0.25f).coerceIn(-0.22f, 0.22f)
            invalidate()
        }

        private fun scaleXOf(): Float =
            (1f + (NavMetrics.PRESSED_SCALE - 1f) * press) /
                (1f - jellyX).coerceAtLeast(0.8f)

        private fun scaleYOf(): Float =
            (1f + (NavMetrics.PRESSED_SCALE - 1f) * press) *
                (1f - jellyY).coerceAtLeast(0.8f)

        override fun onDraw(canvas: Canvas) {
            if (baseWidth <= 0f) return
            currentRect(drawRect)
            GlassLensRenderer.drawInnerLens(
                canvas = canvas,
                context = context,
                left = drawRect.left,
                top = drawRect.top,
                right = drawRect.right,
                bottom = drawRect.bottom,
                radius = drawRect.height() / 2f,
                density = density,
                dispersion = press,
                // 底栏下面压着模糊壁纸：透镜按 capybara 的 0.34 白档走薄玻璃，
                // 否则整块实白把边缘折射与壁纸全盖住，和"液态玻璃"正好相反。
                fillAlphaScale = 0.42f
            )

            // 透镜只画玻璃本身，**不再复制 Tab 内容**。
            //
            // ## 为什么砍掉"透镜里的着色副本"
            //
            // capybara 的做法是：按下时把原 Tab 隐藏，再把一份着色副本画进透镜，
            // 让文字看起来"被吸进玻璃"。照搬过来连出两次事故：
            // 1. 用 `Bitmap + View.draw()` 截图 —— View 挂上硬件渲染节点后，
            //    `draw()` 可能拿到上一帧的层内容，副本是未选中灰且带旧位移；
            // 2. 改成同源重绘后，副本干净了，但**同一份内容仍被画了两遍**，
            //    第二份偏移十几 dp 并带模糊（实测把副本染红后，红色字形右边和
            //    下方各拖出一团模糊红影）。
            //
            // 而这份副本本来就不必要：透镜的 translationZ 是 1、tabHost 是 2，
            // **Tab 内容本来就画在透镜之上**。隐藏 + 重画只是在同一个位置把
            // 同样的字形画两遍，多出来的那一份只能变成重影。
            //
            // 现在：透镜只负责玻璃（渐变填充 + 镜面外圈 + 按压色散），Tab 内容
            // 照常在最上层显示，按下时按权重点亮为强调色。观感一致，且没有
            // 任何"两份内容对不齐"的可能。
        }
    }

    // ---------------------------------------------------------------- 手势层

    private inner class ScrubView(context: Context) : View(context) {

        init {
            isClickable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            setBackgroundColor(0x00000000)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> onDown(event)
                MotionEvent.ACTION_MOVE -> onMove(event)
                MotionEvent.ACTION_UP -> {
                    onUp()
                    performClick()
                }
                MotionEvent.ACTION_CANCEL -> onUp()
            }
            forwardToGlass(event)
            return true
        }

        override fun performClick(): Boolean = super.performClick()

        private fun forwardToGlass(event: MotionEvent) {
            val copy = MotionEvent.obtain(event)
            copy.offsetLocation(
                (left - glass.left).toFloat(),
                (top - glass.top).toFloat()
            )
            glass.onTouchEvent(copy)
            copy.recycle()
        }
    }
}
