package com.zcshou.gogogo.run.ui.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import com.example.liquidglass.BlurMethod
import com.example.liquidglass.LiquidGlassView
import java.util.WeakHashMap
import kotlin.math.max

/**
 * V6.4 地图背景共享捕获（`setCustomBackdropCapture` 接管 + 同帧去重 + 去蓝校色）。
 *
 * ## 为什么要接管
 *
 * 百度 `TextureMapView` 的硬件位图回读**带明显蓝色偏移**，且饱和度过高。
 * 旧方案是在玻璃内侧盖一块 90% 中性实底把颜色压死 —— 蓝是压住了，
 * 玻璃也彻底失去了通透感。
 *
 * 现在改成在采样环节做校正：
 * 1. 以 [CAPTURE_SCALE] 把地图整帧降到 0.3x（**每帧只做一次**）；
 * 2. 用去蓝 + 降饱和 + 高光收敛矩阵校正颜色；
 * 3. 按各面板请求的矩形从这张共享帧裁切；
 * 4. 交给 `LiquidGlassView` 走 `IIR_GAUSSIAN_NEON` / `SMART` 模糊与折射。
 *
 * ## ⚠ 位图所有权（踩过的坑）
 *
 * `LiquidGlassView` 会**接管并 recycle** `setCustomBackdropCapture` 返回的位图
 * （`onSizeChanged` 与「背景签名变化」时各 recycle 一次）。因此：
 * - 返回的位图**必须每次新建**，绝不能池化复用 —— 复用会在下一帧拿到已回收位图；
 * - 为了把这点分配压到可忽略，返回值按 [CAPTURE_SCALE] 缩到 0.3x
 *   （1080×120 的面板 ≈ 78KB，而 1:1 是 0.5MB）；
 * - 真正昂贵的部分（整张地图绘制 + 校色）由 [sources] 里的**共享帧**承担，
 *   同一帧内多块面板只截一次地图。
 */
object MapBackdropCapture {

    /** 整帧与面板采样倍率：0.3x 足够保留道路/水系轮廓，又只有原图 9% 的像素量。 */
    private const val CAPTURE_SCALE = 0.3f

    /** 预模糊帧倍率：0.1x，放大后等效 8~10px 屏幕模糊。 */
    private const val TINY_SCALE = 0.1f

    /** 同帧判定窗口（ms）：两块面板在同一个 vsync 内绘制。 */
    private const val FRAME_WINDOW_MS = 8L

    /**
     * 去蓝校正矩阵。
     * R/G 轻微提亮并去青，B 压到 0.86 抵消回读偏蓝，末尾 -2 防止暗部发紫。
     */
    private val DEBLUE = ColorMatrix(
        floatArrayOf(
            1.06f, 0f, 0f, 0f, 4f,
            0f, 1.02f, 0f, 0f, 2f,
            0f, 0f, 0.86f, 0f, -2f,
            0f, 0f, 0f, 1f, 0f
        )
    )

    /** 再叠一层 8% 降饱和：地图水系与公园的高饱和绿蓝在玻璃里过分刺眼。 */
    private val DESCALE = ColorMatrix().apply { setSaturation(0.92f) }

    /** 高光收敛：整体 0.97 增益 + 6 偏移，把纯白道路名从"荧光"拉回纸面。 */
    private val HIGHLIGHT_CLAMP = ColorMatrix(
        floatArrayOf(
            0.97f, 0f, 0f, 0f, 6f,
            0f, 0.97f, 0f, 0f, 6f,
            0f, 0f, 0.97f, 0f, 6f,
            0f, 0f, 0f, 1f, 0f
        )
    )

    private val correction: ColorMatrixColorFilter = ColorMatrixColorFilter(
        ColorMatrix().apply {
            set(DEBLUE)
            postConcat(DESCALE)
            postConcat(HIGHLIGHT_CLAMP)
        }
    )

