package com.zcshou.gogogo.run.engine

import com.zcshou.gogogo.run.bridge.ILocationInjector
import com.zcshou.gogogo.run.data.db.RunRouteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 状态机全流程 JVM 测试：注入假 EngineEnv / ILocationInjector，确定性验证
 * 预热沉降 → 巡航 → 插点 → 打卡驻留 → 并回 → 收尾 → 结束。
 */
class RunCruiseEngineTest {

    private class FakeClock {
        var elapsedMs = 1_000L
        var wallMs = 1_700_000_000_000L
        var nanos = 1_000_000_000L
        fun advance(ms: Long) {
            elapsedMs += ms
            wallMs += ms
            nanos += ms * 1_000_000
        }
    }

    private class FakeEnv(private val clock: FakeClock) : EngineEnv {
        var mockAllowed = true
        var vibrated = false
        override fun isMockLocationAllowed(): Boolean = mockAllowed
        override fun vibrate() { vibrated = true }
        override fun elapsedRealtimeMs(): Long = clock.elapsedMs
        override fun wallTimeMs(): Long = clock.wallMs
        override fun nanoTime(): Long = clock.nanos
    }

    private class RecordingInjector : ILocationInjector {
        data class Frame(
            val lat: Double,
            val lng: Double,
            val alt: Double,
            val speed: Float,
            val bearing: Float,
            val accuracy: Float,
            val verticalAccuracy: Float,
            val speedAccuracy: Float,
            val bearingAccuracy: Float
        )

        val frames: MutableList<Frame> = java.util.concurrent.CopyOnWriteArrayList()
        var engineTakeover = false
        var released = false

        override fun injectLocation(
            latitude: Double,
            longitude: Double,
            altitude: Double,
            speedMps: Float,
            bearingDegrees: Float,
            accuracyMeters: Float,
            verticalAccuracyMeters: Float,
            speedAccuracyMps: Float,
            bearingAccuracyDegrees: Float
        ) {
            frames += Frame(
                latitude, longitude, altitude, speedMps, bearingDegrees,
                accuracyMeters, verticalAccuracyMeters, speedAccuracyMps, bearingAccuracyDegrees
            )
        }

        override fun setEngineDriven(enabled: Boolean) { engineTakeover = enabled }
        override fun releaseInjection() { released = true }
    }

    private val center = TrackMathUtils.LatLng(36.667662, 117.027707)

    private fun route(isClockwise: Boolean = true): RunRouteEntity = RunRouteEntity(
        id = 7,
        name = "测试圈",
        buildMode = "TRACK_SPLINE",
        singleLapMeters = TrackMathUtils.cumulativeDistances(
            TrackMathUtils.catmullRomClosed(TrackFactory.ovalAnchors(center), 2.0)
        ).last(),
        isClockwise = isClockwise,
        denseWaypointsJson = "",
        anchorNodesJson = "[]",
        baseAltitudeMeters = 55.0
    )

    private fun densePoints(): List<TrackMathUtils.LatLng> =
        TrackMathUtils.catmullRomClosed(TrackFactory.ovalAnchors(center), 2.0)

    private suspend fun awaitPhase(
        engine: RunCruiseEngine,
        phase: RunPhase,
        timeoutMs: Long = 15_000
    ): RunRuntimeState = withTimeout(timeoutMs) {
        while (engine.state.value.phase != phase) delay(5)
        engine.state.value
    }

