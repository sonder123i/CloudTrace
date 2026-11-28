package com.zcshou.gogogo.run.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.ui.glass.StaticBackdropProvider
import kotlin.math.sin

/**
 * V6.0 L0 · 冷银建筑画布（Optical Reference Canvas）。
 *
 * 静态绘制五层，全部只在尺寸 / 主题变化时重建，滚动或巡航期间 0 重绘：
 * 1. 冷银白建筑柔光渐变底（浅色 `#F8FAFC → #E2E8F0` / 深色深空岩灰）；
 * 2. 一抹冷银冰蓝弥散光斑（浅色 24% `#DBEAFE`，深色冰蓝微光）；
 * 3. 左下角极淡冷银反光，与冰蓝光斑形成冷调层次；
 * 4. 冷银灰极简几何网格（72dp 间距，4%~6% 透明度），作为液态玻璃边缘折射的参照物；
 * 5. 5%~7% 透明度地形等高线矢量暗纹（左上缓坡，不干扰文字）。
 *
 * 网格与等高线不是装饰：玻璃边缘的 SDF 折射需要高频参照物才能看出真实形变。
 * 性能：无位图截取、无 RenderEffect 实时模糊，绘制成本恒定为 5 层静态填充。
 */
class BathymetricBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private val canvasPaint = Paint()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val contourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.7f * density
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.6f * density
    }

    private var baseGradient: Shader? = null
    private var iceGlow: Shader? = null
    private var silverGlow: Shader? = null
    private val contourPath = Path()
    private val gridPath = Path()

    init {
        loadPalette()
    }

    /** 昼夜 Token 只在构建与主题翻转时读取，onDraw 里 0 资源查询。 */
    private fun loadPalette() {
        canvasPaint.color = context.getColor(R.color.pf_canvas)
        contourPaint.color = context.getColor(R.color.dr_l0_contour)
        gridPaint.color = context.getColor(R.color.dr_l0_grid)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 向共享背景池登记：整页画布只离屏渲染一次，供所有玻璃卡片采样
        StaticBackdropProvider.attach(this) { canvas -> draw(canvas) }
    }

    override fun onDetachedFromWindow() {
        StaticBackdropProvider.detach(this)
        super.onDetachedFromWindow()
    }

    /**
     * 主题翻转：重读 Token → 重建渐变 → 作废快照。
     * 需要在同一 Activity 内切换深浅色时由宿主显式调用。
     */
    fun onThemeChanged() {
        loadPalette()
        rebuildShaders()
        StaticBackdropProvider.invalidate(this)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShaders()
        // 尺寸变了：旧快照的网格与光斑位置全部失效
        StaticBackdropProvider.invalidate(this)
    }

    private fun rebuildShaders() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        baseGradient = LinearGradient(
            0f, 0f, w * 0.28f, h.toFloat(),
            intArrayOf(
                context.getColor(R.color.dr_l0_silver_top),
                context.getColor(R.color.dr_l0_silver_bottom)
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        // 冷银冰蓝：右上大半径弥散光斑，让玻璃卡片透出冷调层次
        iceGlow = RadialGradient(
            w * 0.92f, h * 0.04f, w * 0.86f,
            intArrayOf(context.getColor(R.color.dr_l0_ice_glow), 0x00DBEAFE),
            null, Shader.TileMode.CLAMP
        )
        // 冷银反光：左下角，强化冷调纵深
        silverGlow = RadialGradient(
            w * 0.04f, h * 0.78f, w * 0.72f,
            intArrayOf(context.getColor(R.color.dr_l0_silver_glow), 0x00FFFFFF),
            null, Shader.TileMode.CLAMP
        )
        buildContours(w, h)
        buildGrid(w, h)
    }

    /** 冷银灰几何网格：作为玻璃 SDF 折射的清晰参照物。 */
    private fun buildGrid(w: Int, h: Int) {
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
    }

    /**
     * 等高线：一组从左下向右上抬升的缓坡曲线，间隔随高度递增。
     * 用二次贝塞尔近似等深线，避免逐像素计算。
     */
    private fun buildContours(w: Int, h: Int) {
        contourPath.reset()
        val step = 46f * density
        var y = h * 0.12f
        var index = 0
        while (y < h * 1.05f) {
            val amp = (10f + index % 3 * 5f) * density
            val phase = index * 0.72f
            contourPath.moveTo(-w * 0.05f, y)
            var x = -w * 0.05f
            while (x < w * 1.05f) {
                val nx = x + w * 0.22f
                val cy = y + sin(phase + x / (w * 0.28f)) * amp
                contourPath.quadTo((x + nx) / 2f, cy, nx, y + sin(phase + nx / (w * 0.28f)) * amp * 0.6f)
                x = nx
            }
            y += step
            index++
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), canvasPaint)
        baseGradient?.let {
            basePaint.shader = it
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), basePaint)
        }
        silverGlow?.let {
            glowPaint.shader = it
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glowPaint)
        }
        iceGlow?.let {
            glowPaint.shader = it
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glowPaint)
        }
        glowPaint.shader = null
        basePaint.shader = null
        if (width > 0 && height > 0) {
            canvas.drawPath(gridPath, gridPaint)
            canvas.drawPath(contourPath, contourPaint)
        }
    }
}
