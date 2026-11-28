package com.zcshou.gogogo.run.ui.splash

/**
 * 启动动效策略判定器（纯逻辑，无 Android 依赖，可直接 JUnit 覆盖）。
 *
 * 设计目标是「不搞一刀切硬等待」：启动动画只在**真的该演**的时候演满 1050ms，
 * 其余入口（快捷方式 / 定时直达 / 更新弹窗 / 转屏恢复 / 省电）一律不挡操作。
 *
 * | 场景 | 判定 | 结果 |
 * |---|---|---|
 * | 转屏 / 进程重建 | `hasSavedInstanceState` | [Mode.NONE]（禁止重播） |
 * | 后台 / 锁屏 / 省电抑制 | `renderSuppressed` / `powerSaveMode` | [Mode.NONE] |
 * | 系统动画缩放为 0x | `animatorScale == 0f` | [Mode.NONE]（瞬切） |
 * | 欢迎页交接 | `skipLaunchSplash` | [Mode.NONE]（刚看完呼吸图标） |
 * | 快捷方式 / 定时 / 漫游 / 更新 | 任一直达 extra | [Mode.FAST_CUT]（≤200ms，不拦截点击） |
 * | 常规冷启动 | 以上都不是 | [Mode.FULL_SPLASH]（1050ms 完整演出） |
 *
 * 判定顺序即优先级：**先否决、再降级、最后才演完整动效**，保证任何异常输入都不会
 * 把用户困在启动遮罩里。
 */
object LaunchPolicy {

    /** 完整启动动画时长（与 [LaunchSplashOverlayView] 的三段式时间轴一致）。 */
    const val FULL_SPLASH_DURATION_MS = 1050L

    /** 快捷方式等直达场景的极简淡入时长；不拦截任何触摸。 */
    const val FAST_CUT_DURATION_MS = 200L

    /** 省电 / 点击快进时的最短收尾时长，做到「有收尾但不拖沓」。 */
    const val REDUCED_DURATION_MS = 120L

    /** Stage 1「玻璃苏醒 · 呼吸流光」结束点。 */
    const val STAGE_ONE_END_MS = 240L

    /** Stage 2「跑者起势 · 光纤引路」结束点。 */
    const val STAGE_TWO_END_MS = 660L

    /** 启动动效判定结果。 */
    enum class Mode {
        /** 完整播放「云迹破壳 · 跑者入画」。 */
        FULL_SPLASH,

        /** 只做 ≤200ms 的极简淡入，不挂遮罩、不拦截输入。 */
        FAST_CUT,

        /** 完全不动画，直接进首页（转屏 / 省电 / 已看过欢迎页动画）。 */
        NONE
    }

    /**
     * 判定输入。全部为原始值而非 `Intent` / `Bundle`，便于单测构造任意组合；
     * 调用方（[com.zcshou.gogogo.run.ui.home.RunHomeActivity]）负责把平台数据翻译成它。
     */
    data class Input(
        /** `savedInstanceState != null`：转屏或进程重建恢复，绝不能重播。 */
        val hasSavedInstanceState: Boolean = false,
        /** Intent 携带固定快捷方式动作（一键发车 / 漫游 / 停止）。 */
        val hasShortcutAction: Boolean = false,
        /** Intent 携带动态路线的快捷方式 routeId。 */
        val hasShortcutRouteId: Boolean = false,
        /** Intent 携带定时任务的自动发车 routeId。 */
        val hasAutoRouteId: Boolean = false,
        /** 通知点按要求直接打开「自由漫游」Tab。 */
        val openRoam: Boolean = false,
        /** 通知点按要求直接展示更新弹窗。 */
        val showUpdate: Boolean = false,
        /** 欢迎页交接：刚看完循环呼吸图标，进首页不再重复播放。 */
        val skipLaunchSplash: Boolean = false,
        /** 系统「动画程序时长缩放」，0f 表示用户关闭了动画。 */
        val animatorScale: Float = 1f,
        /** 玻璃渲染闸门处于抑制态（后台巡航 / 锁屏）。 */
        val renderSuppressed: Boolean = false,
        /** 系统省电模式。 */
        val powerSaveMode: Boolean = false
    )

    fun decide(input: Input): Mode {
        // 1) 转屏 / 进程重建：结构恢复比重播动画重要，直接放弃。
        if (input.hasSavedInstanceState) return Mode.NONE

        // 2) 用户明确要求「不要再演一遍」（欢迎页交接）。
        if (input.skipLaunchSplash) return Mode.NONE

        // 3) 省电 / 抑制 / 关闭动画：算力与电量优先，0fps 优先于观感。
        if (input.powerSaveMode) return Mode.NONE
        if (input.renderSuppressed) return Mode.NONE
        if (input.animatorScale <= 0f) return Mode.NONE

        // 4) 直达场景：不能让 1 秒动画拖慢「点一下就开始跑」的确定性。
        if (input.hasShortcutAction ||
            input.hasShortcutRouteId ||
            input.hasAutoRouteId ||
            input.openRoam ||
            input.showUpdate
        ) {
            return Mode.FAST_CUT
        }

        // 5) 常规冷启动：完整演出。
        return Mode.FULL_SPLASH
    }

    /** 该模式是否需要挂载全屏遮罩（只有完整动效会挡住首页）。 */
    fun coversHome(mode: Mode): Boolean = mode == Mode.FULL_SPLASH

    /** 该模式对应的入场时长；`NONE` 为 0。 */
    fun durationMs(mode: Mode): Long = when (mode) {
        Mode.FULL_SPLASH -> FULL_SPLASH_DURATION_MS
        Mode.FAST_CUT -> FAST_CUT_DURATION_MS
        Mode.NONE -> 0L
    }
}