    @Test
    fun fullFlow_settleThenDetourDwellThenFinish() = runBlocking {
        val clock = FakeClock()
        val env = FakeEnv(clock)
        val injector = RecordingInjector()
        val engine = RunCruiseEngine(
            env = env,
            injector = injector,
            repository = null,
            tickMillis = 5L,
            random = Random(11),
            settleMillis = 200L,
            cooldownMillis = 200L,
            dwellMillis = 100L,
            randomStartSlice = false
        )

        engine.startWithPoints(
            route = route(),
            densePoints = densePoints(),
            targetKm = 0.05f,
            targetPaceSecPerKm = 340,
            distanceJitterMeters = 0,
            enableLaneDrift = true,
            enableCooldownWalk = true
        )
        assertTrue("引擎应已接管注入", injector.engineTakeover)

        val settle = awaitPhase(engine, RunPhase.PRE_START_SETTLE, 3_000)
        assertTrue("预热倒计时 ${settle.settleRemainSec}", settle.settleRemainSec >= 0)

        awaitPhase(engine, RunPhase.RUNNING, 5_000)
        val snapshotAtRunning = injector.frames.toList()
        assertTrue("预热期应已注入坐标", snapshotAtRunning.isNotEmpty())

        // 预热期应存在接近 0 的原地漂移帧（切换瞬间可能混入巡航帧，故断言"存在"而非"全部"）
        val lowSpeedFrames = snapshotAtRunning.count { it.speed < 0.2f }
        assertTrue(
            "应存在预热低速帧，实际低速帧=$lowSpeedFrames/${snapshotAtRunning.size}",
            lowSpeedFrames >= 3
        )

        // 插点：东侧 60m
        val target = TrackMathUtils.destinationPoint(center, 90.0, 60.0)
        engine.insertDetour(target.lat, target.lng)
        awaitPhase(engine, RunPhase.DETOUR_POINT, 3_000)

        // 等驻留：出现 speed == 0 的注入帧
        withTimeout(15_000) {
            while (injector.frames.none { it.speed == 0f }) delay(5)
        }
        val zeroSpeedIndex = injector.frames.indexOfFirst { it.speed == 0f }
        assertTrue("应出现驻留零速帧", zeroSpeedIndex >= 0)

        // 驻留结束后回到 RUNNING 并继续注入
        awaitPhase(engine, RunPhase.RUNNING, 10_000)
        val afterDwell = injector.frames.size
        assertTrue("驻留后应继续注入", afterDwell > zeroSpeedIndex)

        awaitPhase(engine, RunPhase.FINISHED, 40_000)
        assertTrue("结束时应释放注入", injector.released)
        assertTrue("成功结束应震动", env.vibrated)
        assertEquals(0.0, engine.state.value.speedMps, 1e-9)
    }

    @Test
    fun altitude_isSpatiallyBoundAndJittered() = runBlocking {
        val clock = FakeClock()
        val env = FakeEnv(clock)
        val injector = RecordingInjector()
        val engine = RunCruiseEngine(
            env = env,
            injector = injector,
            repository = null,
            tickMillis = 5L,
            random = Random(5),
            settleMillis = 50L
        )
        engine.startWithPoints(route(), densePoints(), 0.03f, 340, 0, enableLaneDrift = false, enableCooldownWalk = false)
        awaitPhase(engine, RunPhase.RUNNING, 5_000)
        withTimeout(20_000) {
            while (engine.state.value.phase != RunPhase.FINISHED) delay(5)
        }
        val alts = injector.frames.map { it.alt }
        assertTrue("应有注入帧", alts.isNotEmpty())
        // 微地形：55 ± (1.2 + 0.15)
        assertTrue("海拔越界: min=${alts.min()} max=${alts.max()}",
            alts.min() > 53.6 && alts.max() < 56.4)
        // 不是恒定值（死锁海拔会被识破）
        assertTrue("海拔应起伏", alts.max() - alts.min() > 0.3)
    }

    @Test
    fun reversal_changesInitialHeading() = runBlocking {
        fun firstRunningBearing(clockwise: Boolean): Float {
            val clock = FakeClock()
            val injector = RecordingInjector()
            val engine = RunCruiseEngine(
                env = FakeEnv(clock),
                injector = injector,
                repository = null,
                tickMillis = 5L,
                random = Random(3),
                settleMillis = 20L,
                randomStartSlice = false
            )
            runBlocking {
                engine.startWithPoints(route(clockwise), densePoints(), 0.02f, 340, 0, enableLaneDrift = false, enableCooldownWalk = false)
                withTimeout(10_000) {
                    while (engine.state.value.phase != RunPhase.RUNNING) delay(5)
                    delay(200)
                }
                engine.release()
            }
            val post = injector.frames.drop(injector.frames.size / 2)
            return post.lastOrNull()?.bearing ?: 0f
        }

        val cw = firstRunningBearing(true)
        val ccw = firstRunningBearing(false)
        val delta = TrackMathUtils.headingDelta(cw.toDouble(), ccw.toDouble())
        assertTrue("顺时针=$cw 逆时针=$ccw 差值=$delta", delta > 60.0)
    }

    @Test
    fun overshoot_keepsRunningPastTargetBeforeCooldown() = runBlocking {
        val engine = RunCruiseEngine(
            env = FakeEnv(FakeClock()),
            injector = RecordingInjector(),
            repository = null,
            tickMillis = 5L,
            random = Random(9),
            settleMillis = 30L,
            cooldownMillis = 50L,
            randomStartSlice = false,
            overshootMeters = 20..20
        )
        engine.startWithPoints(
            route(),
            densePoints(),
            targetKm = 0.02f,
            targetPaceSecPerKm = 300,
            distanceJitterMeters = 0,
            enableLaneDrift = false,
            enableCooldownWalk = false
        )
        awaitPhase(engine, RunPhase.RUNNING, 5_000)
        awaitPhase(engine, RunPhase.FINISHED, 40_000)
        val meters = engine.state.value.accumulatedMeters
        assertTrue("达标后应多跑 20m 再收尾，实际=$meters", meters >= 39.0)
    }

