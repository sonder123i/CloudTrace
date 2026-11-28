package com.zcshou.gogogo.run.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.graphics.ColorUtils
import com.zcshou.gogogo.R

/**
 * 干净磨砂玻璃底片：静态材质，不含任何彩色扫光。
 *
 * 悬浮窗跑在 `TYPE_APPLICATION_OVERLAY` 里，既不能开 `FLAG_BLUR_BEHIND`
 * （Android 12+ 会生成全屏 Dim Layer，输入分发器把窗外触摸全判为 untrusted，
 * 表现为「悬浮窗把下层页面卡死」），也采不到下层 App 的像素。所以玻璃的「厚度」
 * 只能靠材质本身画出来：
 *
 * 1. 底色：竖向微渐变（顶部提亮 6%），避免纯色平板感；
 * 2. 镜面高光：顶部 45% 高度的白色渐变，形成受光的上边缘；
 * 3. 内阴影：底部 30% 轻微压暗，压出体积；
 * 4. 描边：1dp 冷银边 + 顶部 1px 冰青亮线。
 *
 * 彩色只留给边缘流光（见 [BorderBeamDrawable]）——填充保持中性，
 * 这样白磨砂才干净，彩色不会被摊薄到整块玻璃上。
 *
 * 颜色全部取自 `dr_overlay_*` Token，随主题昼夜翻转
 * （浅色「冰晶白磨砂」/ 深色「深空黑曜石」）。
 */
