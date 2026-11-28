package com.zcshou.gogogo.run.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.zcshou.gogogo.run.data.repository.RunRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 设备重启、应用覆盖更新或系统时间变化后恢复已启用的定时跑圈闹钟。
 */
class RunScheduleBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_TIME_CHANGED &&
            action != Intent.ACTION_TIMEZONE_CHANGED
        ) {
            return
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repo = RunRepository(context.applicationContext)
                val schedules = repo.observeSchedules().first()
                schedules.filter { it.isScheduleEnabled }.forEach { schedule ->
                    RunScheduleAlarmManager.scheduleNext(context.applicationContext, schedule)
                }
            } catch (e: Exception) {
                // goAsync 只有 10 秒窗口；开库/迁移失败若不捕获会在开机或覆盖更新后直接崩进程
                Log.e("RunScheduleBoot", "恢复定时跑圈闹钟失败", e)
            } finally {
                pending.finish()
            }
        }
    }
}
