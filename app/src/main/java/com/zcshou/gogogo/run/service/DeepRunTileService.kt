package com.zcshou.gogogo.run.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.data.prefs.RunPrefs
import com.zcshou.gogogo.run.data.repository.RunRepository
import com.zcshou.gogogo.run.guard.EnvGuardManager
import com.zcshou.gogogo.run.guard.SetupGuard
import com.zcshou.gogogo.run.ui.home.RunHomeActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 下拉控制中心磁贴：未开跑时一键按默认路线发车，跑动中再点一次触发慢走收尾。
 * 全部走本地 Room + 前台服务，无后端。
 */
class DeepRunTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val active = RunSessionHolder.sessionActive
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (active) "收尾" else "云迹跑圈"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_cloudtrace)
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (RunSessionHolder.sessionActive) {
            runCatching {
                ContextCompat.startForegroundService(
                    this,
                    RunSessionService.commandIntent(this, RunSessionService.ACTION_FINISH_EARLY)
                )
            }.onFailure { openApp() }
            return
        }
        scope.launch {
            val repository = RunRepository(applicationContext)
            val route = repository.observeRoutes().first().firstOrNull()
            if (route == null ||
                !SetupGuard.isReady(this@DeepRunTileService) ||
                !EnvGuardManager.hasRuntimeLocation(this@DeepRunTileService)
            ) {
                openApp()
                return@launch
            }
            RoamService.stop(this@DeepRunTileService)
            val started = runCatching {
                ContextCompat.startForegroundService(
                    this@DeepRunTileService,
                    RunSessionService.startIntent(
                        this@DeepRunTileService,
                        route.id,
                        route.lastTargetDistanceKm,
                        route.lastTargetPaceSecPerKm,
                        route.lastSmartJitterEnabled,
                        true,
                        linkedPkg = RunPrefs.targetApp(this@DeepRunTileService)
                    )
                )
            }.isSuccess
            if (!started) openApp()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun openApp() {
        val intent = Intent(this, RunHomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pending)
        } else {
            // PendingIntent 重载自 API 34 才有，旧系统只能用 Intent 重载
            @Suppress("StartActivityAndCollapseDeprecated", "DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
