package com.zcshou.gogogo.run.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 玻璃管线优先级与采样比换算。
 *
 * 这些规则决定"哪台机器走哪条渲染路径"，一旦判错就会出现两类事故：
 * 低端机被拉进 AGSL 管线掉帧，或高端机因为忘了解除冻结而永远停在静态磨砂。
 */
class GlassPipelineRulesTest {

    @Test
    fun `API 33 及以上在光学档走 AGSL 静态画布折射`() {
        assertEquals(
            GlassPipeline.OPTICAL_AGSL,
            GlassPipelineRules.resolve(
                sdkInt = 33,
                balancedQuality = false,
                powerSave = false,
                highContrast = false
            )
        )
        assertEquals(
            GlassPipeline.OPTICAL_AGSL,
            GlassPipelineRules.resolve(36, false, false, false)
        )
    }

    @Test
    fun `API 27 到 32 退回 QWEA0 玻璃管线`() {
        assertEquals(
            GlassPipeline.OPTICAL_LENS,
            GlassPipelineRules.resolve(27, false, false, false)
        )
        assertEquals(
            GlassPipeline.OPTICAL_LENS,
            GlassPipelineRules.resolve(32, false, false, false)
        )
    }

    @Test
    fun `省电平衡档压过任何硬件能力`() {
        assertEquals(
            GlassPipeline.BALANCED_MATTE,
            GlassPipelineRules.resolve(36, balancedQuality = true, powerSave = false, highContrast = false)
        )
    }

    @Test
    fun `系统省电与高对比度同样压到静态磨砂`() {
        assertEquals(
            GlassPipeline.BALANCED_MATTE,
            GlassPipelineRules.resolve(36, false, powerSave = true, highContrast = false)
        )
        assertEquals(
            GlassPipeline.BALANCED_MATTE,
            GlassPipelineRules.resolve(36, false, powerSave = false, highContrast = true)
        )
    }

    @Test
    fun `巡航或锁屏冻结时任何管线都不许出图`() {
        assertFalse(GlassPipelineRules.isOpticalActive(GlassPipeline.OPTICAL_AGSL, suppressed = true))
        assertFalse(GlassPipelineRules.isOpticalActive(GlassPipeline.OPTICAL_LENS, suppressed = true))
        assertFalse(GlassPipelineRules.isOpticalActive(GlassPipeline.BALANCED_MATTE, suppressed = false))
        assertTrue(GlassPipelineRules.isOpticalActive(GlassPipeline.OPTICAL_AGSL, suppressed = false))
    }

    @Test
    fun `采样比按位图与视图尺寸换算`() {
        assertEquals(0.5f, GlassPipelineRules.samplingScale(1080, 540, 0.5f), 0.0001f)
        assertEquals(0.25f, GlassPipelineRules.samplingScale(1200, 300, 0.25f), 0.0001f)
    }

    @Test
    fun `宿主未布局时回退到给定倍率而不是除零`() {
        assertEquals(0.5f, GlassPipelineRules.samplingScale(0, 540, 0.5f), 0f)
        assertEquals(0.25f, GlassPipelineRules.samplingScale(1080, 0, 0.25f), 0f)
        assertEquals(0.25f, GlassPipelineRules.samplingScale(-1, -1, 0.25f), 0f)
    }

    @Test
    fun `染色不透明度收敛在可读区间`() {
        assertEquals(0.5f, GlassPipelineRules.clampTintAlpha(0.1f), 0f)
        assertEquals(0.94f, GlassPipelineRules.clampTintAlpha(1f), 0f)
        assertEquals(0.8f, GlassPipelineRules.clampTintAlpha(0.8f), 0.0001f)
        assertEquals(0.5f, GlassPipelineRules.clampTintAlpha(Float.NaN), 0f)
    }
}
