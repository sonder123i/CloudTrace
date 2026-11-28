package com.zcshou.gogogo.mascot

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.zcshou.gogogo.R

/**
 * 鲸鲸探头组件：玻璃台词气泡 + 透明鲸鲸图。
 *
 * - 底部居中锚点做轻微呼吸（scaleY 1.0↔1.03，2.8s）
 * - 点击触发果冻 Spring + 触觉 + 转动画 + 随机台词
 * - 关闭 / 不可见 / 后台 / detach 时停止全部动画
 *
 * 播放层目前是 [StaticMascotPlayer]；以后拿到 Lottie/Rive 文件时，
 * 只需替换这里构造的 player，不动页面与设置。
 */
class MascotPeekView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val imageView: ImageView
    private val bubble: TextView
    private val player: MascotPlayer

    private var mode = MascotMode.OFF
    private var state = MascotState.PEEK_IDLE
    private var breathing: ValueAnimator? = null
    private var springX: SpringAnimation? = null
    private var springY: SpringAnimation? = null
    private var pendingReset: Runnable? = null
    private var bubbleHide: Runnable? = null
    private var bubbleGeneration = 0
    private var dialogueState = MascotDialogueEngine.State.IDLE
    private var bubbleBelow = false

    /** 连戳彩蛋：由宿主弹 `egg_poke_pressure` 等小卡。 */
    var onPokeCombo: (() -> Unit)? = null

    init {
        clipChildren = false
        clipToPadding = false
        LayoutInflater.from(context).inflate(R.layout.view_mascot_peek, this, true)
        imageView = findViewById(R.id.mascotImage)
        bubble = findViewById(R.id.mascotBubble)
        player = StaticMascotPlayer(imageView)
        if (isDark()) {
            imageView.background =
                ContextCompat.getDrawable(context, R.drawable.bg_mascot_glow)
        }
        visibility = View.GONE

        imageView.post { updatePivot() }
        imageView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                updatePivot()
            }
        }
        imageView.setOnClickListener { onMascotTapped() }
        bubble.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            // 气泡浮在头顶/脚下外侧，不参与测量，避免出现气泡时鲸鲸跳位。
            bubble.translationY = bubbleOffset()
        }
    }

    /**
     * 绑定模式和状态。
     *
     * OFF 时会立刻 GONE 并停止动画，不占任何位置；[dialogueState] 决定
     * 点击鲸鲸和自动冒泡时用哪一组本地台词。
     */
    fun bind(
        mode: MascotMode,
        state: MascotState,
        dialogueState: MascotDialogueEngine.State = MascotDialogueEngine.State.IDLE
    ) {
        this.mode = mode
        this.state = state
        this.dialogueState = dialogueState
        if (mode == MascotMode.OFF) {
            visibility = View.GONE
            stopAll()
            return
        }

        visibility = View.VISIBLE
        player.show(state)
        startBreathing()
    }

    fun setState(state: MascotState) {
        if (this.state == state) return
        this.state = state
        if (mode != MascotMode.OFF) player.show(state)
    }

    fun showBubble(text: String, autoHideMs: Long = 3500L) {
        if (mode == MascotMode.OFF) return
        bubbleGeneration++
        val generation = bubbleGeneration
        bubbleHide?.let { bubble.removeCallbacks(it) }
        bubble.animate().cancel()
        bubble.text = text
        bubble.visibility = View.VISIBLE
        bubble.alpha = 0f
        bubble.scaleX = 0.94f
        bubble.scaleY = 0.94f
        bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160L).start()

        val hide = Runnable { hideBubble(generation) }
        bubbleHide = hide
        bubble.postDelayed(hide, autoHideMs)
    }

    /** 由宿主在页面稳定后触发；带 18s 冷却，返回是否真的弹了。 */
    fun showAutoBubble(bypassCooldown: Boolean = false): Boolean {
        if (mode == MascotMode.OFF) return false
        val line = MascotDialogueEngine.onSystemEvent(dialogueState, bypassCooldown) ?: return false
        showBubble(line)
        return true
    }

    private fun hideBubble(generation: Int = -1) {
        bubbleHide?.let { bubble.removeCallbacks(it) }
        bubbleHide?.let { removeCallbacks(it) }
        bubbleHide = null
        if (bubble.visibility != View.VISIBLE) return
        val current = generation < 0 || generation == bubbleGeneration
        bubble.animate().cancel()
        if (!current) return
        bubble.animate()
            .alpha(0f)
            .scaleX(0.94f)
            .scaleY(0.94f)
            .setDuration(140L)
            .start()
        val finishHide = Runnable {
            if (bubbleGeneration == generation || generation < 0) {
                bubble.visibility = View.GONE
            }
            bubbleHide = null
        }
        bubbleHide = finishHide
        postDelayed(finishHide, 150L)
    }

    private fun hideBubbleImmediately() {
        bubbleGeneration++
        bubbleHide?.let { bubble.removeCallbacks(it) }
        bubbleHide?.let { removeCallbacks(it) }
        bubbleHide = null
        bubble.animate().cancel()
        bubble.visibility = View.GONE
    }

    /** 宿主不可见/切后台时调用；保留当前画面，只暂停动画。 */
    fun pause() {
        stopAll()
    }

    /** 探出父容器时由 [MascotTouchProxy] 转发点击。 */
    fun triggerPoke() {
        onMascotTapped()
    }

    fun release() {
        stopAll()
        player.release()
    }

    private fun onMascotTapped() {
        if (mode == MascotMode.OFF || visibility != View.VISIBLE) return
        val haptic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        imageView.performHapticFeedback(haptic)

        stopBreathing()
        pendingReset?.let { imageView.removeCallbacks(it) }
        springX?.cancel()
        springY?.cancel()

        imageView.scaleX = 1.12f
        imageView.scaleY = 0.88f
        springX = spring(imageView, DynamicAnimation.SCALE_X, SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY)
        springY = spring(imageView, DynamicAnimation.SCALE_Y, SpringForce.DAMPING_RATIO_HIGH_BOUNCY)
        springX?.start()
        springY?.start()

        player.show(MascotState.CHEER)
        val poke = MascotDialogueEngine.onUserPokeResult(dialogueState)
        showBubble(poke.text)
        if (poke.combo) onPokeCombo?.invoke()

        val reset = Runnable {
            if (mode != MascotMode.OFF) {
                player.show(state)
                startBreathing()
            }
        }
        pendingReset = reset
        imageView.postDelayed(reset, 2500L)
    }

    private fun spring(
        view: View,
        property: DynamicAnimation.ViewProperty,
        damping: Float
    ): SpringAnimation = SpringAnimation(view, property, 1f).apply {
        spring = SpringForce(1f).apply {
            dampingRatio = damping
            stiffness = SpringForce.STIFFNESS_MEDIUM
        }
    }

    private fun startBreathing() {
        if (mode == MascotMode.OFF || !isShown || breathing != null) return
        imageView.scaleX = 1f
        breathing = ValueAnimator.ofFloat(1f, 1.03f).apply {
            duration = 2800L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { imageView.scaleY = it.animatedValue as Float }
            start()
        }
    }

    private fun stopBreathing() {
        breathing?.cancel()
        breathing = null
        imageView.scaleY = 1f
    }

    private fun stopAll() {
        pendingReset?.let { imageView.removeCallbacks(it) }
        pendingReset = null
        springX?.cancel()
        springY?.cancel()
        springX = null
        springY = null
        stopBreathing()
        hideBubbleImmediately()
        player.stop()
    }

    private fun updatePivot() {
        imageView.pivotX = imageView.width / 2f
        imageView.pivotY = imageView.height.toFloat()
    }

    /** 首页放在卡片顶部时，气泡朝下可以避开上方的周数据卡。 */
    fun setBubbleBelow(below: Boolean) {
        bubbleBelow = below
        bubble.translationY = bubbleOffset()
    }

    private fun bubbleOffset(): Float {
        val gap = (bubble.height + dp(4)).toFloat()
        return if (bubbleBelow) gap else -gap
    }

    private fun isDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) {
            stopAll()
        } else if (mode != MascotMode.OFF) {
            player.show(state)
            startBreathing()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (mode != MascotMode.OFF && isShown) startBreathing()
    }

    override fun onDetachedFromWindow() {
        stopAll()
        super.onDetachedFromWindow()
    }
}