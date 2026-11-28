package com.zcshou.gogogo.run.ui.home

import com.zcshou.gogogo.run.data.db.RunRouteEntity
import com.zcshou.gogogo.run.engine.PaceStrategy
import com.zcshou.gogogo.run.engine.RunRuntimeState

/**
 * 首页对 Fragment / 悬浮胶囊暴露的运行控制面。
 */
interface RunHost {
    fun startRun(
        route: RunRouteEntity,
        targetKm: Float,
        paceSecPerKm: Int,
        laneDrift: Boolean,
        cooldown: Boolean,
        reverseDirection: Boolean = false,
        paceStrategy: PaceStrategy = PaceStrategy.STEADY
    )

    fun pauseResume()

    /** 无地图版临时插点：按方位 + 距离选点 */
    fun pickDetour()

    fun finishEarly()

    fun abortRun()

    fun isServiceReady(): Boolean

    fun currentState(): RunRuntimeState

    /** 悬浮窗形态切换后强制重建窗口，刷新尺寸与触摸区域 */
    fun relayoutOverlay() = Unit

    /** 结束模拟定位：停止引擎/漫游注入、移除测试 Provider、关闭悬浮窗 */
    fun stopSimulation() = Unit

    /** 临时手动接管：暂停引擎自身注入，把注入权交给摇杆 */
    fun beginManualOverride() = Unit

    /** 结束手动接管：恢复引擎自动注入 */
    fun endManualOverride() = Unit

    /** 弹系统对话框前临时隐藏悬浮窗，避免遮挡确认按钮 */
    fun setOverlayHidden(hidden: Boolean) = Unit

    /** 运行中调整目标配速（秒/公里） */
    fun adjustPace(paceSecPerKm: Int) = Unit

    /** 运行中调整目标距离（公里） */
    fun adjustTargetKm(km: Float) = Unit
}