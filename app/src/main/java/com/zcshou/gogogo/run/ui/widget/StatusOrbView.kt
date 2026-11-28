package com.zcshou.gogogo.run.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.zcshou.gogogo.R

/**
 * V5.0 微呼吸光学指示灯。
 *
 * 6dp 核心圆点 + 一层同心光晕，三种状态都有轻微呼吸：
 * - 正常（true）：绿色 `#34C759`，**慢速浅呼吸**——幅度只有红色的六成、周期更长，
 *   表达「安静地活着」而不是「有事发生」。完全没有呼吸会让列表看起来是静态截图；
 * - 异常（false）：红色 `#FF3B30`，呼吸更深更快，天然吸引注意；
 * - 中性（null）：灰色常亮，不参与呼吸（它没有状态含义，动了反而干扰）。
 *
 * 动画在 View 不可见时立即 pause，移除时 cancel，避免后台耗电。
 */
class StatusOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val dp = { v: Float -> v * density }

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null
    private var breath = 1f

    init {
        // 初始状态就是 null；setState(null) 会因为状态未变而提前返回，
        // 必须在构造时把画笔设成中性灰，否则会保留 Paint 默认的黑色。
        corePaint.color = context.getColor(R.color.pf_neutral_dot)
        haloPaint.color = corePaint.color
    }

    var state: Boolean? = null
        private set

    fun setState(ok: Boolean?) {
        if (state == ok) return
        state = ok
        corePaint.color = context.getColor(
            when (ok) {
                true -> R.color.pf_ok
                false -> R.color.pf_bad
                null -> R.color.pf_neutral_dot
            }
        )
        haloPaint.color = corePaint.color
        // 正常与异常都呼吸，只有中性灰保持静止；状态在红/绿间切换时重建动画以切换周期
        if (ok == null) {
            stopBreathing()
        } else {
            animator?.cancel()
            animator = null
            startBreathing()
        }
        invalidate()
    }

    private fun startBreathing() {
        if (animator != null || !isShown) return
        if (com.zcshou.gogogo.run.data.prefs.DisplayPrefs.quality(context) ==
            com.zcshou.gogogo.run.data.prefs.DisplayPrefs.RenderQuality.BALANCED
        ) {
            breath = 1f
            return
        }
        // 绿色「安静呼吸」比红色「告警呼吸」更慢更浅：周期 2.6s vs 1.9s
        val alert = state == false
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (alert) 1_900L else 2_600L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                breath = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopBreathing() {
        animator?.cancel()
        animator = null
        breath = 1f
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (state != null) startBreathing()
    }

    override fun onDetachedFromWindow() {
        stopBreathing()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) {
            animator?.pause()
        } else if (state != null) {
            if (animator == null) startBreathing() else animator?.resume()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val core = dp(3f)

        when (state) {
            false -> {
                // 告警：光晕 22%→56%，核心 150→255，对比强烈
                haloPaint.alpha = (22 + 34 * breath).toInt().coerceIn(0, 255)
                corePaint.alpha = (150 + 105 * breath).toInt().coerceIn(0, 255)
            }
            true -> {
                // 正常：只在 38%~56% 光晕、88%~100% 核心之间轻轻起伏，
                // 幅度小到不抢注意力，但能看出「它在呼吸」
                haloPaint.alpha = (38 + 18 * breath).toInt().coerceIn(0, 255)
                corePaint.alpha = (224 + 31 * breath).toInt().coerceIn(0, 255)
            }
            else -> {
                haloPaint.alpha = 56
                corePaint.alpha = 255
            }
        }

        canvas.drawCircle(cx, cy, core * 2.5f, haloPaint)
        canvas.drawCircle(cx, cy, core, corePaint)
    }
}
