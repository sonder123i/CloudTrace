package com.zcshou.gogogo.run.ui.glass

import androidx.core.graphics.ColorUtils
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassView
import com.zcshou.gogogo.R

/**
 * V6.4 液态玻璃唯一调参入口。
 *
 * 目标不是"白卡片"，而是 Apple iOS 26 的材质行为：
 * - 自适应染色：亮背景压一点烟灰，暗背景提一点乳白；
 * - 更低遮蔽 + 更强模糊/饱和，让背景真正参与材质；
 * - 更厚的边缘透镜（bevel / refraction）+ 极细色散；
 * - 投影交给 View outline elevation，避免库内置 0.75α 硬阴影。
 *
 * 需要整体调风格时只改这里。
 */
object OpticalGlassTuning {

    /** 边缘色散：0.08~0.12 为克制档，0.10 是当前基准。 */
    const val DISPERSION = 0.10f

    const val BEVEL_DP = 16f
    const val REFRACTION_DP = 10f
    const val NAV_BEVEL_DP = 12f
    const val NAV_REFRACTION_DP = 8f

    /** 0.24 比库默认 0.0625 更接近 iOS 的乳白磨砂。 */
    const val BLUR_AMOUNT = 0.24f
    /** 1.72 倍饱和，接近网页玻璃的 saturate(1.8)。 */
    const val SATURATION = 172f

    // ---------------------------------------------------------------- 地图面板

    /**
     * 地图面板磨砂强度。地图有大量道路白线与水系蓝绿，模糊太重会把路网糊成一团，
     * 太轻又压不住文字背后的高对比色块；0.34 是"能看出路网形状但读得清字"的点。
     */
    private const val MAP_BLUR = 0.34f

    /** 地图本身饱和度就高，这里不再拉满，只做轻微提鲜。 */
    private const val MAP_SATURATION = 126f

    /** 夜间地图底色偏深，染色稍重一点点维持对比度。 */
    private const val MAP_TINT_ALPHA_DAY = 0.60f
    private const val MAP_TINT_ALPHA_NIGHT = 0.66f

    fun apply(
        view: LiquidGlassView,
        bevelDp: Float = BEVEL_DP,
        refractionDp: Float = REFRACTION_DP
    ) {
        val density = view.resources.displayMetrics.density
        view.material = GlassMaterial.REGULAR
        view.bevelWidth = bevelDp * density
        view.refractionHeight = refractionDp * density
        view.refractionFalloff = 2f
        view.dispersionStrength = DISPERSION
        view.enableSensorHighlight = true
        view.enableAdaptiveTint = isNight(view)
        view.blurAmount = BLUR_AMOUNT
        // API 36 平台色彩管线可用时，多给的饱和度会被平台再现得更准；
        // 不可用的机器维持原值，避免过饱和
        view.saturation = if (GlassApi36Support.probe()) SATURATION + 6f else SATURATION
        view.enableShadow = false
        view.enablePressEffect = true
    }

    /**
     * 地图页面板专用配方。
     *
     * ## V6.4 彻底移除「90% 中性实底」
     *
     * 旧实现为压住 `TextureMapView` 捕获回读的蓝色偏移，把子 View 背景设成
     * `0xE6F5F7FA` / `0xE61C1C1E`（90% 实底）。蓝是压住了，玻璃也变成了一块贴纸：
     * 边缘折射、地图路网、水系颜色全被盖死。
     *
     * 现在偏色改在**采样环节**校正（见 [MapBackdropCapture] 的去蓝矩阵），
     * 玻璃只需要一个 58%~66% 的冷雾染色 + 共享低分辨率捕获即可兼顾通透与可读性。
     * 调用方必须先 `MapBackdropCapture.bind(view, mapView)`。
     */
    fun applyMapPanel(view: LiquidGlassView) {
        apply(view, bevelDp = 14f, refractionDp = 8f)
        view.blurAmount = MAP_BLUR
        view.saturation = MAP_SATURATION
        view.enableAdaptiveTint = false
        MapBackdropCapture.applyBlurProfile(view)

        val night = isNight(view)
        val alpha = if (night) MAP_TINT_ALPHA_NIGHT else MAP_TINT_ALPHA_DAY
        val base = view.context.getColor(R.color.dr_editor_optical_tint)
        view.glassTint = ColorUtils.setAlphaComponent(base, (alpha * 255f).toInt())
    }

    private fun isNight(view: LiquidGlassView): Boolean {
        val mode = view.context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
}