class PrismGlassDrawable @JvmOverloads constructor(
    private val context: Context,
    private val cornerRadiusPx: Float,
    /** 告警态：底色换成暖白/深蓝，其余结构不变。 */
    private val warn: Boolean = false
) : Drawable() {

    private val density = context.resources.displayMetrics.density

    private val rect = RectF()
    private val shape = Path()
    private val outerRimShape = Path()
    private val innerRimShape = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.85f * density
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    private var alphaScale = 1f

    private val surface = context.getColor(
        if (warn) R.color.dr_overlay_surface_warn else R.color.dr_overlay_surface
    )
    private val specular = context.getColor(R.color.dr_prism_specular)
    private val rimOuter = context.getColor(R.color.dr_overlay_rim_outer)
    private val rimSilver = context.getColor(R.color.dr_overlay_rim_silver)
    private val rimIce = context.getColor(R.color.dr_overlay_rim_ice)

    /**
     * 深色底上镜面高光必须收着画。
     *
     * 同一套 alpha 叠在白底和黑曜石上效果完全不同：白底需要较强高光才显厚度，
     * 黑底上大面积镜面高光会把整块面板冲成浅灰，深空质感全丢。
     * 这里按底色亮度反比缩放——底色越暗，叠层越轻。
     */
    private val isDarkSurface: Boolean = runCatching {
        val r = Color.red(surface) / 255f
        val g = Color.green(surface) / 255f
        val b = Color.blue(surface) / 255f
        (0.213f * r + 0.715f * g + 0.072f * b) < 0.5f
    }.getOrDefault(false)

    private val specularFactor = if (isDarkSurface) 0.24f else 0.9f
    private val innerShadowFactor = if (isDarkSurface) 0.4f else 0.85f

    private var cachedLeft = Int.MIN_VALUE
    private var cachedTop = Int.MIN_VALUE
    private var cachedRight = Int.MIN_VALUE
    private var cachedBottom = Int.MIN_VALUE
    private var cachedAlpha = -1f
    private var cachedRadius = 0f
    private var baseShader: LinearGradient? = null
    private var specShader: LinearGradient? = null
    private var shadowShader: LinearGradient? = null
    private var rimShader: LinearGradient? = null
    private var sheenShader: LinearGradient? = null

    private fun ensureCache(b: android.graphics.Rect) {
        if (b.left == cachedLeft && b.top == cachedTop &&
            b.right == cachedRight && b.bottom == cachedBottom &&
            alphaScale == cachedAlpha
        ) {
            return
        }
        cachedLeft = b.left
        cachedTop = b.top
        cachedRight = b.right
        cachedBottom = b.bottom
        cachedAlpha = alphaScale

        rect.set(b)
        val radius = cornerRadiusPx.coerceAtMost(minOf(b.width(), b.height()) / 2f)
        cachedRadius = radius
        shape.reset()
        shape.addRoundRect(rect, radius, radius, Path.Direction.CW)

        outerRimShape.reset()
        val outerInset = outerRimPaint.strokeWidth / 2f
        outerRimShape.addRoundRect(
            rect.left + outerInset, rect.top + outerInset, rect.right - outerInset, rect.bottom - outerInset,
            (radius - outerInset).coerceAtLeast(0f), (radius - outerInset).coerceAtLeast(0f),
            Path.Direction.CW
        )

        innerRimShape.reset()
        val innerInset = outerRimPaint.strokeWidth + rimPaint.strokeWidth / 2f
        innerRimShape.addRoundRect(
            rect.left + innerInset, rect.top + innerInset, rect.right - innerInset, rect.bottom - innerInset,
            (radius - innerInset).coerceAtLeast(0f), (radius - innerInset).coerceAtLeast(0f),
            Path.Direction.CW
        )

        val bLeft = b.left.toFloat()
        val bTop = b.top.toFloat()
        val bBottom = b.bottom.toFloat()
        val h = b.height().toFloat()
        val diagX = bLeft + b.width() * 0.25f

        baseShader = LinearGradient(
            bLeft, bTop, diagX, bBottom,
            intArrayOf(
                withAlpha(ColorUtils.blendARGB(surface, Color.WHITE, if (isDarkSurface) 0.04f else 0.08f), 1f),
                withAlpha(surface, 1f),
                withAlpha(ColorUtils.blendARGB(surface, Color.BLACK, if (isDarkSurface) 0.06f else 0.02f), 1f)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        specShader = LinearGradient(
            bLeft, bTop, bLeft, bTop + h * 0.36f,
            withAlpha(specular, 0.65f * specularFactor), Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        shadowShader = LinearGradient(
            bLeft, bTop + h * 0.72f, bLeft, bBottom,
            Color.TRANSPARENT, withAlpha(Color.BLACK, 0.14f * innerShadowFactor),
            Shader.TileMode.CLAMP
        )
        rimShader = LinearGradient(
            bLeft, bTop, bLeft, bBottom,
            intArrayOf(
                withAlpha(rimSilver, 0.95f),
                withAlpha(rimIce, 0.75f),
                withAlpha(rimSilver, 0.22f)
            ),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP
        )
        sheenShader = if (h > 14f * density && b.right - b.left > 2.2f * radius) {
            val y = b.top + 1.2f * density
            LinearGradient(
                b.left + radius, y, b.right - radius, y,
                intArrayOf(Color.TRANSPARENT, withAlpha(rimIce, 0.8f), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            null
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        ensureCache(b)

        val save = canvas.save()
        canvas.clipPath(shape)

        paint.shader = baseShader
        canvas.drawRect(rect, paint)

        paint.shader = specShader
        canvas.drawRect(rect, paint)

        paint.shader = shadowShader
        canvas.drawRect(rect, paint)

        paint.shader = null
        canvas.restoreToCount(save)

        // 双层倒角描边：外圈冷静微边界 + 内圈顶部受光高光环，增强玻璃切角层次
        outerRimPaint.color = withAlpha(rimOuter, 1f)
        canvas.drawPath(outerRimShape, outerRimPaint)

        rimPaint.shader = rimShader
        canvas.drawPath(innerRimShape, rimPaint)
        rimPaint.shader = null

        val topSheen = sheenShader
        if (topSheen != null) {
            val y = b.top + 1.2f * density
            sheenPaint.shader = topSheen
            canvas.drawLine(b.left + cachedRadius, y, b.right - cachedRadius, y, sheenPaint)
            sheenPaint.shader = null
        }
    }

    private fun withAlpha(color: Int, factor: Float): Int =
        ColorUtils.setAlphaComponent(
            color,
            (Color.alpha(color) * factor * alphaScale).toInt().coerceIn(0, 255)
        )

    override fun setAlpha(alpha: Int) {
        alphaScale = (alpha / 255f).coerceIn(0f, 1f)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** 给 View 套上磨砂玻璃底片（若为 CapsuleShellView 同步写入圆角阴影轮廓）。 */
        fun attach(view: View, cornerRadiusDp: Float, warn: Boolean = false) {
            val radius = cornerRadiusDp * view.resources.displayMetrics.density
            if (view is CapsuleShellView) {
                view.setOutlineCornerRadius(radius)
            }
            view.background = PrismGlassDrawable(view.context, radius, warn)
        }
    }
}
