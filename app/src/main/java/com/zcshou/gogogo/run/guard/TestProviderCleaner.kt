package com.zcshou.gogogo.run.guard

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import com.elvishew.xlog.XLog
import java.io.File

/**
 * test provider 残留自愈。
 *
 * 背景：addTestProvider 把系统 GPS / NETWORK provider 顶掉后，只有 Service.onDestroy 会 remove。
 * 进程被系统杀、崩溃、被省电策略清理时 onDestroy 不执行，provider 可能留在 system_server，
 * 真实 GPS 长期不可用（用户表现为打车/共享位置/相机地理信息全坏，通常要重启手机才恢复）。
 *
 * 两条硬约束：
 * 1) 只在主进程清理。:remote（百度定位 SDK）同样会跑 Application.onCreate，
 *    它在主进程注入期间执行清理会把正在使用的 provider 摘掉，表现为「跑到一半突然不动」。
 * 2) 直接 removeTestProvider 并吞掉异常，不要先 setTestProviderEnabled(false)：
 *    后者对真实 provider 会抛异常，个别 ROM 上还会真的把系统 GPS 关掉。
 */
object TestProviderCleaner {

    private val PROVIDERS = arrayOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER
    )

    fun isMainProcess(context: Context): Boolean = try {
        val name = currentProcessName()
        // 取不到进程名时选择「不清」：宁可残留，也不要在子进程里误清正在使用的 provider
        name != null && name == context.packageName
    } catch (t: Throwable) {
        false
    }

    private fun currentProcessName(): String? {
        runCatching {
            val raw = File("/proc/self/cmdline").readText().trim().trim('\u0000')
            if (raw.isNotEmpty()) return raw
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { Application.getProcessName() }.getOrNull()
        } else {
            null
        }
    }

    /** 幂等清理：provider 不是本进程注册的 test provider 时 removeTestProvider 抛异常，忽略即可 */
    fun cleanup(context: Context) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        for (provider in PROVIDERS) {
            try {
                lm.removeTestProvider(provider)
                XLog.i("TESTPROVIDER: removed residual ${provider}")
            } catch (t: Throwable) {
                // IllegalArgumentException / SecurityException：该 provider 未处于 test 模式（正常情况）
            }
        }
    }

    fun cleanupIfMainProcess(context: Context) {
        if (isMainProcess(context)) cleanup(context)
    }
}

/**
 * 开机一次性清理：进程若在关机前被杀，provider 残留会跨重启继续顶掉真实 GPS。
 * 只清理，不拉起任何前台服务（开机 startForegroundService 有后台启动限制与 ANR 风险）。
 */
class TestProviderBootCleaner : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        TestProviderCleaner.cleanupIfMainProcess(context.applicationContext)
    }
}
