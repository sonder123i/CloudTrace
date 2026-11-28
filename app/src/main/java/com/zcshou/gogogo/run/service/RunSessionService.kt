package com.zcshou.gogogo.run.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.bridge.InjectorProvider
import com.zcshou.gogogo.run.data.prefs.AdvancedPrefs
import com.zcshou.gogogo.run.data.repository.RunRepository
import com.zcshou.gogogo.run.engine.RunCruiseEngine
import com.zcshou.gogogo.run.engine.PaceStrategy
import com.zcshou.gogogo.run.engine.RunPhase
import com.zcshou.gogogo.run.engine.RunRuntimeState
import com.zcshou.gogogo.run.guard.EnvGuardManager
import com.zcshou.gogogo.run.guard.ShizukuWifiHelper
import com.zcshou.gogogo.run.guard.StepGuard
import com.zcshou.gogogo.run.ui.home.RunHomeActivity
import com.zcshou.service.ServiceGo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 巡航会话前台服务：托管 RunCruiseEngine，定时闹钟或首页指令均可拉起；
 * 锁屏/退后台不中断，通知栏常驻进度与暂停动作。
 */
class RunSessionService : Service() {

    companion object {
        private const val CHANNEL_ID = "run_session"
        private const val NOTIFICATION_ID = 2
        /** FGS 启动失败时的兜底通知 id（避免与前台通知 2 冲突） */
        private const val NOTIFICATION_BLOCKED_ID = 22
        /** WakeLock 单次持有上限（保险丝）：3 小时 */
        private const val WAKE_LOCK_TIMEOUT_MS = 3 * 60 * 60 * 1000L
        /** 持有超过该时长后由下一次状态回调滑动续期 */
        private const val WAKE_LOCK_RENEW_INTERVAL_MS = 2 * 60 * 60 * 1000L
        private const val EXTRA_ROUTE_ID = "route_id"
        private const val EXTRA_KM = "km"
        private const val EXTRA_PACE = "pace"
        private const val EXTRA_DRIFT = "drift"
        private const val EXTRA_COOLDOWN = "cooldown"
        private const val EXTRA_LINKED_PKG = "linked_pkg"
        private const val EXTRA_REVERSE = "reverse_direction"
        private const val EXTRA_TARGET_METERS = "target_meters"
        private const val EXTRA_DISTANCE_JITTER = "distance_jitter_meters"
        private const val EXTRA_AUTO_WIFI = "auto_isolate_wifi"
        private const val EXTRA_PACE_STRATEGY = "pace_strategy"

        const val ACTION_START = "com.zcshou.gogogo.run.action.START"
        const val ACTION_TOGGLE_PAUSE = "com.zcshou.gogogo.run.action.TOGGLE_PAUSE"
        const val ACTION_DETOUR = "com.zcshou.gogogo.run.action.DETOUR"
        const val ACTION_FINISH_EARLY = "com.zcshou.gogogo.run.action.FINISH_EARLY"
        const val ACTION_ABORT = "com.zcshou.gogogo.run.action.ABORT"
        const val ACTION_MANUAL_TAKEOVER = "com.zcshou.gogogo.run.action.MANUAL_TAKEOVER"
        const val ACTION_MANUAL_RELEASE = "com.zcshou.gogogo.run.action.MANUAL_RELEASE"
        const val ACTION_ADJUST_PACE = "com.zcshou.gogogo.run.action.ADJUST_PACE"
        const val ACTION_ADJUST_TARGET = "com.zcshou.gogogo.run.action.ADJUST_TARGET"

        fun adjustPaceIntent(context: Context, paceSecPerKm: Int): Intent =
            Intent(context, RunSessionService::class.java).apply {
                action = ACTION_ADJUST_PACE
                putExtra(EXTRA_PACE, paceSecPerKm)
            }

        fun adjustTargetIntent(context: Context, targetMeters: Double): Intent =
            Intent(context, RunSessionService::class.java).apply {
                action = ACTION_ADJUST_TARGET
                putExtra(EXTRA_TARGET_METERS, targetMeters)
            }

        fun startIntent(
            context: Context,
            routeId: Long,
            targetKm: Float,
            paceSecPerKm: Int,
            laneDrift: Boolean,
            cooldown: Boolean,
            linkedPkg: String? = null,
            reverseDirection: Boolean = false,
            distanceJitterMeters: Int = 150,
            autoIsolateWifi: Boolean = true,
            paceStrategy: String = PaceStrategy.STEADY.name
        ): Intent = Intent(context, RunSessionService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_ROUTE_ID, routeId)
            putExtra(EXTRA_KM, targetKm)
            putExtra(EXTRA_PACE, paceSecPerKm)
            putExtra(EXTRA_DRIFT, laneDrift)
            putExtra(EXTRA_COOLDOWN, cooldown)
            putExtra(EXTRA_REVERSE, reverseDirection)
            putExtra(EXTRA_DISTANCE_JITTER, distanceJitterMeters)
            putExtra(EXTRA_AUTO_WIFI, autoIsolateWifi)
            putExtra(EXTRA_PACE_STRATEGY, paceStrategy)
            if (linkedPkg != null) putExtra(EXTRA_LINKED_PKG, linkedPkg)
        }

