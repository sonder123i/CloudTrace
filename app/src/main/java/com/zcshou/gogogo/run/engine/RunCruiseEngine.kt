package com.zcshou.gogogo.run.engine

import android.content.Context
import android.util.Log
import com.zcshou.gogogo.run.bridge.ILocationInjector
import com.zcshou.gogogo.run.data.db.RunRecordEntity
import com.zcshou.gogogo.run.data.db.RunRouteEntity
import com.zcshou.gogogo.run.data.prefs.RunSnapshot
import com.zcshou.gogogo.run.data.prefs.RunSnapshotStore
import com.zcshou.gogogo.run.data.repository.RunRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 跑步巡航状态机：500ms 心跳沿高密度轨迹推进，经 ILocationInjector 注入模拟坐标。
 */
class RunCruiseEngine(
    private val env: EngineEnv,
    private val injector: ILocationInjector,
    private val repository: RunRecordSink? = null,
    private val tickMillis: Long = 500L,
    private val random: Random = Random.Default,
    private val settleMillis: Long = SETTLE_DURATION_MS,
    private val cooldownMillis: Long = COOLDOWN_DURATION_MS,
    private val dwellMillis: Long = DETOUR_DWELL_MS,
    private val randomStartSlice: Boolean = true,
    /** 达标后额外多跑的距离区间（米）；0..0 表示达标即收尾 */
    private val overshootMeters: IntRange = 0..0,
    /**
     * 注入（fix）间隔：默认跟随心跳，生产侧传 1000ms。
     * 与 tickMillis 解耦后 fix 频率接近真机 1Hz，而 UI 发布、物理积分、
     * 摇杆响应仍按 500ms 走，不会变卡。
     */
    private val injectIntervalMs: Long = tickMillis
) {

    constructor(
        context: Context,
        injector: ILocationInjector,
        repository: RunRecordSink? = null,
        tickMillis: Long = 500L,
        random: Random = Random.Default,
        overshootMeters: IntRange = 0..0,
        injectIntervalMs: Long = tickMillis
    ) : this(
        ContextEngineEnv(context), injector, repository, tickMillis, random,
        overshootMeters = overshootMeters,
        injectIntervalMs = injectIntervalMs
    )

    /** fix 节流：每 N 次心跳注入一次（N = 注入间隔 / 心跳，四舍五入，至少 1） */
    private val injectEveryNthTick: Long =
        ((injectIntervalMs + tickMillis.coerceAtLeast(1L) / 2) / tickMillis.coerceAtLeast(1L))
            .coerceAtLeast(1L)

    /** 已执行的心跳计数，用于 fix 节流 */
    private var tickCount = 0L

    /** accuracy 随机游走状态：真机精度是缓慢漂移，逐拍独立重采样会被判为抖动 */
    private var accuracyWalk = 6.5f

    companion object {
        private const val TAG = "RunCruise"
        const val LANE_DRIFT_RANGE_M = 3.5
        const val LANE_TRANSITION_M = 30.0
        const val PAUSE_WANDER_RADIUS_M = 2.0
        const val COOLDOWN_DURATION_MS = 25_000L
        const val COOLDOWN_SPEED_MIN = 1.1
        const val COOLDOWN_SPEED_MAX = 1.4
        const val BASE_ALTITUDE_M = 55.0
        const val SETTLE_DURATION_MS = 5_000L
        const val SETTLE_WANDER_RADIUS_M = 1.5
        const val DETOUR_DWELL_MS = 2_500L
        private const val SNAPSHOT_INTERVAL_MS = 5_000L
        private const val TURN_LOOKAHEAD_M = 15.0
        private const val DETOUR_REJOIN_M = 30.0
        private const val DETOUR_DWELL_METERS = 12.0
        private const val CURVATURE_LOOKAROUND_M = 8.0
        private const val ACCURACY_MIN = 3.5f
        private const val ACCURACY_MAX = 12.0f
        private const val VERTICAL_ACC_MIN = 2.0f
        private const val VERTICAL_ACC_MAX = 5.0f
        private const val SPEED_ACC_MIN = 0.3f
        private const val SPEED_ACC_MAX = 0.8f
        private const val BEARING_ACC_MIN = 5.0f
        private const val BEARING_ACC_MAX = 15.0f
        /** 微地形起伏振幅（与 TrackMathUtils.microTerrainAltitude 默认值同源），用于累计爬升 */
        private const val TERRAIN_AMPLITUDE_M = 1.2
        /** 标准分段长度；目标里程过短时改用 min(单圈, 400m) 保证至少能分出几段 */
        private const val SPLIT_STEP_LONG_M = 1000.0
        private const val SPLIT_STEP_SHORT_M = 400.0
        private const val SPLIT_TAIL_MIN_M = 50.0

        // --- 遥测动作线索 ---
        private const val WARMUP_CUE_METERS = 300.0
        private const val SPRINT_CUE_METERS = 200.0
        const val MOTION_WARMUP = "热身降速"
        const val MOTION_TURN = "弯道减速"
        const val MOTION_SPRINT = "末段冲刺"
        const val MOTION_STEADY = "稳步巡航"
        const val MOTION_COOLDOWN = "慢走缓冲"
        const val MOTION_DETOUR = "绕路打卡"
    }

    // 引擎单线程：所有可变状态只在它上面读写。limitedParallelism(1) 保证
    // 命令（暂停/插点/调参）与 tick 循环串行执行，消除“主线程写 / tick 读”的
    // 可见性与读改写竞态；startWithPoints 在调用线程完成初始化后经 launchLoop
    // 交接（协程 launch 自带 happens-before）。
    @OptIn(ExperimentalCoroutinesApi::class)
    private val engineDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + engineDispatcher)

    // 收尾专用：不随 release() 取消，保证“服务被回收/销毁”路径上的结束记录也能落库
    private val finalizeScope = CoroutineScope(SupervisorJob() + engineDispatcher)

    private val _state = MutableStateFlow(RunRuntimeState())
    val state: StateFlow<RunRuntimeState> = _state.asStateFlow()

    private var job: Job? = null
    // 调用线程（startWithPoints 置位）与引擎线程（tick/finalize 读写）都会访问
    @Volatile
    private var active = false

    private var routeId = 0L
    private var routeName = ""
    private var points: List<TrackMathUtils.LatLng> = emptyList()
    private var cumulative: DoubleArray = DoubleArray(0)
    private var lapLength = 0.0

    private var progressMeters = 0.0
    private var totalMeters = 0.0
    // 以下字段全部只在引擎单线程上读写（见 engineDispatcher 注释），
    // 主线程一律经 onEngineThread 投递，不再需要 @Volatile
    private var targetMeters = 0.0
    private var finishThresholdMeters = 0.0
    private var lapIndex = 0
    private var currentSpeedMps = 0.0
    private var lastPosition: TrackMathUtils.LatLng? = null
    private var lastBearing = 0.0

    private var paceSim: PaceSimulator? = null
    private var targetPaceSecPerKm = 340
    private var activePaceStrategy = PaceStrategy.STEADY
    private var laneDriftEnabled = true
    private var cooldownEnabled = true
    private var laneFrom = 0.0
    private var laneTo = 0.0

    private var paused = false
    private var wasCooldownBeforePause = false
    private var pauseCenter: TrackMathUtils.LatLng? = null
    private var wanderPos: TrackMathUtils.LatLng? = null
    private var isClosedLoopTrack = true
    private var pingPongForward = true

    private var detourPath: List<TrackMathUtils.LatLng> = emptyList()
    private var detourCumulative: DoubleArray = DoubleArray(0)
    private var detourProgress = 0.0
    private var detourRejoinMeters = 0.0
    private var detourActive = false
    private var detourDwellRemainMs = 0L
    private var detourDwellCenter: TrackMathUtils.LatLng? = null
    private var detourDwellDone = false

    private var settleRemainMs = 0L
    private var settleCenter: TrackMathUtils.LatLng? = null
    private var settlePos: TrackMathUtils.LatLng? = null

    private var cooldownRemainingMs = 0L
    private var earlyFinishRequested = false
    private var startedAtElapsedMs = 0L
    private var startedAtWallMs = 0L
    private var lastSnapshotMs = 0L
    private var lastLoggedPhase: RunPhase? = null
    private var lastLoggedMessage: String? = null
    private var baseAltitudeM = BASE_ALTITUDE_M
    private var manualOverride = false
    /** 最近一次注入的海拔与动作线索：悬浮窗遥测行直接读，不必回算 */
    private var lastAltitude = BASE_ALTITUDE_M
    private var lastMotionCue: String? = null

    // --- 真实分段与累计爬升（只在引擎单线程读写，finish 时一次落库） ---
    private val splitMarks = ArrayList<RunSplit>(16)
    private var splitStepMeters = SPLIT_STEP_LONG_M
    private var splitStartMeters = 0.0
    private var splitStartElapsedSec = 0L
    private var splitNextBoundary = SPLIT_STEP_LONG_M
    private var lastTerrainAltitude = Double.NaN
    private var elevationGainMeters = 0.0

    fun start(
        route: RunRouteEntity,
        targetKm: Float,
        targetPaceSecPerKm: Int,
        distanceJitterMeters: Int = 150,
        enableLaneDrift: Boolean = true,
        enableCooldownWalk: Boolean = true,
        resumeFrom: RunSnapshot? = null,
        reverseDirection: Boolean = false,
        paceStrategy: PaceStrategy = PaceStrategy.STEADY
    ) {
        startWithPoints(
            route = route,
            densePoints = TrackJsonCodec.decode(route.denseWaypointsJson),
            targetKm = targetKm,
            targetPaceSecPerKm = targetPaceSecPerKm,
            distanceJitterMeters = distanceJitterMeters,
            enableLaneDrift = enableLaneDrift,
            enableCooldownWalk = enableCooldownWalk,
            resumeFrom = resumeFrom,
            reverseDirection = reverseDirection,
            paceStrategy = paceStrategy
        )
    }

    /** 直接传入轨迹点启动（单测与 GPX 导入共用，避免依赖 org.json） */
    fun startWithPoints(
        route: RunRouteEntity,
        densePoints: List<TrackMathUtils.LatLng>,
        targetKm: Float,
        targetPaceSecPerKm: Int,
        distanceJitterMeters: Int = 150,
        enableLaneDrift: Boolean = true,
        enableCooldownWalk: Boolean = true,
        resumeFrom: RunSnapshot? = null,
        reverseDirection: Boolean = false,
        paceStrategy: PaceStrategy = PaceStrategy.STEADY
    ) {
        if (active) return
        val dense = densePoints
        if (dense.size < 3) {
            _state.value = RunRuntimeState(phase = RunPhase.FINISHED, message = "路线轨迹数据不足，无法开跑")
            return
        }
        routeId = route.id
        routeName = route.name
        baseAltitudeM = route.baseAltitudeMeters
        isClosedLoopTrack = route.isClosedLoop
        pingPongForward = true
        val oriented = if (route.isClockwise != reverseDirection) dense else dense.reversed()
        points = TrackMathUtils.smooth3(oriented)
        cumulative = TrackMathUtils.cumulativeDistances(points)
        lapLength = cumulative.last()
        val resolvedTarget = targetKm * 1000.0 + random.nextInt(0, distanceJitterMeters + 1)
        targetMeters = resolvedTarget
        // 防雷同：达标后随机多跑 15~65m 再进慢走收尾（区间为空时保持旧行为）
        finishThresholdMeters = if (overshootMeters.isEmpty() || overshootMeters.last <= 0) {
            resolvedTarget
        } else {
            resolvedTarget + random.nextInt(
                overshootMeters.first.coerceAtLeast(0),
                overshootMeters.last + 1
            )
        }
        paceSim = PaceSimulator(targetPaceSecPerKm, random, strategy = paceStrategy)
        activePaceStrategy = paceStrategy
        this.targetPaceSecPerKm = targetPaceSecPerKm
        laneDriftEnabled = enableLaneDrift
        cooldownEnabled = enableCooldownWalk

        if (resumeFrom != null && RunSnapshotStore.isResumable(resumeFrom) && resumeFrom.routeId == route.id) {
            totalMeters = resumeFrom.accumulatedMeters
            lapIndex = resumeFrom.currentLapIndex
        } else {
            totalMeters = 0.0
            lapIndex = 0
        }
        // 分段步长：长距离按 1km，短目标改用 min(单圈, 400m)，保证总能复盘出多段
        splitStepMeters = if (targetMeters < 1500.0) {
            minOf(lapLength.takeIf { it > 1.0 } ?: SPLIT_STEP_SHORT_M, SPLIT_STEP_SHORT_M)
                .coerceAtLeast(SPLIT_TAIL_MIN_M)
        } else {
            SPLIT_STEP_LONG_M
        }
        splitMarks.clear()
        splitStartMeters = totalMeters
        splitStartElapsedSec = 0L
        splitNextBoundary = totalMeters + splitStepMeters
        lastTerrainAltitude = Double.NaN
        elevationGainMeters = 0.0
        // 闭环路线才随机切片起跑点；开放折返路线始终从起点出发
        val startSlice = if (isClosedLoopTrack && randomStartSlice && resumeFrom == null && lapLength > 0) {
            random.nextDouble(0.0, lapLength)
        } else {
            0.0
        }
        progressMeters = if (lapLength > 0) (totalMeters + startSlice) % lapLength else 0.0

        tickCount = 0L
        manualOverride = false
        lastPosition = null
        lastBearing = 0.0
        accuracyWalk = 6.5f
        lastAltitude = baseAltitudeM
        lastMotionCue = null
        lastLoggedPhase = null
        lastLoggedMessage = null
        startedAtElapsedMs = 0L
        startedAtWallMs = 0L
        laneFrom = 0.0
        laneTo = if (laneDriftEnabled) random.nextDouble(-LANE_DRIFT_RANGE_M, LANE_DRIFT_RANGE_M) else 0.0
        paused = false
        wasCooldownBeforePause = false
        pauseCenter = null
        wanderPos = null
        detourActive = false
        detourDwellRemainMs = 0L
        detourDwellCenter = null
        detourDwellDone = false
        cooldownRemainingMs = 0L
        earlyFinishRequested = false
        currentSpeedMps = 0.0
        lastSnapshotMs = 0L
        settleRemainMs = settleMillis
        settleCenter = null
        settlePos = null

        active = true
        injector.setEngineDriven(true)
        _state.value = RunRuntimeState(
            phase = RunPhase.ENV_GUARD,
            targetMeters = targetMeters,
            currentLapIndex = lapIndex,
            position = positionAt(progressMeters)
        )
        launchLoop()
    }

    fun pause() = onEngineThread {
        if (!active || paused || detourActive) return@onEngineThread
        wasCooldownBeforePause = _state.value.phase == RunPhase.COOLDOWN_WALK
        paused = true
        val center = lastPosition ?: positionAt(progressMeters)
        pauseCenter = center
        wanderPos = center
        _state.value = _state.value.copy(phase = RunPhase.PAUSED, message = "暂停中")
    }

    fun resume() = onEngineThread {
        if (!active || !paused) return@onEngineThread
        paused = false
        pauseCenter = null
        wanderPos = null
        val nextPhase = when {
            settleRemainMs > 0 -> RunPhase.PRE_START_SETTLE
            wasCooldownBeforePause -> RunPhase.COOLDOWN_WALK
            else -> RunPhase.RUNNING
        }
        val nextMsg = when (nextPhase) {
            RunPhase.PRE_START_SETTLE -> "预热中，请切到跑步 App 准备开始"
            RunPhase.COOLDOWN_WALK -> if (earlyFinishRequested) "慢走缓冲收尾" else "达标，慢走缓冲收尾"
            else -> null
        }
        wasCooldownBeforePause = false
        _state.value = _state.value.copy(phase = nextPhase, message = nextMsg)
    }

    /** 临时手动接管：暂停引擎自身的坐标注入，把注入权交给摇杆 */
    fun setManualOverride(enabled: Boolean) = onEngineThread {
        manualOverride = enabled
    }

    /**
     * 运行中调整目标配速（秒/公里）。
     * 重新创建 PaceSimulator 只重置热身/冲刺系数，不改变已跑里程与路线进度。
     */
    fun adjustTargetPace(paceSecPerKm: Int) = onEngineThread {
        if (!active || paceSecPerKm <= 0) return@onEngineThread
        val clamped = paceSecPerKm.coerceIn(180, 900)
        if (clamped == targetPaceSecPerKm) return@onEngineThread
        targetPaceSecPerKm = clamped
        // 换配速不换策略：否则运行中微调配速会把前慢后快/间歇形态悄悄打回匀速
        paceSim = PaceSimulator(clamped, random, strategy = activePaceStrategy)
        publish()
    }

    /**
     * 运行中调整目标距离（米）。
     * 不允许把目标设到已跑里程以下（至少保留 10m），避免立刻触发结束。
     */
    fun adjustTargetMeters(meters: Double) = onEngineThread {
        if (!active || meters <= 0.0) return@onEngineThread
        val minTarget = totalMeters + 10.0
        val newTarget = meters.coerceIn(minTarget, 100_000.0)
        if (kotlin.math.abs(newTarget - targetMeters) < 0.5) return@onEngineThread
        targetMeters = newTarget
        // 与起跑时一致：保留 15~65m 的随机超额，避免调整后每趟轨迹雷同
        finishThresholdMeters = if (overshootMeters.isEmpty() || overshootMeters.last <= 0) {
            newTarget
        } else {
            newTarget + random.nextInt(
                overshootMeters.first.coerceAtLeast(0),
                overshootMeters.last + 1
            )
        }
        publish()
    }

    /** 响应悬浮窗「临时插点」：生成 当前点 -> 打卡点 -> 主线前方 30m 汇入点 的支线 */
    fun insertDetour(lat: Double, lng: Double) = onEngineThread {
        // lapLength<=0（如轨迹全是重复点）或收尾慢走/暂停态拒绝插点
        if (!active || paused || lapLength <= 0.0 || _state.value.phase == RunPhase.COOLDOWN_WALK) {
            return@onEngineThread
        }
        settleRemainMs = 0L
        detourDwellRemainMs = 0L
        detourDwellCenter = null
        val from = lastPosition ?: positionAt(progressMeters)
        val rejoinProgress = if (isClosedLoopTrack) {
            (progressMeters + DETOUR_REJOIN_M) % lapLength
        } else {
            (progressMeters + if (pingPongForward) DETOUR_REJOIN_M else -DETOUR_REJOIN_M)
                .coerceIn(0.0, lapLength)
        }
        val rejoin = positionAt(rejoinProgress)
        val target = TrackMathUtils.LatLng(lat, lng)
        detourPath = listOf(from, target, rejoin)
        detourCumulative = TrackMathUtils.cumulativeDistances(detourPath)
        detourProgress = 0.0
        detourRejoinMeters = rejoinProgress
        detourActive = true
        detourDwellDone = false
        _state.value = _state.value.copy(phase = RunPhase.DETOUR_POINT, message = "绕路打卡中")
    }

    /** 提前慢走收尾 */
    fun finishEarly() = onEngineThread {
        if (!active) return@onEngineThread
        if (_state.value.phase == RunPhase.COOLDOWN_WALK) return@onEngineThread
        earlyFinishRequested = true
        settleRemainMs = 0L
        detourDwellRemainMs = 0L
        detourDwellCenter = null
        cooldownRemainingMs = cooldownMillis
        detourActive = false
        paused = false
        wasCooldownBeforePause = false
        _state.value = _state.value.copy(phase = RunPhase.COOLDOWN_WALK, message = "慢走缓冲收尾")
    }

    fun abort() = onEngineThread {
        if (active) finish("已手动中止", success = false)
    }

    fun release() {
        // 收尾投递到 finalizeScope（同一引擎线程，串行于 tick）：scope.cancel()
        // 不会影响它，保证服务被回收/销毁路径上的结束记录也能落库
        finalizeScope.launch { if (active) finish("已释放", success = false) }
        scope.cancel()
    }

    /** 把命令投递到引擎单线程执行；所有可变状态变更必须经由此入口 */
    private fun onEngineThread(block: () -> Unit) {
        scope.launch { block() }
    }

    private fun launchLoop() {
        job = scope.launch {
            startedAtElapsedMs = env.elapsedRealtimeMs()
            startedAtWallMs = env.wallTimeMs()
            if (!env.isMockLocationAllowed()) {
                finish("未授权模拟位置，请在开发者选项中选中本应用", success = false)
                return@launch
            }
            if (settleMillis > 0L) {
                // 5 秒起跑预热锚定：原地 1.5m 微漂移，不计里程，倒计时提示切入目标 App
                settleCenter = positionAt(progressMeters)
                settlePos = settleCenter
                _state.value = _state.value.copy(
                    phase = RunPhase.PRE_START_SETTLE,
                    settleRemainSec = (settleMillis / 1000).toInt(),
                    message = "预热中，请切到跑步 App 准备开始"
                )
            } else {
                _state.value = _state.value.copy(phase = RunPhase.RUNNING, message = null)
            }
            var lastNanos = env.nanoTime()
            while (active && isActive) {
                delay(tickMillis)
                val nowNanos = env.nanoTime()
                val dt = ((nowNanos - lastNanos) / 1_000_000_000.0).coerceIn(0.05, 2.0)
                lastNanos = nowNanos
                tick(dt)
                publish()
                maybeSnapshot()
            }
        }
    }

    private fun tick(dtSec: Double) {
        when {
            paused -> tickPause()
            _state.value.phase == RunPhase.COOLDOWN_WALK -> tickCooldown(dtSec)
            detourDwellRemainMs > 0 -> tickDetourDwell(dtSec)
            settleRemainMs > 0 -> tickSettle(dtSec)
            detourActive -> tickDetour(dtSec)
            else -> tickRun(dtSec)
        }
    }

    /** 起跑预热：原地微漂移，不累加里程 */
    private fun tickSettle(dtSec: Double) {
        val center = settleCenter ?: return
        val from = settlePos ?: center
        val next = TrackMathUtils.destinationPoint(
            from,
            random.nextDouble(0.0, 360.0),
            random.nextDouble(0.05, 0.15)
        )
        val candidate = if (TrackMathUtils.distanceMeters(next, center) > SETTLE_WANDER_RADIUS_M) {
            TrackMathUtils.destinationPoint(center, random.nextDouble(0.0, 360.0), random.nextDouble(0.0, 0.8))
        } else {
            next
        }
        settlePos = candidate
        currentSpeedMps = random.nextDouble(0.0, 0.15)
        lastMotionCue = null
        settleRemainMs -= (dtSec * 1000).toLong()
        _state.value = _state.value.copy(
            settleRemainSec = ((settleRemainMs + 999) / 1000).toInt().coerceAtLeast(0)
        )
        if (settleRemainMs <= 0) {
            _state.value = _state.value.copy(phase = RunPhase.RUNNING, message = null)
        }
        injectAt(candidate, currentSpeedMps)
    }

    private fun tickRun(dtSec: Double) {
        val sim = paceSim ?: return
        val headingNow = TrackMathUtils.bearingAt(points, cumulative, progressMeters)
        val headingAhead = TrackMathUtils.bearingAt(points, cumulative, progressMeters + TURN_LOOKAHEAD_M)
        val headingChange = TrackMathUtils.headingDelta(headingNow, headingAhead)
        val speed = sim.speedMps(totalMeters, targetMeters - totalMeters, headingChange, dtSec)
        currentSpeedMps = speed
        // 动作线索：末段冲刺 > 热身降速 > 弯道减速 > 稳步巡航
        lastMotionCue = when {
            targetMeters - totalMeters < SPRINT_CUE_METERS -> MOTION_SPRINT
            totalMeters < WARMUP_CUE_METERS -> MOTION_WARMUP
            headingChange > PaceSimulator.TURN_HEADING_DEG -> MOTION_TURN
            else -> MOTION_STEADY
        }

        val step = speed * dtSec
        advanceTrack(step, rotateLane = true)
        totalMeters += step
        onDistanceAdvanced()

        if (totalMeters >= finishThresholdMeters) {
            if (cooldownEnabled) {
                cooldownRemainingMs = cooldownMillis
                _state.value = _state.value.copy(phase = RunPhase.COOLDOWN_WALK, message = "达标，慢走缓冲收尾")
            } else {
                finish("已完成目标里程", success = true)
                return
            }
        }
        injectAt(positionAt(progressMeters), speed)
    }

    private fun advanceTrack(step: Double, rotateLane: Boolean) {
        if (lapLength <= 0.0) return
        if (isClosedLoopTrack) {
            progressMeters += step
            while (progressMeters >= lapLength) {
                progressMeters -= lapLength
                lapIndex++
                if (rotateLane && laneDriftEnabled) {
                    laneFrom = laneTo
                    laneTo = random.nextDouble(-LANE_DRIFT_RANGE_M, LANE_DRIFT_RANGE_M)
                } else if (!rotateLane) {
                    laneFrom = laneTo
                }
            }
            return
        }
        // 开放路线：抵达起终点后折返，不再跨越终点瞬移回起点
        var remaining = step
        while (remaining > 0.0) {
            if (pingPongForward) {
                val room = lapLength - progressMeters
                if (remaining < room) {
                    progressMeters += remaining
                    remaining = 0.0
                } else {
                    remaining -= room
                    progressMeters = lapLength
                    pingPongForward = false
                    lapIndex++
                    if (rotateLane && laneDriftEnabled) {
                        laneFrom = laneTo
                        laneTo = random.nextDouble(-LANE_DRIFT_RANGE_M, LANE_DRIFT_RANGE_M)
                    } else if (!rotateLane) {
                        laneFrom = laneTo
                    }
                }
            } else {
                val room = progressMeters
                if (remaining < room) {
                    progressMeters -= remaining
                    remaining = 0.0
                } else {
                    remaining -= room
                    progressMeters = 0.0
                    pingPongForward = true
                    lapIndex++
                    if (rotateLane && laneDriftEnabled) {
                        laneFrom = laneTo
                        laneTo = random.nextDouble(-LANE_DRIFT_RANGE_M, LANE_DRIFT_RANGE_M)
                    } else if (!rotateLane) {
                        laneFrom = laneTo
                    }
                }
            }
        }
    }

    private fun tickCooldown(dtSec: Double) {
        val speed = random.nextDouble(COOLDOWN_SPEED_MIN, COOLDOWN_SPEED_MAX)
        currentSpeedMps = speed
        lastMotionCue = MOTION_COOLDOWN
        val step = speed * dtSec
        advanceTrack(step, rotateLane = false)
        totalMeters += step
        onDistanceAdvanced()
        cooldownRemainingMs -= (dtSec * 1000).toLong()
        if (cooldownRemainingMs <= 0) {
            finish(if (earlyFinishRequested) "已提前收尾" else "已完成目标里程", success = true)
            return
        }
        injectAt(positionAt(progressMeters), speed)
    }

    private fun tickPause() {
        lastMotionCue = null
        if (manualOverride) {
            currentSpeedMps = 0.0
            return
        }
        val center = pauseCenter ?: return
        val from = wanderPos ?: center
        val next = TrackMathUtils.destinationPoint(
            from,
            random.nextDouble(0.0, 360.0),
            random.nextDouble(0.05, 0.20)
        )
        val candidate = if (TrackMathUtils.distanceMeters(next, center) > PAUSE_WANDER_RADIUS_M) {
            TrackMathUtils.destinationPoint(center, random.nextDouble(0.0, 360.0), random.nextDouble(0.0, 1.2))
        } else {
            next
        }
        wanderPos = candidate
        currentSpeedMps = random.nextDouble(0.0, 0.2)
        injectAt(candidate, currentSpeedMps)
    }

    private fun tickDetour(dtSec: Double) {
        val sim = paceSim ?: return
        val speed = sim.speedMps(totalMeters, targetMeters - totalMeters, 0.0, dtSec)
        currentSpeedMps = speed
        lastMotionCue = MOTION_DETOUR
        val step = speed * dtSec
        detourProgress += step
        totalMeters += step
        onDistanceAdvanced()
        val total = detourCumulative.last()
        // 打卡点 = 支线第 2 个点（from -> target -> rejoin）
        val targetAt = if (detourCumulative.size > 1) detourCumulative[1] else total
        val targetPoint = detourPath.getOrNull(1) ?: detourPath.last()

        // 抵达打卡点：驻留 2.5 秒后继续并回主线
        if (!detourDwellDone && detourProgress >= targetAt) {
            detourDwellDone = true
            detourDwellRemainMs = dwellMillis
            detourDwellCenter = targetPoint
            _state.value = _state.value.copy(message = "已到打卡点，驻留中")
            injectAt(targetPoint, 0.0)
            return
        }
        // 到达汇入点，回到主线 RUNNING；下一拍由 tickRun 判定是否达标收尾
        if (detourProgress >= total) {
            detourActive = false
            val targetProgress = detourRejoinMeters % lapLength
            if (isClosedLoopTrack && targetProgress < progressMeters) {
                lapIndex++
                if (laneDriftEnabled) {
                    laneFrom = laneTo
                    laneTo = random.nextDouble(-LANE_DRIFT_RANGE_M, LANE_DRIFT_RANGE_M)
                }
            } else {
                laneFrom = laneTo
            }
            progressMeters = targetProgress
            _state.value = _state.value.copy(phase = RunPhase.RUNNING, message = null)
            injectAt(positionAt(progressMeters), speed)
            return
        }
        // 打卡点前后 12m 降速，模拟停步打卡
        val slowZone = if (detourDwellDone) 0.0 else DETOUR_DWELL_METERS
        val near = slowZone > 0 && detourProgress >= (targetAt - slowZone)
        val pos = TrackMathUtils.interpolate(detourPath, detourCumulative, detourProgress)
        lastBearing = TrackMathUtils.bearingAt(detourPath, detourCumulative, detourProgress)
        currentSpeedMps = if (near) speed * 0.35 else speed
        injectAt(pos, currentSpeedMps)
    }

    /** 打卡点 2.5 秒驻留：原地微幅漂移（0.6m 内），结束后沿支线并回主线 */
    private fun tickDetourDwell(dtSec: Double) {
        val center = detourDwellCenter ?: return
        val candidate = TrackMathUtils.destinationPoint(
            center,
            random.nextDouble(0.0, 360.0),
            random.nextDouble(0.0, 0.6)
        )
        detourDwellRemainMs -= (dtSec * 1000).toLong()
        currentSpeedMps = 0.0
        injectAt(candidate, 0.0)
        if (detourDwellRemainMs <= 0) {
            detourDwellCenter = null
            detourActive = true
            _state.value = _state.value.copy(message = "打卡完成，并回跑道")
        }
    }

    private fun positionAt(progress: Double): TrackMathUtils.LatLng {
        val base = TrackMathUtils.interpolate(points, cumulative, progress)
        val rawBearing = TrackMathUtils.bearingAt(points, cumulative, progress)
        val bearing = if (!isClosedLoopTrack && !pingPongForward) {
            (rawBearing + 180.0) % 360.0
        } else {
            rawBearing
        }
        lastBearing = bearing
        val lane = laneOffsetAt(progress)
        if (lane == 0.0) return base
        // 曲率自适应：急弯按比例收窄横向偏移，防止内侧轨迹自交打结
        val curvature = TrackMathUtils.curvatureAngleAt(
            points, cumulative, progress, CURVATURE_LOOKAROUND_M
        )
        val adapted = TrackMathUtils.curvatureAdaptiveLateral(lane, curvature)
        return TrackMathUtils.offsetPoint(base, bearing, adapted)
    }

    private fun laneOffsetAt(progress: Double): Double =
        if (progress < LANE_TRANSITION_M) {
            laneFrom + (laneTo - laneFrom) * (progress / LANE_TRANSITION_M)
        } else {
            laneTo
        }

    private fun elapsedSecNow(): Long = if (startedAtElapsedMs == 0L) {
        0L
    } else {
        (env.elapsedRealtimeMs() - startedAtElapsedMs) / 1000
    }

    /** 每次里程推进后：累计正爬升 + 结算已完成的分段 */
    private fun onDistanceAdvanced() {
        accumulateElevation()
        recordSplits()
    }

    /**
     * 累计正海拔爬升：只取注入坐标所用的同一条微地形正弦波（不含逐点噪声，
     * 否则噪声会把「微小抖动」累加成一两百米的假爬升）。
     */
    private fun accumulateElevation() {
        if (lapLength <= 0.0) return
        val fraction = (progressMeters % lapLength) / lapLength
        val altitude = baseAltitudeM + TERRAIN_AMPLITUDE_M * sin(2 * PI * fraction)
        val previous = lastTerrainAltitude
        if (!previous.isNaN() && altitude > previous) elevationGainMeters += altitude - previous
        lastTerrainAltitude = altitude
    }

    private fun recordSplits() {
        if (splitStepMeters <= 0.0) return
        val elapsed = elapsedSecNow()
        while (totalMeters >= splitNextBoundary) {
            appendSplit(splitNextBoundary - splitStartMeters, elapsed)
            splitStartMeters = splitNextBoundary
            splitStartElapsedSec = elapsed
            splitNextBoundary += splitStepMeters
        }
    }

    private fun appendSplit(distMeters: Double, elapsedSec: Long, phaseName: String = _state.value.phase.name) {
        if (distMeters < 1.0) return
        val duration = (elapsedSec - splitStartElapsedSec).coerceAtLeast(1L)
        splitMarks += RunSplit(
            idx = splitMarks.size + 1,
            distM = distMeters.roundToInt(),
            durSec = duration.toInt(),
            paceSec = (duration * 1000.0 / distMeters).roundToInt(),
            phase = phaseName
        )
    }

    private fun injectAt(position: TrackMathUtils.LatLng, speedMps: Double) {
        lastPosition = position
        val fraction = if (lapLength > 0) (progressMeters % lapLength) / lapLength else 0.0
        val altitude = TrackMathUtils.microTerrainAltitude(baseAltitudeM, fraction, random = random)
        // 海拔在节流判断之前落盘：UI 每拍都能拿到最新值，注入频率不受影响
        lastAltitude = altitude
        val bearing = (lastBearing + random.nextDouble(-2.0, 2.0) + 360.0) % 360.0

        // fix 节流：只压注入频率；UI 发布、物理积分、摇杆响应仍按 tickMillis 走。
        // 用「心跳计数」而不是墙钟差值，保证测试用假时钟时行为可预期。
        if (++tickCount % injectEveryNthTick != 0L) return

        // 精度随机游走：单一事实源放在引擎侧，注入层不再二次重采样
        accuracyWalk = (accuracyWalk + gaussian() * 0.35f).coerceIn(ACCURACY_MIN, ACCURACY_MAX)
        injector.injectLocation(
            position.lat,
            position.lng,
            altitude,
            speedMps.toFloat().coerceAtLeast(0f),
            bearing.toFloat(),
            accuracyWalk,
            random.nextDouble(VERTICAL_ACC_MIN.toDouble(), VERTICAL_ACC_MAX.toDouble()).toFloat(),
            random.nextDouble(SPEED_ACC_MIN.toDouble(), SPEED_ACC_MAX.toDouble()).toFloat(),
            random.nextDouble(BEARING_ACC_MIN.toDouble(), BEARING_ACC_MAX.toDouble()).toFloat()
        )
    }

    /** Box–Muller：kotlin.random.Random 没有 nextGaussian */
    private fun gaussian(): Float {
        val u1 = random.nextDouble().coerceAtLeast(1e-7)
        val u2 = random.nextDouble()
        return (sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)).toFloat()
    }

    private fun publish() {
        // 结束后不再发布，避免把 FINISHED 的 0 速度覆盖成收尾瞬间的步速
        if (!active) return
        val elapsed = if (startedAtElapsedMs == 0L) 0L
        else (env.elapsedRealtimeMs() - startedAtElapsedMs) / 1000
        val pace = if (currentSpeedMps > 0.3) (1000.0 / currentSpeedMps).toInt() else 0
        val next = _state.value.copy(
            accumulatedMeters = totalMeters,
            targetMeters = targetMeters,
            currentLapIndex = lapIndex,
            speedMps = currentSpeedMps,
            paceSecPerKm = pace,
            targetPaceSecPerKm = targetPaceSecPerKm,
            position = lastPosition,
            elapsedSec = elapsed,
            altitudeMeters = lastAltitude,
            accuracyMeters = accuracyWalk,
            motionCue = lastMotionCue
        )
        logPhaseIfChanged(next)
        _state.value = next
    }

    private fun logPhaseIfChanged(state: RunRuntimeState) {
        if (state.phase == lastLoggedPhase && state.message == lastLoggedMessage) return
        lastLoggedPhase = state.phase
        lastLoggedMessage = state.message
        Log.i(
            TAG,
            "phase=${state.phase} msg=${state.message ?: "-"} " +
                "km=${"%.3f".format(state.accumulatedKm)}/${"%.3f".format(state.targetKm)} " +
                "lap=${state.currentLapIndex} v=${"%.2f".format(state.speedMps)} " +
                "settle=${state.settleRemainSec}"
        )
    }

    private fun maybeSnapshot() {
        val repo = repository ?: return
        val now = env.elapsedRealtimeMs()
        if (now - lastSnapshotMs < SNAPSHOT_INTERVAL_MS) return
        lastSnapshotMs = now
        scope.launch(Dispatchers.IO) {
            repo.saveSnapshot(
                RunSnapshot(
                    routeId = routeId,
                    targetDistanceMeters = targetMeters,
                    accumulatedMeters = totalMeters,
                    currentLapIndex = lapIndex,
                    lastUpdatedTimestamp = env.wallTimeMs()
                )
            )
        }
    }

    private fun finish(message: String, success: Boolean) {
        if (!active) return
        active = false
        injector.releaseInjection()
        if (success) env.vibrate()
        val elapsed = if (startedAtElapsedMs == 0L) 0L
        else (env.elapsedRealtimeMs() - startedAtElapsedMs) / 1000
        lastMotionCue = null
        // 先记收尾前所处的阶段：尾段 split 的 phase 应是收尾时的真实阶段
        // （如 COOLDOWN_WALK），而不是置成 FINISHED 之后再取值
        val phaseBeforeFinish = _state.value.phase.name
        _state.value = _state.value.copy(
            phase = RunPhase.FINISHED,
            speedMps = 0.0,
            elapsedSec = elapsed,
            message = message,
            motionCue = null
        )
        // 尾段：不足一整段但 ≥50m 才记，避免把慢走最后几步算成一段配速
        val tailMeters = totalMeters - splitStartMeters
        if (tailMeters >= SPLIT_TAIL_MIN_M) appendSplit(tailMeters, elapsed, phaseBeforeFinish)
        val record = RunRecordEntity(
            routeId = routeId,
            routeName = routeName,
            actualDistanceMeters = totalMeters,
            durationSeconds = elapsed.toInt(),
            averagePaceSec = if (totalMeters > 50) (elapsed * 1000 / totalMeters).toInt() else 0,
            startedAt = startedAtWallMs,
            status = if (success) "COMPLETED" else "ABORTED",
            splitsJson = RunSplitCodec.encode(splitMarks),
            paceStrategy = activePaceStrategy.name,
            elevationGainMeters = elevationGainMeters
        )
        repository?.let { repo ->
            // finalizeScope 不随 release() 取消，外再包 NonCancellable 双保险：
            // 结束记录是本次运行的唯一持久化产物，绝不能随协程取消而静默丢失
            finalizeScope.launch {
                withContext(NonCancellable) {
                    runCatching {
                        if (success) repo.clearSnapshot()
                        repo.addRecord(record)
                    }
                }
            }
        }
    }

}

