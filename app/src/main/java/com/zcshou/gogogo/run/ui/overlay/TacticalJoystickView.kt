package com.zcshou.gogogo.run.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.ui.theme.FiberStyle
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * 原版手感战术摇杆：
 * 中心球直径 ≈ 盘径 40%；必须按球起步；轻点球切换锁定；
 * 锁定后松手保持角度与力度继续走，解锁后松手回中停止。
 */
class TacticalJoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        fun onSteer(bearingDeg: Float, strength: Float)
        fun onLockChanged(locked: Boolean)
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val dp = { v: Float -> v * density }

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.dr_overlay_round)
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.dr_overlay_rim_silver)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.dr_overlay_text_secondary)
        alpha = 0x2E
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.dr_overlay_ice)
        setShadowLayer(dp(12f), 0f, 0f, context.getColor(R.color.dr_ice_a45))
    }
    private val ballStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.dr_overlay_text_primary)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        color = context.getColor(R.color.dr_overlay_text_primary)
    }
    private val lockPath = Path()

    private var cx = 0f
    private var cy = 0f
    private var outerRadius = 0f
    private var ballRadius = 0f
    private var travel = 0f

    private var knobX = 0f
    private var knobY = 0f
    private var activePointer = -1
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var lastBearing = 0f
    private var lastStrength = 0f
    private var springKnobToX: SpringAnimation? = null
    private var springKnobToY: SpringAnimation? = null

    var isLocked: Boolean = false
        private set

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(120f).toInt()
        val w = resolveSize(size, widthMeasureSpec)
        val h = resolveSize(size, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        outerRadius = min(w, h) / 2f - dp(2f)
        ballRadius = outerRadius * 0.40f
        travel = (outerRadius - ballRadius).coerceAtLeast(1f)
        knobX = 0f
        knobY = 0f
        // V4.0 光纤渐变摇杆球
        ballPaint.shader = FiberStyle.linear(context, w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawCircle(cx, cy, outerRadius, basePaint)
        canvas.drawCircle(cx, cy, outerRadius, strokePaint)
        canvas.drawCircle(cx, cy, ballRadius + travel * 0.55f, ringPaint)

        val kx = cx + knobX
        val ky = cy + knobY
        if (isLocked) {
            canvas.drawLine(cx, cy, kx, ky, ringPaint)
        }
        canvas.drawCircle(kx, ky, ballRadius, ballPaint)
        canvas.drawCircle(kx, ky, ballRadius, ballStrokePaint)
        drawLockInBall(canvas, kx, ky)
    }

    private val lockBodyRect = RectF()
    private val lockArcRect = RectF()

    /** 锁图标直接画在球里，锁定=闭合锁，解锁=打开锁 */
    private fun drawLockInBall(canvas: Canvas, x: Float, y: Float) {
        val bodyW = ballRadius * 0.62f
        val bodyH = ballRadius * 0.46f
        val bodyTop = y - bodyH * 0.15f
        lockPaint.alpha = if (isLocked) 230 else 110
        lockPath.reset()
        lockBodyRect.set(x - bodyW / 2f, bodyTop, x + bodyW / 2f, bodyTop + bodyH)
        lockPath.addRoundRect(lockBodyRect, bodyW * 0.18f, bodyW * 0.18f, Path.Direction.CW)
        if (isLocked) {
            lockArcRect.set(
                x - bodyW * 0.32f,
                bodyTop - bodyH * 0.75f,
                x + bodyW * 0.32f,
                bodyTop + bodyH * 0.1f
            )
            lockPath.addArc(lockArcRect, 180f, 180f)
        } else {
            // 开锁：锁梁右移、只画半圈
            lockArcRect.set(
                x - bodyW * 0.10f,
                bodyTop - bodyH * 0.80f,
                x + bodyW * 0.60f,
                bodyTop + bodyH * 0.05f
            )
            lockPath.addArc(lockArcRect, 200f, 140f)
        }
        canvas.drawPath(lockPath, lockPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val ballCx = cx + knobX
                val ballCy = cy + knobY
                val distToBall = hypot(event.x - ballCx, event.y - ballCy)
                // 原版规则：必须按在中心球里（留 1.3 倍容差），否则交给外层拖窗/忽略
                if (distToBall > ballRadius * 1.3f) return false
                // 新按下时立刻打断正在进行的弹簧归位
                springKnobToX?.cancel()
                springKnobToY?.cancel()
                activePointer = event.getPointerId(0)
                downX = event.x
                downY = event.y
                moved = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val idx = if (activePointer >= 0) event.findPointerIndex(activePointer) else -1
                if (idx < 0) return true
                val px = event.getX(idx)
                val py = event.getY(idx)
                if (!moved && hypot(px - downX, py - downY) > dp(6f)) moved = true
                updateKnob(px, py)
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val actionIdx = event.actionIndex
                if (event.getPointerId(actionIdx) != activePointer) return true
                parent?.requestDisallowInterceptTouchEvent(false)
                activePointer = -1
                if (!moved) toggleLock() else if (!isLocked) recenterAndStop()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (activePointer < 0) return true
                activePointer = -1
                if (!moved) {
                    toggleLock()
                } else if (!isLocked) {
                    recenterAndStop()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateKnob(px: Float, py: Float) {
        var dx = px - cx
        var dy = py - cy
        val dist = hypot(dx, dy)
        val maxTravel = travel + ballRadius * 0.55f
        if (dist > maxTravel) {
            dx = dx / dist * maxTravel
            dy = dy / dist * maxTravel
        }
        knobX = dx
        knobY = dy
        lastBearing = bearingOf(dx, dy)
        val raw = (hypot(dx, dy) / maxTravel).coerceIn(0f, 1f)
        val strength = ((raw - DEAD_ZONE) / (1f - DEAD_ZONE)).coerceIn(0f, 1f)
        lastStrength = strength
        listener?.onSteer(lastBearing, strength)
        invalidate()
    }

    private fun toggleLock() {
        isLocked = !isLocked
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (isLocked) {
            lastStrength = 0f
            listener?.onSteer(lastBearing, 0f)
        } else {
            recenterAndStop()
        }
        listener?.onLockChanged(isLocked)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        springKnobToX?.cancel()
        springKnobToY?.cancel()
        super.onDetachedFromWindow()
    }

    /** 顶栏方向锁按钮共用入口 */
    fun toggleLockFromButton() = toggleLock()

    private fun recenterAndStop() {
        lastStrength = 0f
        listener?.onSteer(lastBearing, 0f)
        // V4.0：物理弹簧归位（阻尼 0.72 / 刚度 380），替代瞬移
        springKnobToX?.cancel()
        springKnobToY?.cancel()
        springKnobToX = SpringAnimation(FloatValueHolder(knobX)).apply {
            setSpring(
                SpringForce(0f).apply {
                    stiffness = 380f
                    dampingRatio = 0.72f
                }
            )
            addUpdateListener { _, value, _ ->
                knobX = value
                invalidate()
            }
            start()
        }
        springKnobToY = SpringAnimation(FloatValueHolder(knobY)).apply {
            setSpring(
                SpringForce(0f).apply {
                    stiffness = 380f
                    dampingRatio = 0.72f
                }
            )
            addUpdateListener { _, value, _ ->
                knobY = value
                invalidate()
            }
            start()
        }
    }

    fun unlock() {
        if (!isLocked) return
        isLocked = false
        recenterAndStop()
        listener?.onLockChanged(false)
    }

    /** 屏幕坐标（y 向下）转正北顺时针航向角 */
    private fun bearingOf(dx: Float, dy: Float): Float {
        val deg = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat()
        return (deg + 360f) % 360f
    }

    private companion object {
        const val DEAD_ZONE = 0.12f
    }
}
