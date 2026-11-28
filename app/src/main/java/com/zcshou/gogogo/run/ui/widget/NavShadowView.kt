package com.zcshou.gogogo.run.ui.widget

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * V6.5 导航独立投影层。
 *
 * 之前把 elevation 放在导航容器上，阴影会画在玻璃上方，把整条导航压成深灰。
 * 这里改用独立的、无 elevation 的 View，在玻璃**下方**手绘一层大半径柔和阴影。
 *
 * ## 为什么要把轨道内部挖掉
 *
 * 玻璃轨道是半透的（28% 白），阴影从它下面透上来，会在轨道**里面**糊出一片
 * 灰斑 —— 轨道不再是"漂在页面上的玻璃"，而是"一块脏了的板子"。曾经轨道
 * 是 80% 不透明，阴影被盖得看不见，所以这个缺陷一直没暴露；一调通透就露了。
 *
 * 所以这里用 DST_OUT 把轨道轮廓从阴影里减掉：阴影只保留在胶囊外侧那一圈。
 * 减完剩下的才是真正的"投影" —— 从玻璃边缘向外散开，玻璃内部一点不沾。
 */
class NavShadowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var navWidth = 0f
    var navHeight = 0f
    var navBottomInset = 0f
    /** 轨道圆角，默认取半高（胶囊）。 */
    var navCornerRadius = 0f

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x380B1220.toInt()
        maskFilter = BlurMaskFilter(22f * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val cutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val rect = RectF()
    private val cutPath = Path()

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
        // DST_OUT 需要一层离屏缓冲才能只挖自己，不然会把下面整页也挖穿
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun update(widthPx: Int, heightPx: Int, bottomInsetPx: Int, cornerRadiusPx: Int = 0) {
        val nextW = widthPx.toFloat()
        val nextH = heightPx.toFloat()
        val nextBottom = bottomInsetPx.toFloat()
        val nextRadius = if (cornerRadiusPx > 0) cornerRadiusPx.toFloat() else nextH / 2f
        if (nextW == navWidth && nextH == navHeight &&
            nextBottom == navBottomInset && nextRadius == navCornerRadius
        ) {
            return
        }
        navWidth = nextW
        navHeight = nextH
        navBottomInset = nextBottom
        navCornerRadius = nextRadius
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (navWidth <= 0f || navHeight <= 0f) return
        val cx = width / 2f
        val bottom = height - navBottomInset
        val top = bottom - navHeight
        rect.set(
            cx - navWidth / 2f - 2f * density,
            top + 4f * density,
            cx + navWidth / 2f + 2f * density,
            bottom + 7f * density
        )
        val radius = navCornerRadius.coerceAtLeast(navHeight / 2f)
        canvas.drawRoundRect(rect, radius, radius, paint)

        // 把轨道本身从阴影里减掉：投影只留在胶囊外侧
        cutPath.reset()
        cutPath.addRoundRect(
            cx - navWidth / 2f, top,
            cx + navWidth / 2f, bottom,
            radius, radius, Path.Direction.CW
        )
        canvas.drawPath(cutPath, cutPaint)
    }
}
