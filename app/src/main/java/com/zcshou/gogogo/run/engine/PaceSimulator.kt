package com.zcshou.gogogo.run.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 配速策略：在「热身降速 + 巡航漂移 + 急转减速 + 末段冲刺」的拟真骨架上，
 * 叠加不同的速度分布形态。策略只改变分布，不改变总目标里程。
 */
enum class PaceStrategy(val label: String) {
    /** 匀速巡航：与历史行为完全一致 */
    STEADY("匀速"),

    /** 前慢后快：前 50% 里程 0.94×，中段平滑过渡，后 50% 提速到 1.06× */
    NEGATIVE_SPLIT("前慢后快"),

    /** 间歇变速：每 600m 循环 400m 冲刺（1.12×）+ 200m 恢复跑（0.86×） */
    INTERVAL("间歇变速");

    companion object {
        /** 旧数据 / 未知字符串统一回落到匀速，绝不因枚举改名导致开跑失败 */
        fun fromName(name: String?): PaceStrategy =
            entries.firstOrNull { it.name == name } ?: STEADY
    }
}

/** 预演曲线上的一点：距起点里程 + 该位置的瞬时配速（秒/公里） */
data class PacePreviewPoint(val distanceMeters: Double, val paceSecPerKm: Int)

/** 开跑前预演结果：曲线点集 + 预计总用时 */
data class PacePreviewStats(
    val strategy: PaceStrategy,
    val points: List<PacePreviewPoint>,
    val totalSeconds: Int,
    val fastestPaceSec: Int,
    val slowestPaceSec: Int
)

/**
 * 人体配速模拟：热身减速、巡航高斯波动、急转弯减速、末段冲刺 + 配速策略形态。
 */
class PaceSimulator(
    targetPaceSecPerKm: Int,
    private val random: Random = Random.Default,
    private val warmupMeters: Double = 300.0,
    private val sprintMeters: Double = 200.0,
    val strategy: PaceStrategy = PaceStrategy.STEADY
) {

    companion object {
        const val WARMUP_FACTOR_MIN = 0.90
        const val WARMUP_FACTOR_MAX = 0.95
        const val SPRINT_FACTOR_MIN = 1.05
        const val SPRINT_FACTOR_MAX = 1.10
        const val CRUISE_JITTER = 0.12
        const val TURN_HEADING_DEG = 45.0
        const val TURN_SLOWDOWN = 0.80

        // --- 策略形态参数（与单测、预演曲线同源） ---
        /** 前慢后快：前半程 / 后半程速度系数与过渡区（占总里程比例） */
        const val NEGATIVE_SLOW = 0.94
        const val NEGATIVE_FAST = 1.06
        const val NEGATIVE_RAMP_START = 0.45
        const val NEGATIVE_RAMP_END = 0.55

        /** 间歇：600m 一个循环，400m 冲刺 + 200m 恢复，边界处 30m 平滑过渡 */
        const val INTERVAL_CYCLE_M = 600.0
        const val INTERVAL_SPRINT_M = 400.0
        const val INTERVAL_SPRINT = 1.12
        const val INTERVAL_RECOVER = 0.86
        const val INTERVAL_EDGE_M = 30.0

        /** 预演用的确定性中点系数（实际模拟在区间内随机取值） */
        private const val PREVIEW_WARMUP_FACTOR =
            (WARMUP_FACTOR_MIN + WARMUP_FACTOR_MAX) / 2.0
        private const val PREVIEW_SPRINT_FACTOR =
            (SPRINT_FACTOR_MIN + SPRINT_FACTOR_MAX) / 2.0

        /**
         * 开跑前 ETA / 曲线预演（纯函数、确定性，不依赖随机数）。
         *
         * 与真实引擎共用同一套热身/冲刺包络与策略系数，因此 ETA 与实跑总时长的
         * 差距只来自巡航高斯漂移与急弯减速，正常在 ±8% 内。
         */
        fun previewProfile(
            targetKm: Float,
            targetPaceSecPerKm: Int,
            strategy: PaceStrategy,
            sampleCount: Int = 24
        ): PacePreviewStats {
            val totalMeters = (targetKm.coerceIn(0.1f, 100f) * 1000.0)
            val baseSpeed = 1000.0 / targetPaceSecPerKm.coerceIn(120, 1200)
            // 积分步长固定 5m，兼顾边界精度与开销（最长 100km 也只有 2 万步）
            val steps = (totalMeters / 5.0).roundToInt().coerceIn(8, 20_000)
            val stepMeters = totalMeters / steps

            fun speedAt(distance: Double): Double = baseSpeed *
                previewEnvelope(distance, totalMeters - distance) *
                strategyFactor(strategy, distance, totalMeters - distance)

            var totalSeconds = 0.0
            for (i in 0 until steps) {
                totalSeconds += stepMeters / speedAt(i * stepMeters)
            }

            val samples = sampleCount.coerceIn(2, 200)
            val points = ArrayList<PacePreviewPoint>(samples)
            var fastest = Int.MAX_VALUE
            var slowest = 0
            for (i in 0 until samples) {
                val distance = totalMeters * i / (samples - 1)
                val pace = (1000.0 / speedAt(distance)).roundToInt()
                points += PacePreviewPoint(distance, pace)
                if (pace < fastest) fastest = pace
                if (pace > slowest) slowest = pace
            }
            return PacePreviewStats(
                strategy = strategy,
                points = points,
                totalSeconds = totalSeconds.roundToInt(),
                fastestPaceSec = fastest,
                slowestPaceSec = slowest
            )
        }

        /** 预演包络：与实例的随机包络同界，取区间中点保证可复现 */
        private fun previewEnvelope(accumulated: Double, remaining: Double): Double = when {
            accumulated < 300.0 -> PREVIEW_WARMUP_FACTOR
            remaining < 200.0 -> PREVIEW_SPRINT_FACTOR
            else -> 1.0
        }
    }

    val baseSpeedMps: Double = 1000.0 / targetPaceSecPerKm

    private val warmupFactor: Double = random.nextDouble(WARMUP_FACTOR_MIN, WARMUP_FACTOR_MAX)
    private val sprintFactor: Double = random.nextDouble(SPRINT_FACTOR_MIN, SPRINT_FACTOR_MAX)
    private var cruiseFactor: Double = 1.0

    /**
     * @param accumulatedMeters 已跑里程（含起步）
     * @param remainingMeters   距目标剩余里程
     * @param headingChangeDeg  前方航向变化（度）
     * @param dtSec             心跳间隔（秒）
     */
    fun speedMps(
        accumulatedMeters: Double,
        remainingMeters: Double,
        headingChangeDeg: Double,
        dtSec: Double
    ): Double {
        cruiseFactor += random.nextGaussian() * 0.02 * dtSec
        cruiseFactor = cruiseFactor.coerceIn(1.0 - CRUISE_JITTER, 1.0 + CRUISE_JITTER)

        var speed = baseSpeedMps * cruiseFactor *
            strategyFactor(strategy, accumulatedMeters, remainingMeters)
        when {
            accumulatedMeters < warmupMeters -> speed *= warmupFactor
            remainingMeters < sprintMeters -> speed *= sprintFactor
        }
        if (headingChangeDeg > TURN_HEADING_DEG) speed *= TURN_SLOWDOWN
        return speed
    }
}