        fun commandIntent(context: Context, action: String): Intent =
            Intent(context, RunSessionService::class.java).setAction(action)

        fun detourIntent(context: Context, lat: Double, lng: Double): Intent =
            Intent(context, RunSessionService::class.java).apply {
                action = ACTION_DETOUR
                putExtra("lat", lat)
                putExtra("lng", lng)
            }
    }

    private data class StartConfig(
        val routeId: Long,
        val targetKm: Float,
        val pace: Int,
        val drift: Boolean,
        val cooldown: Boolean,
        val linkedPkg: String?,
        val reverseDirection: Boolean,
        val distanceJitterMeters: Int,
        val autoIsolateWifi: Boolean,
        val paceStrategy: String
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: RunRepository

    private var engine: RunCruiseEngine? = null
    /** 引擎状态收集协程：断连/重绑时必须取消，防止双 collector 并存 */
    private var stateCollectJob: kotlinx.coroutines.Job? = null
    private var pendingStopJob: kotlinx.coroutines.Job? = null
    private var foregroundReady = false
    /** 悬浮窗授权结果：通知每秒刷新多次，缓存一次 appops 查询避免反复 IPC。 */
    private var overlayAllowed = false
    private var serviceGoRequested = false
    private var pendingStart: StartConfig? = null
    private var runStarted = false
    private var finishHandled = false
    // 主线程（onDestroy/onRunFinished）与 IO 协程（断网/恢复）并发读写，必须 volatile
    @Volatile private var wifiDisabledByUs = false
    @Volatile private var scanDisabledByUs = false
    private var linkedAppLaunched = false
    private var linkedTargetAppPkg: String? = null
    private var lastNotifyMs = 0L
    private var lastPhase: RunPhase? = null
    private var stepGuard: StepGuard? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockAcquiredAtMs = 0L
    private var lastHapticPhase: RunPhase? = null
    private var halfHapticDone = false
    private var lastStepWarningAt = 0L
    private var injectionReceiver: BroadcastReceiver? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var screenOff = false
    private var cruiseIsRunning = false
    private var lastWidgetPhase: RunPhase? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? InjectorProvider ?: return
            val cruise = RunCruiseEngine(
                applicationContext,
                binder.injector,
                repository,
                // 心跳：注入间隔 ≤500ms 时跟随它（保住 200ms 的顺滑档），否则固定 500ms
                tickMillis = minOf(500L, AdvancedPrefs.injectionIntervalMs(applicationContext)),
                injectIntervalMs = AdvancedPrefs.injectionIntervalMs(applicationContext),
                overshootMeters = 15..65
            )
            // 系统自动重连会再次回调 onServiceConnected：先取消旧收集，
            // 避免新旧两个 collector 交替向 Holder/通知发布状态
            stateCollectJob?.cancel()
            engine = cruise
            stateCollectJob = scope.launch {
                cruise.state.collect { state ->
                    val warn = stepGuard?.update(state.accumulatedMeters) == true
                    if (warn) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastStepWarningAt > 15_000) {
                            lastStepWarningAt = now
                            vibrate(longArrayOf(0, 90, 120, 90))
                        }
                    } else {
                        lastStepWarningAt = 0L
                    }
                    val merged = state.copy(stepsWarning = warn)
                    RunSessionHolder.publish(merged)
                    cruiseIsRunning = merged.phase == RunPhase.RUNNING ||
                        merged.phase == RunPhase.DETOUR_POINT
                    updateRenderSuppression()
                    updateNotification(merged)
                    handleHaptics(merged)
                    handleWakeLock(merged)
                    // 桌面小组件的状态点/按钮只在相位切换时刷新，避免每拍广播
                    if (merged.phase != lastWidgetPhase) {
                        lastWidgetPhase = merged.phase
                        com.zcshou.gogogo.run.widget.CloudTraceWidgetProvider
                            .refreshAll(this@RunSessionService)
                    }
                    if (merged.phase == RunPhase.FINISHED) onRunFinished()
                }
            }
            maybeStartEngine()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // ServiceGo 进程内死亡后系统会自动重连（BIND_AUTO_CREATE），但旧引擎已失联：
            // 取消收集、释放引擎（落库 ABORTED）、发布 FINISHED 解除 sessionActive 锁定并恢复 Wi-Fi
            stateCollectJob?.cancel()
            stateCollectJob = null
            engine?.release()
            engine = null
            cruiseIsRunning = false
            updateRenderSuppression()
            releaseWakeLock()
            val disconnected = RunRuntimeState(
                phase = RunPhase.FINISHED,
                message = "注入服务已断开，本次运行已中止"
            )
            RunSessionHolder.publish(disconnected)
            updateNotification(disconnected)
            pendingStopJob?.cancel()
            pendingStopJob = scope.launch {
                restoreWifiIfNeeded()
                delay(1_500)
                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = RunRepository(applicationContext)
        overlayAllowed = Settings.canDrawOverlays(this)
        createChannel()
        // targetSdk 34：location 型 FGS 的 startForeground 有硬前提——运行时定位权限
        // 已授予且应用处于可用状态，否则系统直接抛 SecurityException 崩溃循环。
        // 缺权限/被拒绝时降级为普通提示通知并自毁，绝不崩溃
        if (!EnvGuardManager.hasRuntimeLocation(this)) {
            notifyForegroundStartBlocked("缺少定位权限：请在系统设置授予「精确位置」后再开跑")
            stopSelf()
            return
        }
        if (runCatching {
                startForeground(NOTIFICATION_ID, buildNotification(RunRuntimeState(), "准备中…"))
            }.isFailure
        ) {
            notifyForegroundStartBlocked("前台服务被系统拒绝启动，请从首页重新开跑")
            stopSelf()
            return
        }
        foregroundReady = true
        // 计步守护默认关闭（见 AdvancedPrefs）：关闭时根本不创建 StepGuard，
        // 后续 update()/start()/stop() 全部走空安全短路，不注册传感器也不产生告警
        stepGuard = if (com.zcshou.gogogo.run.data.prefs.AdvancedPrefs.isStepGuardEnabled(this)) {
            StepGuard(this)
        } else {
            null
        }
        registerInjectionAlarm()
        registerScreenState()
    }

    /** FGS 启动失败时的兜底：普通通知（非前台），引导用户回到首页修复环境 */
    private fun notifyForegroundStartBlocked(reason: String) {
        val contentIntent = PendingIntent.getActivity(
            this,
            3,
            Intent(this, RunHomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("${getString(R.string.app_name)} · 无法开跑")
            .setContentText(reason)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_BLOCKED_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundReady) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 用户可能刚从设置页返回，重新读一次悬浮窗授权
        overlayAllowed = Settings.canDrawOverlays(this)
        when (intent?.action) {
            ACTION_START -> {
                val routeId = intent.getLongExtra(EXTRA_ROUTE_ID, 0L)
                if (routeId > 0) {
                    pendingStopJob?.cancel()
                    pendingStopJob = null
                    finishHandled = false
                    // 同实例连续开跑（收尾窗口内二次 START）：联动启动与半程
                    // 震动是「每次跑」语义，不重置会让第二跑静默缺失这两个反馈
                    linkedAppLaunched = false
                    halfHapticDone = false
                    if (engine?.state?.value?.phase == RunPhase.FINISHED) {
                        runStarted = false
                    }
                    pendingStart = StartConfig(
                        routeId = routeId,
                        targetKm = intent.getFloatExtra(EXTRA_KM, 3.0f),
                        pace = intent.getIntExtra(EXTRA_PACE, 340),
                        drift = intent.getBooleanExtra(EXTRA_DRIFT, true),
                        cooldown = intent.getBooleanExtra(EXTRA_COOLDOWN, true),
                        linkedPkg = intent.getStringExtra(EXTRA_LINKED_PKG),
                        reverseDirection = intent.getBooleanExtra(EXTRA_REVERSE, false),
                        distanceJitterMeters = intent.getIntExtra(EXTRA_DISTANCE_JITTER, 150).coerceIn(0, 500),
                        autoIsolateWifi = intent.getBooleanExtra(EXTRA_AUTO_WIFI, true),
                        paceStrategy = intent.getStringExtra(EXTRA_PACE_STRATEGY)
                            ?: PaceStrategy.STEADY.name
                    )
                    // 立即将会话标记为活跃，再停 RoamService，确保 FreeRoamController.release() 不会误删 Provider
                    if (!RunSessionHolder.sessionActive) {
                        RunSessionHolder.publish(RunRuntimeState(phase = RunPhase.ENV_GUARD))
                    }
                    ensureServiceGo()
                    RoamService.stop(this)
                    maybeStartEngine()
                } else if (engine == null) {
                    stopSelf()
                }
            }

            ACTION_TOGGLE_PAUSE -> {
                val cruise = engine
                if (cruise == null) {
                    stopSelf()
                } else if (cruise.state.value.phase == RunPhase.PAUSED) {
                    cruise.resume()
                } else {
                    cruise.pause()
                }
            }

            ACTION_DETOUR -> engine?.insertDetour(
                intent.getDoubleExtra("lat", 0.0),
                intent.getDoubleExtra("lng", 0.0)
            ) ?: run { if (engine == null && pendingStart == null) stopSelf() }

            ACTION_FINISH_EARLY -> engine?.finishEarly() ?: stopSelf()

            ACTION_ABORT -> engine?.abort() ?: stopSelf()

            ACTION_MANUAL_TAKEOVER -> engine?.setManualOverride(true)

            ACTION_MANUAL_RELEASE -> engine?.setManualOverride(false)

            ACTION_ADJUST_PACE -> engine?.adjustTargetPace(
                intent.getIntExtra(EXTRA_PACE, 0)
            )

            ACTION_ADJUST_TARGET -> engine?.adjustTargetMeters(
                intent.getDoubleExtra(EXTRA_TARGET_METERS, 0.0)
            )

            else -> if (engine == null && pendingStart == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pendingStopJob?.cancel()
        stateCollectJob?.cancel()
        // 先释放引擎落库，再取消服务协程，并兜底重置全局会话状态
        engine?.release()
        engine = null
        scope.cancel()
        if (RunSessionHolder.sessionActive) {
            RunSessionHolder.reset()
        }
        if (wifiDisabledByUs || scanDisabledByUs) {
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                restoreWifiIfNeeded()
            }
        }
        stepGuard?.stop()
        releaseWakeLock()
        injectionReceiver?.let { runCatching { unregisterReceiver(it) } }
        injectionReceiver = null
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        RenderGate.setRenderSuppressed(false, "服务退出")
        if (serviceGoRequested) {
            runCatching { unbindService(connection) }
            // ServiceGo 可能曾被旧入口 startService 拉起（started 状态），
            // 仅解绑不会销毁它：显式 stopService 兜底，避免前台通知/注入循环残留
            runCatching { stopService(Intent(this, ServiceGo::class.java)) }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun ensureServiceGo() {
        if (serviceGoRequested) return
        serviceGoRequested = true
        // 仅绑定：前台通知由本服务（NOTIFICATION_ID=2）独占，
        // 不再 startForegroundService 拉起 ServiceGo，避免双前台通知并存且 DEFAULT 通道发声
        bindService(Intent(this, ServiceGo::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun maybeStartEngine() {
        val config = pendingStart ?: return
        if (runStarted) return
        linkedTargetAppPkg = config.linkedPkg
        val cruise = engine ?: return
        runStarted = true
        scope.launch {
            val route = repository.getRoute(config.routeId)
            if (route == null) {
                RunSessionHolder.reset()
                stopSelf()
                return@launch
            }
            // 实验性能力：仅在高级选项与定时配置同时允许且 Shizuku 就绪时才自动断 Wi-Fi
            if (config.autoIsolateWifi &&
                AdvancedPrefs.isShizukuAutoWifiEnabled(this@RunSessionService) &&
                EnvGuardManager.isShizukuReady()
            ) {
                // Shizuku newProcess/waitFor 是进程阻塞调用，必须移出主线程防 ANR
                withContext(Dispatchers.IO) {
                    if (EnvGuardManager.isWifiActivelyConnected(this@RunSessionService)) {
                        wifiDisabledByUs = ShizukuWifiHelper.setWifiEnabled(false)
                    }
                    if (EnvGuardManager.isWifiScanAlwaysAvailable(this@RunSessionService)) {
                        scanDisabledByUs = ShizukuWifiHelper.setWifiScanAlwaysEnabled(false)
                    }
                }
            }
            // 解码轨迹 JSON 放到后台默认线程池，避免阻塞主线程
            val densePoints = withContext(Dispatchers.Default) {
                com.zcshou.gogogo.run.engine.TrackJsonCodec.decode(route.denseWaypointsJson)
            }
            launchLinkedTargetApp()
            stepGuard?.start()
            cruise.startWithPoints(
                route = route,
                densePoints = densePoints,
                targetKm = config.targetKm,
                targetPaceSecPerKm = config.pace,
                distanceJitterMeters = config.distanceJitterMeters,
                enableLaneDrift = config.drift,
                enableCooldownWalk = config.cooldown,
                reverseDirection = config.reverseDirection,
                paceStrategy = PaceStrategy.fromName(config.paceStrategy)
            )
        }
    }

    private suspend fun restoreWifiIfNeeded() {
        if (!wifiDisabledByUs && !scanDisabledByUs) return
        withContext(Dispatchers.IO) {
            if (wifiDisabledByUs) {
                ShizukuWifiHelper.setWifiEnabled(true)
                wifiDisabledByUs = false
            }
            if (scanDisabledByUs) {
                ShizukuWifiHelper.setWifiScanAlwaysEnabled(true)
                scanDisabledByUs = false
            }
        }
    }

    private fun onRunFinished() {
        if (finishHandled) return
        finishHandled = true
        stepGuard?.stop()
        releaseWakeLock()
        pendingStopJob?.cancel()
        pendingStopJob = scope.launch {
            restoreWifiIfNeeded()
            delay(1500)
            stopSelf()
        }
    }

    /** 定时任务绑定目标跑步 App 时，开跑瞬间自动拉起 */
    private fun launchLinkedTargetApp() {
        if (linkedAppLaunched) return
        val pkg = linkedTargetAppPkg ?: return
        linkedAppLaunched = true
        runCatching {
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(it)
            }
        }
    }

    /** 模拟注入被系统抢占/拦截时：紧急长震并自动暂停，避免轨迹飞线 */
    private fun registerInjectionAlarm() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // 广播仅接受本应用发送（ServiceGo.notifyInjectionLost 已 setPackage），
                // 防 API<33 下默认导出的 receiver 被三方应用伪造触发长震+强制暂停
                if (intent?.getPackage() != packageName) return
                vibrate(longArrayOf(0, 700, 200, 700))
                engine?.pause()
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(engine?.state?.value ?: RunRuntimeState(), "注入被系统中断，已自动暂停"))
            }
        }
        injectionReceiver = receiver
        val filter = IntentFilter(ServiceGo.ACTION_INJECTION_LOST)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                // RECEIVER_NOT_EXPORTED 重载自 API 33 才有；旧系统运行时注册默认「导出」，
                // 顶部的 onReceive 已校验 intent 的 packageName，伪造广播不会生效。
                @Suppress("UnspecifiedRegisterReceiverFlag", "DEPRECATION")
                registerReceiver(receiver, filter)
            }
        }
    }

    /** 锁屏监听：锁屏期间冻结玻璃渲染（注销传感器高光、停止连续重绘）。 */
    private fun registerScreenState() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> screenOff = true
                    Intent.ACTION_SCREEN_ON -> screenOff = false
                }
                updateRenderSuppression()
            }
        }
        screenReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
        }
    }

    /** V4.0 巡航零额外耗电：RUNNING / 锁屏时冻结所有玻璃卡片的渲染。 */
    private fun updateRenderSuppression() {
        if (screenOff) {
            RenderGate.setRenderSuppressed(true, "锁屏")
        } else {
            RenderGate.setRenderSuppressed(cruiseIsRunning, "巡航中")
        }
    }

    private fun handleHaptics(state: RunRuntimeState) {
        val previous = lastHapticPhase
        if (state.phase != previous) {
            when {
                previous == RunPhase.PRE_START_SETTLE && state.phase == RunPhase.RUNNING ->
                    vibrate(longArrayOf(0, 120))
                (previous == RunPhase.RUNNING || previous == RunPhase.DETOUR_POINT) &&
                    state.phase == RunPhase.COOLDOWN_WALK ->
                    vibrate(longArrayOf(0, 600))
                previous == RunPhase.COOLDOWN_WALK && state.phase == RunPhase.FINISHED ->
                    vibrate(longArrayOf(0, 600, 250, 600))
            }
            lastHapticPhase = state.phase
        }
        if (!halfHapticDone && state.targetMeters > 0 &&
            state.accumulatedMeters >= state.targetMeters / 2.0 &&
            (state.phase == RunPhase.RUNNING || state.phase == RunPhase.DETOUR_POINT)
        ) {
            halfHapticDone = true
            vibrate(longArrayOf(0, 100, 140, 100))
        }
    }

    private fun handleWakeLock(state: RunRuntimeState) {
        val keepAlive = state.phase == RunPhase.PRE_START_SETTLE ||
            state.phase == RunPhase.RUNNING ||
            state.phase == RunPhase.DETOUR_POINT ||
            state.phase == RunPhase.PAUSED ||
            state.phase == RunPhase.COOLDOWN_WALK
        if (keepAlive) acquireWakeLock() else releaseWakeLock()
    }

    private fun acquireWakeLock() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (wakeLock?.isHeld == true) {
            // 滑动续期：固定 3 小时上限会让全马（约 4 小时）等长距离在后半程
            // 锁屏停摆——持有超过 2 小时就先释放再重取，覆盖整个会话
            if (now - wakeLockAcquiredAtMs < WAKE_LOCK_RENEW_INTERVAL_MS) return
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        }
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeepRun:run").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            wakeLockAcquiredAtMs = now
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun vibrate(pattern: LongArray) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "跑步巡航",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "跑圈进度与暂停控制" }
            )
        }
    }

    private fun updateNotification(state: RunRuntimeState) {
        val now = android.os.SystemClock.elapsedRealtime()
        val phaseChanged = state.phase != lastPhase
        if (!phaseChanged && now - lastNotifyMs < 3000) return
        lastNotifyMs = now
        lastPhase = state.phase
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(state, null))
    }

    private fun buildNotification(state: RunRuntimeState, overrideText: String?): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, RunHomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleIntent = PendingIntent.getService(
            this,
            1,
            commandIntent(this, ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val finishIntent = PendingIntent.getService(
            this,
            2,
            commandIntent(this, ACTION_FINISH_EARLY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pace = if (state.paceSecPerKm > 0) {
            String.format(Locale.US, "%d'%02d\"", state.paceSecPerKm / 60, state.paceSecPerKm % 60)
        } else {
            "--"
        }
        val text = overrideText ?: if (state.stepsWarning) {
            "未检测到步数，请把手机放进裤子口袋"
        } else if (state.phase == RunPhase.PRE_START_SETTLE) {
            String.format(
                Locale.US,
                "预热 %d s · 请切到跑步 App，手机放口袋同步计步",
                state.settleRemainSec
            )
        } else {
            String.format(
                Locale.US,
                "%.2f / %.2f km · 配速 %s · 第 %d 圈",
                state.accumulatedKm,
                state.targetKm,
                pace,
                state.currentLapIndex
            )
        }
        val brand = getString(R.string.app_name)
        val title = when (state.phase) {
            RunPhase.FINISHED -> "$brand · 已结束"
            RunPhase.PAUSED -> "$brand · 已暂停"
            RunPhase.PRE_START_SETTLE -> "$brand · 预热中"
            else -> "$brand · 跑圈中"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(
                if (overlayAllowed) {
                    "随身携带手机走动，同步硬件计步"
                } else {
                    "悬浮窗未开启：暂停 / 收尾可在本通知操作"
                }
            )
            .setOngoing(state.phase != RunPhase.FINISHED)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(
                0,
                if (state.phase == RunPhase.PAUSED) "继续" else "暂停",
                toggleIntent
            )
            .addAction(0, "收尾", finishIntent)
            .build()
    }
}