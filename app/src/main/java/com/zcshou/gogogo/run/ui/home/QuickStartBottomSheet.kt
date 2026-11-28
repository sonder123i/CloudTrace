package com.zcshou.gogogo.run.ui.home

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.data.repository.RunRepository
import com.zcshou.gogogo.run.engine.PaceSimulator
import com.zcshou.gogogo.run.engine.PaceStrategy
import com.zcshou.gogogo.run.ui.widget.PaceStrategySparklineView
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一键开跑参数微调弹窗：目标公里数 / 配速 / 变道 / 慢走收尾。
 */
class QuickStartBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_ROUTE_ID = "route_id"
        private const val ARG_ROUTE_NAME = "route_name"

        private const val KM_MIN = 0.5f
        private const val KM_STEP = 0.1f
        private const val KM_MAX_PROGRESS = 95

        private const val PACE_MIN = 240
        private const val PACE_STEP = 5
        private const val PACE_MAX_PROGRESS = 48

        fun newInstance(routeId: Long, routeName: String): QuickStartBottomSheet =
            QuickStartBottomSheet().apply {
                arguments = Bundle().apply {
                    putLong(ARG_ROUTE_ID, routeId)
                    putString(ARG_ROUTE_NAME, routeName)
                }
            }
    }

    private lateinit var repository: RunRepository
    private var lastTickAtMs = 0L

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.28f)
            // window 动画是 SurfaceFlinger 层的变换，view 树不会重绘，
            // 玻璃会按动画前的位置采样（折射被冻住）；进场改由下面的 view 动画负责。
            setWindowAnimations(0)
        }
        // 弹出参数微调面板时，底层主视图同步轻微缩小并压暗，营造 iOS 模态景深
        springHostScale(0.96f)
        // MaterialComponents 的 design_bottom_sheet 会自带白色 shape 背景，
        // 等布局完成后再清掉从根布局到 dialog decor 之间的所有底色，
        // 露出根布局的顶部圆角玻璃面板。
        view?.post {
            var parent: View? = view?.parent as? View
            while (parent != null && parent !== dialog?.window?.decorView) {
                parent.background = null
                parent = parent.parent as? View
            }
            dialog?.window?.decorView?.background = null
            animateSheetIn()
        }
        // 拖拽用的是 offsetTopAndBottom，同样不会重录子视图，滑动过程中逐帧重绘玻璃
        (view?.parent as? View)?.let { sheet ->
            (sheet.layoutParams as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams)
                ?.behavior
                ?.let { behavior ->
                    (behavior as? com.google.android.material.bottomsheet.BottomSheetBehavior<*>)
                        ?.addBottomSheetCallback(object :
                            com.google.android.material.bottomsheet.BottomSheetBehavior
                            .BottomSheetCallback() {
                            override fun onStateChanged(view: View, newState: Int) = Unit

                            override fun onSlide(sheet: View, slideOffset: Float) {
                                view?.invalidate()
                            }
                        })
                }
        }
    }

    /** 玻璃面板从下方滑入：动的是 view 自身，每帧重绘才能让折射跟着走。 */
    private fun animateSheetIn() {
        val glass = view ?: return
        glass.animate().cancel()
        glass.translationY = glass.height.toFloat()
        glass.animate()
            .translationY(0f)
            .setDuration(280L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.4f))
            .setUpdateListener { glass.invalidate() }
            .start()
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        springHostScale(1f)
        super.onDismiss(dialog)
    }

    private fun springHostScale(target: Float) {
        val host = activity?.findViewById<View>(R.id.homeRoot) ?: return
        host.pivotX = host.width / 2f
        host.pivotY = host.height / 2f
        listOf(SpringAnimation.SCALE_X, SpringAnimation.SCALE_Y).forEach { property ->
            SpringAnimation(host, property, target).apply {
                setSpring(
                    SpringForce(target).apply {
                        dampingRatio = 0.72f
                        stiffness = 380f
                    }
                )
                start()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_quick_start, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        (view as? com.example.liquidglass.LiquidGlassView)?.let {
            com.zcshou.gogogo.run.ui.glass.SheetGlass.attach(this, it)
        }
        repository = RunRepository(requireContext())
        val routeId = requireArguments().getLong(ARG_ROUTE_ID)
        val routeName = requireArguments().getString(ARG_ROUTE_NAME).orEmpty()

        val title = view.findViewById<TextView>(R.id.sheetTitle)
        val kmLabel = view.findViewById<TextView>(R.id.kmLabel)
        val paceLabel = view.findViewById<TextView>(R.id.paceLabel)
        val seekKm = view.findViewById<SeekBar>(R.id.seekKm)
        val seekPace = view.findViewById<SeekBar>(R.id.seekPace)
        val switchDrift = view.findViewById<Switch>(R.id.switchDrift)
        val switchReverse = view.findViewById<Switch>(R.id.switchReverse)
        val switchCooldown = view.findViewById<Switch>(R.id.switchCooldown)
        val sparkline = view.findViewById<PaceStrategySparklineView>(R.id.strategySparkline)
        val etaLabel = view.findViewById<TextView>(R.id.tvEta)

        title.text = routeName
        seekKm.max = KM_MAX_PROGRESS
        seekKm.progress = 25
        seekPace.max = PACE_MAX_PROGRESS
        seekPace.progress = 20

        fun km(): Float = KM_MIN + seekKm.progress * KM_STEP
        fun pace(): Int = PACE_MIN + seekPace.progress * PACE_STEP

        // 配速策略：匀速 / 前慢后快 / 间歇变速；切换后 ETA 与曲线立即重算
        var strategy = PaceStrategy.STEADY

        fun refreshLabels() {
            kmLabel.text = String.format(Locale.US, "目标公里数：%.1f km", km())
            val p = pace()
            paceLabel.text = String.format(Locale.US, "目标配速：%d'%02d\" /km", p / 60, p % 60)
            // 预演与真实引擎共用包络/策略系数：滑杆一拖就是一次纯计算的即时曲线
            val stats = PaceSimulator.previewProfile(km(), p, strategy)
            sparkline.setProfile(stats.points)
            etaLabel.text = formatEta(stats.totalSeconds)
        }

        val strategyViews = listOf(
            view.findViewById<TextView>(R.id.strategy0) to PaceStrategy.STEADY,
            view.findViewById<TextView>(R.id.strategy1) to PaceStrategy.NEGATIVE_SPLIT,
            view.findViewById<TextView>(R.id.strategy2) to PaceStrategy.INTERVAL
        )

        fun renderStrategy() {
            val ctx = context ?: return
            strategyViews.forEach { (chip, candidate) ->
                val on = candidate == strategy
                chip.isSelected = on
                chip.setTextColor(
                    ContextCompat.getColor(
                        ctx,
                        if (on) R.color.pf_on_primary else R.color.pf_text_secondary
                    )
                )
            }
        }
        strategyViews.forEach { (chip, candidate) ->
            chip.setOnClickListener {
                if (strategy == candidate) return@setOnClickListener
                strategy = candidate
                renderStrategy()
                tickHaptic()
                refreshLabels()
            }
        }
        renderStrategy()

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    seekKm.tag = true
                    tickHaptic()
                }
                refreshLabels()
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        }
        seekKm.setOnSeekBarChangeListener(listener)
        seekPace.setOnSeekBarChangeListener(listener)
        refreshLabels()

        // 回填该路线最近一次保存的公里数 / 配速 / 智能拟真设置，用户未拖动前自动生效
        viewLifecycleOwner.lifecycleScope.launch {
            val saved = repository.getRoute(routeId) ?: return@launch
            if (seekKm.tag == true) return@launch
            seekKm.progress = ((saved.lastTargetDistanceKm - KM_MIN) / KM_STEP)
                .toInt().coerceIn(0, KM_MAX_PROGRESS)
            seekPace.progress = ((saved.lastTargetPaceSecPerKm - PACE_MIN) / PACE_STEP)
                .coerceIn(0, PACE_MAX_PROGRESS)
            switchDrift.isChecked = saved.lastSmartJitterEnabled
            refreshLabels()
        }

        view.findViewById<TextView>(R.id.btnStartRun).setOnClickListener {
            val host = activity as? RunHost
            if (host == null) {
                com.zcshou.gogogo.run.ui.glass.GlassFeedback.toast(
                    requireContext(),
                    "宿主页面不可用"
                )
                return@setOnClickListener
            }
            val targetKm = km()
            val targetPace = pace()
            val drift = switchDrift.isChecked
            val reverse = switchReverse.isChecked
            val cooldown = switchCooldown.isChecked
            val chosenStrategy = strategy
            viewLifecycleOwner.lifecycleScope.launch {
                val route = repository.getRoute(routeId)
                if (route == null) {
                    com.zcshou.gogogo.run.ui.glass.GlassFeedback.toast(
                        requireContext(),
                        "路线已删除"
                    )
                    return@launch
                }
                host.startRun(route, targetKm, targetPace, drift, cooldown, reverse, chosenStrategy)
                dismissAllowingStateLoss()
            }
        }
    }

    /** 「预计用时 17分05秒 · 约 18:47 完成」 */
    private fun formatEta(totalSeconds: Int): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val duration = if (minutes >= 60) {
            String.format(Locale.US, "%d小时%02d分", minutes / 60, minutes % 60)
        } else {
            String.format(Locale.US, "%d分%02d秒", minutes, seconds)
        }
        val finishAt = SimpleDateFormat("HH:mm", Locale.US)
            .format(Date(System.currentTimeMillis() + totalSeconds * 1000L))
        return "预计用时 $duration · 约 $finishAt 完成"
    }

    /**
     * 滑杆拖动的微振动（CLOCK_TICK）：45ms 节流，避免一次长拖动触发上百次马达。
     * 没有马达或系统拒绝时静默降级，不影响开跑。
     */
    private fun tickHaptic() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTickAtMs < 45L) return
        lastTickAtMs = now
        runCatching {
            val ctx = context ?: return
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                    .defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(12L)
            }
        }
    }
}