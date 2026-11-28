package com.zcshou.gogogo.run.guard

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.ui.theme.ThemeHelper
import java.lang.ref.WeakReference

/**
 * 跳转系统设置期间的顶部迷你提示横幅（需悬浮窗权限；无权限降级 Toast）。
 */
object SetupHintBanner {

    private var viewRef: WeakReference<TextView>? = null
    private var wmRef: WeakReference<WindowManager>? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hide() }

    fun show(context: Context, text: String) {
        val ctx = ThemeHelper.themedContext(context.applicationContext)
        if (!Settings.canDrawOverlays(ctx)) {
            Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
            return
        }
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val existing = viewRef?.get()?.takeIf { it.isAttachedToWindow }
        val tv = existing ?: TextView(ctx).apply {
            setBackgroundResource(R.drawable.bg_capsule_solid)
            setTextColor(ctx.getColor(R.color.pf_text_primary))
            textSize = 12f
            setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10))
            maxWidth = dp(ctx, 320)
        }
        tv.text = text
        if (existing == null) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(ctx, 44)
            }
            runCatching { wm.addView(tv, params) }.onSuccess {
                viewRef = WeakReference(tv)
                wmRef = WeakReference(wm)
            }.onFailure { return }
        }
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, 30_000)
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        val tv = viewRef?.get()
        val wm = wmRef?.get()
        viewRef = null
        wmRef = null
        if (tv != null) {
            runCatching { wm?.removeViewImmediate(tv) }
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
