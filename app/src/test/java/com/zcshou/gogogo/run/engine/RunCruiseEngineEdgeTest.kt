package com.zcshou.gogogo.run.engine

import com.zcshou.gogogo.run.bridge.ILocationInjector
import com.zcshou.gogogo.run.data.db.RunRecordEntity
import com.zcshou.gogogo.run.data.db.RunRouteEntity
import com.zcshou.gogogo.run.data.prefs.RunSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RunCruiseEngineEdgeTest {

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

    private class FakeEnv(private val clock: FakeClock, var mockAllowed: Boolean = true) : EngineEnv {
        override fun isMockLocationAllowed(): Boolean = mockAllowed
        override fun vibrate() = Unit
        override fun elapsedRealtimeMs(): Long = clock.elapsedMs
        override fun wallTimeMs(): Long = clock.wallMs
        override fun nanoTime(): Long = clock.nanos
    }

    private class RecordingSink : RunRecordSink {
        val records = java.util.concurrent.CopyOnWriteArrayList<RunRecordEntity>()
        override suspend fun addRecord(record: RunRecordEntity): Long {
            records += record
            return record.id
        }

        override suspend fun clearSnapshot() = Unit
        override suspend fun saveSnapshot(snapshot: RunSnapshot) = Unit
    }

    private class RecordingInjector : ILocationInjector {
        val frames = java.util.concurrent.CopyOnWriteArrayList<TrackMathUtils.LatLng>()
        override fun injectLocation(
            latitude: Double, longitude: Double, altitude: Double,
            speedMps: Float, bearingDegrees: Float, accuracyMeters: Float,
            verticalAccuracyMeters: Float, speedAccuracyMps: Float,
            bearingAccuracyDegrees: Float
        ) {
            frames += TrackMathUtils.LatLng(latitude, longitude)
        }

        override fun setEngineDriven(enabled: Boolean) = Unit
        override fun releaseInjection() = Unit
    }

    private suspend fun waitUntil(timeoutMs: Long = 3_000L, predicate: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!predicate()) delay(10)
        }
    }

    @Test
    fun finishEarlyDuringSettle_staysInCooldownUntilFinish() = runBlocking {
        val clock = FakeClock()
        val env = FakeEnv(clock)
        val injector = RecordingInjector()
        val sink = RecordingSink()
        val engine = RunCruiseEngine(
            env = env,
            injector = injector,
            repository = sink,
            tickMillis = 20L,
            random = Random(42),
            settleMillis = 200L,
            cooldownMillis = 2_000L,
            randomStartSlice = false
        )
        val center = TrackMathUtils.LatLng(36.667662, 117.027707)
        val dense = TrackMathUtils.catmullRomClosed(TrackFactory.ovalAnchors(center), 2.0)
        val route = RunRouteEntity(
            id = 1,
            name = "操场",
            buildMode = "TRACK_SPLINE",
            singleLapMeters = TrackMathUtils.cumulativeDistances(dense).last(),
            isClosedLoop = true,
            denseWaypointsJson = "",
            anchorNodesJson = "[]"
        )

        engine.startWithPoints(route, dense, targetKm = 1.0f, targetPaceSecPerKm = 300, distanceJitterMeters = 0)
        waitUntil { engine.state.value.phase == RunPhase.PRE_START_SETTLE }

        engine.finishEarly()
        waitUntil { engine.state.value.phase == RunPhase.COOLDOWN_WALK }

        clock.advance(250L)
        delay(80L)
        assertEquals(RunPhase.COOLDOWN_WALK, engine.state.value.phase)

        engine.pause()
        waitUntil { engine.state.value.phase == RunPhase.PAUSED }
        engine.resume()
        waitUntil { engine.state.value.phase == RunPhase.COOLDOWN_WALK }
        engine.abort()
        waitUntil { engine.state.value.phase == RunPhase.FINISHED }
    }

    @Test
    fun openRoute_bouncesOutAndBackWithoutTeleporting() = runBlocking {
        val clock = FakeClock()
        val env = FakeEnv(clock)
        val injector = RecordingInjector()
        val engine = RunCruiseEngine(
            env = env,
            injector = injector,
            repository = null,
            tickMillis = 15L,
            random = Random(7),
            settleMillis = 0L,
            cooldownMillis = 0L,
            randomStartSlice = true
        )
        val start = TrackMathUtils.LatLng(36.0, 117.0)
        val end = TrackMathUtils.destinationPoint(start, 90.0, 80.0)
        val dense = TrackMathUtils.catmullRomOpen(
            listOf(start, TrackMathUtils.destinationPoint(start, 90.0, 40.0), end),
            2.0
        )
        val route = RunRouteEntity(
            id = 2,
            name = "折返线",
            buildMode = "TRACK_SPLINE",
            singleLapMeters = TrackMathUtils.cumulativeDistances(dense).last(),
            isClosedLoop = false,
            denseWaypointsJson = "",
            anchorNodesJson = "[]"
        )

        engine.startWithPoints(
            route,
            dense,
            targetKm = 0.2f,
            targetPaceSecPerKm = 240,
            distanceJitterMeters = 0,
            enableLaneDrift = false,
            enableCooldownWalk = false
        )
        val stepJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).let {
            waitUntil { engine.state.value.phase == RunPhase.RUNNING || engine.state.value.phase == RunPhase.FINISHED }
            repeat(60) {
                clock.advance(500L)
                delay(15L)
                if (engine.state.value.phase == RunPhase.FINISHED) return@repeat
            }
        }
        engine.abort()
        waitUntil { engine.state.value.phase == RunPhase.FINISHED }

        // 相邻帧步长不得发生跨 80m 瞬移
        for (i in 1 until injector.frames.size) {
            val d = TrackMathUtils.distanceMeters(injector.frames[i - 1], injector.frames[i])
            assertTrue("unexpected jump of ${d}m between frame ${i - 1} and $i", d < 20.0)
        }
    }

    @Test
    fun deniedMockLocation_stillPersistsNonZeroStartedAt() = runBlocking {
        val clock = FakeClock()
        val env = FakeEnv(clock, mockAllowed = false)
        val sink = RecordingSink()
        val engine = RunCruiseEngine(
            env = env,
            injector = RecordingInjector(),
            repository = sink,
            tickMillis = 15L,
            random = Random(1),
            settleMillis = 0L,
            cooldownMillis = 0L
        )
        val center = TrackMathUtils.LatLng(36.667662, 117.027707)
        val dense = TrackMathUtils.catmullRomClosed(TrackFactory.ovalAnchors(center), 2.0)
        val route = RunRouteEntity(
            id = 3,
            name = "未授权",
            buildMode = "TRACK_SPLINE",
            singleLapMeters = 400.0,
            denseWaypointsJson = "",
            anchorNodesJson = "[]"
        )
        engine.startWithPoints(route, dense, 1.0f, 300)
        waitUntil { engine.state.value.phase == RunPhase.FINISHED && sink.records.isNotEmpty() }
        assertTrue(sink.records.first().startedAt > 0L)
    }
}