/** 供 UI 生成路线：标准操场锚点 + 样条加密（无地图场景直接原地生成） */
object TrackFactory {
    const val STRAIGHT_LENGTH_M = 84.39
    const val TURN_RADIUS_M = 36.5

    /** 目标周长 -> (直道长度, 弯道半径)；标准 400 / 国内常见 300 / 200 */
    fun presetGeometry(targetMeters: Int): Pair<Double, Double> = when (targetMeters) {
        300 -> 62.0 to 28.0
        200 -> 30.9 to 22.0
        else -> STRAIGHT_LENGTH_M to TURN_RADIUS_M
    }

    fun ovalAnchors(center: TrackMathUtils.LatLng): List<TrackMathUtils.LatLng> =
        ovalAnchors(center, STRAIGHT_LENGTH_M, TURN_RADIUS_M)

    fun ovalAnchors(
        center: TrackMathUtils.LatLng,
        straightLengthM: Double,
        turnRadiusM: Double
    ): List<TrackMathUtils.LatLng> {
        val r = turnRadiusM
        val d = straightLengthM / 2
        val diag = r * cos(Math.toRadians(45.0))
        val curveOffset = d + r * sin(Math.toRadians(45.0))
        val offset = listOf(
            r to 0.0,
            diag to curveOffset,
            -diag to curveOffset,
            -r to 0.0,
            -diag to -curveOffset,
            diag to -curveOffset
        )
        return offset.map { (east, north) ->
            val lat = center.lat + north / 110574.0
            val lng = center.lng + east / (111320.0 * cos(Math.toRadians(center.lat)))
            TrackMathUtils.LatLng(lat, lng)
        }
    }

    fun denseJson(center: TrackMathUtils.LatLng, spacingMeters: Double = 2.0): Pair<String, Double> =
        denseJson(center, STRAIGHT_LENGTH_M, TURN_RADIUS_M, spacingMeters)

    fun denseJson(
        center: TrackMathUtils.LatLng,
        straightLengthM: Double,
        turnRadiusM: Double,
        spacingMeters: Double = 2.0
    ): Pair<String, Double> {
        val dense = TrackMathUtils.catmullRomClosed(
            ovalAnchors(center, straightLengthM, turnRadiusM), spacingMeters
        )
        return TrackJsonCodec.encode(dense) to TrackMathUtils.cumulativeDistances(dense).last()
    }
}

/** WGS-84 轨迹点序列 -> JSON（[{"lat":..,"lng":..}]，引擎与存储统一契约） */
fun List<TrackMathUtils.LatLng>.toWgsJson(): String = JSONArray().apply {
    this@toWgsJson.forEach {
        put(org.json.JSONObject().put("lat", it.lat).put("lng", it.lng))
    }
}.toString()