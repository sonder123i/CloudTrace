package com.zcshou.gogogo.run.guard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.zcshou.gogogo.run.data.db.RunScheduleEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * 定时窗口调度：windowStartMinutes + random(0..jitter)，按重复日掩码顺延，
 * 优先 setExactAndAllowWhileIdle，无精确闹钟权限时降级 setAndAllowWhileIdle。
 */
object RunScheduleAlarmManager {

    private const val REQUEST_BASE = 4000

    /** 跳过日期键的格式：与 skipDateIso 持久化值严格同源 */
    private const val SKIP_DATE_PATTERN = "yyyy-MM-dd"

    private val DAY_LABELS = charArrayOf('一', '二', '三', '四', '五', '六', '日')

    fun scheduleNext(
        context: Context,
        schedule: RunScheduleEntity,
        fromTrigger: Boolean = false
    ): Long? {
        if (!schedule.isScheduleEnabled) {
            cancel(context, schedule.id)
            return null
        }
        val triggerAt = computeNextTrigger(schedule, minDayOffset = if (fromTrigger) 1 else 0)
        val pi = pendingIntent(context, schedule.id)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
        return triggerAt
    }

    fun cancel(context: Context, scheduleId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { am.cancel(pendingIntent(context, scheduleId)) }
    }

    /**
     * @param jitterMinutes 覆盖随机延迟（纯展示或单测传 0 可得到确定的候选时刻）
     */
    fun computeNextTrigger(
        schedule: RunScheduleEntity,
        now: Long = System.currentTimeMillis(),
        minDayOffset: Int = 0,
        jitterMinutes: Int = -1
    ): Long {
        val jitter = if (jitterMinutes >= 0) {
            jitterMinutes
        } else if (schedule.windowJitterMinutes > 0) {
            Random.nextInt(0, schedule.windowJitterMinutes + 1)
        } else {
            0
        }
        for (offset in minDayOffset..(minDayOffset + 8)) {
            val cal = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, schedule.windowStartMinutes / 60)
                set(Calendar.MINUTE, schedule.windowStartMinutes % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.MINUTE, jitter)
            }
            if (cal.timeInMillis <= now + 5_000) continue
            if (!isDayAllowed(schedule.repeatDaysMask, cal)) continue
            // 「跳过本次」：命中被标记的那一天就整日顺延，绝不落到该日触发
            if (schedule.skipDateIso != null && skipDateIso(cal.timeInMillis) == schedule.skipDateIso) {
                continue
            }
            return cal.timeInMillis
        }
        return now + 24 * 3600_000L
    }

    /** bit0=周一 … bit6=周日；mask=0 视为每天 */
    fun isDayAllowed(mask: Int, cal: Calendar): Boolean {
        if (mask == 0) return true
        val index = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            else -> 6
        }
        return (mask shr index) and 1 == 1
    }

    /** 某时刻对应的 skipDateIso 键（本地时区自然日） */
    fun skipDateIso(millis: Long): String =
        SimpleDateFormat(SKIP_DATE_PATTERN, Locale.US).format(Date(millis))

    fun todayIso(now: Long = System.currentTimeMillis()): String = skipDateIso(now)

    /**
     * 重复日掩码的人话描述：「每天」/「工作日」/「周一·三·五」。
     * 掩码为 0 或全 7 位都与「每天」等价，统一显示成同一句。
     */
    fun describeRepeatDays(mask: Int): String {
        if (mask == 0 || mask and 0b1111111 == 0b1111111) return "每天"
        if (mask == 0b0011111) return "工作日"
        val sb = StringBuilder()
        for (i in 0 until 7) {
            if ((mask shr i) and 1 == 1) {
                if (sb.isEmpty()) sb.append("周") else sb.append('·')
                sb.append(DAY_LABELS[i])
            }
        }
        return sb.toString()
    }

    /**
     * 距离下次触发的人话倒计时：「明早 07:15 · 6小时后」。
     * 纯函数（不读系统时钟），便于单测覆盖跨月/跨年边界。
     */
    fun formatNextCountdown(nextTriggerMs: Long, nowMs: Long): String {
        val remainMs = nextTriggerMs - nowMs
        if (remainMs <= 0L) return "即将开跑"
        if (remainMs < 5 * 60_000L) return "即将开跑"
        val next = Calendar.getInstance().apply { timeInMillis = nextTriggerMs }
        val now = Calendar.getInstance().apply { timeInMillis = nowMs }
        val dayDiff = (next.get(Calendar.YEAR) * 1000L + next.get(Calendar.DAY_OF_YEAR)) -
            (now.get(Calendar.YEAR) * 1000L + now.get(Calendar.DAY_OF_YEAR))
        val time = String.format(
            Locale.US,
            "%02d:%02d",
            next.get(Calendar.HOUR_OF_DAY),
            next.get(Calendar.MINUTE)
        )
        val period = when (next.get(Calendar.HOUR_OF_DAY)) {
            in 5..10 -> "早"
            in 11..12 -> "上午"
            in 13..17 -> "下午"
            in 18..22 -> "晚"
            else -> "凌晨"
        }
        val head = when {
            dayDiff <= 0L -> "$period $time"
            dayDiff == 1L -> "明$period $time"
            dayDiff == 2L -> "后天 $time"
            else -> SimpleDateFormat("M月d日", Locale.CHINA).format(Date(nextTriggerMs)) + " $time"
        }
        val remain = when {
            remainMs < 3600_000L -> "${(remainMs + 59_999L) / 60_000L}分钟后"
            remainMs < 24 * 3600_000L -> "${remainMs / 3600_000L}小时后"
            else -> "${remainMs / (24 * 3600_000L)}天后"
        }
        return "$head · $remain"
    }

    private fun pendingIntent(context: Context, scheduleId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_BASE + scheduleId.toInt(),
            Intent(context, RunScheduleAlarmReceiver::class.java)
                .putExtra(RunScheduleAlarmReceiver.EXTRA_SCHEDULE_ID, scheduleId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}