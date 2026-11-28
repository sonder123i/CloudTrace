package com.zcshou.gogogo.run.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.location.LocationManager
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.zcshou.gogogo.run.bridge.ILocationInjector
import com.zcshou.gogogo.run.bridge.InjectorProvider
import com.zcshou.gogogo.run.service.RunSessionHolder
import com.zcshou.service.ServiceGo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin

/**
 * 自由漫游控制器：复用 ServiceGo 的注入通道，自行以 10Hz 驱动坐标；
 * 无 UI，地图页只订阅 [state] 绘制位置与尾迹。
 *
 * 注：本控制器虽由地图页驱动，但本质是注入驱动器，按依赖方向应居于 engine 包
 * （UI 只订阅状态、调用命令），故从 run/ui/home 迁入 run/engine。
 */
object FreeRoamController {

    private const val TICK_MS = 100L
    private const val TRAIL_EVERY_TICKS = 2
    private const val MAX_TRAIL = 1200

    data class RoamState(
        val position: TrackMathUtils.LatLng? = null,
        val baseAltitude: Double = 50.0,
        val trail: List<TrackMathUtils.LatLng> = emptyList(),
        val trailSeq: Long = 0L,
        val attached: Boolean = false,
        val speedMps: Double = 0.0
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(RoamState())
    val state: StateFlow<RoamState> = _state.asStateFlow()
    private val trailBuffer = ArrayDeque<TrackMathUtils.LatLng>(MAX_TRAIL + 1)
    private var trailSeq = 0L

    private var appContext: Context? = null
    private var injector: ILocationInjector? = null
    private var bindRequested = false
    private var ticker: Job? = null
    private var glideJob: Job? = null
    private var motionBearing = 0.0
    private var motionSpeed = 0.0
    private var lastBeforePause: TrackMathUtils.LatLng? = null

    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val provider = service as? InjectorProvider ?: return
            val inj = provider.injector
            injector = inj
            inj.setEngineDriven(true)
            val ctx = appContext ?: return
            val real = lastDeviceLocation(ctx)
            val base = real?.takeIf { it.hasAltitude() }?.altitude ?: 50.0
            val start = real?.let { TrackMathUtils.LatLng(it.latitude, it.longitude) }
                ?: TrackMathUtils.LatLng(39.9087, 116.3975)
            _state.value = _state.value.copy(
                position = _state.value.position ?: start,
                baseAltitude = base,
                attached = true
            )
            injectFrame(_state.value.position ?: start, 0.0, 0.0)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            injector = null
            // 断连后旧绑定已死：清理绑定标志并解绑，让下次 attach() 能干净地重新绑定，
            // 否则 bindRequested 永久为 true，attach 永久 early-return，漫游静默失效
            if (bindRequested) {
                runCatching { appContext?.unbindService(connection) }
            }
            bindRequested = false
            _state.value = _state.value.copy(attached = false)
        }
    }

    fun attach(context: Context) {
        if (injector != null || bindRequested) return
        val ctx = context.applicationContext
        appContext = ctx
        bindRequested = true
        val intent = Intent(ctx, ServiceGo::class.java)
        runCatching {
            // 仅绑定：前台通知由 RoamService / RunSessionService 承担，避免双通知
            ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.onFailure { bindRequested = false }
    }

    fun isAttached(): Boolean = injector != null

    /** 平滑空降：约 0.8 秒内分段注入，尾迹连续 */
    fun teleport(lat: Double, lng: Double) {
        glideJob?.cancel()
        glideJob = null
        injector?.setEngineDriven(true)
        val target = TrackMathUtils.LatLng(lat, lng)
        val from = _state.value.position
        if (from == null) {
            injectFrame(target, 0.0, 0.0)
            appendTrail(target)
            return
        }
        // 赋给 glideJob：drive()/stopMotion() 才能取消进行中的空降，避免两路注入交错
        glideJob = scope.launch {
            val total = TrackMathUtils.distanceMeters(from, target)
            // 分段随距离适度加密（单帧跳变 ≈400m 上限，总时长 ≤ ~3.3s）：
            // 超远空降不再产生离谱的帧间位移，近距离保持原来的 0.8s 手感
            val steps = maxOf(14.0, total / 400.0).toInt().coerceAtMost(60)
            val bearing = TrackMathUtils.bearingDegrees(from, target)
            // loc.setSpeed 封顶到与 drive() 一致的可信区间：空降允许位置瞬移，
            // 但 speed 字段带出 ~3900m/s 的荒谬值会被有速度校验的目标 App 判定异常
            val speed = (total / (steps * 0.055)).coerceAtMost(15.0)
            try {
                for (i in 1..steps) {
                    delay(55)
                    val f = i / steps.toDouble()
                    val p = TrackMathUtils.LatLng(
                        from.lat + (target.lat - from.lat) * f,
                        from.lng + (target.lng - from.lng) * f
                    )
                    injectFrame(p, speed, bearing)
                    appendTrail(p)
                }
            } finally {
                clearGlideJobIfCurrent(coroutineContext[Job])
            }
        }
    }

    /** 摇杆驱动：speedMps <= 0 即停止；bearingDeg 为正北顺时针角度 */
    fun drive(bearingDeg: Double, speedMps: Double) {
        glideJob?.cancel()
        glideJob = null
        motionBearing = bearingDeg
        motionSpeed = speedMps.coerceIn(0.0, 15.0)
        if (motionSpeed <= 0.01) {
            ticker?.cancel()
            ticker = null
            _state.value = _state.value.copy(speedMps = 0.0)
            return
        }
        if (ticker?.isActive == true) return
        injector?.setEngineDriven(true)
        ticker = scope.launch {
            var tick = 0
            while (isActive) {
                delay(TICK_MS)
                val cur = _state.value.position ?: continue
                val next = TrackMathUtils.destinationPoint(
                    cur, motionBearing, motionSpeed * TICK_MS / 1000.0
                )
                injectFrame(next, motionSpeed, motionBearing)
                tick++
                if (tick % TRAIL_EVERY_TICKS == 0) appendTrail(next)
            }
        }
    }

    /** 300m 内的短距离平滑走过去：按给定速度匀速推进，不瞬移 */
    fun glideTo(lat: Double, lng: Double, speedMps: Double) {
        glideJob?.cancel()
        ticker?.cancel()
        ticker = null
        injector?.setEngineDriven(true)
        val target = TrackMathUtils.LatLng(lat, lng)
        val from = _state.value.position
        if (from == null) {
            injectFrame(target, 0.0, 0.0)
            appendTrail(target)
            return
        }
        val distance = TrackMathUtils.distanceMeters(from, target)
        val speed = speedMps.coerceIn(0.5, 8.0)
        if (distance < 0.8) {
            injectFrame(target, 0.0, 0.0)
            appendTrail(target)
            return
        }
        val bearing = TrackMathUtils.bearingDegrees(from, target)
        val durationMs = (distance / speed * 1000.0).toLong().coerceAtLeast(200L)
        glideJob = scope.launch {
            val started = SystemClock.elapsedRealtime()
            try {
                while (isActive) {
                    val elapsed = (SystemClock.elapsedRealtime() - started).toDouble()
                    val f = (elapsed / durationMs).coerceIn(0.0, 1.0)
                    val point = TrackMathUtils.LatLng(
                        from.lat + (target.lat - from.lat) * f,
                        from.lng + (target.lng - from.lng) * f
                    )
                    injectFrame(point, if (f < 1.0) speed else 0.0, bearing)
                    appendTrail(point)
                    if (f >= 1.0) break
                    delay(100)
                }
            } finally {
                clearGlideJobIfCurrent(coroutineContext[Job])
            }
        }
    }

    fun stopMotion() {
        glideJob?.cancel()
        glideJob = null
        ticker?.cancel()
        ticker = null
        // 补一帧零速，避免系统里残留上一帧的高速度
        _state.value.position?.let { injectFrame(it, 0.0, motionBearing) }
        _state.value = _state.value.copy(speedMps = 0.0)
    }

    fun clearTrail() {
        _state.value = _state.value.copy(trail = emptyList())
    }

    /** 采真实定位前暂停注入：移除测试 Provider，让系统返回真实基站/Wi-Fi 位置 */
    fun pauseInjection() {
        lastBeforePause = _state.value.position
        runCatching { injector?.releaseInjection() }
    }

    /** 采样结束后恢复注入与接管 */
    fun resumeInjection() {
        val inj = injector ?: return
        runCatching { inj.setEngineDriven(true) }
        lastBeforePause?.let { injectFrame(it, 0.0, 0.0) }
    }

    fun updateBaseAltitude(altitudeMeters: Double) {
        _state.value = _state.value.copy(baseAltitude = altitudeMeters)
    }

    fun seedPosition(position: TrackMathUtils.LatLng?) {
        if (position == null) return
        _state.value = _state.value.copy(position = position)
    }

    /** 手动接管结束后仅解绑自身连接，不释放 ServiceGo 测试 Provider，以免中断巡航 */
    fun detachKeepProviders() {
        ticker?.cancel()
        ticker = null
        glideJob?.cancel()
        glideJob = null
        motionSpeed = 0.0
        injector = null
        val ctx = appContext
        if (bindRequested && ctx != null) {
            runCatching { ctx.unbindService(connection) }
        }
        bindRequested = false
        trailBuffer.clear()
        trailSeq = 0L
        _state.value = RoamState()
    }

    /** 彻底结束漫游：停止驱动、移除测试 Provider、解绑并关闭 ServiceGo */
    fun release() {
        // 巡航会话接管期间禁止再发 releaseInjection/stopMotion，避免把 RunSessionService 刚建好的 Provider 删掉
        if (!RunSessionHolder.sessionActive) {
            stopMotion()
            runCatching { injector?.releaseInjection() }
        } else {
            ticker?.cancel()
            ticker = null
            glideJob?.cancel()
            glideJob = null
            motionSpeed = 0.0
        }
        injector = null
        val ctx = appContext
        if (bindRequested && ctx != null) {
            runCatching { ctx.unbindService(connection) }
        }
        bindRequested = false
        if (!RunSessionHolder.sessionActive && ctx != null) {
            runCatching { ctx.stopService(Intent(ctx, ServiceGo::class.java)) }
        }
        trailBuffer.clear()
        trailSeq = 0L
        _state.value = RoamState()
    }

    /** 漫游注入的水平精度（随机游走状态，避免固定值穿帮） */
    private var roamAccuracy = 4.5f

    /**
     * finally 自清必须带身份校验：被取消的旧协程，其 finally 可能在
     * 新 glideJob 赋值之后才执行，直接置空会丢掉新任务的引用，
     * 导致后续 teleport/drive 取消不到正在跑的 glide（两路注入交错）
     */
    private fun clearGlideJobIfCurrent(self: Job?) {
        if (self != null && glideJob === self) glideJob = null
    }

    private fun injectFrame(p: TrackMathUtils.LatLng, speed: Double, bearing: Double) {
        val inj = injector
        if (inj == null) {
            _state.value = _state.value.copy(position = p)
            return
        }
        val seconds = SystemClock.elapsedRealtime() / 1000.0
        val alt = _state.value.baseAltitude + 1.2 * sin(seconds / 20.0)
        // 漫游的精度也做随机游走：注入层现在尊重传入值，固定 4.0m 反而是机械特征
        roamAccuracy = (roamAccuracy + (Math.random() - 0.5).toFloat() * 0.6f).coerceIn(3.0f, 9.0f)
        inj.injectLocation(
            p.lat, p.lng, alt,
            speed.toFloat(), bearing.toFloat(),
            roamAccuracy, 6.0f, 0.5f, 8.0f
        )
        _state.value = _state.value.copy(position = p, speedMps = speed)
    }

    private fun appendTrail(p: TrackMathUtils.LatLng) {
        if (trailBuffer.size >= MAX_TRAIL) {
            trailBuffer.removeFirst()
        }
        trailBuffer.addLast(p)
        trailSeq++
        _state.value = _state.value.copy(trail = trailBuffer.toList(), trailSeq = trailSeq)
    }

    private fun lastDeviceLocation(context: Context): android.location.Location? {
        val granted = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        return runCatching {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val candidates = listOf(
                lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER),
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER),
                lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            )
            candidates.filterNotNull().firstOrNull { !isMockLocation(it) }
                ?: candidates.filterNotNull().maxByOrNull { it.time }
        }.getOrNull()
    }

    private fun isMockLocation(location: android.location.Location): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            location.isMock
        } else {
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        }
}