    private class SourceFrame {
        /** 0.3x 整帧（已校色）：共享采样源。 */
        var bitmap: Bitmap? = null
        /** 0.1x 预模糊帧：面板从这里放大绘制，保证"磨砂"这件事不依赖库内模糊是否生效。 */
        var tiny: Bitmap? = null
        var width = 0
        var height = 0
        var stamp = Long.MIN_VALUE
    }

    /** 共享整帧（不属于任何面板，可安全复用与回收）。 */
    private val sources = WeakHashMap<View, SourceFrame>()

    private val layerPaint = Paint().apply { colorFilter = correction }
    private val blitPaint = Paint().apply { isFilterBitmap = true }

    /** 递归保护：地图自己也在绘制时不要回头截它。 */
    private var capturing = false

    // ---------------------------------------------------------------- 绑定

    /**
     * 让 [glass] 从 [source]（必须是 `TextureMapView`）取背景，走本对象的共享捕获。
     *
     * **红线**：`MapView`（SurfaceView 实现）不能作为背景源，否则采出纯黑；
     * 调用方必须确认是 `TextureMapView`。
     */
    /** 让 [glass] 从 [source]（必须是 `TextureMapView`）取背景，走本对象的共享捕获。 */
    fun bind(glass: LiquidGlassView, source: View) {
        glass.setCustomBackdropCapture { rect -> capture(source, rect) }
    }

    /** Activity 销毁时释放共享整帧（面板位图归库所有，不在这里回收）。 */
    fun release() {
        // WeakHashMap 支持迭代中移除，但逐 key 走 release(source) 保证口径一致
        sources.keys.toList().forEach { release(it) }
    }

    /**
     * 页面销毁时只释放**自己绑定**的地图源帧（面板位图归库所有，不在这里回收）。
     * 不要在单页面销毁时调全局 [release]：首页地图可能还活在任务栈下方，
     * 整体清池会让它在返回时白白重截重校色一帧。
     */
    fun release(source: View) {
        val frame = sources.remove(source) ?: return
        frame.bitmap?.let { if (!it.isRecycled) it.recycle() }
        frame.tiny?.let { if (!it.isRecycled) it.recycle() }
        frame.bitmap = null
        frame.tiny = null
    }

    // ---------------------------------------------------------------- 捕获

