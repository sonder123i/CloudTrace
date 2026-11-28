package com.zcshou.gogogo.run.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.ui.home.RunHomeActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/**
 * 更新下载前台服务：把长下载从 Activity 生命周期里剥离。
 *
 * 通知点击不会直接安装，只回到首页打开更新面板；安装始终需要用户再点一次。
 */
class UpdateDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager by lazy { UpdateManager.getInstance(this) }
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "应用更新",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun notification(state: UpdateManager.State): Notification {
        val open = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            Intent(this, RunHomeActivity::class.java)
                .putExtra(UpdateManager.EXTRA_SHOW_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(
                if (state.phase == UpdateManager.Phase.READY) {
                    "更新已下载，点击安装"
                } else {
                    "正在更新${getString(R.string.app_name)}"
                }
            )
            .setContentText(state.message)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(state.active)
        if (state.active) {
            builder.setProgress(
                100,
                state.progress,
                state.phase != UpdateManager.Phase.DOWNLOADING
            )
            val pause = PendingIntent.getService(
                this,
                NOTIFICATION_ID + 1,
                Intent(this, UpdateDownloadService::class.java).setAction(ACTION_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "暂停", pause)
        } else {
            builder.setAutoCancel(true)
        }
        return builder.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(notification(manager.state.value))
        if (intent?.action == ACTION_PAUSE) {
            manager.pauseDownload()
            return START_NOT_STICKY
        }
        manager.runDownload()
        scope.coroutineContext.cancelChildren()
        scope.launch {
            var lastNotifyAt = 0L
            var lastPhase: UpdateManager.Phase? = null
            manager.state.collect { state ->
                val now = android.os.SystemClock.elapsedRealtime()
                if (!state.active || state.phase != lastPhase || now - lastNotifyAt >= 400L) {
                    lastNotifyAt = now
                    lastPhase = state.phase
                    runCatching { notifications.notify(NOTIFICATION_ID, notification(state)) }
                }
                if (!state.active) {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 14（targetSdk 34）走 1 参版本：不覆写会让超时静默走过场、来不及优雅暂停 */
    override fun onTimeout(startId: Int) {
        onTimeout(startId, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        manager.pauseDownload()
        stopForegroundCompat()
        stopSelf()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        manager.serviceStopped()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL_ID = "app_updates"
        const val NOTIFICATION_ID = 9801
        const val ACTION_PAUSE = "pause"
    }
}
