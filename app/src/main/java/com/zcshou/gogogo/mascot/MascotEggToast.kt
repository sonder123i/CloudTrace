package com.zcshou.gogogo.mascot

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * 首次解锁彩蛋时的轻量拍立得浮卡。
 *
 * 只在 [MascotMode] != OFF 时展示（`SULKING` 除外 —— 它本身就是「关掉伴侣」的产物，
 * 此时已经没有鲸鲸可以出来庆贺了）。浮卡 3.2 秒后自动消失，不阻塞任何操作。
 */
object MascotEggToast {

    private const val DURATION_MS = 3_200L

    /**
     * 尝试解锁并（首次时）弹出浮卡。
     *
     * @return true 表示首次解锁（调用方可据此追加其他动作）
     */
    fun unlockAndShow(context: Context, egg: MascotEgg): Boolean {
        val first = MascotEggStore.unlock(context, egg)
        if (!first) return false
        if (egg == MascotEgg.SULKING) return true
        if (MascotPrefs.mode(context) == MascotMode.OFF) return true
        show(context, egg)
        return true
    }

    /** 直接展示（不做解锁判定），用于已确认首解锁的场景 */
    fun show(context: Context, egg: MascotEgg) {
        val activity = context as? Activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        // 同一时刻只留一张浮卡：按 tag 清掉所有旧卡 —— 有对话框/snackbar 插入
        // 其它视图后浮卡未必还在末位，只查最后一个子 view 会漏掉导致叠卡
        while (true) {
            val stale = root.findViewWithTag<View>(TAG_EGG_TOAST) ?: break
            root.removeView(stale)
        }
        val card = MascotEggCardView(activity).apply { tag = TAG_EGG_TOAST }
        if (!card.bind(egg)) return
        val density = activity.resources.displayMetrics.density
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = (96 * density).toInt()
        }
        root.addView(card, params)
        card.alpha = 0f
        card.translationY = 24 * density
        card.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220L)
            .start()
        card.postDelayed({
            card.animate().alpha(0f).translationY(16 * density).setDuration(200L)
                .withEndAction { root.removeView(card) }
                .start()
        }, DURATION_MS)
    }

    private const val TAG_EGG_TOAST = "mascot_egg_toast"
}
