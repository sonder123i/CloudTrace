package com.zcshou.gogogo.run.guard

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 第三方可启动 App 查询助手：统一在 Dispatchers.IO 执行包枚举与标签加载，
 * 避免 RouteListFragment / SettingsFragment 重复逻辑及阻塞主线程。
 */
object InstalledAppHelper {

    suspend fun loadLaunchableApps(context: Context): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val appCtx = context.applicationContext
            val pm = appCtx.packageManager
            val self = appCtx.packageName
            pm.getInstalledApplications(0)
                .asSequence()
                .filter { it.packageName != self && pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { it.packageName to it.loadLabel(pm).toString() }
                .sortedBy { it.second }
                .toList()
        }

    fun appLabel(context: Context, pkg: String): String = runCatching {
        val pm = context.applicationContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