/**
 * 策略速度系数（纯函数，引擎与预演共用）。
 *
 * - 匀速：1.0（完全保持历史行为）
 * - 前慢后快：按「已跑 / 总里程」比例前后分段，中段用 smoothstep 平滑过渡
 * - 间歇：按累计里程取 600m 周期内的相位，400m 冲刺 / 200m 恢复，
 *   两段边界各留 30m 过渡，避免瞬时速度跳变被判定为异常轨迹
 */
fun strategyFactor(
    strategy: PaceStrategy,
    accumulatedMeters: Double,
    remainingMeters: Double
): Double = when (strategy) {
    PaceStrategy.STEADY -> 1.0
    PaceStrategy.NEGATIVE_SPLIT -> {
        val total = (accumulatedMeters + remainingMeters).coerceAtLeast(1.0)
        val fraction = (accumulatedMeters / total).coerceIn(0.0, 1.0)
        val start = PaceSimulator.NEGATIVE_RAMP_START
        val end = PaceSimulator.NEGATIVE_RAMP_END
        when {
            fraction <= start -> PaceSimulator.NEGATIVE_SLOW
            fraction >= end -> PaceSimulator.NEGATIVE_FAST
            else -> {
                val t = smoothStep((fraction - start) / (end - start))
                PaceSimulator.NEGATIVE_SLOW +
                    (PaceSimulator.NEGATIVE_FAST - PaceSimulator.NEGATIVE_SLOW) * t
            }
        }
    }
    PaceStrategy.INTERVAL -> {
        val cycle = PaceSimulator.INTERVAL_CYCLE_M
        val phase = ((accumulatedMeters % cycle) + cycle) % cycle
        val sprintEnd = PaceSimulator.INTERVAL_SPRINT_M
        val edge = PaceSimulator.INTERVAL_EDGE_M
        val fast = PaceSimulator.INTERVAL_SPRINT
        val slow = PaceSimulator.INTERVAL_RECOVER
        // 过渡带以边界为中心：恢复跑 → 冲刺的换相跨在周期接缝（cycle/0）上，
        // 因此接缝两侧各占半个过渡带，相位 0 处正好是中点，周期性完全连续
        fun toSprint(t: Double): Double = slow + (fast - slow) * smoothStep(t)
        fun toRecover(t: Double): Double = fast + (slow - fast) * smoothStep(t)
        when {
            // 周期的后半段过渡带：恢复跑起步升向冲刺
            phase < edge -> toSprint(0.5 + phase / (2 * edge))
            phase < sprintEnd - edge -> fast
            // 冲刺 → 恢复跑，全程 2×edge
            phase < sprintEnd + edge -> toRecover((phase - (sprintEnd - edge)) / (2 * edge))
            phase < cycle - edge -> slow
            // 周期的前半段过渡带：仍在升向下一周期的冲刺
            else -> toSprint((phase - (cycle - edge)) / (2 * edge))
        }
    }
}

/** 3t²-2t³：两端一阶导为 0，速度过渡不会有折角 */
private fun smoothStep(t: Double): Double {
    val x = t.coerceIn(0.0, 1.0)
    return x * x * (3.0 - 2.0 * x)
}

private fun Random.nextGaussian(): Double {
    // Box-Muller，避免依赖 java.util.Random 的同步实现
    var u1 = nextDouble()
    while (u1 <= 1e-12) u1 = nextDouble()
    val u2 = nextDouble()
    return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
}
