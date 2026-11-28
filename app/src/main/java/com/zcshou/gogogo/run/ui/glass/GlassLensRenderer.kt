package com.zcshou.gogogo.run.ui.glass

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.zcshou.gogogo.R
import kotlin.math.roundToInt

/**
 * V6.4 双层同心光学玻璃透镜绘制器（纯静态矢量，零截图、零实时模糊）。
 *
 * 结构：
 * - 外层长胶囊：1dp 银白锐利高光描边（上沿亮、下沿微透），由 [drawOuterCapsuleRim] 绘制；
 * - 内层选中滑块：高透玻璃渐变填充 + 1.5dp 纯白镜面外圈 + 顶部镜面高光弧，
 *   由 [drawInnerLens] 绘制，滑动过程中只做位移，不重新采样底层像素；
 * - 卡片切边：1.5dp 冷银白镜面描边（顶部亮、底部微透）。
 *
 * ## V6.4 零分配改造
 *
 * 旧版每次调用都会 `LinearGradient(...)`：底栏透镜一个动画帧要建 5 个 Shader，
 * `LensChipTrack` 与 `LiquidGlassContainer` 再叠加，GC 压力直接反映为掉帧。
 * 现在所有渐变都在 **局部坐标系**（左上角为原点、宽高即绘制尺寸）构建一次并缓存，
 * 绘制时只用一条预分配的 [Matrix] 做 `setTranslate` 后 `Shader.setLocalMatrix`：
 * - 形状尺寸不变时（卡片、静态胶囊）永不重建；
 * - 尺寸连续变化时（按压 / 拖拽中的透镜）按 `宽×高` 命中 LRU，只重建真正新的尺寸；
 * - 主题翻转由 [onThemeChanged] 统一清空，避免深浅色 Token 串味。
 *
 * 缓存与 Paint 都是 object 级单例：绘制只发生在主线程，逐个 draw 顺序绑定
 * 各自的局部矩阵，不会互相污染。
 */
object GlassLensRenderer {

    // ---------------------------------------------------------------- 静态画笔

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val prismPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val rimRect = RectF()
    private val prismRect = RectF()

    /** 复用矩阵：只做平移，绝不进 draw 时分配。 */
    private val localMatrix = Matrix()

    // ---------------------------------------------------------------- 主题色缓存

    private class Palette(
        val lensHalo: Int,
        val fillTop: Int,
        val fillMid: Int,
        val fillBottom: Int,
        val silverRim: Int,
        val crystalRim: Int,
        val silverRimSoft: Int,
        val silverSheen: Int,
        val prismRed: Int,
        val prismCyan: Int
    )

    private var palette: Palette? = null
    private var paletteNight = false
    private var paletteGeneration = -1

    /** 主题代次：任何 Material Token 变更都必须 +1，强制重建渐变缓存。 */
    private var themeGeneration = 0

    // ---------------------------------------------------------------- 渐变缓存

    private class LensSet(
        val fill: Shader,
        val gloss: Shader,
        val rim: Shader,
        val sheen: Shader?
    )

    private class CapsuleSet(val rim: Shader, val topSheen: Shader, val bottomSheen: Shader)

    private class BoundedCache<K, V>(private val capacity: Int) :
        LinkedHashMap<K, V>(capacity + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean =
            size > capacity
    }

    private val lensCache = BoundedCache<Long, LensSet>(12)
    private val capsuleCache = BoundedCache<Long, CapsuleSet>(8)
    private val cardRimCache = BoundedCache<Long, Shader>(8)

    /**
     * 几何键：宽 / 高 / 圆角各 13 位（8191px 足够覆盖任何设备），
     * 低 8 位放填充倍率。三者任一变化才需要重建 Shader。
     */
    private fun cacheKey(w: Float, h: Float, radius: Float, variant: Int = 0): Long =
        (w.roundToInt().toLong().coerceIn(0L, 0x1FFFL) shl 51) or
            (h.roundToInt().toLong().coerceIn(0L, 0x1FFFL) shl 38) or
            (radius.roundToInt().toLong().coerceIn(0L, 0x1FFFL) shl 25) or
            (variant.toLong() and 0xFFL)

    /** 主题 / Token 变化：清空所有缓存并在下一帧按新色重建。 */
    fun onThemeChanged() {
        themeGeneration = (themeGeneration + 1) and 0x7FFFFFFF
        palette = null
        lensCache.clear()
        capsuleCache.clear()
        cardRimCache.clear()
    }

