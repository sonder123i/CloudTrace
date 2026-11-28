package com.zcshou.gogogo.mascot

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.zcshou.gogogo.R

/**
 * 首页大标题旁的 32dp 迷你鲸鲸角标。
 *
 * 与 [MascotPeekView] 共用同一套台词引擎与连戳彩蛋，但尺寸只占一行标题的高度：
 * 有路线之后首页不再展示 72dp 大探头，这个角标保证鲸鲸「永远在场」。
 *
 * 动画纪律：只在可见时呼吸；`pause()` / 不可见 / detach 立即停掉 ValueAnimator。
 */
class MascotMiniBadgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val imageView: ImageView
    private val bubble: TextView
    private val player: MascotPlayer

    private var mode = MascotMode.OFF
    private var dialogueState = MascotDialogueEngine.State.IDLE
    private var breathing: ValueAnimator? = null
    private var bubbleHide: Runnable? = null
    private var bubbleGeneration = 0
    private var lastPokeAtMs = 0L
    private var pokeCount = 0

    /** 连戳 4 次命中彩蛋时回调（宿主弹拍立得卡） */
    var onPokeCombo: (() -> Unit)? = null

    init {
        clipChildren = false
        clipToPadding = false
        LayoutInflater.from(context).inflate(R.layout.view_mascot_mini_badge, this, true)
        imageView = findViewById(R.id.miniMascotImage)
        bubble = findViewById(R.id.miniMascotBubble)
        player = StaticMascotPlayer(imageView)
        visibility = View.GONE
        imageView.setOnClickListener { onTapped() }
        bubble.setOnClickListener { hideBubbleImmediately() }
        // 气泡挂在 32dp 徽章内部，按 top|end 定位时会盖住鲸鲸的脸；
        // 下移到徽章底边之外，既露出表情又正好贴着下方的周数据玻璃条。
        bubble.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            bubble.translationY = bubbleOffset()
        }
    }

    /** 气泡相对徽章顶部的下移量：整张徽章高度 + 4dp 间隙。 */
    private fun bubbleOffset(): Float =
        height.toFloat() + 4f * resources.displayMetrics.density

    /**
     * 绑定模式与台词语境。OFF 时立刻 GONE 且不占位。
     */
    fun bind(
        mode: MascotMode,
        dialogueState: MascotDialogueEngine.State,
        visualState: MascotState = MascotState.PEEK_IDLE
    ) {
        this.mode = mode
        this.dialogueState = dialogueState
        if (mode == MascotMode.OFF) {
            visibility = View.GONE
            stopBreathing()
            hideBubbleImmediately()
            return
        }
        visibility = View.VISIBLE
        player.show(visualState)
        startBreathing()
    }

    fun setDialogueState(state: MascotDialogueEngine.State) {
        dialogueState = state
    }

    fun showBubble(text: String, autoHideMs: Long = 3200L) {
        if (mode == MascotMode.OFF) return
        bubbleGeneration++
        val generation = bubbleGeneration
        bubbleHide?.let { bubble.removeCallbacks(it) }
        bubble.animate().cancel()
        bubble.text = text
        bubble.visibility = View.VISIBLE
        bubble.alpha = 0f
        bubble.scaleX = 0.92f
        bubble.scaleY = 0.92f
        bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150L).start()
        val hide = Runnable { hideBubble(generation) }
        bubbleHide = hide
        bubble.postDelayed(hide, autoHideMs)
    }

    fun pause() {
        stopBreathing()
        hideBubbleImmediately()
    }

    fun release() {
        stopBreathing()
        hideBubbleImmediately()
        player.release()
    }

    private fun onTapped() {
        if (mode == MascotMode.OFF || visibility != View.VISIBLE) return
        imageView.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.CLOCK_TICK
            }
        )
        // 与 MascotPeekView 同一判定口径：4.5s 窗口内连戳 4 次解锁 POKE_COMBO
        val now = System.currentTimeMillis()
        pokeCount = if (now - lastPokeAtMs <= COMBO_WINDOW_MS) pokeCount + 1 else 1
        lastPokeAtMs = now

        val poke = MascotDialogueEngine.onUserPokeResult(dialogueState)
        showBubble(poke.text)
        player.show(MascotState.CHEER)
        if (poke.combo) {
            pokeCount = 0
            onPokeCombo?.invoke()
        }
        imageView.postDelayed({ if (mode != MascotMode.OFF) player.show(MascotState.PEEK_IDLE) }, 1800L)
    }

    private fun hideBubble(generation: Int) {
        if (generation != bubbleGeneration) return
        bubbleHide?.let { bubble.removeCallbacks(it) }
        bubbleHide = null
        bubble.animate().alpha(0f).setDuration(120L).start()
        val finish = Runnable {
            if (bubbleGeneration == generation) bubble.visibility = View.GONE
        }
        postDelayed(finish, 130L)
    }

    private fun hideBubbleImmediately() {
        bubbleGeneration++
        bubbleHide?.let { bubble.removeCallbacks(it) }
        bubbleHide = null
        bubble.animate().cancel()
        bubble.visibility = View.GONE
    }

    private fun startBreathing() {
        if (breathing != null) return
        breathing = ValueAnimator.ofFloat(1.0f, 1.05f, 1.0f).apply {
            duration = 2_600L
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                val scale = anim.animatedValue as Float
                imageView.scaleY = scale
                imageView.scaleX = 2f - scale
            }
            start()
        }
    }

    private fun stopBreathing() {
        breathing?.cancel()
        breathing = null
        imageView.scaleX = 1f
        imageView.scaleY = 1f
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) stopBreathing() else if (mode != MascotMode.OFF) startBreathing()
    }

    override fun onDetachedFromWindow() {
        stopBreathing()
        hideBubbleImmediately()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val COMBO_WINDOW_MS = 4_500L
    }
}
