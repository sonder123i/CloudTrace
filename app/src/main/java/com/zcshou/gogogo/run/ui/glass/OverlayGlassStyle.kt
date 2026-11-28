package com.zcshou.gogogo.run.ui.glass

import android.annotation.SuppressLint
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import java.util.WeakHashMap
import java.util.function.Consumer
import kotlin.math.max

/**
 * V4.0 跨应用悬浮窗玻璃工具。
 *
 * - API 31+ 开启背景模糊（setBlurBehindRadius 36dp）；
 * - 监听系统跨窗模糊可用性：小米 HyperOS / OPPO ColorOS / vivo OriginOS 等省电策略
 *   可能关闭第三方跨窗模糊，宿主收到 disabled 回调后必须把底色不透明度提到 92%+，
 *   严禁出现"无模糊且全透明导致文字与底层运动 App 重叠"；
 * - 松手后 SpringAnimation 弹性吸附屏幕左右边缘（阻尼 0.72 / 刚度 380）。
 */
object OverlayGlassStyle {

    const val BLUR_BEHIND_RADIUS_DP = 36f

    /**
     * 开启窗口背景模糊。返回 false 表示系统不支持（API < 31）。
     *
     * 注意：只可用于应用内的 Dialog / Activity 窗口。
     * 严禁用于 TYPE_APPLICATION_OVERLAY 悬浮窗——Android 12+ 会为 blurBehindRadius
     * 生成一层全屏 "Dim Layer"（uid 1000），输入分发器把这层视为遮挡物，把悬浮窗
     * 范围之外的所有触摸都判为 untrusted 并丢弃，表现为"悬浮窗把下层页面卡死"。
     */
    fun enableBlurBehind(lp: WindowManager.LayoutParams, radiusPx: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return runCatching {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            lp.setBlurBehindRadius(radiusPx.toInt())
            true
        }.getOrDefault(false)
    }

    /**
     * 注册跨窗模糊可用性监听（平台文档：注册时会立即回调一次当前状态）。
     * @return 反注册用的 token（传回 [unwatchCrossWindowBlur]），不可用或失败时为 null。
     */
    fun watchCrossWindowBlur(
        windowManager: WindowManager,
        onEnabledChanged: (Boolean) -> Unit
    ): Any? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            onEnabledChanged(false)
            return null
        }
        val consumer = Consumer<Boolean> { onEnabledChanged(it) }
        return runCatching {
            windowManager.addCrossWindowBlurEnabledListener(consumer)
            consumer
        }.getOrNull()
    }

    fun unwatchCrossWindowBlur(windowManager: WindowManager?, token: Any?) {
        if (windowManager == null || token == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val consumer = token as? Consumer<Boolean> ?: return
        runCatching { windowManager.removeCrossWindowBlurEnabledListener(consumer) }
    }

    /** 每个 View 同时只允许一个吸附弹簧：新拖拽开始时取消上一次，避免两个弹簧互抢 lp.x。 */
    private val snapAnims = WeakHashMap<View, SpringAnimation>()

    /**
     * 松手后弹簧吸附到最近的左右边缘，落定后回调最终 x（宿主持久化）。
     */
    fun springSnapToEdgeX(
        view: View,
        windowManager: WindowManager,
        lp: WindowManager.LayoutParams,
        onSettled: (Int) -> Unit
    ) {
        snapAnims[view]?.cancel()
        val metrics = view.resources.displayMetrics
        val margin = (8 * metrics.density).toInt()
        val leftX = margin
        val rightX = max(metrics.widthPixels - view.width - margin, leftX)
        val target =
            if (lp.x + view.width / 2 < metrics.widthPixels / 2) leftX else rightX

        val anim = SpringAnimation(FloatValueHolder(lp.x.toFloat())).apply {
            // dynamicanimation 1.0.0 中 SpringAnimation.spring 为 null，必须显式注入 SpringForce
            setSpring(
                SpringForce(target.toFloat()).apply {
                    stiffness = 380f
                    dampingRatio = 0.72f
                }
            )
            addUpdateListener { _, value, _ ->
                lp.x = value.toInt()
                runCatching { windowManager.updateViewLayout(view, lp) }
            }
            addEndListener { _, canceled, _, _ ->
                snapAnims.remove(view)
                if (!canceled) onSettled(lp.x)
            }
        }
        snapAnims[view] = anim
        anim.start()
    }

    /** 窗口被移除时调用：终止仍在运行的吸附弹簧，避免对已移除窗口反复 updateViewLayout。 */
    fun cancelSnap(view: View) {
        snapAnims.remove(view)?.cancel()
    }

    private val pressInterpolator = OvershootInterpolator(1.6f)

    /** 悬浮窗通用按压回弹：按下微缩 0.94 + 轻触觉反馈，松手/取消过冲弹回。 */
    @SuppressLint("ClickableViewAccessibility")
    fun attachTactilePress(vararg views: View) {
        views.forEach { target ->
            target.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(85L).start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(180L)
                            .setInterpolator(pressInterpolator)
                            .start()
                    }
                }
                false
            }
        }
    }
}