package com.zcshou.gogogo.run.ui.glass

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import com.elvishew.xlog.XLog
import com.zcshou.gogogo.R

/**
 * V6.4 Z 轴四层光学材质容器（静态画布共享折射 + 零逐帧截屏）。
 *
 * - L1 [lgMatte]：92% 高密度哑光陶瓷 + 0.5dp 极细内描边，用于次级信息容器；
 * - L2 [lgPrism]：AGSL 透镜（API 33+ / 光学档）或对角线微光泽 + 棱镜虹彩描边；
 * - L3 [lgLens]：高透悬浮透镜，用于导航胶囊/弹窗底板，防文字穿透；
 * - 默认：V4 静态磨砂 + 顶部高光，兼容旧调用点。
 *
 * ## V6.4 光学管线
 *
 * 当设备满足「API 33+ · 光学档 · 未省电 · 未巡航冻结」且视图树里存在
 * [StaticBackdropProvider] 登记的静态画布时，本容器不再画纯色，而是走
 * [ContainerOptics]：把**自身相对画布根容器的偏移**作为 Uniform 喂给预缓存的
 * 静态画布纹理，单 Pass 完成圆角 SDF 折射 + 中心柔焦 + 边缘色散。
 * 列表滚动 60~120fps 时不重新截屏任何内容。
 *
 * 不满足条件（低版本 / 省电平衡 / 系统省电 / 高对比度 / 无静态画布）时，
 * 自动无缝退回零采样静态材质 —— 低端机零卡顿。
 *
 * 性能铁律：`draw()` 内不创建 Paint / Path / Shader / Rect / Matrix；
 * 所有形状与渐变都在尺寸、圆角、主题变化时重建。
 */
class LiquidGlassContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shapePath = Path()
    private val shapeRect = RectF()

    /** 复用窗口坐标数组：`getLocationInWindow` 每次都会写满它，无需新建。 */
    private val selfLocation = IntArray(2)
    private val hostLocation = IntArray(2)

    private val minOpticalSize = 24f * density

    private var cornerRadiusPx = 24f * density
    private var bevelWidthPx = 8f * density
    private var interactive = true
    private var matte = false
    private var prism = false
    private var lens = false

    private var tintColor: Int = 0
    private var hasExplicitTint = false
    private var rimShadowColor: Int = 0

    // L1 / L2 / L3 主题色缓存（onGlassEnvironmentChanged 时刷新）
    private var matteSurface = 0
    private var prismTop = 0
    private var prismBottom = 0
    private var lensSurface = 0
    private var opticalSurface = 0
    private var isNightTheme = false

    // 尺寸相关 Shader 缓存：只在 onSizeChanged / 主题变化时重建
    private var prismFillShader: Shader? = null
    private var prismGlossShader: Shader? = null
    private var frostedShadowShader: Shader? = null

    // AGSL 透镜
    private var optics: ContainerOptics? = null
    private var opticsResolved = false
    private var opticsBroken = false

    private var pressAnimator: ValueAnimator? = null

    init {
        val ta = context.obtainStyledAttributes(attrs, R.styleable.LiquidGlassContainer)
        cornerRadiusPx = ta.getDimension(
            R.styleable.LiquidGlassContainer_lgCornerRadius, 24f * density
        )
        bevelWidthPx = ta.getDimension(
            R.styleable.LiquidGlassContainer_lgBevelWidth, 8f * density
        )
        interactive = ta.getBoolean(R.styleable.LiquidGlassContainer_lgInteractive, true)
        hasExplicitTint = ta.hasValue(R.styleable.LiquidGlassContainer_lgTint)
        tintColor = ta.getColor(
            R.styleable.LiquidGlassContainer_lgTint,
            context.getColor(R.color.dr_glass_surface_tint)
        )
        matte = ta.getBoolean(R.styleable.LiquidGlassContainer_lgMatte, false)
        prism = ta.getBoolean(R.styleable.LiquidGlassContainer_lgPrism, false)
        lens = ta.getBoolean(R.styleable.LiquidGlassContainer_lgLens, false)
        ta.recycle()

        loadThemeColors()
        applySoftShadow()

        setWillNotDraw(false)
        isClickable = interactive
        isFocusable = interactive
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, safeRadius())
            }
        }
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        DeepRunGlassManager.register(this)
    }

    override fun onDetachedFromWindow() {
        DeepRunGlassManager.unregister(this)
        pressAnimator?.cancel()
        pressAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShape()
        rebuildSizeShaders()
        invalidateOutline()
        invalidate()
    }

    // ---------------------------------------------------------------- 对外 API

    fun onGlassEnvironmentChanged() {
        loadThemeColors()
        rebuildSizeShaders()
        if (!hasExplicitTint) {
            tintColor = context.getColor(R.color.dr_glass_surface_tint)
        }
        invalidateOutline()
        invalidate()
    }

    /** 保留 API 兼容：静态材质无需重采样。 */
    fun refreshBackdrop() = Unit

    /** 保留 API 兼容：不再使用外部位图作为玻璃背景。 */
    fun setExternalBackdrop(bitmap: Bitmap, originX: Float, originY: Float, scale: Float) = Unit

    fun clearExternalBackdrop() = Unit

    fun setGlassCornerRadius(px: Float) {
        cornerRadiusPx = px.coerceAtLeast(0f)
        rebuildShape()
        rebuildSizeShaders()
        invalidateOutline()
        invalidate()
    }

    fun setGlassBevelWidth(px: Float) {
        bevelWidthPx = px.coerceAtLeast(0f)
        invalidate()
    }

    fun setGlassTint(color: Int) {
        tintColor = color
        hasExplicitTint = true
        invalidate()
    }

    fun setInteractiveGlass(enabled: Boolean) {
        interactive = enabled
        isClickable = enabled
    }

    fun setGlassLevel(matteLevel: Boolean, prismLevel: Boolean, lensLevel: Boolean) {
        matte = matteLevel
        prism = prismLevel
        lens = lensLevel
        invalidate()
    }

    // ---------------------------------------------------------------- 绘制

    override fun draw(canvas: Canvas) {
        if (width > 0 && height > 0) {
            // 光学管线优先；任何一步不满足条件都退回静态材质
            if (!drawOptical(canvas)) {
                when {
                    matte -> drawMatteSurface(canvas)
                    prism -> drawPrismSurface(canvas)
                    lens -> drawLensSurface(canvas)
                    else -> drawFrostedSurface(canvas)
                }
            }
        }
        super.draw(canvas)
    }

    /**
     * 静态画布共享折射。只有「圆角矩形形状 + 相对画布偏移」两个变量进入着色器，
     * 滚动时零截屏。返回 false 表示本帧不走光学管线。
     *
     * 三个层级都参与折射，但用染色不透明度与色散拉开 Z 轴：
     * - [matte] 辅助条：92% 染色（更实、无色散），只保留边缘透镜；
     * - [prism] 核心卡：80% 染色 + 棱镜微色散；
     * - [lens] / 默认：80% 染色 + 极轻色散。
     */
    private fun drawOptical(canvas: Canvas): Boolean {
        if (width < minOpticalSize || height < minOpticalSize) return false
        if (opticsBroken) return false
        if (!DeepRunGlassManager.isOpticalPipelineActive()) return false
        val snapshot = StaticBackdropProvider.snapshotFor(this) ?: return false
        val shader = resolveOptics() ?: return false

        getLocationInWindow(selfLocation)
        snapshot.host.getLocationInWindow(hostLocation)
        val originX = (selfLocation[0] - hostLocation[0]).toFloat()
        val originY = (selfLocation[1] - hostLocation[1]).toFloat()

        val tint = when {
            hasExplicitTint -> tintColor
            // 辅助条保持"实体"读感：从旧版 92% 降到 88%，让背后画布的冷银光斑
            // 透出一点点，同时文字对比度不打折 —— 这是 Z 轴景深的下层
            matte -> ColorUtils.setAlphaComponent(matteSurface, 0xE2)
            else -> opticalSurface
        }
        val dispersion = when {
            prism -> 0.10f
            matte -> 0f
            else -> 0.035f
        }

        return try {
            shader.draw(
                canvas = canvas,
                view = this,
                snapshot = snapshot,
                originX = originX,
                originY = originY,
                radius = safeRadius(),
                tint = tint,
                bevel = bevelWidthPx,
                dispersion = dispersion,
                night = isNightTheme
            )
        } catch (t: Throwable) {
            // 任意 ROM 的 AGSL 兼容异常：永久回退静态材质，绝不反复抛
            opticsBroken = true
            XLog.w("GLASS: AGSL container optics disabled (${t.javaClass.simpleName}: ${t.message})")
            false
        }
    }

    private fun resolveOptics(): ContainerOptics? {
        if (opticsResolved) return optics
        opticsResolved = true
        val created = ContainerOpticsFactory.create()
        if (created == null) opticsBroken = true
        optics = created
        return created
    }

    private fun rebuildShape() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            shapePath.reset()
            return
        }
        shapeRect.set(0f, 0f, w, h)
        shapePath.reset()
        shapePath.addRoundRect(shapeRect, safeRadius(), safeRadius(), Path.Direction.CW)
    }

    /** 尺寸相关的两条渐变（L2 对角线受光 / 兼容层底部内阴影）。 */
    private fun rebuildSizeShaders() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            prismFillShader = null
            prismGlossShader = null
            frostedShadowShader = null
            return
        }
        prismFillShader = LinearGradient(
            0f, 0f, w, h,
            prismTop, prismBottom, Shader.TileMode.CLAMP
        )
        prismGlossShader = LinearGradient(
            0f, 0f, w * 0.62f, h * 0.5f,
            intArrayOf(0x24FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        frostedShadowShader = LinearGradient(
            0f, h * 0.75f, 0f, h,
            0x00000000, rimShadowColor, Shader.TileMode.CLAMP
        )
    }

    /** L1 · 哑光陶瓷：高密度磨砂 + 1.5dp 冷银白镜面切边。 */
    private fun drawMatteSurface(canvas: Canvas) {
        fillPaint.shader = null
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = if (hasExplicitTint) tintColor else matteSurface
        canvas.drawPath(shapePath, fillPaint)

        GlassLensRenderer.drawCardRim(
            canvas,
            context,
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            safeRadius(),
            density
        )
    }

    /** L2 · 棱镜液态玻璃：对角线受光渐变 + 虹彩描边 + 顶部天光。 */
    private fun drawPrismSurface(canvas: Canvas) {
        fillPaint.style = Paint.Style.FILL
        if (hasExplicitTint) {
            fillPaint.shader = null
            fillPaint.color = tintColor
        } else {
            fillPaint.shader = prismFillShader
        }
        canvas.drawPath(shapePath, fillPaint)

        // 曲面微光泽：左上角一小片对角线高光，模拟斜射光在微凸玻璃上的反射
        fillPaint.shader = prismGlossShader
        canvas.drawPath(shapePath, fillPaint)
        fillPaint.shader = null

        // 1.5dp 冷银白镜面切边（顶部亮、底部微透）
        GlassLensRenderer.drawCardRim(
            canvas,
            context,
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            safeRadius(),
            density
        )
    }

    /** L3 · 高透悬浮透镜：高透底色 + 上下 1dp 银白锐利高光。 */
    private fun drawLensSurface(canvas: Canvas) {
        fillPaint.shader = null
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = if (hasExplicitTint) tintColor else lensSurface
        canvas.drawPath(shapePath, fillPaint)
        GlassLensRenderer.drawOuterCapsuleRim(
            canvas,
            context,
            width,
            height,
            safeRadius(),
            density
        )
    }

    /** 兼容层：V4 静态磨砂 + 顶部高光。 */
    private fun drawFrostedSurface(canvas: Canvas) {
        fillPaint.shader = null
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = tintColor
        canvas.drawPath(shapePath, fillPaint)

        fillPaint.shader = frostedShadowShader
        fillPaint.alpha = 0x12
        canvas.drawPath(shapePath, fillPaint)
        fillPaint.shader = null
        fillPaint.alpha = 255
        GlassLensRenderer.drawCardRim(
            canvas,
            context,
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            safeRadius(),
            density
        )
    }

    private fun safeRadius(): Float =
        cornerRadiusPx.coerceIn(0f, minOf(width, height) / 2f)

    private fun loadThemeColors() {
        rimShadowColor = context.getColor(R.color.dr_glass_rim_shadow)
        matteSurface = context.getColor(R.color.dr_l1_matte_surface)
        prismTop = context.getColor(R.color.dr_l2_glass_top)
        prismBottom = context.getColor(R.color.dr_l2_glass_bottom)
        lensSurface = context.getColor(R.color.dr_l3_lens_surface)
        opticalSurface = context.getColor(R.color.dr_l3_optical_surface)
        isNightTheme =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }

    /** L2 弥散阴影：≤8% 透明度，大半径柔和，不产生硬边。 */
    private fun applySoftShadow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            outlineAmbientShadowColor = 0x0D000000
            outlineSpotShadowColor = 0x14000000
        }
    }

    // ---------------------------------------------------------------- 按压反馈

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
        if (interactive) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> animatePress(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animatePress(false)
            }
        }
        return handled
    }

    /** Liquid Press：按下微缩，松手过冲回弹（纯变换，不触发重绘）。 */
    private fun animatePress(pressed: Boolean) {
        pressAnimator?.cancel()
        val target = if (pressed) 0.97f else 1f
        pressAnimator = ValueAnimator.ofFloat(scaleX, target).apply {
            duration = if (pressed) 120L else 280L
            interpolator = if (pressed) DecelerateInterpolator() else OvershootInterpolator(1.6f)
            addUpdateListener {
                val value = it.animatedValue as Float
                scaleX = value
                scaleY = value
            }
            start()
        }
    }
}
