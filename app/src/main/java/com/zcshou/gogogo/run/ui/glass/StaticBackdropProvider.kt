package com.zcshou.gogogo.run.ui.glass

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import java.util.WeakHashMap
import kotlin.math.max

/**
 * V6.4 静态画布共享背景池（BlurView `BlurTarget` 思想的落地实现）。
 *
 * ## 为什么需要它
 *
 * 首页 / 体检 / 设置页的底色是 [com.zcshou.gogogo.run.ui.overlay.BathymetricBackdropView]
 * ——一块**只随尺寸与主题变化**的静态画布（渐变 + 冰蓝光斑 + 72dp 网格 + 等高线）。
 * 卡片滚动时画布内容并不会变，变的只是卡片在画布上的位置。
 * 旧实现里每块玻璃都要自己 `View.draw()` 截一次屏，滚动时等于整页反复重录。
 *
 * ## 现在的做法
 *
 * 1. 宿主（静态画布）挂载时向本对象登记一个**离屏渲染器**；
 * 2. 本对象在尺寸/主题变化后渲染**一次**，产出两张共享纹理：
 *    - `crisp`（0.5x）：给边缘 SDF 折射采样 72dp 网格与等高线，保证形变可见；
 *    - `blurred`（0.25x，经两级降采样）：给玻璃内部柔焦透视；
 * 3. 任意子视图（含 `LiquidGlassContainer`）通过 [snapshotFor] 沿视图树就近取用，
 *    只把自身相对画布的偏移交给着色器 —— **滚动期间 0 截屏**。
 *
 * ## 约束
 *
 * - 快照只在尺寸 / 主题变化时重建，`onDraw` 路径上只做 `WeakHashMap` 查表与只读采样；
 * - 旧快照**延迟到 swap 时**才 `release()`（[invalidate] 只标记不回收）：
 *   快照的 `BitmapShader` 可能已交给 `RuntimeShader.setInputShader`，立刻 recycle
 *   会撞「trying to use a recycled bitmap」；重建期间新旧最多并存 2 份（约 6~10MB）；
 * - 登记表用 [WeakHashMap]，View 被回收不会拖住 Activity。
 */
object StaticBackdropProvider {

    /** crisp 纹理相对宿主视图的采样倍率。 */
    const val CRISP_SCALE = 0.5f

    /** blurred 纹理相对宿主视图的采样倍率。 */
    const val BLURED_SCALE = 0.25f

    /** 两级降采样：crisp(0.5x) → tiny(0.125x) → blurred(0.25x)。 */
    private const val TINY_SCALE = 0.125f

    private const val MIN_HOST_SIZE = 32

