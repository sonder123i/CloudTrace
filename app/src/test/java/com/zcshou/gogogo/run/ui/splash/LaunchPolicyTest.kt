package com.zcshou.gogogo.run.ui.splash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动动效策略判定。
 *
 * 这些分支决定了「用户能不能在 1 秒内开始跑步」，判错的两类事故都很致命：
 * 快捷方式被 1050ms 遮罩挡住（点了没反应），或转屏后突然又演一遍开屏（像卡死）。
 */
class LaunchPolicyTest {

    @Test
    fun `转屏或进程重建返回 NONE`() {
        assertEquals(
            LaunchPolicy.Mode.NONE,
            LaunchPolicy.decide(
                LaunchPolicy.Input(
                    hasSavedInstanceState = true,
                    // 即使其余条件全部满足完整动效，也不能重播
                    animatorScale = 1f
                )
            )
        )
    }

    @Test
    fun `欢迎页交接的 skipExtra 返回 NONE`() {
        assertEquals(
            LaunchPolicy.Mode.NONE,
            LaunchPolicy.decide(LaunchPolicy.Input(skipLaunchSplash = true))
        )
    }

    @Test
    fun `关闭系统动画缩放返回 NONE`() {
        assertEquals(
            LaunchPolicy.Mode.NONE,
            LaunchPolicy.decide(LaunchPolicy.Input(animatorScale = 0f))
        )
    }

    @Test
    fun `省电模式与渲染抑制都返回 NONE`() {
        assertEquals(
            LaunchPolicy.Mode.NONE,
            LaunchPolicy.decide(LaunchPolicy.Input(powerSaveMode = true))
        )
        assertEquals(
            LaunchPolicy.Mode.NONE,
            LaunchPolicy.decide(LaunchPolicy.Input(renderSuppressed = true))
        )
    }

    @Test
    fun `快捷方式与直达 extra 返回 FAST_CUT`() {
        assertEquals(
            LaunchPolicy.Mode.FAST_CUT,
            LaunchPolicy.decide(LaunchPolicy.Input(hasShortcutAction = true))
        )
        assertEquals(
            LaunchPolicy.Mode.FAST_CUT,
            LaunchPolicy.decide(LaunchPolicy.Input(hasShortcutRouteId = true))
        )
        assertEquals(
            LaunchPolicy.Mode.FAST_CUT,
            LaunchPolicy.decide(LaunchPolicy.Input(hasAutoRouteId = true))
        )
        assertEquals(
            LaunchPolicy.Mode.FAST_CUT,
            LaunchPolicy.decide(LaunchPolicy.Input(openRoam = true))
        )
        assertEquals(
            LaunchPolicy.Mode.FAST_CUT,
            LaunchPolicy.decide(LaunchPolicy.Input(showUpdate = true))
        )
    }

    @Test
    fun `常规冷启动返回完整动效`() {
        assertEquals(
            LaunchPolicy.Mode.FULL_SPLASH,
            LaunchPolicy.decide(LaunchPolicy.Input())
        )
    }

    @Test
    fun `否决项优先级高于直达场景`() {
        assertEquals(
            LaunchPolicy.Mode.NONE,
            LaunchPolicy.decide(
                LaunchPolicy.Input(hasShortcutAction = true, animatorScale = 0f)
            )
        )
        assertEquals(
            LaunchPolicy.Mode.NONE,
            LaunchPolicy.decide(
                LaunchPolicy.Input(hasAutoRouteId = true, hasSavedInstanceState = true)
            )
        )
    }

    @Test
    fun `只有完整动效会遮挡首页`() {
        assertTrue(LaunchPolicy.coversHome(LaunchPolicy.Mode.FULL_SPLASH))
        assertFalse(LaunchPolicy.coversHome(LaunchPolicy.Mode.FAST_CUT))
        assertFalse(LaunchPolicy.coversHome(LaunchPolicy.Mode.NONE))
    }

    @Test
    fun `时长与模式一一对应`() {
        assertEquals(
            LaunchPolicy.FULL_SPLASH_DURATION_MS,
            LaunchPolicy.durationMs(LaunchPolicy.Mode.FULL_SPLASH)
        )
        assertEquals(
            LaunchPolicy.FAST_CUT_DURATION_MS,
            LaunchPolicy.durationMs(LaunchPolicy.Mode.FAST_CUT)
        )
        assertEquals(0L, LaunchPolicy.durationMs(LaunchPolicy.Mode.NONE))
    }
}
