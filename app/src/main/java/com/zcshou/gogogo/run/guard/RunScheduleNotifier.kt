package com.zcshou.gogogo.run.guard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.data.db.RunRouteEntity
import com.zcshou.gogogo.run.data.db.RunScheduleEntity
import com.zcshou.gogogo.run.ui.home.RunHomeActivity
import java.util.Locale

/**
 * 定时任务降级提醒：旧版 Android 后台拉起服务被拒时，用高优先级 + 全屏 Intent 提醒，
 * 用户点按即进入首页并自动开跑。
 */
object RunScheduleNotifier {

    private const val CHANNEL_ID = "run_schedule"
    private const val NOTIFICATION_ID = 3

    const val EXTRA_AUTO_ROUTE_ID = "auto_route_id"
    const val EXTRA_AUTO_KM = "auto_km"
    const val EXTRA_AUTO_PACE = "auto_pace"
    const val EXTRA_AUTO_LINKED_PKG = "auto_linked_pkg"

    fun postFallback(context: Context, schedule: RunScheduleEntity, route: RunRouteEntity) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "定时开跑提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "定时到点但无法后台拉起服务时的提醒" }
            )
        }
        val intent = Intent(context, RunHomeActivity::class.java).apply {
            putExtra(EXTRA_AUTO_ROUTE_ID, route.id)
            putExtra(EXTRA_AUTO_KM, schedule.targetDistanceKm)
            putExtra(EXTRA_AUTO_PACE, schedule.targetPaceSecPerKm)
            schedule.linkedTargetAppPkg?.let { putExtra(EXTRA_AUTO_LINKED_PKG, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val content = PendingIntent.getActivity(
            context,
            10,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("该跑步了：${route.name}")
            .setContentText(
                String.format(Locale.US, "目标 %.1f km · 点按立即开跑", schedule.targetDistanceKm)
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(content)
            .setFullScreenIntent(content, true)
            .setTimeoutAfter(15 * 60_000L)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}