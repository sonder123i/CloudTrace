package com.zcshou.gogogo.run.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.zcshou.gogogo.R
import java.util.Calendar

/**
 * 月历打卡热力图：7 列 × 6 行，格内亮度 = 当日跑量相对当月峰值的比例。
 *
 * 性能红线：`onDraw` 内零分配 —— 日期文案取自静态字符串表，强度色在 [setMonth]
 * 里预计算成 IntArray，格子几何全部用 float 算术 + drawRoundRect(Float...) 绘制，
 * 不 new RectF / 不拼字符串。
 */
class HistoryCalendarHeatmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        color = ContextCompat.getColor(context, R.color.pf_text_primary)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * density
        textAlign = Paint.Align.CENTER
    }
    private val dayTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * density
        textAlign = Paint.Align.CENTER
    }

    private val textPrimary = ContextCompat.getColor(context, R.color.pf_text_primary)
    private val textSecondary = ContextCompat.getColor(context, R.color.pf_text_secondary)
    private val textOnStrong = ContextCompat.getColor(context, R.color.pf_on_primary)
    private val accent = ContextCompat.getColor(context, R.color.pf_volt)

    /** 5 档强度色：0 = 空档，1..4 从浅到深 */
    private val levelColors = IntArray(5)
    private val dayColors = IntArray(DAY_COUNT)
    private val dayMeters = FloatArray(DAY_COUNT)
    private val monthDays = IntArray(DAY_COUNT)

    private var year = 1970
    private var month = 1
    private var leadingBlanks = 0
    private var daysInMonth = 31
    private var selectedDay = -1

    private var onDayClickListener: ((Int) -> Unit)? = null

    private val gap = 5f * density
    private val headerHeight = 18f * density
    private var cellSize = 0f
    private var left = 0f
    private var top = 0f

    init {
        rebuildLevelColors()
    }

    private fun rebuildLevelColors() {
        levelColors[0] = ContextCompat.getColor(context, R.color.pf_stroke)
        levelColors[1] = withAlpha(accent, 44)
        levelColors[2] = withAlpha(accent, 96)
        levelColors[3] = withAlpha(accent, 160)
        levelColors[4] = withAlpha(accent, 226)
    }

    fun setOnDayClickListener(listener: ((Int) -> Unit)?) {
        onDayClickListener = listener
    }

    /**
     * 绑定某个月的跑量（下标 = 日 - 1，单位米）。空数组或月份非法时按空月渲染。
     */
    fun setMonth(year: Int, month: Int, meters: FloatArray, selectedDay: Int = -1) {
        this.year = year
        this.month = month
        this.selectedDay = selectedDay
        val calendar = Calendar.getInstance().apply {
            clear()
            set(year, month - 1, 1)
        }
        daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        // Calendar.SUNDAY = 1 … SATURDAY = 7，转成「周一为首列」的 0..6
        leadingBlanks = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
        var peak = 0f
        for (i in 0 until DAY_COUNT) {
            val value = if (i < meters.size) meters[i] else 0f
            dayMeters[i] = value
            monthDays[i] = if (i < daysInMonth) i + 1 else 0
            if (value > peak) peak = value
        }
        for (i in 0 until DAY_COUNT) {
            val value = dayMeters[i]
            val level = when {
                value <= 0f -> 0
                peak <= 0f -> 1
                else -> {
                    val ratio = value / peak
                    when {
                        ratio < 0.25f -> 1
                        ratio < 0.5f -> 2
                        ratio < 0.75f -> 3
                        else -> 4
                    }
                }
            }
            dayColors[i] = levelColors[level]
        }
        contentDescription = "${year}年${month}月打卡热力图"
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).takeIf {
            MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
        } ?: (320 * density).toInt()
        val available = width - paddingLeft - paddingRight - gap * 6
        cellSize = (available / 7f).coerceAtMost(34f * density)
        val height = paddingTop + paddingBottom + headerHeight + gap + (cellSize + gap) * ROWS - gap
        setMeasuredDimension(width, height.toInt().coerceAtLeast((120 * density).toInt()))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        left = paddingLeft + (w - paddingLeft - paddingRight - (cellSize * 7 + gap * 6)) / 2f
        top = paddingTop + headerHeight + gap
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cellSize <= 0f) return
        val radius = 7f * density
        val headers = WEEK_LABELS
        labelPaint.color = textSecondary
        val headerCenterY = paddingTop + headerHeight * 0.55f
        for (column in 0 until 7) {
            val cx = left + column * (cellSize + gap) + cellSize / 2f
            canvas.drawText(headers[column], cx, headerCenterY, labelPaint)
        }
        val textBaselineOffset = dayTextPaint.textSize * 0.35f
        for (index in 0 until DAY_COUNT) {
            val day = monthDays[index]
            if (day == 0) continue
            val slot = index + leadingBlanks
            val row = slot / 7
            val column = slot % 7
            val cellLeft = left + column * (cellSize + gap)
            val cellTop = top + row * (cellSize + gap)
            cellPaint.color = dayColors[index]
            canvas.drawRoundRect(
                cellLeft, cellTop, cellLeft + cellSize, cellTop + cellSize,
                radius, radius, cellPaint
            )
            if (day == selectedDay) {
                canvas.drawRoundRect(
                    cellLeft + 1f, cellTop + 1f,
                    cellLeft + cellSize - 1f, cellTop + cellSize - 1f,
                    radius, radius, selectedPaint
                )
            }
            dayTextPaint.color = when {
                dayColors[index] == levelColors[3] || dayColors[index] == levelColors[4] ->
                    textOnStrong
                day == selectedDay -> textPrimary
                else -> textSecondary
            }
            canvas.drawText(
                DAY_LABELS[day - 1],
                cellLeft + cellSize / 2f,
                cellTop + cellSize / 2f + textBaselineOffset,
                dayTextPaint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) return true
        if (event.actionMasked != MotionEvent.ACTION_UP) return false
        val hit = dayAt(event.x, event.y)
        if (hit > 0) {
            // 先走 performClick：无障碍服务的点击动作经由它派发
            performClick()
            onDayClickListener?.invoke(hit)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** 命中测试：纯算术，不构造 RectF */
    private fun dayAt(x: Float, y: Float): Int {
        if (cellSize <= 0f || y < top) return -1
        val column = ((x - left) / (cellSize + gap)).toInt()
        val row = ((y - top) / (cellSize + gap)).toInt()
        if (column !in 0 until 7 || row < 0 || row >= ROWS) return -1
        val slot = row * 7 + column
        val index = slot - leadingBlanks
        if (index < 0 || index >= daysInMonth) return -1
        return if (dayMeters[index] > 0f) monthDays[index] else -1
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private companion object {
        const val DAY_COUNT = 31
        const val ROWS = 6
        val WEEK_LABELS = arrayOf("一", "二", "三", "四", "五", "六", "日")
        /** 静态日期文案表：绘制阶段直接取用，避免每帧 toString() */
        val DAY_LABELS = Array(31) { (it + 1).toString() }
    }
}
