package com.zcshou.gogogo.run.data.prefs

import android.content.Context

/** 体检页「高级选项」里的实验性开关，默认全部关闭。 */
object AdvancedPrefs {

    private const val NAME = "pf_advanced"
    private const val KEY_SHIZUKU_WIFI = "shizuku_auto_wifi"
    private const val KEY_INJECTION_INTERVAL = "injection_interval_ms"
    private const val KEY_STEP_GUARD = "step_guard"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /**
     * 计步守护：虚拟里程在走、但硬件步数长时间不增长时提醒「请放入口袋」。
     *
     * **默认关闭**：它依赖系统计步传感器与活动识别权限，不同机型差异大，
     * 静置、骑车、把手机拿在手里都会误报，对多数用户是噪音而非帮助。
     * 需要的人可以自己打开。
     */
    fun isStepGuardEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STEP_GUARD, false)

    fun setStepGuardEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_STEP_GUARD, enabled).apply()
    }

    fun isShizukuAutoWifiEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHIZUKU_WIFI, false)

    fun setShizukuAutoWifiEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHIZUKU_WIFI, enabled).apply()
    }

    /**
     * 坐标注入（fix）间隔：只决定注入频率，与引擎 500ms 心跳解耦。
     * 默认 1000ms ≈ 真机 GPS 1Hz（更接近真实 fix 节奏，也更省电）。
     */
    fun injectionIntervalMs(context: Context): Long =
        prefs(context).getLong(KEY_INJECTION_INTERVAL, 1000L).coerceIn(200L, 1000L)

    fun setInjectionIntervalMs(context: Context, value: Long) {
        prefs(context).edit().putLong(KEY_INJECTION_INTERVAL, value.coerceIn(200L, 1000L)).apply()
    }
}
