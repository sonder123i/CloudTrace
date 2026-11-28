package com.zcshou.gogogo.run.guard

import com.zcshou.gogogo.run.data.db.RunScheduleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class RunScheduleAlarmManagerTest {

    private fun calAt(year: Int, month: Int, day: Int, hour: Int, minute: Int): Calendar =
        Calendar.getInstance(TimeZone.getDefault()).apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }

    private fun triggerCal(schedule: RunScheduleEntity, now: Long, minDayOffset: Int = 0): Calendar =
        Calendar.getInstance().apply {
            timeInMillis = RunScheduleAlarmManager.computeNextTrigger(
                schedule = schedule,
                now = now,
                minDayOffset = minDayOffset,
                jitterMinutes = 0
            )
        }

    private fun dailySchedule(windowMinutes: Int = 18 * 60): RunScheduleEntity = RunScheduleEntity(
        routeId = 1,
        title = "每日",
        targetDistanceKm = 3f,
        isScheduleEnabled = true,
        windowStartMinutes = windowMinutes,
        windowJitterMinutes = 8,
        repeatDaysMask = 0
    )

    @Test
    fun `default weekday mask 0b0011111 allows Monday to Friday and excludes weekend`() {
        val schedule = RunScheduleEntity(routeId = 1, title = "工作日", targetDistanceKm = 3f)
        assertEquals(0b0011111, schedule.repeatDaysMask)

        // 2026-11-23 is Monday
        for (d in 23..27) {
            assertTrue(RunScheduleAlarmManager.isDayAllowed(schedule.repeatDaysMask, calAt(2026, Calendar.NOVEMBER, d, 8, 0)))
        }
        // 2026-11-28 Sat, 29 Sun
        assertFalse(RunScheduleAlarmManager.isDayAllowed(schedule.repeatDaysMask, calAt(2026, Calendar.NOVEMBER, 28, 8, 0)))
        assertFalse(RunScheduleAlarmManager.isDayAllowed(schedule.repeatDaysMask, calAt(2026, Calendar.NOVEMBER, 29, 8, 0)))
    }

    @Test
    fun `mask zero allows every day`() {
        for (d in 23..29) {
            assertTrue(RunScheduleAlarmManager.isDayAllowed(0, calAt(2026, Calendar.NOVEMBER, d, 8, 0)))
        }
    }

    @Test
    fun `computeNextTrigger with minDayOffset=1 never re-fires in same-day jitter window`() {
        val nowCal = calAt(2026, Calendar.NOVEMBER, 27, 18, 30)
        val schedule = RunScheduleEntity(
            routeId = 1,
            title = "每日",
            targetDistanceKm = 3f,
            isScheduleEnabled = true,
            windowStartMinutes = 18 * 60 + 30,
            windowJitterMinutes = 30,
            repeatDaysMask = 0
        )
        repeat(40) {
            val trigger = RunScheduleAlarmManager.computeNextTrigger(
                schedule = schedule,
                now = nowCal.timeInMillis,
                minDayOffset = 1
            )
            val triggerCal = Calendar.getInstance().apply { timeInMillis = trigger }
            assertEquals(28, triggerCal.get(Calendar.DAY_OF_MONTH))
        }
    }

    // ---------------- 自定义重复星期 ----------------

    /** 一三五（bit0/bit2/bit4 = 0b0010101）：周二不会开跑，顺延到周三 */
    @Test
    fun `custom mask mon-wed-fri skips non-selected weekdays`() {
        val schedule = RunScheduleEntity(
            routeId = 1,
            title = "一三五",
            targetDistanceKm = 3f,
            isScheduleEnabled = true,
            windowStartMinutes = 18 * 60,
            windowJitterMinutes = 0,
            repeatDaysMask = 0b0010101
        )
        // 2026-11-24 Tue 10:00 -> 下一次是 11-25 Wed 18:00
        val trigger = triggerCal(schedule, calAt(2026, Calendar.NOVEMBER, 24, 10, 0).timeInMillis)
        assertEquals(Calendar.NOVEMBER, trigger.get(Calendar.MONTH))
        assertEquals(25, trigger.get(Calendar.DAY_OF_MONTH))
        assertEquals(18, trigger.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, trigger.get(Calendar.MINUTE))
    }

    @Test
    fun `custom mask only saturday advances across the working week`() {
        val schedule = RunScheduleEntity(
            routeId = 1,
            title = "仅周六",
            targetDistanceKm = 3f,
            isScheduleEnabled = true,
            windowStartMinutes = 9 * 60,
            windowJitterMinutes = 0,
            repeatDaysMask = 1 shl 5
        )
        // 2026-11-23 Mon 12:00 -> 11-28 Sat 09:00
        val trigger = triggerCal(schedule, calAt(2026, Calendar.NOVEMBER, 23, 12, 0).timeInMillis)
        assertEquals(28, trigger.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.SATURDAY, trigger.get(Calendar.DAY_OF_WEEK))
        assertEquals(9, trigger.get(Calendar.HOUR_OF_DAY))
    }

    // ---------------- 跳过今日 ----------------

    @Test
    fun `skipDateIso pushes today trigger to tomorrow`() {
        val now = calAt(2026, Calendar.NOVEMBER, 25, 10, 0).timeInMillis
        val schedule = dailySchedule().copy(
            skipDateIso = RunScheduleAlarmManager.skipDateIso(now)
        )
        val trigger = triggerCal(schedule, now)
        assertEquals(26, trigger.get(Calendar.DAY_OF_MONTH))
        assertEquals(18, trigger.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `skipDateIso with single-day mask flows into next week`() {
        // 只勾周一，且周一被跳过 -> 顺延到下一个周一
        val now = calAt(2026, Calendar.NOVEMBER, 23, 6, 0).timeInMillis
        val schedule = RunScheduleEntity(
            routeId = 1,
            title = "仅周一",
            targetDistanceKm = 3f,
            isScheduleEnabled = true,
            windowStartMinutes = 7 * 60,
            windowJitterMinutes = 0,
            repeatDaysMask = 1,
            skipDateIso = RunScheduleAlarmManager.skipDateIso(now)
        )
        val trigger = triggerCal(schedule, now)
        assertEquals(30, trigger.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.MONDAY, trigger.get(Calendar.DAY_OF_WEEK))
    }

    @Test
    fun `skipDateIso for another day does not skip today`() {
        val now = calAt(2026, Calendar.NOVEMBER, 25, 10, 0).timeInMillis
        val otherDay = calAt(2026, Calendar.NOVEMBER, 27, 10, 0).timeInMillis
        val schedule = dailySchedule().copy(
            skipDateIso = RunScheduleAlarmManager.skipDateIso(otherDay)
        )
        val trigger = triggerCal(schedule, now)
        assertEquals(25, trigger.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `skipDateIso key is stable within the same local day`() {
        val morning = calAt(2026, Calendar.NOVEMBER, 25, 0, 1).timeInMillis
        val night = calAt(2026, Calendar.NOVEMBER, 25, 23, 59).timeInMillis
        assertEquals(
            RunScheduleAlarmManager.todayIso(morning),
            RunScheduleAlarmManager.todayIso(night)
        )
        assertFalse(
            RunScheduleAlarmManager.todayIso(morning) ==
                RunScheduleAlarmManager.todayIso(calAt(2026, Calendar.NOVEMBER, 26, 0, 1).timeInMillis)
        )
    }

    // ---------------- 跨月 / 跨年 ----------------

    @Test
    fun `computeNextTrigger crosses month boundary`() {
        val now = calAt(2026, Calendar.NOVEMBER, 30, 20, 0).timeInMillis
        val trigger = triggerCal(dailySchedule(windowMinutes = 7 * 60), now)
        assertEquals(Calendar.DECEMBER, trigger.get(Calendar.MONTH))
        assertEquals(1, trigger.get(Calendar.DAY_OF_MONTH))
        assertEquals(7, trigger.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `computeNextTrigger crosses year boundary`() {
        val now = calAt(2026, Calendar.DECEMBER, 31, 20, 0).timeInMillis
        val trigger = triggerCal(dailySchedule(windowMinutes = 7 * 60), now)
        assertEquals(2027, trigger.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, trigger.get(Calendar.MONTH))
        assertEquals(1, trigger.get(Calendar.DAY_OF_MONTH))
    }

    // ---------------- 展示文案 ----------------

    @Test
    fun `describeRepeatDays covers daily workday and custom`() {
        assertEquals("每天", RunScheduleAlarmManager.describeRepeatDays(0))
        assertEquals("每天", RunScheduleAlarmManager.describeRepeatDays(0b1111111))
        assertEquals("工作日", RunScheduleAlarmManager.describeRepeatDays(0b0011111))
        assertEquals("周一·三·五", RunScheduleAlarmManager.describeRepeatDays(0b0010101))
        assertEquals("周日", RunScheduleAlarmManager.describeRepeatDays(1 shl 6))
    }

    @Test
    fun `formatNextCountdown for tomorrow morning`() {
        val now = calAt(2026, Calendar.NOVEMBER, 28, 22, 0).timeInMillis
        val next = calAt(2026, Calendar.NOVEMBER, 29, 7, 15).timeInMillis
        val text = RunScheduleAlarmManager.formatNextCountdown(next, now)
        assertTrue(text, text.startsWith("明早 07:15 · "))
        assertTrue(text, text.endsWith("小时后"))
    }

    @Test
    fun `formatNextCountdown for later today`() {
        val now = calAt(2026, Calendar.NOVEMBER, 28, 8, 0).timeInMillis
        val next = calAt(2026, Calendar.NOVEMBER, 28, 15, 0).timeInMillis
        val text = RunScheduleAlarmManager.formatNextCountdown(next, now)
        assertTrue(text, text.startsWith("下午 15:00 · "))
        assertTrue(text, text.endsWith("小时后"))
    }

    @Test
    fun `formatNextCountdown for day after tomorrow`() {
        val now = calAt(2026, Calendar.NOVEMBER, 28, 12, 0).timeInMillis
        val next = calAt(2026, Calendar.NOVEMBER, 30, 7, 15).timeInMillis
        val text = RunScheduleAlarmManager.formatNextCountdown(next, now)
        assertTrue(text, text.startsWith("后天 07:15 · "))
    }

    @Test
    fun `formatNextCountdown for imminent or overdue`() {
        val now = calAt(2026, Calendar.NOVEMBER, 28, 8, 0).timeInMillis
        assertEquals(
            "即将开跑",
            RunScheduleAlarmManager.formatNextCountdown(now + 2 * 60_000L, now)
        )
        assertEquals(
            "即将开跑",
            RunScheduleAlarmManager.formatNextCountdown(now - 60_000L, now)
        )
    }
}