    private fun palette(context: Context): Palette {
        val night = isNight(context)
        val cached = palette
        if (cached != null && night == paletteNight && paletteGeneration == themeGeneration) {
            return cached
        }
        val fresh = Palette(
            lensHalo = context.getColor(R.color.dr_lens_halo),
            fillTop = context.getColor(R.color.dr_lens_fill_top),
            fillMid = context.getColor(R.color.dr_lens_fill_mid),
            fillBottom = context.getColor(R.color.dr_lens_fill_bottom),
            silverRim = context.getColor(R.color.dr_silver_rim),
            crystalRim = context.getColor(R.color.dr_crystal_rim),
            silverRimSoft = context.getColor(R.color.dr_silver_rim_soft),
            silverSheen = context.getColor(R.color.dr_silver_sheen),
            prismRed = context.getColor(R.color.dr_prism_red),
            prismCyan = context.getColor(R.color.dr_prism_cyan)
        )
        palette = fresh
        paletteNight = night
        paletteGeneration = themeGeneration
        // Token 变了，旧 Shader 里的颜色就过期了
        lensCache.clear()
        capsuleCache.clear()
        cardRimCache.clear()
        return fresh
    }

    private fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** 按倍率缩放颜色的 alpha，用于把同一套水晶配方调成"薄玻璃"档。 */
    private fun scaleAlpha(color: Int, scale: Float): Int {
        if (scale >= 1f) return color
        val alpha = (Color.alpha(color) * scale.coerceIn(0f, 1f)).roundToInt()
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    /** 绑定局部矩阵后再交给 Paint：平移复用同一份 Shader，不再逐帧 new。 */
    private fun bind(paint: Paint, shader: Shader, dx: Float, dy: Float) {
        localMatrix.setTranslate(dx, dy)
        shader.setLocalMatrix(localMatrix)
        paint.shader = shader
    }

    // ---------------------------------------------------------------- 内层透镜

    /** 内层选中透镜：高透玻璃滑块 + 1.5dp 镜面高光外圈 + 微凸曲面光泽。 */
    fun drawInnerLens(
        canvas: Canvas,
        context: Context,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radius: Float,
        density: Float,
        dispersion: Float = 0f,
        /**
         * 填充不透明度倍率。默认 1 = 既有路线 chip 的实心水晶观感；
         * 底栏透镜传 0.42，与 capybara 的 `NavSelectedSolidAlpha = 0.34` 同档 ——
         * 底栏下面压着模糊壁纸，透镜太实就成了一块贴纸，边缘折射全被盖住。
         */
        fillAlphaScale: Float = 1f
    ) {
        if (right - left < 8f * density || bottom - top < 4f * density) return
        val w = right - left
        val h = bottom - top
        val lensRadius = radius.coerceAtMost(h / 2f)
        val pal = palette(context)
        val set = lensSet(pal, w, h, lensRadius, density, fillAlphaScale)

        // 0. 极淡冷调接触阴影：纯白水晶在浅色玻璃上也能显出厚度与边界
        fillPaint.style = Paint.Style.FILL
        fillPaint.shader = null
        fillPaint.color = pal.lensHalo
        val halo = 2f * density
        canvas.drawRoundRect(
            left - halo, top - halo * 0.6f, right + halo, bottom + halo * 1.2f,
            lensRadius + halo, lensRadius + halo, fillPaint
        )
        // Paint.setColor 会把颜色 alpha 写进 paint；必须复位，否则后续 shader 填充只剩 5% 不透明度
        fillPaint.alpha = 255

        // 1. 高透水晶填充：顶部受光更亮，底部保持通透冷白
        fillPaint.style = Paint.Style.FILL
        bind(fillPaint, set.fill, left, top)
        canvas.drawRoundRect(left, top, right, bottom, lensRadius, lensRadius, fillPaint)

        // 2. 微凸曲面光泽：左上受光面对角衰减
        bind(fillPaint, set.gloss, left, top)
        canvas.drawRoundRect(left, top, right, bottom, lensRadius, lensRadius, fillPaint)
        fillPaint.shader = null

        // 3. 1.5dp 镜面高光外圈：纯白 → 冷银 → 银白柔光 → 底部微透
        val stroke = 1.5f * density
        val inset = stroke / 2f
        rimRect.set(left + inset, top + inset, right - inset, bottom - inset)
        rimPaint.strokeWidth = stroke
        bind(rimPaint, set.rim, left + inset, top + inset)
        canvas.drawRoundRect(
            rimRect,
            (lensRadius - inset).coerceAtLeast(0f),
            (lensRadius - inset).coerceAtLeast(0f),
            rimPaint
        )
        rimPaint.shader = null

        // 4. 顶部 1dp 镜面高光弧（两端渐隐，模拟玻璃受光）
        val topSheen = set.sheen
        if (topSheen != null) {
            val y = top + 1.1f * density
            val startX = left + lensRadius * 0.42f
            val endX = right - lensRadius * 0.42f
            sheenPaint.strokeWidth = 1f * density
            bind(sheenPaint, topSheen, startX, y)
            canvas.drawLine(startX, y, endX, y, sheenPaint)
            sheenPaint.shader = null
        }

        // 5. 底部 0.75dp 微透切边，塑造玻璃物理厚度
        if (h > 12f * density) {
            val y = bottom - 1f * density
            val startX = left + lensRadius * 0.5f
            val endX = right - lensRadius * 0.5f
            sheenPaint.strokeWidth = 0.75f * density
            sheenPaint.color = pal.silverRimSoft
            sheenPaint.alpha = 90
            canvas.drawLine(startX, y, endX, y, sheenPaint)
            sheenPaint.alpha = 255
        }

        // 6. 角部限定色散：**只在按压抬起时出现**；静止透镜保持纯白，避免"贴纸彩边"
        if (dispersion > 0.04f && lensRadius > 6f * density) {
            drawCornerDispersion(
                canvas, pal, left, top, right, bottom, lensRadius, density,
                strength = dispersion.coerceIn(0f, 1f)
            )
        }
    }

    private fun lensSet(
        pal: Palette,
        w: Float,
        h: Float,
        lensRadius: Float,
        density: Float,
        fillAlphaScale: Float
    ): LensSet {
        val alphaCenti = (fillAlphaScale.coerceIn(0f, 1f) * 100f).roundToInt()
        val key = cacheKey(w, h, lensRadius, alphaCenti)
        lensCache[key]?.let { return it }

        val fill = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                scaleAlpha(pal.fillTop, fillAlphaScale),
                scaleAlpha(pal.fillMid, fillAlphaScale),
                scaleAlpha(pal.fillBottom, fillAlphaScale)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        val gloss = LinearGradient(
            0f, 0f, w, h * 0.9f,
            intArrayOf(0x42FFFFFF, 0x12FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        val stroke = 1.5f * density
        val rim = LinearGradient(
            0f, 0f, 0f, (h - stroke).coerceAtLeast(1f),
            intArrayOf(pal.silverRim, pal.crystalRim, pal.silverRimSoft, 0x00FFFFFF),
            floatArrayOf(0f, 0.42f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )
        val sheenWidth = w - lensRadius * 0.84f
        val sheen = if (h > 10f * density && sheenWidth > 1f) {
            LinearGradient(
                0f, 0f, sheenWidth, 0f,
                intArrayOf(0x00FFFFFF, pal.silverSheen, pal.silverSheen, 0x00FFFFFF),
                floatArrayOf(0f, 0.16f, 0.84f, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            null
        }
        return LensSet(fill, gloss, rim, sheen).also { lensCache[key] = it }
    }

    private fun drawCornerDispersion(
        canvas: Canvas,
        pal: Palette,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radius: Float,
        density: Float,
        strength: Float
    ) {
        val offset = 0.55f * density
        val r2 = radius * 2f
        prismPaint.strokeWidth = 1.2f * density

        fun corner(l: Float, t: Float, r: Float, b: Float, start: Float) {
            prismRect.set(l - offset, t - offset, r + offset, b + offset)
            prismPaint.color = pal.prismRed
            prismPaint.alpha = (24 * strength).roundToInt()
            canvas.drawArc(prismRect, start, 90f, false, prismPaint)
            prismRect.set(l + offset, t + offset, r - offset, b - offset)
            prismPaint.color = pal.prismCyan
            canvas.drawArc(prismRect, start, 90f, false, prismPaint)
        }

        corner(left, top, left + r2, top + r2, 180f)
        corner(right - r2, top, right, top + r2, 270f)
        corner(right - r2, bottom - r2, right, bottom, 0f)
        corner(left, bottom - r2, left + r2, bottom, 90f)
    }

    // ---------------------------------------------------------------- 外层胶囊

    /** 外层胶囊：上下边缘各一道 1dp 银白锐利高光，侧边以柔光收口。 */
    fun drawOuterCapsuleRim(
        canvas: Canvas,
        context: Context,
        width: Int,
        height: Int,
        radius: Float,
        density: Float
    ) {
        if (width <= 0 || height <= 0) return
        val pal = palette(context)
        val w = width.toFloat()
        val h = height.toFloat()
        val key = cacheKey(w, h, radius)
        val set = capsuleCache[key] ?: buildCapsuleSet(pal, w, h, radius, density)
            .also { capsuleCache[key] = it }

        val stroke = 1f * density
        val inset = stroke / 2f
        rimRect.set(inset, inset, width - inset, height - inset)
        rimPaint.strokeWidth = stroke
        bind(rimPaint, set.rim, inset, inset)
        val r = (radius - inset).coerceAtLeast(0f)
        canvas.drawRoundRect(rimRect, r, r, rimPaint)
        rimPaint.shader = null

        // 上沿：锐利银白高光（两端收进圆角，避免变成一根横线）
        val topY = 1.2f * density
        val startX = radius * 0.55f
        val endX = w - radius * 0.55f
        sheenPaint.strokeWidth = 1f * density
        bind(sheenPaint, set.topSheen, startX, topY)
        canvas.drawLine(startX, topY, endX, topY, sheenPaint)

        // 下沿：微弱反光，强调玻璃厚度
        val bottomY = h - 1.4f * density
        bind(sheenPaint, set.bottomSheen, startX, bottomY)
        canvas.drawLine(startX, bottomY, endX, bottomY, sheenPaint)
        sheenPaint.shader = null
    }

    private fun buildCapsuleSet(
        pal: Palette,
        w: Float,
        h: Float,
        radius: Float,
        density: Float
    ): CapsuleSet {
        val stroke = 1f * density
        val rim = LinearGradient(
            0f, 0f, 0f, (h - stroke).coerceAtLeast(1f),
            intArrayOf(pal.silverRim, 0x00FFFFFF, 0x00FFFFFF, pal.silverRimSoft),
            floatArrayOf(0f, 0.42f, 0.86f, 1f),
            Shader.TileMode.CLAMP
        )
        val lineWidth = (w - radius * 1.1f).coerceAtLeast(1f)
        val topSheen = LinearGradient(
            0f, 0f, lineWidth, 0f,
            intArrayOf(0x00FFFFFF, pal.silverSheen, pal.silverSheen, 0x00FFFFFF),
            floatArrayOf(0f, 0.12f, 0.88f, 1f),
            Shader.TileMode.CLAMP
        )
        val bottomSheen = LinearGradient(
            0f, 0f, lineWidth, 0f,
            intArrayOf(0x00FFFFFF, pal.silverRimSoft, 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        return CapsuleSet(rim, topSheen, bottomSheen)
    }

    // ---------------------------------------------------------------- 卡片切边

    /**
     * 卡片切边：1.5dp 冷银白镜面高光描边。
     * 顶部最亮、中部冷银、底部极淡，避免形成生硬黑框。
     */
    fun drawCardRim(
        canvas: Canvas,
        context: Context,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radius: Float,
        density: Float
    ) {
        if (right - left <= 0f || bottom - top <= 0f) return
        val pal = palette(context)
        val w = right - left
        val h = bottom - top
        val key = cacheKey(w, h, 0f)
        val shader = cardRimCache[key] ?: buildCardRim(pal, h, density)
            .also { cardRimCache[key] = it }

        val stroke = 1.5f * density
        val inset = stroke / 2f
        rimRect.set(left + inset, top + inset, right - inset, bottom - inset)
        rimPaint.strokeWidth = stroke
        bind(rimPaint, shader, left + inset, top + inset)
        val r = (radius - inset).coerceAtLeast(0f)
        canvas.drawRoundRect(rimRect, r, r, rimPaint)
        rimPaint.shader = null
    }

    private fun buildCardRim(pal: Palette, h: Float, density: Float): Shader {
        val stroke = 1.5f * density
        return LinearGradient(
            0f, 0f, 0f, (h - stroke).coerceAtLeast(1f),
            intArrayOf(pal.silverRim, pal.crystalRim, pal.silverRimSoft, 0x00FFFFFF),
            floatArrayOf(0f, 0.38f, 0.76f, 1f),
            Shader.TileMode.CLAMP
        )
    }
}