    /**
     * 一块静态画布的离屏快照。
     *
     * [crispShader] / [blurredShader] 为 **identity 局部矩阵的 BitmapShader**，
     * 着色器里直接以「位图像素坐标」eval 即可，无需再传 Matrix。
     */
    class Snapshot internal constructor(
        val host: View,
        val crisp: Bitmap,
        val blurred: Bitmap
    ) {
        /** 位图像素 / 宿主视图像素。 */
        val crispScale: Float =
            GlassPipelineRules.samplingScale(host.width, crisp.width, CRISP_SCALE)
        val blurredScale: Float =
            GlassPipelineRules.samplingScale(host.width, blurred.width, BLURED_SCALE)

        internal val crispShader: Shader = BitmapShader(crisp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        internal val blurredShader: Shader = BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

        internal fun release() {
            if (!crisp.isRecycled) crisp.recycle()
            if (!blurred.isRecycled) blurred.recycle()
        }
    }

    private class HostRecord(val view: View, val render: (Canvas) -> Unit) {
        var snapshot: Snapshot? = null
        var dirty: Boolean = true
    }

    private val hosts = WeakHashMap<View, HostRecord>()

    /** 放大填充用的 Paint：只在重建快照时使用，不进入逐帧绘制。 */
    private val scalePaint = Paint().apply { isFilterBitmap = true }

    private var capturing = false

    // ---------------------------------------------------------------- 登记

    /**
     * 登记静态画布宿主。[render] 负责把该宿主内容画到给定画布上
     * （默认就是 `view.draw(canvas)`，宿主不需要自己做缩放）。
     */
    fun attach(view: View, render: ((Canvas) -> Unit)? = null) {
        hosts[view] = HostRecord(view) { canvas -> (render ?: { view.draw(it) }).invoke(canvas) }
    }

    fun detach(view: View) {
        hosts.remove(view)?.snapshot?.release()
    }

    /** 尺寸 / 主题变化：标记重建，下一帧按新内容 swap 时统一回收旧纹理。 */
    fun invalidate(view: View) {
        hosts[view]?.dirty = true
    }

    /** 全局主题翻转：所有宿主一起标记重建（旧纹理延迟到 swap 时回收）。 */
    fun invalidateAll() {
        hosts.values.forEach { it.dirty = true }
    }

    fun isRegistered(view: View): Boolean = hosts.containsKey(view)

    // ---------------------------------------------------------------- 取用

    /**
     * 从 [anchor] 沿视图树就近寻找已登记的静态画布，返回其快照。
     * 找不到或宿主尚未完成布局时返回 null（调用方回退静态材质）。
     */
    fun snapshotFor(anchor: View): Snapshot? {
        val record = findHost(anchor) ?: return null
        val existing = record.snapshot
        if (existing != null && !record.dirty) return existing
        return build(record)
    }

    private fun findHost(anchor: View): HostRecord? {
        var node: View? = anchor
        var depth = 0
        while (node != null && depth < 24) {
            hosts[node]?.let { return it }
            if (node is ViewGroup) {
                val count = node.childCount
                for (i in 0 until count) {
                    hosts[node.getChildAt(i)]?.let { return it }
                }
            }
            node = node.parent as? View
            depth++
        }
        return null
    }

    private fun build(record: HostRecord): Snapshot? {
        val host = record.view
        if (!host.isAttachedToWindow || host.width < MIN_HOST_SIZE || host.height < MIN_HOST_SIZE) {
            return record.snapshot
        }
        // 防止「容器在画布绘制过程中反向请求快照」造成递归
        if (capturing) return record.snapshot
        capturing = true
        try {
            val next = runCatching { renderSnapshot(record) }.getOrNull() ?: return record.snapshot
            val previous = record.snapshot
            record.snapshot = next
            record.dirty = false
            if (previous !== next) previous?.release()
            return next
        } finally {
            capturing = false
        }
    }

    private fun renderSnapshot(record: HostRecord): Snapshot {
        val w = record.view.width
        val h = record.view.height
        val crispW = max(1, (w * CRISP_SCALE).toInt())
        val crispH = max(1, (h * CRISP_SCALE).toInt())
        val tinyW = max(1, (w * TINY_SCALE).toInt())
        val tinyH = max(1, (h * TINY_SCALE).toInt())
        val blurW = max(1, (w * BLURED_SCALE).toInt())
        val blurH = max(1, (h * BLURED_SCALE).toInt())

        val crisp = Bitmap.createBitmap(crispW, crispH, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(crisp)
            canvas.scale(CRISP_SCALE, CRISP_SCALE)
            record.render(canvas)
        } catch (t: Throwable) {
            crisp.recycle()
            throw t
        }

        // 两级降采样：crisp → tiny → blurred。双线性两次等价于一次宽半径箱式模糊，
        // 成本只有两次整图缩放，且全部发生在重建快照时（不是逐帧）。
        // 低内存下任一步失败都要回收已分配的半成品，避免堆到 finalizer
        val tiny: Bitmap = try {
            Bitmap.createBitmap(tinyW, tinyH, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            crisp.recycle()
            throw t
        }
        val blurred: Bitmap = try {
            Bitmap.createBitmap(blurW, blurH, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            tiny.recycle()
            crisp.recycle()
            throw t
        }
        try {
            Canvas(tiny).drawBitmap(
                crisp,
                android.graphics.Rect(0, 0, crispW, crispH),
                android.graphics.Rect(0, 0, tinyW, tinyH),
                scalePaint
            )
            Canvas(blurred).drawBitmap(
                tiny,
                android.graphics.Rect(0, 0, tinyW, tinyH),
                android.graphics.Rect(0, 0, blurW, blurH),
                scalePaint
            )
        } catch (t: Throwable) {
            blurred.recycle()
            crisp.recycle()
            throw t
        } finally {
            tiny.recycle()
        }
        return Snapshot(record.view, crisp, blurred)
    }
}
