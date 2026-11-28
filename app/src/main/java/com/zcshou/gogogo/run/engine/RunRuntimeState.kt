package com.zcshou.gogogo.run.engine

enum class RunPhase {
    IDLE,
    ENV_GUARD,
    PRE_START_SETTLE,
    RUNNING,
    DETOUR_POINT,
    PAUSED,
    COOLDOWN_WALK,
    FINISHED
}

data class RunRuntimeState(
    val phase: RunPhase = RunPhase.IDLE,
    val accumulatedMeters: Double = 0.0,
    val targetMeters: Double = 0.0,
    val currentLapIndex: Int = 0,
    val speedMps: Double = 0.0,
    val paceSecPerKm: Int = 0,
    /** 当前目标配速（悬浮窗运行中调整用） */
    val targetPaceSecPerKm: Int = 0,
    val position: TrackMathUtils.LatLng? = null,
    val elapsedSec: Long = 0,
    val message: String? = null,
    /** PRE_START_SETTLE 倒计时剩余秒数（>0 表示预热中） */
    val settleRemainSec: Int = 0,
    /** 口袋守护：虚拟里程已推进但硬件步数没有增长 */
    val stepsWarning: Boolean = false,
    /** 最近一次注入的海拔（米），悬浮窗遥测行用 */
    val altitudeMeters: Double = 55.0,
    /** 最近一次注入的定位精度（米） */
    val accuracyMeters: Float = 6.5f,
    /** 当前引擎动作线索：热身降速 / 弯道减速 / 末段冲刺 / 稳步巡航 / 慢走缓冲 */
    val motionCue: String? = null
) {
    val targetKm: Double get() = targetMeters / 1000.0
    val accumulatedKm: Double get() = accumulatedMeters / 1000.0
}