    @Test
    fun injectThrottle_halvesFixRateWithoutSlowingUi() = runBlocking {
        // fix 节流只压注入频率：同一场景下注入间隔翻倍，帧数应恰好减半，
        // 而心跳（UI/积分）仍按 tickMillis 走，靠 accumulate 里程不变来间接验证。
        suspend fun run(intervalMs: Long): Pair<Int, Double> {
            val injector = RecordingInjector()
            val engine = RunCruiseEngine(
                env = FakeEnv(FakeClock()),
                injector = injector,
                repository = null,
                tickMillis = 5L,
                random = Random(11),
                settleMillis = 20L,
                cooldownMillis = 20L,
                dwellMillis = 10L,
                randomStartSlice = false,
                injectIntervalMs = intervalMs
            )
            engine.startWithPoints(
                route(),
                densePoints(),
                targetKm = 0.03f,
                targetPaceSecPerKm = 340,
                distanceJitterMeters = 0,
                enableLaneDrift = false,
                enableCooldownWalk = false
            )
            awaitPhase(engine, RunPhase.FINISHED, 40_000)
            return injector.frames.size to engine.state.value.accumulatedMeters
        }

        val (everyTick, metersFast) = run(5L)
        val (everyOtherTick, metersSlow) = run(10L)

        assertTrue("基础注入应产生足够帧，实际=$everyTick", everyTick > 40)
        assertEquals("节流 2 倍应恰好一半帧", everyTick / 2, everyOtherTick)
        assertEquals("节流不应改变里程", metersFast, metersSlow, 0.5)
    }

    /** 记录落库的假仓库：只关心结束记录本身 */
    private class RecordingSink : RunRecordSink {
        val records = java.util.concurrent.CopyOnWriteArrayList<com.zcshou.gogogo.run.data.db.RunRecordEntity>()

        override suspend fun addRecord(
            record: com.zcshou.gogogo.run.data.db.RunRecordEntity
        ): Long {
            records += record
            return records.size.toLong()
        }

        override suspend fun clearSnapshot() = Unit
        override suspend fun saveSnapshot(snapshot: com.zcshou.gogogo.run.data.prefs.RunSnapshot) = Unit
    }

    /**
     * 结束记录必须带真实分段与累计爬升：分段总距离≈总里程，每段耗时/配速为正，
     * 且能被 RunSplitCodec 原样解析回来（战报复盘的唯一数据来源）。
     */
    @Test
    fun finish_writesRealSplitsAndElevationGain() = runBlocking {
        val clock = FakeClock()
        val sink = RecordingSink()
        val engine = RunCruiseEngine(
            env = FakeEnv(clock),
            injector = RecordingInjector(),
            repository = sink,
            tickMillis = 5L,
            random = Random(3),
            settleMillis = 0L,
            cooldownMillis = 0L,
            randomStartSlice = false
        )
        // 假时钟不会自动走：后台按 200ms/2ms 推进，让分段耗时与配速都是正常量级
        val ticker = launch(Dispatchers.Default) {
            while (isActive) {
                clock.advance(200)
                delay(2)
            }
        }
        engine.startWithPoints(
            route = route(),
            densePoints = densePoints(),
            targetKm = 0.9f,
            targetPaceSecPerKm = 340,
            distanceJitterMeters = 0,
            enableLaneDrift = false,
            enableCooldownWalk = false
        )
        awaitPhase(engine, RunPhase.FINISHED, 90_000)
        ticker.cancel()
        withTimeout(5_000) {
            while (sink.records.isEmpty()) delay(5)
        }
        val record = sink.records.first()
        val splits = RunSplitCodec.decode(record.splitsJson)
        assertTrue("应记录真实分段，实际=$splits", splits.size >= 2)
        assertEquals(splits.size, splits.map { it.idx }.distinct().size)
        assertTrue(
            "每段都应有正距离/耗时/配速",
            splits.all { it.distM > 0 && it.durSec > 0 && it.paceSec > 0 }
        )
        val sum = splits.sumOf { it.distM }
        assertTrue(
            "分段总距离应≈总里程 sum=$sum total=${record.actualDistanceMeters}",
            kotlin.math.abs(sum - record.actualDistanceMeters) <= 2.0
        )
        assertTrue(
            "累计爬升应为正，实际=${record.elevationGainMeters}",
            record.elevationGainMeters > 0.0
        )
        assertEquals(PaceStrategy.STEADY.name, record.paceStrategy)
    }
}