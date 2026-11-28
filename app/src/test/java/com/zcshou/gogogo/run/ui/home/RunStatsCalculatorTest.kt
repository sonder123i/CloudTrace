package com.zcshou.gogogo.run.ui.home

import com.zcshou.gogogo.run.engine.RunSplit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class RunStatsCalculatorTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(year: Int, month: Int, day: Int, hour: Int = 8, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun sample(
        year: Int,
        month: Int,
        day: Int,
        meters: Double = 3000.0,
        hour: Int = 8
    ) = RunStatsCalculator.DaySample(at(year, month, day, hour), meters, true)

    // ---------------- 连续打卡 ----------------

    @Test
    fun `current streak counts consecutive days ending today`() {
        val samples = listOf(
            sample(2026, 11, 28),
            sample(2026, 11, 27),
            sample(2026, 11, 26)
        )
        val now = at(2026, 11, 28, 21)
        assertEquals(3, RunStatsCalculator.currentStreak(samples, now, zone))
    }

    @Test
    fun `current streak keeps yesterday chain when today not run yet`() {
        val samples = listOf(sample(2026, 11, 27), sample(2026, 11, 26))
        val now = at(2026, 11, 28, 9)
        // 今天还没跑，但昨天跑过：连续未断，给今天留机会
        assertEquals(2, RunStatsCalculator.currentStreak(samples, now, zone))
    }

    @Test
    fun `current streak is zero after a missed day`() {
        val samples = listOf(sample(2026, 11, 26), sample(2026, 11, 25))
        val now = at(2026, 11, 28, 9)
        assertEquals(0, RunStatsCalculator.currentStreak(samples, now, zone))
    }

    @Test
    fun `current streak ignores duplicates within the same day`() {
        val samples = listOf(
            sample(2026, 11, 28, hour = 7),
            sample(2026, 11, 28, hour = 20),
            sample(2026, 11, 27)
        )
        val now = at(2026, 11, 28, 22)
        assertEquals(2, RunStatsCalculator.currentStreak(samples, now, zone))
    }

    @Test
    fun `aborted runs do not extend the streak`() {
        val samples = listOf(
            RunStatsCalculator.DaySample(at(2026, 11, 28), 800.0, false),
            sample(2026, 11, 27)
        )
        val now = at(2026, 11, 28, 22)
        // 今天只有一条中止记录：等同于今天还没跑，仍按昨天算 1 天
        assertEquals(1, RunStatsCalculator.currentStreak(samples, now, zone))
    }

    @Test
    fun `current streak crosses month boundary`() {
        val samples = listOf(
            sample(2026, 12, 1),
            sample(2026, 11, 30),
            sample(2026, 11, 29)
        )
        val now = at(2026, 12, 1, 12)
        assertEquals(3, RunStatsCalculator.currentStreak(samples, now, zone))
    }

    @Test
    fun `longest streak returns historical maximum`() {
        val samples = listOf(
            sample(2026, 11, 1),
            sample(2026, 11, 2),
            sample(2026, 11, 3),
            sample(2026, 11, 4),
            sample(2026, 11, 10),
            sample(2026, 11, 11)
        )
        assertEquals(4, RunStatsCalculator.longestStreak(samples, zone))
    }

    @Test
    fun `empty samples produce zero streaks`() {
        assertEquals(0, RunStatsCalculator.currentStreak(emptyList(), at(2026, 11, 28), zone))
        assertEquals(0, RunStatsCalculator.longestStreak(emptyList(), zone))
    }

    // ---------------- 月度热力 ----------------

    @Test
    fun `month meters bucket by local day and sum multiple runs`() {
        val samples = listOf(
            sample(2026, 11, 5, 2000.0),
            sample(2026, 11, 5, 1500.0),
            sample(2026, 11, 20, 4000.0),
            // 12 月的记录不能混进 11 月
            sample(2026, 12, 1, 9000.0),
            // 同日中止记录不计入
            RunStatsCalculator.DaySample(at(2026, 11, 21), 5000.0, false)
        )
        val meters = RunStatsCalculator.monthMeters(samples, 2026, 11, zone)
        assertEquals(30, meters.size)
        assertEquals(3500f, meters[4], 0.01f)
        assertEquals(4000f, meters[19], 0.01f)
        assertEquals(0f, meters[20], 0.01f)
        assertEquals(0f, meters[0], 0.01f)
    }

    @Test
    fun `month meters uses leap year day count`() {
        val meters = RunStatsCalculator.monthMeters(emptyList(), 2028, 2, zone)
        assertEquals(29, meters.size)
    }

    // ---------------- 周目标 ----------------

    @Test
    fun `weekly goal ratio handles normal and degenerate goals`() {
        assertEquals(0.5f, RunStatsCalculator.weeklyGoalRatio(7500.0, 15f), 1e-6f)
        assertEquals(1.2f, RunStatsCalculator.weeklyGoalRatio(6000.0, 5f), 1e-6f)
        assertEquals(0f, RunStatsCalculator.weeklyGoalRatio(6000.0, 0f), 1e-6f)
    }

    // ---------------- 前后半程配速差 ----------------

    @Test
    fun `negative split delta is negative when second half is faster`() {
        val splits = listOf(
            RunSplit(1, 1000, 340, 340),
            RunSplit(2, 1000, 320, 320)
        )
        assertEquals(-20, RunStatsCalculator.negativeSplitDeltaSeconds(splits))
    }

    @Test
    fun `negative split delta splits the boundary segment proportionally`() {
        val splits = listOf(
            RunSplit(1, 400, 150, 375),
            RunSplit(2, 400, 160, 400),
            RunSplit(3, 400, 140, 350)
        )
        // 前半 600m = 150s + 80s，后半 600m = 80s + 140s
        assertEquals(-17, RunStatsCalculator.negativeSplitDeltaSeconds(splits))
    }

    @Test
    fun `negative split delta needs at least two splits`() {
        assertNull(RunStatsCalculator.negativeSplitDeltaSeconds(emptyList()))
        assertNull(RunStatsCalculator.negativeSplitDeltaSeconds(listOf(RunSplit(1, 1000, 340, 340))))
    }

    @Test
    fun `day index is stable across a local day`() {
        val morning = at(2026, 11, 28, 0, 5)
        val night = at(2026, 11, 28, 23, 55)
        assertEquals(
            RunStatsCalculator.dayIndex(morning, zone),
            RunStatsCalculator.dayIndex(night, zone)
        )
        assertTrue(
            RunStatsCalculator.dayIndex(at(2026, 11, 29), zone) >
                RunStatsCalculator.dayIndex(at(2026, 11, 28), zone)
        )
        assertEquals(
            LocalDate.of(2026, 11, 28).toEpochDay(),
            RunStatsCalculator.dayIndex(at(2026, 11, 28, 23, 59), zone)
        )
    }
}
