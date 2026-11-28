package com.zcshou.gogogo.run.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.data.db.RunRouteEntity
import com.zcshou.gogogo.run.data.prefs.RunPrefs
import com.zcshou.gogogo.run.data.repository.RunRepository
import com.zcshou.gogogo.run.engine.RunPhase
import com.zcshou.gogogo.run.service.RunSessionHolder
import com.zcshou.gogogo.run.ui.home.RunHomeActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

/**
 * 深海光学 4×1 桌面小组件：本周累计 + 主路线 + 运行状态 + 一键开跑/停止。
 *
 * 合规红线：**绝不在 Receiver 里裸起定位前台服务**。
 * 「开跑」只发 `PendingIntent.getActivity` 跳进 [RunHomeActivity]，由 Activity 走完整的
 * SetupGuard / 定位权限 / Wi-Fi 拦截门控后再启动服务；否则 Android 14+ 会抛
 * ForegroundServiceStartNotAllowedException。
 * 「停止」只在 [RunSessionHolder.sessionActive] 为真时下发指令，避免空转。
 */
class CloudTraceWidgetProvider : AppWidgetProvider() {

    companion object {
        /** 与 RunHomeActivity.EXTRA_SHORTCUT_ACTION 同源 */
        private const val ACTION_DEFAULT_RUN = "default_run"
        private const val ACTION_STOP = "stop"

        const val ACTION_REFRESH = "com.zcshou.gogogo.run.widget.REFRESH"

        /**
         * 请求所有实例刷新。路线/记录变更后由 Repository 调用，
         * 没有小组件时是零成本空操作。
         */
        fun refreshAll(context: Context) {
            val appContext = context.applicationContext
            runCatching {
                val manager = AppWidgetManager.getInstance(appContext) ?: return
                val component = ComponentName(appContext, CloudTraceWidgetProvider::class.java)
                val ids = manager.getAppWidgetIds(component)
                if (ids.isEmpty()) return
                val intent = Intent(appContext, CloudTraceWidgetProvider::class.java).apply {
                    action = ACTION_REFRESH
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                appContext.sendBroadcast(intent)
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { widgetId ->
            // 先渲染一版「待命」，真实数据在后台查库后覆盖，避免首帧空白
            appWidgetManager.updateAppWidget(widgetId, buildViews(context, Snapshot.pending()))
            loadSnapshot(context) { snapshot ->
                appWidgetManager.updateAppWidget(widgetId, buildViews(context, snapshot))
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> {
                val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                    ?: AppWidgetManager.getInstance(context.applicationContext)
                        ?.getAppWidgetIds(
                            ComponentName(context.applicationContext, CloudTraceWidgetProvider::class.java)
                        )
                        ?: return
                onUpdate(context, AppWidgetManager.getInstance(context.applicationContext), ids)
            }
        }
    }

    // ---------------------------------------------------------------- 数据

    private data class Snapshot(
        val weekKm: String,
        val routeName: String,
        val lastPace: String,
        val statusText: String,
        val running: Boolean,
        val hasRoute: Boolean
    ) {
        companion object {
            fun pending() = Snapshot(
                weekKm = "--",
                routeName = "加载中…",
                lastPace = "",
                statusText = "待命",
                running = false,
                hasRoute = false
            )
        }
    }

    private fun loadSnapshot(context: Context, onReady: (Snapshot) -> Unit) {
        val appContext = context.applicationContext
        // goAsync 不适用（Provider 无此 API），用独立 scope + 短查询；查询失败回落待命态
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val snapshot = runCatching {
                val repository = RunRepository(appContext)
                val routes = repository.observeRoutes().first()
                val primary = routes.firstOrNull()
                val weekStart = weekStartMillis()
                val weekMeters = repository.observeCompletedMetersSince(weekStart).first()
                val lastRecord = repository.recentCompleted(1).firstOrNull()
                val state = RunSessionHolder.state.value
                val running = RunSessionHolder.sessionActive &&
                    state.phase != RunPhase.FINISHED && state.phase != RunPhase.IDLE
                Snapshot(
                    weekKm = String.format(Locale.US, "%.2f", weekMeters / 1000.0),
                    routeName = primary?.let { displayRouteName(it) } ?: "还没有路线",
                    lastPace = lastRecord?.let { formatPace(it.averagePaceSec) }
                        ?: primary?.let { formatPace(it.lastTargetPaceSecPerKm) }
                        ?: "",
                    statusText = when {
                        running && state.phase == RunPhase.PAUSED -> "已暂停"
                        running -> "跑圈中"
                        else -> "待命"
                    },
                    running = running,
                    hasRoute = primary != null
                )
            }.getOrDefault(Snapshot.pending())
            onReady(snapshot)
        }
    }

    private fun weekStartMillis(): Long = Calendar.getInstance().apply {
        firstDayOfWeek = Calendar.MONDAY
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun displayRouteName(route: RunRouteEntity): String = if (route.buildMode == "DEMO") {
        route.name.replace(" · 示范", "").replace("（示范）", "").replace("(示范)", "").trim()
    } else {
        route.name
    }

    private fun formatPace(secPerKm: Int): String =
        if (secPerKm > 0) {
            String.format(Locale.US, "%d'%02d\"", secPerKm / 60, secPerKm % 60)
        } else {
            "--"
        }

    // ---------------------------------------------------------------- 视图

    private fun buildViews(context: Context, snapshot: Snapshot): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_cloudtrace_4x1)
        views.setTextViewText(R.id.widgetWeekKm, snapshot.weekKm)
        views.setTextViewText(R.id.widgetRouteName, snapshot.routeName)
        views.setTextViewText(R.id.widgetStatus, snapshot.statusText)
        views.setTextViewText(
            R.id.widgetPace,
            if (snapshot.lastPace.isEmpty()) "" else "配速 ${snapshot.lastPace}"
        )
        // 整体点击：打开首页（不是直接开跑，避免误触）
        views.setOnClickPendingIntent(R.id.widgetRoot, homeIntent(context, action = null))

        val actionLabel = if (snapshot.running) "停止" else "开跑"
        views.setTextViewText(R.id.widgetAction, actionLabel)
        views.setOnClickPendingIntent(
            R.id.widgetAction,
            if (snapshot.running) stopIntent(context) else homeIntent(context, ACTION_DEFAULT_RUN)
        )
        // 运行中状态点用强调色，待命态用次级色
        views.setTextColor(
            R.id.widgetStatus,
            context.getColor(
                if (snapshot.running) R.color.dr_beam_teal else R.color.pf_text_secondary
            )
        )
        return views
    }

    /**
     * 开跑入口：**必须**走 Activity。Activity 内部再走 SetupGuard / 定位权限 /
     * Wi-Fi 拦截，绝不能在 Receiver 里 startForegroundService（Android 14+ 会崩）。
     */
    private fun homeIntent(context: Context, action: String?): PendingIntent {
        val intent = Intent(context, RunHomeActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            if (action != null) putExtra(RunHomeActivity.EXTRA_SHORTCUT_ACTION, action)
        }
        return PendingIntent.getActivity(
            context,
            action?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 停止：只在会话确实活跃时下发，并且走 `startForegroundService` 的
     * 「已存在前台服务 → 追加指令」路径（服务已在运行，不会触发后台启动限制）。
     */
    private fun stopIntent(context: Context): PendingIntent {
        val intent = if (RunSessionHolder.sessionActive) {
            com.zcshou.gogogo.run.service.RunSessionService
                .commandIntent(context, com.zcshou.gogogo.run.service.RunSessionService.ACTION_ABORT)
        } else {
            // 会话已结束：退化为打开首页，绝不空起一个服务
            Intent(context, RunHomeActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
        }
        return PendingIntent.getService(
            context,
            2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
