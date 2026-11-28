package com.zcshou.gogogo.run.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import com.zcshou.gogogo.R
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * V6.4 羽化阅读内芯（替代 `InsetDrawable` + 实色 `GradientDrawable`）。
 *
 * ## 旧问题
 *
 * 弹窗/弹层为了压住背景文字，在玻璃内侧 8dp 处垫了一块 96% 实色圆角矩形。
 * 折射区（外 8dp）与阅读区（内部实底）之间是一条**生硬的分界线**：
 * 玻璃边缘还在折射底下的页面，往里一步突然变成一块实心卡纸。
 *
 * ## 现在的做法
 *
 * 用 N 层同心圆角矩形叠出 12dp 的 alpha 斜坡：
 * 每层覆盖率相同、逐层向内扩大，像素距离边缘越深被覆盖的层数越多，
 * 合成后就是一条连续的 0% → 96% 柔过渡（等效 1-(1-a)^n 的累积曲线）。
 * 顶部再补一条 1dp 镜面细高光，让阅读区上沿仍有玻璃受光的读感。
 *
 * ## 约束
 *
 * - 全部几何（N 条 Path、渐变）在 `onBoundsChange` 时构建，`draw()` 内 0 分配；
 * - [topClearancePx] 给 BottomSheet 的把手栏留出干净透光区。
 */
class FeatheredCoreDrawable @JvmOverloads constructor(
    private val context: Context,
    /** 四角半径 px，顺序 TL / TR / BR / BL（每角一个值，与 `View.setClipToOutline` 一致的语义）。 */
    private val cornerRadiiPx: FloatArray,
    /** 羽化带宽度，默认 12dp。 */
    private val featherPx: Float = 12f * context.resources.displayMetrics.density,
    /** 顶部额外留白（把手栏透光区），默认 0。 */
    private val topClearancePx: Float = 0f
) : Drawable() {

    private val density = context.resources.displayMetrics.density
    private val coreColor = context.getColor(R.color.dr_dialog_core)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    private val layerPaths = arrayOfNulls<Path>(STEPS)
    private val layerRadii = Array(STEPS) { FloatArray(8) }
    private val insetRect = RectF()

    private var layerAlpha = 255
    private var alphaScale = 1f
    private var sheenShader: Shader? = null
    private var sheenY = 0f
    private var sheenStartX = 0f
    private var sheenEndX = 0f
    private var dirty = true

    init {
        fillPaint.color = coreColor
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        dirty = true
    }

    override fun setAlpha(alpha: Int) {
        alphaScale = (alpha / 255f).coerceIn(0f, 1f)
        dirty = true
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        if (dirty) rebuild()
        fillPaint.color = coreColor
        fillPaint.alpha = layerAlpha
        for (i in 0 until STEPS) {
            val path = layerPaths[i] ?: continue
            canvas.drawPath(path, fillPaint)
        }
        val sheen = sheenShader
        if (sheen != null) {
            sheenPaint.shader = sheen
            canvas.drawLine(sheenStartX, sheenY, sheenEndX, sheenY, sheenPaint)
            sheenPaint.shader = null
        }
    }

    private fun rebuild() {
        dirty = false
        val b = bounds
        val w = b.width()
        val h = b.height()
        if (w <= 0 || h <= 0) return

        // 目标中心不透明度（含外部 alpha 缩放）
        val target = (Color.alpha(coreColor) / 255f * alphaScale).coerceIn(0f, 1f)
        // N 层等覆盖率叠加：1-(1-a)^N = target  ⇒  a = 1-(1-target)^(1/N)
        val perLayer = (1.0 - Math.pow((1.0 - target).toDouble(), 1.0 / STEPS))
        layerAlpha = (perLayer * 255.0).roundToInt().coerceIn(0, 255)

        val feather = max(featherPx, 1f)
        for (i in 0 until STEPS) {
            // i=0 缩得最深（羽化带最内圈），i=STEPS-1 贴边
            val inset = feather * (STEPS - 1 - i) / (STEPS - 1f)
            insetRect.set(
                b.left + inset,
                b.top + topClearancePx + inset,
                b.right - inset,
                b.bottom - inset
            )
            if (insetRect.width() <= 0f || insetRect.height() <= 0f) {
                layerPaths[i] = null
                continue
            }
            val radii = layerRadii[i]
            // 入参是 4 个角半径（TL/TR/BR/BL），Path 需要 8 个值（每角 x,y）
            for (c in 0 until 4) {
                val r = (cornerRadiiPx[c] - inset).coerceAtLeast(0f)
                radii[c * 2] = r
                radii[c * 2 + 1] = r
            }
            val path = layerPaths[i] ?: Path().also { layerPaths[i] = it }
            path.reset()
            path.addRoundRect(insetRect, radii, Path.Direction.CW)
        }

        // 顶部 1dp 镜面细边：只在阅读区上沿，两端渐隐
        val maxRadius = cornerRadiiPx.maxOrNull() ?: 0f
        sheenY = b.top + topClearancePx + 1f * density
        sheenStartX = b.left + maxRadius * 0.45f
        sheenEndX = b.right - maxRadius * 0.45f
        sheenShader = if (sheenEndX - sheenStartX > 8f * density) {
            LinearGradient(
                sheenStartX, sheenY, sheenEndX, sheenY,
                intArrayOf(0x00FFFFFF, 0x8CFFFFFF.toInt(), 0x8CFFFFFF.toInt(), 0x00FFFFFF),
                floatArrayOf(0f, 0.14f, 0.86f, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            null
        }
    }

    private companion object {
        /** 层数：12 层足以让 12dp 斜坡看不出台阶，再多多余。 */
        const val STEPS = 12
    }
}
