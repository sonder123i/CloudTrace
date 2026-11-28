package com.zcshou.gogogo.run.ui.glass

import android.content.Context
import com.example.liquidglass.LiquidGlassDialogBuilder
import com.example.liquidglass.LiquidGlassView
import com.zcshou.gogogo.R

/**
 * 应用内弹窗统一入口：整块面板套进 LiquidGlassView，直接折射 Activity 背景。
 *
 * 只替换面板材质，标题 / 正文 / 按钮 / 自定义 View 的用法与原 AlertDialog.Builder
 * 完全一致；因此业务代码只需要把 `AlertDialog.Builder(context)` 换成
 * `GlassDialogs.builder(context)`。
 */
object GlassDialogs {

    private const val DIALOG_CORNER_DP = 26f

    /** 阅读内芯羽化带宽：从面板边缘算起，把 8dp 折射区平滑过渡到内芯。 */
    private const val CORE_FEATHER_DP = 12f

    fun builder(context: Context): LiquidGlassDialogBuilder {
        val textColor = context.getColor(R.color.pf_text_primary)
        return LiquidGlassDialogBuilder(
            context = context,
            cornerRadiusDp = DIALOG_CORNER_DP,
            glassBlurAmount = 0.55f,
            dimBehind = true,
            glassSetup = {
                OpticalGlassTuning.apply(this, bevelDp = 18f, refractionDp = 12f)
                // 弹窗是静态阅读层：不需要按压液态与传感器高光
                enablePressEffect = false
                enableSensorHighlight = false
                enableAdaptiveTint = false
                // 弹窗面积大、文字密集；只靠 glassTint 的“有色介质”模型，
                // 深色按钮/地图会从背部透成灰底（实测取消按钮几乎看不清）。
                // 提高模糊并加一层可读性内芯，外圈仍保留折射与高光。
                blurAmount = 0.55f
                glassTint = context.getColor(R.color.dr_card_optical_tint)
                attachReadabilityCore(this, context)
            }
        ).apply {
            // 内芯已有 96% 遮罩，文字直接跟随应用主题色，避免首帧亮度判定
            // 尚未完成时先渲染成白字而看不清。
            overLightTextColor = textColor
            overDarkTextColor = textColor
        }
    }

    /**
     * 在玻璃面板内部垫一层羽化阅读内芯。
     *
     * 旧实现用 `InsetDrawable` 在 8dp 处放一块 96% 实色圆角矩形，外圈折射区与
     * 阅读区之间会有一条生硬的分界线。现在换成 [FeatheredCoreDrawable]：
     * 从面板边缘起 12dp 内 alpha 连续攀升到 96%，折射区向阅读区平滑过渡，
     * 顶部再留 1dp 镜面细高光。
     *
     * `LiquidGlassDialogBuilder` 会在 installGlass 里清掉弹窗各层底色，
     * 这里等面板真正挂到玻璃上之后再把内芯放回去；内芯只挡背景，不影响布局与文字。
     */
    private fun attachReadabilityCore(glass: LiquidGlassView, context: Context) {
        glass.post {
            val panel = glass.getChildAt(0) ?: return@post
            if (panel.background != null) return@post
            val density = context.resources.displayMetrics.density
            val radius = DIALOG_CORNER_DP * density
            panel.background = FeatheredCoreDrawable(
                context = context,
                cornerRadiiPx = floatArrayOf(radius, radius, radius, radius),
                featherPx = CORE_FEATHER_DP * density
            )
        }
    }

    /**
     * 长文本说明类弹窗：实心卡片，不用玻璃。
     *
     * [builder] 的玻璃是 55% 模糊 + 半透卡片色，盖在普通页面上很好看。
     * 但盖在**另一层玻璃**上时（比如引导底部弹窗上再弹一个说明框），
     * 底下的文字与色块会透上来，正文变成一团灰底灰字 —— 实测说明框里
     * 「正确的顺序是」下面几行几乎读不出来，面板上半截甚至是灰的。
     *
     * 这类弹窗是纯阅读层，不需要折射，需要的是可读性。所以直接退回
     * 系统 AlertDialog + 实心底板：没有折射、没有多层合成，字一定读得清。
     */
    fun readingDialog(context: Context): androidx.appcompat.app.AlertDialog.Builder =
        androidx.appcompat.app.AlertDialog.Builder(context)
}
