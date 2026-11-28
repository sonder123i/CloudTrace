package com.zcshou.gogogo.run.ui.glass

/**
 * 玻璃管线的**纯逻辑**判定（无 Android 依赖，可 JVM 单测）。
 *
 * 之所以把这些规则单独抽出来：`DeepRunGlassManager` 是单例且注册了 `RenderGate`，
 * 在纯 JUnit 里加载会连带初始化 Android 侧对象。这里只保留"给定环境 → 哪条管线"，
 * 让「API 等级 / 省电 / 高对比度 / 冻结」的优先级可以被逐条钉住。
 */
enum class GlassPipeline {
    /** API 33+ 且允许采样：静态画布共享 AGSL 折射。 */
    OPTICAL_AGSL,

    /** API 27–32 且允许采样：QWEA0 玻璃管线（共享刷新节拍）。 */
    OPTICAL_LENS,

    /** 省电 / 高对比度 / 省电平衡档：零采样静态磨砂。 */
    BALANCED_MATTE
}

object GlassPipelineRules {

    /** AGSL 容器透镜可用的最低 API（`RuntimeShader` + `setInputShader`）。 */
    const val AGSL_MIN_SDK = 33

    /**
     * 判定当前应激活的管线。
     *
     * 优先级（高 → 低）：省电平衡档 = 系统省电 = 高对比度 > API 等级。
     * 前三者是用户/系统明确的"降画质换续航/可读性"信号，必须压过硬件能力。
     */
    fun resolve(
        sdkInt: Int,
        balancedQuality: Boolean,
        powerSave: Boolean,
        highContrast: Boolean
    ): GlassPipeline = when {
        balancedQuality || powerSave || highContrast -> GlassPipeline.BALANCED_MATTE
        sdkInt >= AGSL_MIN_SDK -> GlassPipeline.OPTICAL_AGSL
        else -> GlassPipeline.OPTICAL_LENS
    }

    /**
     * AGSL 着色器是否应真正出图。
     * 只有 AGSL 管线 + 未处于巡航/锁屏冻结时才允许逐帧采样。
     */
    fun isOpticalActive(pipeline: GlassPipeline, suppressed: Boolean): Boolean =
        !suppressed && pipeline == GlassPipeline.OPTICAL_AGSL

    /**
     * 快照位图与宿主视图的采样比（位图像素 / 视图像素）。
     * 宿主尚未布局（宽度为 0）时回退到给定倍率，避免除零。
     */
    fun samplingScale(hostSizePx: Int, bitmapSizePx: Int, fallback: Float): Float {
        if (hostSizePx <= 0 || bitmapSizePx <= 0) return fallback
        return bitmapSizePx / hostSizePx.toFloat()
    }

    /**
     * 染色不透明度收敛：过低会读不清字，过高会把玻璃糊成实底。
     * 返回 [min, max] 区间内的值；非有限值按 min 处理。
     */
    fun clampTintAlpha(raw: Float, min: Float = 0.5f, max: Float = 0.94f): Float =
        if (raw.isNaN()) min else raw.coerceIn(min, max)
}
