package com.zcshou.gogogo.run.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

/**
 * 胶囊外壳：在子视图绘制完之后再画一层边缘流光，并为硬件阴影提供圆角轮廓。
 *
 * 为什么不用独立的覆盖层 View：`FrameLayout` 里的 `wrap_content` 子 View 在
 * `AT_MOST` 测量下会直接取父容器的可用宽度（View 默认 `getDefaultSize`），
 * 结果是覆盖层把外壳撑成整屏宽、胶囊凭空变大。在外壳自己的 `dispatchDraw` 里画，
 * 天然贴合胶囊矩形，也不参与任何测量。
 *
 * 流光必须画在**子视图之后**（压住文字边缘），所以用 `dispatchDraw` 而不是 `onDraw`；
 * 同时保持 `clipToOutline = false`，让柔光晕不被轮廓裁掉。
 */
class CapsuleShellView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** 边缘流光；为 null 时不绘制（未接入或已停用）。 */
    var beam: BorderBeamDrawable? = null

    private var cornerRadiusPx: Float = 0f

    init {
        // 没有背景时 FrameLayout 默认会跳过 draw，这里要确保 dispatchDraw 始终执行
        setWillNotDraw(false)
        clipToOutline = false
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val w = view.width
                val h = view.height
                if (w <= 0 || h <= 0) return
                val radius = cornerRadiusPx.coerceIn(0f, minOf(w, h) * 0.5f)
                outline.setRoundRect(0, 0, w, h, radius)
            }
        }
    }

    /** 设置与玻璃底片一致的圆角半径（px），驱动圆角投射阴影。 */
    fun setOutlineCornerRadius(radiusPx: Float) {
        val next = radiusPx.coerceAtLeast(0f)
        if (next == cornerRadiusPx) return
        cornerRadiusPx = next
        invalidateOutline()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val b = beam ?: return
        b.setBounds(0, 0, width, height)
        b.draw(canvas)
    }
}
