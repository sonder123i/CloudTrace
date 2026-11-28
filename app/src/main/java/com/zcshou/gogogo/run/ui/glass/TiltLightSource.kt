package com.zcshou.gogogo.run.ui.glass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.View
import java.util.Collections
import java.util.WeakHashMap

/**
 * V6.4 倾角光源（QWEA0 `LightSourceController` 的本地等价实现）。
 *
 * 上游那个类是 `internal`，本工程无法直接注册/注销；而且它的生命周期绑定在
 * 图库基类里，我们没法在省电档与后台冻结时精确叫停。这里自己实现一份：
 *
 * - 多个玻璃共享**一个**传感器注册（引用计数，最后一个视图注销时立即停表）；
 * - 输出归一化的屏幕空间光向（默认 `(0, -1)` = 光从上方来），一阶低通平滑；
 * - [unregister] 后不再持有任何监听，**严格 0 唤醒**。
 *
 * 只用于给焦散着色器一点点方向性，幅度很小；拿不到重力传感器时静默退化为默认光向。
 */
internal object TiltLightSource : SensorEventListener {

    /** 一阶低通系数：越小越稳，0.18 在 60Hz 下约 100ms 收敛。 */
    private const val SMOOTHING = 0.18f

    private const val GRAVITY = 9.81f

    private val views = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    private var sensorManager: SensorManager? = null
    private var sensor: Sensor? = null
    private var registered = false

    @Volatile
    var dirX: Float = 0f
        private set

    @Volatile
    var dirY: Float = -1f
        private set

    fun register(view: View) {
        val first = synchronized(views) { views.isEmpty().also { views.add(view) } }
        if (!first || registered) return
        runCatching {
            val manager = view.context.applicationContext
                .getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
            val target = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
                ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                ?: return
            sensorManager = manager
            sensor = target
            registered = manager.registerListener(this, target, SensorManager.SENSOR_DELAY_UI)
            dirX = 0f
            dirY = -1f
        }
    }

    fun unregister(view: View) {
        val empty = synchronized(views) {
            views.remove(view)
            views.isEmpty()
        }
        if (!empty || !registered) return
        runCatching { sensorManager?.unregisterListener(this) }
        registered = false
        sensorManager = null
        sensor = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val values = event.values
        if (values.size < 2) return
        // 重力 x 向右为正：向右倾 → 光向右侧偏，边缘高光随之迁移
        val targetX = (-values[0] / GRAVITY).coerceIn(-1f, 1f)
        dirX += (targetX - dirX) * SMOOTHING
        dirY = -1f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
