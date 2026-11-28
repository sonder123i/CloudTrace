package com.zcshou.gogogo.run.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.guard.EnvGuardManager
import com.zcshou.gogogo.run.ui.glass.OverlayGlassStyle
import com.zcshou.gogogo.run.engine.FreeRoamController
import com.zcshou.gogogo.run.ui.home.RunHomeActivity
import com.zcshou.gogogo.run.ui.overlay.RoamHubView
import com.zcshou.gogogo.run.ui.theme.ThemeHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 自由漫游常驻服务：托管虚拟定位注入与悬浮摇杆，
 * 切到其他 App / 锁屏后仍可继续操控（与原版手动模式一致）。
 */
class RoamService : Service() {

    companion object {
        private const val CHANNEL_ID = "roam_session"
        private const val NOTIFICATION_ID = 31
        /** FGS 启动失败时的兜底通知 id（避免与前台通知 31 冲突） */
        private const val NOTIFICATION_BLOCKED_ID = 32

        private const val ACTION_START = "com.zcshou.gogogo.roam.START"
        private const val ACTION_STOP = "com.zcshou.gogogo.roam.STOP"
        private const val ACTION_SHOW = "com.zcshou.gogogo.roam.SHOW"
        private const val ACTION_HIDE = "com.zcshou.gogogo.roam.HIDE"

        const val EXTRA_OPEN_ROAM = "open_roam_tab"

        fun start(context: Context) {
            val intent = Intent(context, RoamService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RoamService::class.java)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var statusJob: Job? = null
    private var hub: RoamHubView? = null
    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var lastNotifyAt = 0L
    private var lastSpeed = -1.0
    private var foregroundReady = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // targetSdk 34：location 型 FGS 的 startForeground 需要运行时定位权限，
        // 缺失/被系统拒绝时降级为普通提示通知并自毁，防崩溃循环（同 RunSessionService）
        val foregroundStarted = EnvGuardManager.hasRuntimeLocation(this) && runCatching {
            startForeground(NOTIFICATION_ID, buildNotification(0.0))
        }.isSuccess
        foregroundReady = foregroundStarted
        if (!foregroundStarted) {
            val blocked = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_cloudtrace)
                .setContentTitle("${getString(R.string.app_name)} · 无法开始漫游")
                .setContentText("缺少定位权限：请在系统设置授予「精确位置」后再试")
                .setAutoCancel(true)
                .build()
            runCatching {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_BLOCKED_ID, blocked)
            }
            stopSelf()
            return
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundReady) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }

            ACTION_SHOW -> {
                // 巡航中从通知点「显示摇杆」会叠出漫游摇杆与巡航胶囊并存，拒之
                if (RunSessionHolder.sessionActive) {
                    toast("巡航进行中，请用巡航胶囊的「临时手动接管」")
                } else {
                    showHub()
                }
            }

            ACTION_HIDE -> {
                hideHub()
                updateNotification(FreeRoamController.state.value.speedMps)
            }

            else -> {
                if (RunSessionHolder.sessionActive) {
                    toast("自动跑圈进行中，请用巡航胶囊的「临时手动接管」")
                    stopSelf()
                    return START_NOT_STICKY
                }
                // 未授予悬浮窗权限时服务只会空转（通知挂着、无任何 UI）：提示后直接收尾
                if (!Settings.canDrawOverlays(this)) {
                    toast("未授予悬浮窗权限，请先在设置中开启后再使用漫游")
                    stopSelf()
                    return START_NOT_STICKY
                }
                showHub()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        statusJob?.cancel()
        scope.cancel()
        removeHubView()
        FreeRoamController.release()
        super.onDestroy()
    }

    // ---------------- 悬浮摇杆 ----------------

    private fun showHub() {
        if (hub != null) return
        if (!Settings.canDrawOverlays(this)) {
            toast("未授予悬浮窗权限，无法显示摇杆")
            return
        }
        val view = RoamHubView(ThemeHelper.themedContext(this))
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val prefs = getSharedPreferences("pf_roam_overlay", MODE_PRIVATE)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", dp(16))
            y = prefs.getInt("y", dp(160))
        }
        view.onLocate = {
            val intent = Intent(this, RunHomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_OPEN_ROAM, true)
            }
            runCatching { startActivity(intent) }
        }
        view.onHide = {
            hideHub()
            updateNotification(lastSpeed)
        }
        view.onStop = { stopEverything() }
        view.onToast = { toast(it) }
        // 快捷地标条展开/收起会改变面板高度：WRAP_CONTENT 窗口不会自动跟着变，
        // 必须重排一次并把窗口拉回屏幕可用区域，否则触摸区与面板错位
        view.onContentSizeChanged = {
            runCatching {
                val maxY = (screenHeight() - view.height - navBarHeight())
                    .coerceAtLeast(statusBarHeight())
                if (lp.y > maxY) {
                    lp.y = maxY
                    params = lp
                }
                wm.updateViewLayout(view, lp)
            }
        }
        view.dragHandler = { dx, dy, end ->
            lp.x = (lp.x + dx).coerceIn(0, (screenWidth() - view.width).coerceAtLeast(0))
            lp.y = (lp.y + dy).coerceIn(
                statusBarHeight(),
                (screenHeight() - view.height - navBarHeight()).coerceAtLeast(statusBarHeight())
            )
            runCatching { wm.updateViewLayout(view, lp) }
            if (end) {
                // V4.0：松手弹簧吸附屏幕左右边缘
                OverlayGlassStyle.springSnapToEdgeX(view, wm, lp) {
                    prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply()
                }
            }
        }
        // 悬浮窗禁用跨窗模糊：Android 12+ 会为 blurBehindRadius 创建全屏 Dim Layer，
        // 输入分发器据此把窗外触摸全部判为 untrusted 丢弃，导致下层页面无法操作。
        view.setCrossWindowBlurEnabled(false)
        runCatching { wm.addView(view, lp) }.onSuccess {
            hub = view
            windowManager = wm
            params = lp
            startStatusLoop()
            updateNotification(FreeRoamController.state.value.speedMps)
        }.onFailure {
            toast("悬浮摇杆创建失败：${it.message}")
        }
    }

    private fun hideHub() {
        FreeRoamController.stopMotion()
        removeHubView()
    }

    private fun removeHubView() {
        val view = hub ?: return
        OverlayGlassStyle.cancelSnap(view)
        runCatching { windowManager?.removeViewImmediate(view) }
        hub = null
        windowManager = null
        params = null
    }

    private fun startStatusLoop() {
        if (statusJob != null) return
        statusJob = scope.launch {
            FreeRoamController.state.collect { state ->
                hub?.updateStatus(state.speedMps)
                val now = android.os.SystemClock.elapsedRealtime()
                val stoppedNow = state.speedMps <= 0.05 && lastSpeed > 0.05
                val movedChange = now - lastNotifyAt > 1500 &&
                    kotlin.math.abs(state.speedMps - lastSpeed) > 0.05
                if (stoppedNow || movedChange) {
                    lastNotifyAt = now
                    lastSpeed = state.speedMps
                    updateNotification(state.speedMps)
                }
            }
        }
    }

    private fun stopEverything() {
        FreeRoamController.release()
        removeHubView()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------- 通知 ----------------

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "自由漫游",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "漫游状态与悬浮摇杆控制" }
            )
        }
    }

    private fun buildNotification(speedMps: Double): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cloudtrace)
            .setContentTitle("自由漫游")
            .setContentText(
                if (speedMps > 0.05) String.format(Locale.US, "漫游中 · %.1f m/s", speedMps)
                else "已接管 · 推摇杆开始移动"
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, RunHomeActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra(EXTRA_OPEN_ROAM, true)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(
                0, "停止",
                PendingIntent.getService(
                    this, 1,
                    Intent(this, RoamService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        // 没有悬浮窗权限时摇杆按钮点了也不会出现，直接不显示，避免死按钮
        if (Settings.canDrawOverlays(this)) {
            builder.addAction(
                0, if (hub == null) "显示摇杆" else "隐藏摇杆",
                PendingIntent.getService(
                    this, 2,
                    Intent(this, RoamService::class.java)
                        .setAction(if (hub == null) ACTION_SHOW else ACTION_HIDE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
        return builder.build()
    }

    private fun updateNotification(speedMps: Double) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(speedMps))
        }
    }

    // ---------------- 工具 ----------------

    private fun screenWidth(): Int = resources.displayMetrics.widthPixels

    private fun screenHeight(): Int = resources.displayMetrics.heightPixels

    private fun statusBarHeight(): Int =
        resources.getIdentifier("status_bar_height", "dimen", "android")
            .takeIf { it > 0 }?.let { resources.getDimensionPixelSize(it) } ?: dp(24)

    private fun navBarHeight(): Int =
        resources.getIdentifier("navigation_bar_height", "dimen", "android")
            .takeIf { it > 0 }?.let { resources.getDimensionPixelSize(it) } ?: dp(48)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
