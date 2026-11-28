package com.zcshou.gogogo.mascot

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.zcshou.gogogo.R

/**
 * 地图上的鲸鲸小人（仅 FULL 模式）。
 *
 * 刻意不用 Baidu Marker：`MapStyleHelper` 会给整个 TextureMapView 加暗夜
 * RenderEffect，Marker 会跟着反相。这里用地图上方的普通 View overlay，
 * 位置通过 `projection.toScreenLocation()` 跟随地图平移/缩放。
 *
 * 移动时两帧每 250ms 交替，静止定格在 frame1；左右镜像在初始化时预合成，
 * 运行期只切换 Drawable，不产生每帧 Bitmap。
 */
class MapMascotOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val glow = View(context)
    private val image = ImageView(context)
    private val bubble = TextView(context)
    private var frame1Right: BitmapDrawable? = null
    private var frame1Left: BitmapDrawable? = null
    private var frame2Right: BitmapDrawable? = null
    private var frame2Left: BitmapDrawable? = null
    private val bitmaps = mutableListOf<Bitmap>()

    private var bobAnimator: ObjectAnimator? = null
    private var tiltAnimator: ObjectAnimator? = null
    private var ticker: Runnable? = null
    private var visible = false
    private var moving = false
    private var frameIndex = 0
    private var facingLeft = false
    private var lastMoveAt = 0L
    private var bubbleHide: Runnable? = null
    private var bubbleGeneration = 0

    init {
        isClickable = false
        isFocusable = false
        clipChildren = false

        glow.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        glow.isClickable = false
        if (isDark()) {
            glow.background = ContextCompat.getDrawable(context, R.drawable.bg_mascot_glow)
        }
        addView(glow)

        image.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        image.isClickable = false
        image.isFocusable = false
        addView(image)

        bubble.setBackgroundResource(R.drawable.bg_bubble)
        bubble.setTextColor(context.getColor(R.color.pf_text_primary))
        bubble.textSize = 12f
        bubble.setTypeface(bubble.typeface, android.graphics.Typeface.BOLD)
        bubble.maxLines = 3
        bubble.ellipsize = TextUtils.TruncateAt.END
        bubble.setMaxWidth((170 * resources.displayMetrics.density).toInt())
        bubble.setPadding(
            dp(10f).toInt(),
            dp(7f).toInt(),
            dp(10f).toInt(),
            dp(7f).toInt()
        )
        bubble.elevation = dp(3f)
        bubble.visibility = GONE
        bubble.layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        )
        addView(bubble)
        bubble.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            view.translationY = -(view.height + dp(4f))
        }

        visibility = GONE
    }

    private fun ensureBitmapsLoaded() {
        if (bitmaps.isNotEmpty()) return
        loadBitmaps()
    }

    private fun loadBitmaps() {
        val first = BitmapFactory.decodeResource(resources, R.drawable.map_jing_run1)
        val second = BitmapFactory.decodeResource(resources, R.drawable.map_jing_run2)
        if (first == null || second == null) return
        bitmaps += first
        bitmaps += second
        val firstLeft = mirrored(first)
        val secondLeft = mirrored(second)
        bitmaps += firstLeft
        bitmaps += secondLeft
        frame1Right = BitmapDrawable(resources, first)
        frame1Left = BitmapDrawable(resources, firstLeft)
        frame2Right = BitmapDrawable(resources, second)
        frame2Left = BitmapDrawable(resources, secondLeft)
        applyFrame()
    }

    private fun mirrored(source: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(result).drawBitmap(
            source,
            Matrix().apply { setScale(-1f, 1f, source.width / 2f, source.height / 2f) },
            null
        )
        return result
    }

    /** 屏幕坐标（地图 View 本地坐标），脚底对准定位点。 */
    fun showAt(x: Int, y: Int, bearing: Float, movingNow: Boolean) {
        if (!isAttachedToWindow) {
            visibility = GONE
            return
        }
        ensureBitmapsLoaded()
        if (!visible) {
            visible = true
            visibility = VISIBLE
            startBob()
        }
        if (movingNow) lastMoveAt = SystemClock.elapsedRealtime()
        val size = dp(56f)
        translationX = x - size / 2f
        translationY = y - size
        setBearing(bearing)
        setMoving(movingNow)
    }

    fun setBearing(bearing: Float) {
        val normalized = ((bearing % 360f) + 360f) % 360f
        val nextFacingLeft = normalized in 90f..270f
        if (nextFacingLeft != facingLeft) {
            facingLeft = nextFacingLeft
            applyFrame()
        }
    }

    /** 地图上的鲸鲸气泡；仅移动/发车等系统事件时由宿主触发。 */
    fun showBubble(text: String, autoHideMs: Long = 3500L) {
        if (!visible) return
        bubbleGeneration++
        val generation = bubbleGeneration
        bubbleHide?.let { bubble.removeCallbacks(it) }
        bubble.animate().cancel()
        bubble.text = text
        bubble.visibility = View.VISIBLE
        bubble.alpha = 1f
        bubble.scaleX = 0.95f
        bubble.scaleY = 0.95f
        bubble.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
        val hide = Runnable {
            if (bubbleGeneration == generation) bubble.visibility = View.GONE
        }
        bubbleHide = hide
        bubble.postDelayed(hide, autoHideMs)
    }

    private fun setMoving(movingNow: Boolean) {
        if (moving == movingNow) return
        moving = movingNow
        if (movingNow) {
            lastMoveAt = SystemClock.elapsedRealtime()
            startTicker()
        } else {
            stopTicker()
            frameIndex = 0
            applyFrame()
        }
    }

    private fun applyFrame() {
        val drawable = when {
            frameIndex == 0 && facingLeft -> frame1Left
            frameIndex == 0 -> frame1Right
            facingLeft -> frame2Left
            else -> frame2Right
        }
        image.setImageDrawable(drawable)
    }

    private fun startTicker() {
        if (ticker != null) return
        val run = object : Runnable {
            override fun run() {
                if (!visible || !moving) return
                if (SystemClock.elapsedRealtime() - lastMoveAt > 400L) {
                    moving = false
                    frameIndex = 0
                    applyFrame()
                    stopTicker()
                    return
                }
                frameIndex = 1 - frameIndex
                applyFrame()
                image.postDelayed(this, 250L)
            }
        }
        ticker = run
        image.postDelayed(run, 250L)
    }

    private fun stopTicker() {
        ticker?.let { image.removeCallbacks(it) }
        ticker = null
    }

    fun hide() {
        visible = false
        moving = false
        lastMoveAt = 0L
        bubbleGeneration++
        bubbleHide?.let { bubble.removeCallbacks(it) }
        bubbleHide = null
        bubble.visibility = GONE
        visibility = GONE
        stopTicker()
        stopBob()
        frameIndex = 0
    }

    fun pause() {
        stopTicker()
        stopBob()
    }

    fun release() {
        hide()
        image.setImageDrawable(null)
        frame1Right = null
        frame1Left = null
        frame2Right = null
        frame2Left = null
        bitmaps.forEach { it.recycle() }
        bitmaps.clear()
    }

    private fun startBob() {
        if (bobAnimator != null) return
        val travel = dp(3f)
        bobAnimator = ObjectAnimator.ofFloat(image, View.TRANSLATION_Y, 0f, -travel).apply {
            duration = 320L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        tiltAnimator = ObjectAnimator.ofFloat(image, View.ROTATION, -3f, 3f).apply {
            duration = 640L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopBob() {
        bobAnimator?.cancel()
        tiltAnimator?.cancel()
        bobAnimator = null
        tiltAnimator = null
        image.translationY = 0f
        image.rotation = 0f
    }

    private fun isDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    override fun onDetachedFromWindow() {
        stopTicker()
        stopBob()
        super.onDetachedFromWindow()
    }
}