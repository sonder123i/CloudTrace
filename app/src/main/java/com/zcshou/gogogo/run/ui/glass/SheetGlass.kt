package com.zcshou.gogogo.run.ui.glass

import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.DialogFragment
import com.example.liquidglass.LiquidGlassView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.zcshou.gogogo.R

/**
 * BottomSheet 玻璃接入助手。
 *
 * BottomSheet 是独立 window，和 Activity 内使用玻璃有三处差异（见上游 FAQ）：
 * 1. 玻璃在弹层里的直接父容器是透明的，必须把 [LiquidGlassView.backdropSource]
 *    显式指到 Activity 的内容层，重叠区按屏幕坐标算，跨 window 依然成立；
 * 2. window 动画是 SurfaceFlinger 层的变换，view 树不重绘，折射会冻在动画前那一帧，
 *    所以关掉 window 动画、改由 view 动画驱动并逐帧 invalidate；
 * 3. MaterialComponents 的 design_bottom_sheet 自带白色 shape 底，要逐层清掉。
 */
object SheetGlass {

    private const val SHEET_RADIUS_DP = 28f

    /** 阅读内芯羽化带宽。 */
    private const val CORE_FEATHER_DP = 12f

    /** 顶部把手栏透光区高度。 */
    private const val HANDLE_CLEARANCE_DP = 24f

    /**
     * 把 [glass] 接到当前 Activity 的背景上，并清掉弹层各级底色。
     * 必须在 onStart / onViewCreated 之后调用（此时 window 已就绪）。
     */
    fun attach(fragment: BottomSheetDialogFragment, glass: LiquidGlassView) {
        val activity = fragment.activity ?: return
        val content = activity.findViewById<View>(android.R.id.content) ?: return
        glass.backdropSource = content
        // 弹层顶部两角收圆、底部贴屏幕边缘
        val radius = SHEET_RADIUS_DP * glass.resources.displayMetrics.density
        glass.setCornerRadii(radius, radius, 0f, 0f)
        OpticalGlassTuning.apply(glass, bevelDp = 14f, refractionDp = 8f)
        glass.enableAdaptiveTint = false
        glass.enablePressEffect = false
        glass.enableShadow = false
        glass.glassTint = activity.getColor(R.color.dr_card_optical_tint)

        clearSheetBackgrounds(fragment, glass)
        attachReadabilityCore(glass)
        watchSheetSlides(fragment, glass)
    }

    /**
     * BottomSheet 同样要承载密集文字和按钮。只靠 glassTint 时，首页的黑色
     * 主按钮会从弹层里透出一块深色胶囊（实测「一键前往授权」后面发黑）。
     *
     * 这里用羽化内芯替代旧的 8dp 实底块：外圈折射区到阅读区是 12dp 连续过渡，
     * 并把顶部 [HANDLE_CLEARANCE_DP] 让给把手栏 —— 那一段直接透出底下页面，
     * 手指抓住把手时能看见下滑的列表在动，弹层才像"浮"着而不是"贴"住。
     */
    private fun attachReadabilityCore(glass: LiquidGlassView) {
        glass.post {
            val sheetContent = glass.getChildAt(0) ?: return@post
            if (sheetContent.background != null) return@post
            val density = glass.resources.displayMetrics.density
            val corner = SHEET_RADIUS_DP * density
            sheetContent.background = FeatheredCoreDrawable(
                context = glass.context,
                // 顶部两角跟随玻璃圆角，底部贴屏幕边缘保持直角（TL/TR/BR/BL）
                cornerRadiiPx = floatArrayOf(corner, corner, 0f, 0f),
                featherPx = CORE_FEATHER_DP * density,
                topClearancePx = HANDLE_CLEARANCE_DP * density
            )
        }
    }

    /** design_bottom_sheet / container / coordinator 的不透明底会挡住折射，逐层清掉。 */
    private fun clearSheetBackgrounds(fragment: DialogFragment, glass: LiquidGlassView) {
        val decor = fragment.dialog?.window?.decorView
        var parent: View? = glass.parent as? View
        while (parent != null && parent !== decor) {
            parent.background = null
            parent = parent.parent as? View
        }
        decor?.background = null
    }

    /**
     * BottomSheetBehavior 拖拽走 offsetTopAndBottom，不会重录子视图，
     * 必须在 onSlide 里逐帧 invalidate，否则折射停在起手那一帧。
     */
    private fun watchSheetSlides(fragment: DialogFragment, glass: LiquidGlassView) {
        val sheet = glass.parent as? View ?: return
        val lp = sheet.layoutParams as? CoordinatorLayout.LayoutParams ?: return
        val behavior = lp.behavior as? BottomSheetBehavior<*> ?: return
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) = Unit

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                glass.invalidate()
            }
        })
        // 弹层高度可能在首帧后才确定，尺寸变化后重拍一次
        glass.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> glass.invalidate() }
    }
}
