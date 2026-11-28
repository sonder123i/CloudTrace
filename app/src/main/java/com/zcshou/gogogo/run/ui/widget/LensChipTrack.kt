package com.zcshou.gogogo.run.ui.widget

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.zcshou.gogogo.run.ui.glass.GlassLensRenderer

/**
 * V5.2 横向玻璃透镜轨道（用于首页「我的路线」快选条）。
 *
 * 选中项由轨道行自身的 drawable 绘制一个内层高透玻璃透镜，
 * 左右边缘各一条弹簧（阻尼 0.72 / 刚度 380）驱动，滑动时自然拉伸回弹。
 * 与底部导航共用 [GlassLensRenderer]，因此材质、1.5dp 镜面外圈与
 * 顶部高光弧完全一致，不额外引入任何子 View 或截屏模糊。
 *
 * 用法：row 必须是这些 chip 的直接父容器，且自身 background 由本类接管。
 */
class LensChipTrack(private val host: View) {

    private val density = host.resources.displayMetrics.density
    private val indicator = LensDrawable(host)
    private var tabs: List<View> = emptyList()
    private var selected = 0
    private var selectedCount = 0

    private var leftSpring: SpringAnimation? = null
    private var rightSpring: SpringAnimation? = null
    private var lastLeftValue = 0f
    private var lastRightValue = 0f
    private var lastLeftDelta = 0f
    private var lastRightDelta = 0f

    init {
        host.background = indicator
        host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            // 弹簧滑动过程中不要被布局回调打断，否则透镜会瞬移
            if (leftSpring?.isRunning == true || rightSpring?.isRunning == true) {
                return@addOnLayoutChangeListener
            }
            moveTo(selected, animate = false)
        }
    }

    /**
     * 绑定当前 chip 列表并移动透镜。
     * [animate] 仅在 chip 数量未变化（真实切换）时为 true，首次渲染直接落位。
     */
    fun bind(tabs: List<View>, selected: Int, animate: Boolean = true) {
        this.tabs = tabs
        this.selected = selected.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
        val changed = tabs.size == selectedCount && this.selected != indicator.selectedIndex
        indicator.selectedIndex = this.selected
        selectedCount = tabs.size
        host.post { moveTo(this.selected, animate = animate && changed) }
    }

    /** 主题切换后重绘（透镜颜色来自资源 Token）。 */
    fun refresh() = indicator.repaint()

    private fun moveTo(index: Int, animate: Boolean) {
        val tab = tabs.getOrNull(index) ?: return
        if (tab.width == 0 || host.width == 0) return
        val tabLoc = IntArray(2)
        val hostLoc = IntArray(2)
        tab.getLocationInWindow(tabLoc)
        host.getLocationInWindow(hostLoc)
        val targetLeft = (tabLoc[0] - hostLoc[0]).toFloat()
        val targetRight = targetLeft + tab.width

        if (!animate) {
            leftSpring?.cancel()
            rightSpring?.cancel()
            indicator.setBounds(targetLeft, targetRight)
            return
        }
        leftSpring?.cancel()
        rightSpring?.cancel()
        lastLeftValue = indicator.leftEdge
        lastRightValue = indicator.rightEdge
        lastLeftDelta = 0f
        lastRightDelta = 0f
        leftSpring = spring(indicator.leftEdge, targetLeft) { value ->
            lastLeftDelta = kotlin.math.abs(value - lastLeftValue)
            lastLeftValue = value
            indicator.setLeftEdge(value)
            indicator.noteVelocity(kotlin.math.max(lastLeftDelta, lastRightDelta))
        }
        rightSpring = spring(indicator.rightEdge, targetRight) { value ->
            lastRightDelta = kotlin.math.abs(value - lastRightValue)
            lastRightValue = value
            indicator.setRightEdge(value)
            indicator.noteVelocity(kotlin.math.max(lastLeftDelta, lastRightDelta))
        }
    }

    private fun spring(from: Float, to: Float, apply: (Float) -> Unit): SpringAnimation {
        return SpringAnimation(FloatValueHolder(from)).apply {
            setSpring(
                SpringForce(to).apply {
                    dampingRatio = 0.72f
                    stiffness = 380f
                }
            )
            addUpdateListener { _, value, _ -> apply(value) }
            addEndListener { _, _, _, _ -> indicator.noteVelocity(0f) }
            start()
        }
    }

    private inner class LensDrawable(private val view: View) : Drawable() {

        var leftEdge = 0f
            private set
        var rightEdge = 0f
            private set
        var selectedIndex = -1

        /** 速度耦合拉伸：x 最大 1.12，y 同步压到 0.96。 */
        private var stretchX = 0f
        private var squashY = 0f

        /**
         * 切换/回弹期间的温和微色散脉冲（0~0.14）。
         * 与底栏按压同源：只在透镜真正在动的时候出现，静止透镜保持纯白无彩边。
         */
        private var dispersion = 0f

        fun noteVelocity(deltaPx: Float) {
            val normalized = (deltaPx / (10.5f * density)).coerceIn(0f, 1f)
            val sx = normalized * 0.12f
            val sy = normalized * 0.04f
            val dz = normalized * 0.14f
            if (kotlin.math.abs(sx - stretchX) > 0.0015f ||
                kotlin.math.abs(sy - squashY) > 0.0005f ||
                kotlin.math.abs(dz - dispersion) > 0.002f
            ) {
                stretchX = sx
                squashY = sy
                dispersion = dz
                invalidateSelf()
            }
        }

        fun setLeftEdge(value: Float) {
            leftEdge = value
            invalidateSelf()
        }

        fun setRightEdge(value: Float) {
            rightEdge = value
            invalidateSelf()
        }

        fun setBounds(left: Float, right: Float) {
            leftEdge = left
            rightEdge = right
            invalidateSelf()
        }

        fun repaint() = invalidateSelf()

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
        }

        override fun draw(canvas: Canvas) {
            if (rightEdge <= leftEdge) return
            val l = leftEdge + 2f * density
            val r = (rightEdge - 2f * density).coerceAtLeast(l + 12f * density)
            val t = 2f * density
            val b = view.height - 2f * density
            if (b <= t) return
            val cx = (l + r) / 2f
            val cy = (t + b) / 2f
            val halfW = (r - l) / 2f * (1f + stretchX)
            val halfH = (b - t) / 2f * (1f - squashY)
            GlassLensRenderer.drawInnerLens(
                canvas = canvas,
                context = view.context,
                left = cx - halfW,
                top = cy - halfH,
                right = cx + halfW,
                bottom = cy + halfH,
                radius = halfH,
                density = density,
                dispersion = dispersion
            )
        }

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
