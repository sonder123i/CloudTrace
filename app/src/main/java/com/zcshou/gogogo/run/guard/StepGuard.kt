package com.zcshou.gogogo.run.guard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 免 Root 口袋守护：只读监听 TYPE_STEP_COUNTER。
 * 虚拟里程已推进但硬件步数一直不动时给出 warning，让 UI 变色并震动提醒。
 * 无计步传感器、系统拒绝注册或未授予 ACTIVITY_RECOGNITION 时静默跳过。
 */
class StepGuard(context: Context) : SensorEventListener {

    private val appContext = context.applicationContext
    private val sensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    private var baseline = -1f
    private var latest = -1f
    private var lastSampledSteps = -1f
    private var lastMovementMeters = 0.0

    var available = false
        private set

    var warning = false
        private set

    fun start() {
        if (sensor == null || available) return
        // Android 10+ 计步事件受 ACTIVITY_RECOGNITION 保护：未授权时注册可能“成功”
        // 但永远收不到首帧事件，会走进 baseline<0 的误报告警分支，因此直接静默禁用
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACTIVITY_RECOGNITION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val ok = runCatching {
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }.getOrNull()
        available = ok == true
    }

    /** 每次引擎状态更新时调用；virtualMeters 为累计虚拟里程 */
    fun update(virtualMeters: Double): Boolean {
        if (!available) return false
        if (baseline < 0f) {
            // 计步传感器为 ON_CHANGE：手机静置在桌上时常收不到首帧回调，超过 100m 仍无步数事件即告警
            warning = virtualMeters > 100.0
            return warning
        }
        if (lastSampledSteps < 0f) {
            lastSampledSteps = latest
            lastMovementMeters = virtualMeters
        }
        val stepDelta = latest - lastSampledSteps
        if (stepDelta >= 1f) {
            lastSampledSteps = latest
            lastMovementMeters = virtualMeters
            warning = false
        } else {
            // 跑出 100m 以上却再无任何步数增长（中途放下手机）同样告警
            warning = (virtualMeters - lastMovementMeters) > 100.0
        }
        return warning
    }

    fun stop() {
        if (available) runCatching { sensorManager?.unregisterListener(this) }
        available = false
        warning = false
        baseline = -1f
        latest = -1f
        lastSampledSteps = -1f
        lastMovementMeters = 0.0
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_STEP_COUNTER || event.values.isEmpty()) return
        latest = event.values[0]
        if (baseline < 0f) baseline = latest
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