    /**
     * 返回**新建**的面板位图（所有权交给 `LiquidGlassView`）。
     * 内容来自本帧共享整帧，因此多块面板共享同一次 `source.draw()`。
     */
    private fun capture(source: View, rect: RectF): Bitmap? {
        if (capturing || rect.width() <= 1f || rect.height() <= 1f) return null
        val frame = frameOf(source) ?: return null
        val tiny = frame.tiny ?: return null
        val w = max(1, (rect.width() * CAPTURE_SCALE).toInt())
        val h = max(1, (rect.height() * CAPTURE_SCALE).toInt())
        val panel = runCatching {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null

        val canvas = Canvas(panel)
        canvas.save()
        // 从 0.1x 预模糊帧放大铺满 panel：这一步本身就是柔和模糊，
        // 不依赖库里是否还会对自定义捕获再糊一次。
        // Canvas 变换预拼接（后调用的先作用于几何）：先 scale 再 translate，
        // 等效映射 device = (CAPTURE_SCALE/TINY_SCALE)·tiny − rect·平移系数。
        // 要让面板原点对准 rect 左上角（tiny 空间的 rect·TINY_SCALE），
        // 平移必须乘 CAPTURE_SCALE；乘成 TINY_SCALE 会让采样窗口
        // 整体偏移 0.2×rect —— 面板越靠地图下方/右侧，透出的内容错位越明显。
        canvas.translate(-rect.left * CAPTURE_SCALE, -rect.top * CAPTURE_SCALE)
        canvas.scale(
            w / (rect.width() * TINY_SCALE),
            h / (rect.height() * TINY_SCALE)
        )
        canvas.drawBitmap(tiny, 0f, 0f, blitPaint)
        canvas.restore()
        return panel
    }

    /**
     * 取当前帧的整图采样。同一个 [FRAME_WINDOW_MS] 窗口内直接复用，
     * 保证「同帧多面板只截一次地图」。
     */
    private fun frameOf(source: View): SourceFrame? {
        val w = (source.width * CAPTURE_SCALE).toInt()
        val h = (source.height * CAPTURE_SCALE).toInt()
        if (w <= 0 || h <= 0) return null
        val record = sources.getOrPut(source) { SourceFrame() }
        val now = SystemClock.uptimeMillis()
        val cached = record.bitmap
        if (cached != null && !cached.isRecycled && record.tiny?.isRecycled != true &&
            record.width == w && record.height == h &&
            now - record.stamp < FRAME_WINDOW_MS
        ) {
            return record
        }

        if (cached == null || cached.isRecycled ||
            record.width != w || record.height != h
        ) {
            cached?.recycle()
            record.tiny?.recycle()
            record.tiny = null
            val fresh = runCatching {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            }.getOrNull() ?: return null
            record.bitmap = fresh
            record.width = w
            record.height = h
        }
        val target = record.bitmap ?: return null
        record.stamp = now

        capturing = true
        try {
            val canvas = Canvas(target)
            canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
            // 整帧降采样（+ 地图路径的校色）一起完成：saveLayer 的 Paint 会在合成时套 ColorFilter
            val layer = canvas.saveLayer(
                null,
                layerPaint,
                Canvas.ALL_SAVE_FLAG
            )
            canvas.scale(CAPTURE_SCALE, CAPTURE_SCALE)
            source.draw(canvas)
            canvas.restoreToCount(layer)

            // 再降一档到 0.1x 作为"预模糊"源：两级降采样的等效模糊半径
            // 约等于屏幕上的 8~10px，正是磨砂该有的柔度。
            // tiny 只在本对象内流转（面板拿到的是拷贝），尺寸不变时直接复用，
            // 避免地图拖动期间每帧分配/丢弃一张位图
            val tw = max(1, (source.width * TINY_SCALE).toInt())
            val th = max(1, (source.height * TINY_SCALE).toInt())
            var tiny = record.tiny
            if (tiny == null || tiny.isRecycled || tiny.width != tw || tiny.height != th) {
                tiny?.recycle()
                tiny = runCatching { Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888) }.getOrNull()
                record.tiny = tiny
                if (tiny == null) return null
            }
            Canvas(tiny).drawBitmap(
                target,
                android.graphics.Rect(0, 0, target.width, target.height),
                android.graphics.Rect(0, 0, tw, th),
                blitPaint
            )
        } catch (t: Throwable) {
            return null
        } finally {
            capturing = false
        }
        return record
    }

    // ---------------------------------------------------------------- 模糊参数

    /**
     * 按 API 等级选择低开销高质量模糊方法（对齐 QWEA0 / QmBlurView 的实践）：
     * - API 31+：`SMART`（内部挑最省的一条路径）；
     * - API 27–30：`IIR_GAUSSIAN_NEON` + `downsampleScale = 4` + `enableOptimizedCapture`。
     *
     * `globalDownsampleFactor` 固定 1：我们返回的位图已经是 0.3x 且每次新建，
     * 让库再缩一次只会多一次拷贝（并且会多一次 recycle，徒增风险）。
     */
    fun applyBlurProfile(glass: LiquidGlassView) {
        glass.highQualityBlur = false
        glass.downsampleScale = 4
        glass.enableOptimizedCapture = true
        glass.globalDownsampleFactor = 1f
        glass.blurMethod = if (android.os.Build.VERSION.SDK_INT >= 31) {
            BlurMethod.SMART
        } else {
            BlurMethod.IIR_GAUSSIAN_NEON
        }
    }
}
