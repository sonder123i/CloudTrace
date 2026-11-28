package com.zcshou.gogogo.run.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class PaceSimulatorTest {

    @Test
    fun baseSpeed_fromPace() {
        val sim = PaceSimulator(340, Random(42))
        assertEquals(2.941, sim.baseSpeedMps, 0.001)
    }

    @Test
    fun warmup_slower_than_base() {
        val sim = PaceSimulator(340, Random(42))
        val v = sim.speedMps(100.0, 2900.0, 0.0, 0.5)
        assertTrue("warmup=$v", v < sim.baseSpeedMps)
    }

    @Test
    fun sprint_faster_than_base() {
        val sim = PaceSimulator(340, Random(42))
        val v = sim.speedMps(2900.0, 100.0, 0.0, 0.5)
        assertTrue("sprint=$v", v > sim.baseSpeedMps)
    }

    @Test
    fun sharpTurn_slowsBy20Percent() {
        val straight = PaceSimulator(340, Random(42)).speedMps(2000.0, 1000.0, 10.0, 0.5)
        val turn = PaceSimulator(340, Random(42)).speedMps(2000.0, 1000.0, 60.0, 0.5)
        assertEquals(straight * PaceSimulator.TURN_SLOWDOWN, turn, 1e-9)
    }

    @Test
    fun cruiseSpeedWithinJitterBounds() {
        val sim = PaceSimulator(340, Random(7))
        repeat(200) {
            val v = sim.speedMps(1500.0, 1500.0, 0.0, 0.5)
            assertTrue("v=$v", v in 2.5..3.4)
        }
    }

    // ---------------- 配速策略 ----------------

    @Test
    fun strategyNames_roundTripAndFallback() {
        PaceStrategy.entries.forEach {
            assertEquals(it, PaceStrategy.fromName(it.name))
        }
        assertEquals(PaceStrategy.STEADY, PaceStrategy.fromName(null))
        assertEquals(PaceStrategy.STEADY, PaceStrategy.fromName("NEGATIVE_SPLITS"))
    }

    @Test
    fun steadyStrategy_keepsHistoricalFactor() {
        assertEquals(1.0, strategyFactor(PaceStrategy.STEADY, 500.0, 500.0), 1e-9)
    }

    /**
     * 前慢后快：按时间而不是按距离比较——后半程 500m 用时必须明显短于前半程。
     * 用固定步长积分，避免 500ms 心跳与随机漂移带来的抖动。
     */
    @Test
    fun negativeSplit_secondHalfClearlyFaster() {
        val sim = PaceSimulator(340, Random(7), strategy = PaceStrategy.NEGATIVE_SPLIT)
        val dt = 0.5
        var dist = 0.0
        var timeSec = 0.0
        var halfTime = -1.0
        while (dist < 1000.0) {
            val speed = sim.speedMps(dist, 1000.0 - dist, 0.0, dt)
            val step = speed * dt
            // 跨过 500m 时记录时间，按比例扣掉半步的溢出
            if (halfTime < 0 && dist + step >= 500.0) {
                halfTime = timeSec + (500.0 - dist) / speed
            }
            dist += step
            timeSec += dt
        }
        val firstHalfSpeed = 500.0 / halfTime
        val secondHalfSpeed = 500.0 / (timeSec - halfTime)
        assertTrue(
            "first=$firstHalfSpeed second=$secondHalfSpeed",
            secondHalfSpeed > firstHalfSpeed * 1.05
        )
    }

    @Test
    fun intervalFactor_alternatesSprintAndRecoveryEachCycle() {
        // 周期内：冲刺段 > 1.10，恢复段 < 0.88
        assertTrue(strategyFactor(PaceStrategy.INTERVAL, 200.0, 800.0) > 1.10)
        assertTrue(strategyFactor(PaceStrategy.INTERVAL, 500.0, 500.0) < 0.88)
        // 周期性：相隔一个 600m 周期的同相位系数完全一致
        assertEquals(
            strategyFactor(PaceStrategy.INTERVAL, 150.0, 850.0),
            strategyFactor(PaceStrategy.INTERVAL, 750.0, 250.0),
            1e-9
        )

        // 一整个周期内必须出现明显的波峰波谷
        var min = Double.MAX_VALUE
        var max = -Double.MAX_VALUE
        var d = 0.0
        while (d <= PaceSimulator.INTERVAL_CYCLE_M) {
            val f = strategyFactor(PaceStrategy.INTERVAL, d, 3000.0 - d)
            if (f < min) min = f
            if (f > max) max = f
            d += 10.0
        }
        assertTrue("min=$min max=$max", max - min > 0.2)
    }

    @Test
    fun strategyFactor_transitionIsContinuous() {
        // 前慢后快：过渡区内不允许出现瞬时跳变（相邻 1m 的差值远小于两段系数差）
        var previous = strategyFactor(PaceStrategy.NEGATIVE_SPLIT, 0.0, 3000.0)
        var d = 1.0
        while (d <= 3000.0) {
            val current = strategyFactor(PaceStrategy.NEGATIVE_SPLIT, d, 3000.0 - d)
            assertTrue("jump at $d: $previous -> $current", abs(current - previous) < 0.02)
            previous = current
            d += 1.0
        }
        // 间歇：边界平滑带同理
        previous = strategyFactor(PaceStrategy.INTERVAL, 0.0, 3000.0)
        d = 1.0
        while (d <= 1200.0) {
            val current = strategyFactor(PaceStrategy.INTERVAL, d, 3000.0 - d)
            assertTrue("jump at $d: $previous -> $current", abs(current - previous) < 0.02)
            previous = current
            d += 1.0
        }
    }

    @Test
    fun previewProfile_returnsCurveAndPlausibleEta() {
        for (strategy in PaceStrategy.entries) {
            val stats = PaceSimulator.previewProfile(3f, 340, strategy)
            val targetSeconds = 3 * 340.0
            val error = abs(stats.totalSeconds - targetSeconds) / targetSeconds
            assertTrue("$strategy eta=${stats.totalSeconds} error=$error", error < 0.08)
            assertEquals(24, stats.points.size)
            assertEquals(0.0, stats.points.first().distanceMeters, 1e-6)
            assertEquals(3000.0, stats.points.last().distanceMeters, 1e-6)
            assertTrue(stats.fastestPaceSec in 1..1200)
            assertTrue(stats.slowestPaceSec >= stats.fastestPaceSec)
        }
    }

    @Test
    fun previewProfile_negativeSplitEndsFasterThanItStarts() {
        val stats = PaceSimulator.previewProfile(3f, 340, PaceStrategy.NEGATIVE_SPLIT)
        val firstPoint = stats.points.first()
        val lastPoint = stats.points.last()
        // 配速越小越快：末段配速必须显著小于起步热身段
        assertTrue("first=${firstPoint.paceSecPerKm} last=${lastPoint.paceSecPerKm}",
            lastPoint.paceSecPerKm < firstPoint.paceSecPerKm - 20)
    }

    @Test
    fun previewProfile_intervalHasWiderSpreadThanSteady() {
        val steady = PaceSimulator.previewProfile(3f, 340, PaceStrategy.STEADY)
        val interval = PaceSimulator.previewProfile(3f, 340, PaceStrategy.INTERVAL)
        val steadySpread = steady.slowestPaceSec - steady.fastestPaceSec
        val intervalSpread = interval.slowestPaceSec - interval.fastestPaceSec
        assertTrue("steady=$steadySpread interval=$intervalSpread", intervalSpread > steadySpread)
    }
}
