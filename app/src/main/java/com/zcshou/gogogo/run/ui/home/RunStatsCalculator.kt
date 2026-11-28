package com.zcshou.gogogo.run.ui.home

import com.zcshou.gogogo.run.data.db.RunRecordEntity
import com.zcshou.gogogo.run.engine.RunSplit
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * 战报统计的全部纯逻辑（无 Android 依赖，可直接 JVM 单测）：
 * 连续打卡天数、最长连胜、月度热力、周目标达成率、前后半程配速差。
 *
 * 所有日期聚合都以本地自然日为单位，并按显式 [ZoneId] 计算，避免跨时区/夏令时
 * 把「昨天 23:59 的跑次」算成今天两次。
 */
object RunStatsCalculator {

    /** 一条跑次的最小投影：时间 + 距离 + 是否完成 */
    data class DaySample(val startedAt: Long, val meters: Double, val completed: Boolean)

    fun toSamples(records: List<RunRecordEntity>): List<DaySample> = records.map {
        DaySample(
            startedAt = it.startedAt,
            meters = it.actualDistanceMeters,
            completed = it.status == "COMPLETED"
        )
    }

    /** 本地自然日序号（1970-01-01 = 0），夏令时/闰年安全 */
    fun dayIndex(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

    /** 有完成记录的日期集合，降序去重 */
    fun completedDays(
        samples: List<DaySample>,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<Long> = samples.asSequence()
        .filter { it.completed }
        .map { dayIndex(it.startedAt, zone) }
        .distinct()
        .sortedDescending()
        .toList()

    /**
     * 当前连续打卡天数。
     *
     * 今天已跑 → 从今天往前数；今天还没跑但昨天跑过 → 仍保留连续（当天补跑可续上）；
     * 最近一次完成早于昨天 → 已断签，返回 0。
     */
    fun currentStreak(
        samples: List<DaySample>,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Int {
        val days = completedDays(samples, zone)
        if (days.isEmpty()) return 0
        val today = dayIndex(nowMs, zone)
        val latest = days[0]
        if (latest != today && latest != today - 1) return 0
        var streak = 1
        var cursor = latest
        for (index in 1 until days.size) {
            val day = days[index]
            if (day == cursor - 1) {
                streak++
                cursor = day
            } else {
                break
            }
        }
        return streak
    }

    /** 历史最长连续打卡天数（同一天多次只算一天） */
    fun longestStreak(
        samples: List<DaySample>,
        zone: ZoneId = ZoneId.systemDefault()
    ): Int {
        val days = completedDays(samples, zone).asReversed()
        if (days.isEmpty()) return 0
        var longest = 1
        var run = 1
        for (index in 1 until days.size) {
            if (days[index] == days[index - 1] + 1) {
                run++
                if (run > longest) longest = run
            } else {
                run = 1
            }
        }
        return longest
    }

    /** 月度热力：下标 = 日 - 1，值为当日完成的累计米数 */
    fun monthMeters(
        samples: List<DaySample>,
        year: Int,
        month: Int,
        zone: ZoneId = ZoneId.systemDefault()
    ): FloatArray {
        val days = YearMonth.of(year, month).lengthOfMonth()
        val meters = FloatArray(days)
        for (sample in samples) {
            if (!sample.completed) continue
            val date = Instant.ofEpochMilli(sample.startedAt).atZone(zone).toLocalDate()
            if (date.year == year && date.monthValue == month) {
                meters[date.dayOfMonth - 1] += sample.meters.toFloat()
            }
        }
        return meters
    }

    /** 周目标达成率（可 >1，UI 自行 clamp 到 100%），目标 ≤0 时返回 0 */
    fun weeklyGoalRatio(weekMeters: Double, goalKm: Float): Float {
        val goalMeters = goalKm.toDouble() * 1000.0
        if (goalMeters <= 0.0) return 0f
        return (weekMeters / goalMeters).toFloat()
    }

    /**
     * 前后半程配速差（秒/公里，后半 - 前半）：
     * 负值 = 前慢后快（Negative Split），正值 = 前快后慢。
     * 分段不足两段或某半程距离过短时返回 null（不做无意义的估算）。
     */
    fun negativeSplitDeltaSeconds(splits: List<RunSplit>): Int? {
        if (splits.size < 2) return null
        val totalDist = splits.sumOf { it.distM.toDouble() }
        if (totalDist < 100.0) return null
        val half = totalDist / 2.0
        var cursor = 0.0
        var firstDist = 0.0
        var firstDur = 0.0
        var secondDist = 0.0
        var secondDur = 0.0
        for (split in splits) {
            val dist = split.distM.toDouble()
            val dur = split.durSec.toDouble()
            val start = cursor
            val end = cursor + dist
            when {
                end <= half -> {
                    firstDist += dist
                    firstDur += dur
                }
                start >= half -> {
                    secondDist += dist
                    secondDur += dur
                }
                else -> {
                    // 跨界段按距离比例拆到前后两半
                    val inFirst = half - start
                    val ratio = inFirst / dist
                    firstDist += inFirst
                    firstDur += dur * ratio
                    secondDist += dist - inFirst
                    secondDur += dur * (1.0 - ratio)
                }
            }
            cursor = end
        }
        if (firstDist < 50.0 || secondDist < 50.0) return null
        val firstPace = firstDur / firstDist * 1000.0
        val secondPace = secondDur / secondDist * 1000.0
        return (secondPace - firstPace).roundToInt()
    }
}
