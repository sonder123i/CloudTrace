package com.zcshou.gogogo.run.ui.overlay

import android.animation.ObjectAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.Keep
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.data.prefs.DisplayPrefs
import com.zcshou.gogogo.run.data.prefs.RoamSpeeds
import com.zcshou.gogogo.run.engine.RunPhase
import com.zcshou.gogogo.run.engine.FreeRoamController
import com.zcshou.gogogo.run.engine.RunRuntimeState
import com.zcshou.gogogo.run.ui.glass.OverlayGlassStyle
import com.zcshou.gogogo.run.ui.home.RunHost
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 统一悬浮窗：收起态巡航胶囊；展开态自动跑圈控制岛 / 紧凑战术摇杆盘。
 * 窗口拖动仅绑定在药丸与顶部把手栏，摇杆圆盘独占触摸，互不抢事件。
 */
class FloatingCapsuleView @JvmOverloads constructor(
    context: Context,
    private val host: RunHost? = null,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    companion object {
        private const val IDLE_DIM_DELAY_MS = 3_000L
        private const val DIM_ALPHA = 0.45f
        private const val FULL_ALPHA = 1.0f
        private const val GHOST_HIDE_MS = 5_000L
    }

    private val capsuleShell: CapsuleShellView
    private val capsuleBar: LinearLayout
    private val ring: RingProgressView
    private val capsuleText: TextView
    private val actions: CapsuleShellView
    private val paceText: TextView
    private val lapText: TextView
    private val pauseButton: TextView
    private val finishButton: TextView
    private val hubStatus: TextView
    private val hubDot: View
    private val hubHeader: LinearLayout
    private val roamPanel: CapsuleShellView
    private val hubDragBar: TextView
    private val joystick: TacticalJoystickView
    private val roamLockButton: ImageView
    private val telemetryText: TextView
    private val stepDot: View
    private var stepPulseWarn = false
    private var lastTelemetryText: String? = null

    /** 拖拽回调：(位移 dx, 位移 dy, 是否松手) */
    var dragHandler: ((Int, Int, Boolean) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var inRoamMode = false
    private var gearSpeed = 3.6f
    private var selectedGear = RoamSpeeds.Gear.RUN
    private var expanded = false
    private var pulseAnimator: ObjectAnimator? = null
    private var idleRunnable: Runnable? = null
    private var ghostRunnable: Runnable? = null
    /** 手动接管收尾回调：detach 后不得再触发 host（与 idle/ghost 同一套保存-清理模式） */
    private var takeoverRunnable: Runnable? = null
    /** 展开高度过渡动画：重复展开/收起时先取消旧动画，detach 时统一取消 */
    private var morphAnimator: android.animation.ValueAnimator? = null
    private var stepsWarningState = false
    private var blurFallbackOn = false

    /** 运行中微调用的本地镜像，避免每个 tick 被旧状态覆盖造成“点了没反应” */
    private var lastTargetPace = 0
    private var lastTargetKm = 0f
    private var lastAccumulatedKm = 0f
    private var lastAdjustAtMs = 0L

    // ---------------------------------------------------------------- 边缘流光

    /**
     * 胶囊边缘流光。直接挂在外壳上而不是单独的 View：
     * 独立 View 在 FrameLayout 里 wrap_content + AT_MOST 会拿到父容器可用宽度，
     * 把外壳撑成整屏宽（胶囊凭空变大的根因）。这里在外壳的 dispatchDraw 里画，
     * 天然贴合胶囊矩形，也不参与任何测量。
     */
    private val capsuleBeamDrawable by lazy {
        BorderBeamDrawable(
            context,
            cornerRadiusPx = 20f * resources.displayMetrics.density,
            borderWidthPx = 1.6f * resources.displayMetrics.density
        )
    }
    private var lastPausedLook: Boolean? = null

    /** 彗尾绕行一周耗时（ms）。太慢看不出流动，太快像故障灯。 */
    private var beamLapMs = 3200L
    /**
     * 色散环自转一周耗时（ms）。必须和绕行周期不同，否则整圈色带会被彗尾「绑着一起转」，
     * 少了薄膜在底下缓慢流动的层次感。取 7.9s：比绕行慢一倍多，两者长时间不同步。
     */
    private var ringLapMs = 7900L
    /**
     * 折叠态最小帧间隔：0 = 跟随屏幕刷新率满帧。
     *
     * 早期为了省电先后设成 50ms（20fps）与 33ms（30fps），实测都能肉眼看出卡顿——
     * 彗尾是连续位移，低帧率下的跳步非常显眼。这套绘制的实际成本是每帧 26 次
     * drawBitmap，远低于一次普通布局，没必要为它牺牲流畅度。省电需求由
     * 「省电平衡」档整体关掉光束来承担，而不是在这里降帧。
     */
    private var beamMinFrameMs = 0L
    private var beamRunning = false
    private var beamPaused = false
    private var beamScheduled = false
    private var beamStartAtMs = 0L
    private var beamLastFrameMs = 0L
    private var ghostHidden = false
    private val balancedRender = DisplayPrefs.quality(context) == DisplayPrefs.RenderQuality.BALANCED

    /**
     * 计步脉搏点是否可用：开关已开启、有传感器且已授予活动识别权限
     * （与 StepGuard 启动条件一致；开关关闭时 StepGuard 不创建，
     * 这里也不能画那个永远告警的脉冲点）。
     */
    private val stepSensorAvailable: Boolean by lazy {
        val enabled =
            com.zcshou.gogogo.run.data.prefs.AdvancedPrefs.isStepGuardEnabled(context)
        val hasSensor = (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
            ?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        enabled && hasSensor && granted
    }

    /**
     * 呼吸：三条互质周期的正弦叠加（3.1s / 4.7s / 2.3s）。
     *
     * 单条正弦一眼就能看出「一呼一吸」的机械规律；三条周期互质且都不等于绕行周期
     * （3.2s）时，合成波要过很久才重复一次，观感上就是「自然起伏」。
     * 权重 0.5 / 0.3 / 0.2 让主周期主导、另两条只做扰动。
     */
    private fun breathAt(elapsedMs: Long): Float {
        val t = elapsedMs / 1000.0
        val a = kotlin.math.sin(t * 2.0 * Math.PI / 3.1)
        val b = kotlin.math.sin(t * 2.0 * Math.PI / 4.7 + 1.7)
        val c = kotlin.math.sin(t * 2.0 * Math.PI / 2.3 + 3.4)
        val mixed = 0.5 * a + 0.3 * b + 0.2 * c
        // mixed ∈ [-1, 1] → [0, 1]
        return ((mixed + 1.0) / 2.0).toFloat().coerceIn(0f, 1f)
    }

    private val beamFrameCallback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            beamScheduled = false
            if (!beamRunning || beamPaused) return
            val now = frameTimeNanos / 1_000_000L
            if (beamStartAtMs == 0L) beamStartAtMs = now
            val due = beamLastFrameMs == 0L || now - beamLastFrameMs >= beamMinFrameMs
            if (due) {
                beamLastFrameMs = now
                val elapsed = now - beamStartAtMs
                val phase = (elapsed % beamLapMs) / beamLapMs.toFloat()
                val ringPhase = (elapsed % ringLapMs) / ringLapMs.toFloat()
                val breath = breathAt(elapsed)
                // 同屏三块面板共用同一相位与呼吸，避免逐帧分配 List
                updateBeamPanel(capsuleShell, phase, ringPhase, breath)
                updateBeamPanel(actions, phase, ringPhase, breath)
                updateBeamPanel(roamPanel, phase, ringPhase, breath)
                // 计步告警才让脉搏点闪烁：正常时恒亮，省电冻结时随回调一起停
                if (stepPulseWarn && stepDot.visibility == VISIBLE) {
                    stepDot.alpha = 0.3f + 0.7f * breath
                }
            }
            scheduleBeamFrame()
        }
    }

    private fun updateBeamPanel(panel: CapsuleShellView, phase: Float, ringPhase: Float, breath: Float) {
        if (panel.visibility != VISIBLE) return
        panel.beam?.let { beam ->
            beam.phase = phase
            beam.ringPhase = ringPhase
            beam.breath = breath
        }
        panel.invalidate()
    }

    private fun scheduleBeamFrame() {
        if (balancedRender || ghostHidden || beamScheduled || !beamRunning || beamPaused) return
        beamScheduled = true
        android.view.Choreographer.getInstance().postFrameCallback(beamFrameCallback)
    }

    private fun startBeam() {
        if (balancedRender || beamRunning) return
        beamRunning = true
        beamStartAtMs = 0L
        beamLastFrameMs = 0L
        scheduleBeamFrame()
    }

    private fun stopBeam() {
        beamRunning = false
        beamScheduled = false
        android.view.Choreographer.getInstance().removeFrameCallback(beamFrameCallback)
    }

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        LayoutInflater.from(context).inflate(R.layout.floating_capsule, this, true)
        capsuleShell = findViewById(R.id.capsuleShell)
        capsuleShell.beam = capsuleBeamDrawable
        capsuleBar = findViewById(R.id.capsuleBar)
        ring = findViewById(R.id.capsuleRing)
        capsuleText = findViewById(R.id.capsuleText)
        actions = findViewById(R.id.capsuleActions)
        paceText = findViewById(R.id.capsulePace)
        lapText = findViewById(R.id.capsuleLap)
        pauseButton = findViewById(R.id.btnCapsulePause)
        finishButton = findViewById(R.id.btnCapsuleFinish)
        hubStatus = findViewById(R.id.hubStatus)
        hubDot = findViewById(R.id.hubDot)
        hubHeader = findViewById(R.id.hubHeader)
        roamPanel = findViewById(R.id.capsuleRoamPanel)
        hubDragBar = findViewById(R.id.hubDragBar)
        joystick = findViewById(R.id.hubJoystick)
        roamLockButton = findViewById(R.id.btnRoamLock)
        telemetryText = findViewById(R.id.capsuleTelemetryText)
        stepDot = findViewById(R.id.capsuleStepDot)

        attachWindowDrag(capsuleBar)
        attachWindowDrag(hubHeader)
        attachWindowDrag(hubDragBar)

        capsuleBar.setOnClickListener {
            capsuleShell.beam?.triggerBurst()
            if (inRoamMode) {
                if (roamPanel.visibility == VISIBLE) collapseRoam() else expandRoam()
            } else {
                toggleExpanded()
            }
        }
        capsuleBar.setOnLongClickListener {
            ghostHide()
            true
        }
        val hubCollapseBtn = findViewById<View>(R.id.btnHubCollapse)
        val roamCollapseBtn = findViewById<View>(R.id.btnRoamCollapse)
        val paceMinusBtn = findViewById<View>(R.id.btnPaceMinus)
        val pacePlusBtn = findViewById<View>(R.id.btnPacePlus)
        val distMinusBtn = findViewById<View>(R.id.btnDistMinus)
        val distPlusBtn = findViewById<View>(R.id.btnDistPlus)
        val detourBtn = findViewById<TextView>(R.id.btnCapsuleDetour)
        val manualBtn = findViewById<TextView>(R.id.btnCapsuleManual)
        val returnBtn = findViewById<TextView>(R.id.btnCapsuleReturn)
        val stopSimBtn = findViewById<TextView>(R.id.btnHubStopSim)
        val stopRoamBtn = findViewById<TextView>(R.id.btnHubStopSimRoam)

        hubCollapseBtn.setOnClickListener { collapseActions() }
        roamCollapseBtn.setOnClickListener { collapseRoam() }
        roamLockButton.setOnClickListener {
            roamPanel.beam?.triggerBurst()
            joystick.toggleLockFromButton()
            updateLockChip()
        }
        pauseButton.setOnClickListener {
            actions.beam?.triggerBurst()
            host?.pauseResume()
            scheduleIdleDim()
        }
        paceMinusBtn.setOnClickListener { nudgePace(-10) }
        pacePlusBtn.setOnClickListener { nudgePace(10) }
        distMinusBtn.setOnClickListener { nudgeTargetKm(-0.5f) }
        distPlusBtn.setOnClickListener { nudgeTargetKm(0.5f) }
        detourBtn.setOnClickListener {
            actions.beam?.triggerBurst()
            host?.pickDetour()
            scheduleIdleDim()
        }
        finishButton.setOnClickListener {
            actions.beam?.triggerBurst()
            host?.finishEarly()
            scheduleIdleDim()
        }
        manualBtn.setOnClickListener {
            beginManualTakeover()
        }
        returnBtn.setOnClickListener {
            endManualTakeover()
        }
        stopSimBtn.setOnClickListener { confirmStopSimulation() }
        stopRoamBtn.setOnClickListener { confirmStopSimulation() }
        OverlayGlassStyle.attachTactilePress(
            hubCollapseBtn, roamCollapseBtn, roamLockButton,
            pauseButton, paceMinusBtn, pacePlusBtn, distMinusBtn, distPlusBtn,
            detourBtn, finishButton, manualBtn, returnBtn, stopSimBtn, stopRoamBtn
        )
        setupHubGear()
        gearSpeed = RoamSpeeds.speed(context, selectedGear)
        joystick.listener = object : TacticalJoystickView.Listener {
            override fun onSteer(bearingDeg: Float, strength: Float) {
                if (strength <= 0.02f) {
                    FreeRoamController.stopMotion()
                    return
                }
                FreeRoamController.drive(
                    bearingDeg.toDouble(),
                    (gearSpeed * strength).toDouble()
                )
            }

            override fun onLockChanged(locked: Boolean) {
                updateLockChip()
                scheduleIdleDim()
            }
        }

        updateLockChip()
        applyPanelBackgrounds()

        startPulse()
        scheduleIdleDim()
    }

    /**
     * 展开态控制岛 / 手动接管摇杆盘也换成棱镜玻璃。
     * 与胶囊同一套材质，只是圆角更大（24dp）——悬浮窗里没有下层像素可折射，
     * 三块面板必须共用同一套「薄膜 + 高光 + 双层微光细边」才读得出是同一块玻璃。
     */
    private fun applyPanelBackgrounds() {
        val density = resources.displayMetrics.density
        listOf(actions, roamPanel).forEach { panel ->
            PrismGlassDrawable.attach(panel, 24f)
            panel.beam = BorderBeamDrawable(
                context,
                cornerRadiusPx = 24f * density,
                borderWidthPx = 1.6f * density
            )
        }
    }

    /** 窗口拖动只绑定在这些把手上，摇杆区域不受影响 */
    private fun attachWindowDrag(handle: View) {
        handle.isClickable = true
        handle.setOnTouchListener(object : OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        moved = false
                        animate().cancel()
                        alpha = FULL_ALPHA
                        scheduleIdleDim()
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (!moved && hypot(dx, dy) > touchSlop) {
                            moved = true
                            v.cancelLongPress()
                        }
                        if (moved) {
                            dragHandler?.invoke(dx.toInt(), dy.toInt(), false)
                            downX = event.rawX
                            downY = event.rawY
                            return true
                        }
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (moved) {
                            moved = false
                            dragHandler?.invoke(0, 0, true)
                            return true
                        }
                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                            // 直接分发点击并取消长按，避免摇杆/胶囊被误判为长按隐身
                            v.cancelLongPress()
                            v.isPressed = false
                            v.performClick()
                            return true
                        }
                    }
                }
                return false
            }
        })
    }

    private fun setupHubGear() {
        data class GearChip(val view: TextView, val gear: RoamSpeeds.Gear)
        val chips = listOf(
            GearChip(findViewById(R.id.hubGearWalk), RoamSpeeds.Gear.WALK),
            GearChip(findViewById(R.id.hubGearJog), RoamSpeeds.Gear.RUN),
            GearChip(findViewById(R.id.hubGearBike), RoamSpeeds.Gear.BIKE),
            GearChip(findViewById(R.id.hubGearCar), RoamSpeeds.Gear.CAR)
        )
        fun paint() {
            chips.forEach { chip ->
                val on = chip.gear == selectedGear
                chip.view.setTextColor(
                    context.getColor(if (on) R.color.dr_overlay_ice else R.color.dr_overlay_text_secondary)
                )
                chip.view.setTypeface(
                    null,
                    if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
                )
                chip.view.setBackgroundResource(
                    if (on) R.drawable.bg_overlay_chip_selected else 0
                )
            }
        }
        chips.forEach { chip ->
            OverlayGlassStyle.attachTactilePress(chip.view)
            chip.view.setOnClickListener {
                selectedGear = chip.gear
                gearSpeed = RoamSpeeds.speed(context, chip.gear)
                roamPanel.beam?.triggerBurst()
                if (inRoamMode) capsuleText.text = "手动接管 · ${chip.gear.label}"
                paint()
            }
            chip.view.setOnLongClickListener {
                editGearSpeed(chip.gear) {
                    if (selectedGear == chip.gear) gearSpeed = it
                }
                true
            }
        }
        paint()
    }

    private fun editGearSpeed(gear: RoamSpeeds.Gear, onSaved: (Float) -> Unit) {
        FreeRoamController.stopMotion()
        joystick.unlock()
        host?.setOverlayHidden(true)
        val current = RoamSpeeds.speed(context, gear)
        val input = EditText(context).apply {
            com.zcshou.gogogo.run.ui.widget.DialogStyler.styleInput(this)
            setText(current.toString())
            setSelection(text.length)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val container = FrameLayout(context).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(context)
            .setTitle("${gear.label}速度（m/s）")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val value = input.text.toString().toFloatOrNull() ?: return@setPositiveButton
                RoamSpeeds.setSpeed(context, gear, value)
                onSaved(value.coerceIn(0.2f, 40f))
            }
            .setNegativeButton("取消", null)
            .setOnDismissListener { host?.setOverlayHidden(false) }
            .showOverlaid()
    }

    /**
     * 悬浮窗模式下（本视图为 TYPE_APPLICATION_OVERLAY 窗口）对话框必须提升窗口层级，
     * 否则会被 overlay 层压住不可见/不可交互；在页面内嵌模式下保持普通窗口。
     * 同款处理见 RoamHubView。
     */
    private fun AlertDialog.Builder.showOverlaid() {
        val dialog = create()
        val lp = layoutParams as? WindowManager.LayoutParams
        if (lp?.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        }
        dialog.show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun updateLockChip() {
        roamLockButton.setImageResource(
            if (joystick.isLocked) R.drawable.ic_lock_line else R.drawable.ic_lock_open_line
        )
        val color = context.getColor(
            if (joystick.isLocked) R.color.dr_overlay_ice else R.color.dr_overlay_text_secondary
        )
        roamLockButton.imageTintList = ColorStateList.valueOf(color)
    }

    /** V6.6：跨窗模糊被 ROM 禁用时切换实体兜底，杜绝透字。 */
    fun setCrossWindowBlurEnabled(enabled: Boolean) {
        val fallback = !enabled
        if (blurFallbackOn == fallback) return
        blurFallbackOn = fallback
        applyCapsuleBackground()
    }

    /** 上一次绘制的胶囊外观，避免每帧 update() 重建 Drawable。 */
    private var capsuleLook: Pair<Boolean, Boolean>? = null

    private fun applyCapsuleBackground() {
        // 悬浮窗拿不到下层 App 的像素（FLAG_BLUR_BEHIND 会生成全屏 Dim Layer 吞掉窗外触摸），
        // 玻璃厚度只能靠材质本身：双层微光描边 + 顶部镜面高光 + 底部微阴影。
        // 跨窗模糊可用时用半透版本，不可用时换成更实的兜底，避免文字压在壁纸上。
        val look = stepsWarningState to blurFallbackOn
        if (capsuleLook == look) return
        capsuleLook = look
        val radiusPx = 20f * resources.displayMetrics.density
        capsuleShell.setOutlineCornerRadius(radiusPx)
        capsuleShell.background = PrismGlassDrawable(
            context,
            radiusPx,
            warn = stepsWarningState
        ).also { it.alpha = if (blurFallbackOn) 255 else 240 }
    }

    /**
     * 展开态遥测行：运动线索 · 海拔 · 精度 · 计步脉搏点。
     *
     * 计步传感器缺失或未授予 ACTIVITY_RECOGNITION 时只显示海拔与线索 ——
     * 这种情况下 StepGuard 本身就不会启动，画一个永远告警的脉冲点纯属误导。
     */
    private fun updateTelemetry(state: RunRuntimeState) {
        val cue = state.motionCue
        val altitude = state.altitudeMeters.roundToInt()
        val accuracy = state.accuracyMeters.roundToInt()
        val text = buildString(24) {
            if (!cue.isNullOrEmpty()) {
                append(cue).append(" · ")
            }
            append("海拔 ").append(altitude).append("m · ±").append(accuracy).append("m")
        }
        if (text != lastTelemetryText) {
            lastTelemetryText = text
            telemetryText.text = text
        }
        if (!stepSensorAvailable) {
            stepDot.visibility = View.GONE
            stepPulseWarn = false
            return
        }
        stepDot.visibility = View.VISIBLE
        if (stepPulseWarn != state.stepsWarning) {
            stepPulseWarn = state.stepsWarning
            stepDot.backgroundTintList = ColorStateList.valueOf(
                context.getColor(
                    if (state.stepsWarning) R.color.dr_overlay_danger else R.color.dr_overlay_ok
                )
            )
            if (!state.stepsWarning) stepDot.alpha = 1f
        }
    }

    fun update(state: RunRuntimeState) {
        val pace = if (state.paceSecPerKm > 0) {
            String.format(Locale.US, "%d'%02d\"", state.paceSecPerKm / 60, state.paceSecPerKm % 60)
        } else {
            "--"
        }
        updateTelemetry(state)
        if (!inRoamMode) {
            stepsWarningState = state.stepsWarning
            applyCapsuleBackground()
            if (state.stepsWarning) {
                capsuleText.text = "请放入口袋"
                capsuleText.setTextColor(context.getColor(R.color.dr_overlay_ice))
                hubStatus.text = "未检测到步数，请放入口袋"
                hubDot.backgroundTintList = ColorStateList.valueOf(
                    context.getColor(R.color.dr_overlay_ice)
                )
            } else {
                capsuleText.setTextColor(context.getColor(R.color.dr_overlay_text_primary))
                capsuleText.text = if (state.phase == RunPhase.PRE_START_SETTLE) {
                    String.format(Locale.US, "预热 %ds", state.settleRemainSec)
                } else {
                    String.format(Locale.US, "%.2f/%.2fkm · %s", state.accumulatedKm, state.targetKm, pace)
                }
                hubStatus.text = when (state.phase) {
                    RunPhase.PRE_START_SETTLE -> "预热中 · 请切到跑步 App"
                    RunPhase.RUNNING -> "模拟运行中 · 已接管"
                    RunPhase.PAUSED -> "已暂停 · 虚拟定位保持"
                    RunPhase.DETOUR_POINT -> "绕路打卡中"
                    RunPhase.COOLDOWN_WALK -> "慢走收尾中"
                    else -> "模拟运行中"
                }
                hubDot.backgroundTintList = ColorStateList.valueOf(
                    context.getColor(if (state.phase == RunPhase.PAUSED) R.color.dr_overlay_ice else R.color.dr_overlay_ok)
                )
            }
        }
        ring.setProgress(if (state.targetMeters > 0) {
            (state.accumulatedMeters / state.targetMeters).toFloat()
        } else {
            0f
        })
        // 悬浮窗步进器显示“目标值”，折叠态仍显示实时配速
        val now = SystemClock.elapsedRealtime()
        if (now - lastAdjustAtMs > 800L) {
            lastTargetPace = state.targetPaceSecPerKm.takeIf { it > 0 } ?: state.paceSecPerKm
            lastTargetKm = state.targetKm.toFloat()
        }
        lastAccumulatedKm = state.accumulatedKm.toFloat()
        paceText.text = formatPace(lastTargetPace)
        lapText.text = String.format(Locale.US, "%.1fkm", lastTargetKm)
        val isPaused = state.phase == RunPhase.PAUSED
        pauseButton.text = if (isPaused) "继续" else "暂停"
        if (lastPausedLook != isPaused) {
            lastPausedLook = isPaused
            pauseButton.setBackgroundResource(
                if (isPaused) R.drawable.bg_overlay_pill_tonal else R.drawable.bg_overlay_recess
            )
            pauseButton.setTextColor(
                context.getColor(if (isPaused) R.color.dr_overlay_ice else R.color.dr_overlay_text_primary)
            )
        }
        finishButton.isEnabled = state.phase == RunPhase.RUNNING || state.phase == RunPhase.DETOUR_POINT
    }

    /** 配速步进：±10s/km，限制在 3'00" ~ 15'00"。 */
    private fun nudgePace(deltaSec: Int) {
        if (lastTargetPace <= 0) lastTargetPace = 340
        val next = (lastTargetPace + deltaSec).coerceIn(180, 900)
        if (next == lastTargetPace) return
        lastTargetPace = next
        lastAdjustAtMs = SystemClock.elapsedRealtime()
        paceText.text = formatPace(next)
        actions.beam?.triggerBurst()
        host?.adjustPace(next)
        scheduleIdleDim()
    }

    /** 目标距离步进：±0.5km，且不能低于已跑里程 + 0.1km。 */
    private fun nudgeTargetKm(deltaKm: Float) {
        val base = if (lastTargetKm > 0f) lastTargetKm else 3.0f
        val minKm = (lastAccumulatedKm + 0.1f).coerceAtLeast(0.5f)
        val next = (base + deltaKm).coerceIn(minKm, 100f)
        if (kotlin.math.abs(next - lastTargetKm) < 0.01f) return
        lastTargetKm = next
        lastAdjustAtMs = SystemClock.elapsedRealtime()
        lapText.text = String.format(Locale.US, "%.1fkm", next)
        actions.beam?.triggerBurst()
        host?.adjustTargetKm(next)
        scheduleIdleDim()
    }

    private fun formatPace(secPerKm: Int): String =
        if (secPerKm <= 0) "--"
        else String.format(Locale.US, "%d'%02d\"", secPerKm / 60, secPerKm % 60)

    // ---------------- 展开 / 收起 ----------------

    private fun toggleExpanded() {
        if (actions.visibility == VISIBLE) collapseActions() else expandActions()
    }

    private fun expandActions() {
        expanded = true
        idleRunnable?.let { removeCallbacks(it) }
        animate().cancel()
        alpha = FULL_ALPHA
        // 展开控制岛时彻底隐藏迷你药丸，杜绝左上角重叠
        hideMiniPill()
        actions.visibility = VISIBLE
        actions.alpha = 0f
        actions.scaleX = 0.94f
        actions.scaleY = 0.94f
        actions.beam?.triggerBurst()
        actions.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()
        morphHeight(actions, expand = true)
        post { host?.relayoutOverlay() }
        scheduleIdleDim()
    }

    private fun collapseActions() {
        if (actions.visibility != VISIBLE) return
        expanded = false
        actions.animate()
            .alpha(0f)
            .scaleX(0.94f)
            .scaleY(0.94f)
            .setDuration(140)
            .withEndAction {
                actions.visibility = GONE
                // 点击收起后弹性变形回迷你药丸
                showMiniPill(animate = true)
                post { host?.relayoutOverlay() }
            }
            .start()
        morphHeight(actions, expand = false)
        post { host?.relayoutOverlay() }
        scheduleIdleDim()
    }

    /**
     * 展开态：迷你药丸整体让位，避免与左上角控制岛叠字。
     * 外壳一起隐藏——玻璃背景与流光都挂在它上面，只藏内层会留下一个空壳。
     */
    private fun hideMiniPill() {
        capsuleShell.animate().cancel()
        capsuleShell.visibility = GONE
        capsuleShell.alpha = 1f
        capsuleShell.scaleX = 1f
        capsuleShell.scaleY = 1f
    }

    /** 收起态：迷你药丸带过冲回弹重新出现。 */
    private fun showMiniPill(animate: Boolean) {
        capsuleShell.animate().cancel()
        capsuleShell.visibility = VISIBLE
        if (!animate) {
            capsuleShell.alpha = 1f
            capsuleShell.scaleX = 1f
            capsuleShell.scaleY = 1f
            return
        }
        capsuleShell.alpha = 0f
        capsuleShell.scaleX = 0.86f
        capsuleShell.scaleY = 0.86f
        capsuleShell.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(260L)
            .setInterpolator(OvershootInterpolator(1.8f))
            .start()
    }

    /**
     * 迷你药丸 ↔ 展开控制岛的平滑尺寸过渡：
     * 只对外层 actions 的高度做轻量 ValueAnimator，不触发整窗重新测量以外的开销。
     */
    private fun morphHeight(target: View, expand: Boolean) {
        val lp = target.layoutParams ?: return
        // 重复展开/收起时先取消进行中的旧动画，避免两个 animator 同时写 lp.height
        morphAnimator?.cancel()
        if (expand) {
            val widthSpec = MeasureSpec.makeMeasureSpec(
                (parent as? View)?.width ?: width,
                MeasureSpec.AT_MOST
            )
            target.measure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val targetHeight = target.measuredHeight
            if (targetHeight <= 0) return
            lp.height = 0
            target.layoutParams = lp
            android.animation.ValueAnimator.ofInt(0, targetHeight).apply {
                duration = 240L
                interpolator = android.view.animation.DecelerateInterpolator(1.4f)
                addUpdateListener {
                    lp.height = it.animatedValue as Int
                    target.layoutParams = lp
                    host?.relayoutOverlay()
                }
                start()
                morphAnimator = this
            }
        } else {
            val from = target.height
            if (from <= 0) return
            android.animation.ValueAnimator.ofInt(from, 0).apply {
                duration = 160L
                addUpdateListener {
                    lp.height = it.animatedValue as Int
                    target.layoutParams = lp
                    host?.relayoutOverlay()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        target.layoutParams = lp
                    }
                })
                start()
                morphAnimator = this
            }
        }
    }

    private fun expandRoam() {
        idleRunnable?.let { removeCallbacks(it) }
        animate().cancel()
        alpha = FULL_ALPHA
        roamPanel.visibility = VISIBLE
        post { host?.relayoutOverlay() }
        scheduleIdleDim()
    }

    private fun collapseRoam() {
        FreeRoamController.stopMotion()
        joystick.unlock()
        roamPanel.visibility = GONE
        roamPanel.alpha = 1f
        post { host?.relayoutOverlay() }
        scheduleIdleDim()
    }

    /** 点击悬浮窗外面时调用：展开态自动收起为小胶囊 */
    fun collapseIfExpanded(): Boolean {
        if (inRoamMode && roamPanel.visibility == VISIBLE) {
            collapseRoam()
            return true
        }
        if (!inRoamMode && actions.visibility == VISIBLE) {
            collapseActions()
            return true
        }
        return false
    }

    // ---------------- 手动接管 / 结束模拟 ----------------

    private fun beginManualTakeover() {
        val currentState = host?.currentState()
        val running = currentState?.phase
        if (running == RunPhase.RUNNING || running == RunPhase.DETOUR_POINT) {
            host?.pauseResume()
        }
        host?.beginManualOverride()
        // 先用当前跑道位置预置漫游起点，防止首次连接时瞬移到真实定位/北京
        FreeRoamController.seedPosition(currentState?.position)
        FreeRoamController.attach(context)
        inRoamMode = true
        expanded = false
        idleRunnable?.let { removeCallbacks(it) }
        animate().cancel()
        alpha = FULL_ALPHA
        actions.visibility = GONE
        // 从展开控制岛切到手动接管：迷你药丸重新出现作为摇杆盘标题
        showMiniPill(animate = false)
        roamPanel.visibility = VISIBLE
        capsuleText.text = "手动接管 · ${selectedGear.label}"
        post { host?.relayoutOverlay() }
        scheduleIdleDim()
    }

    private fun endManualTakeover() {
        FreeRoamController.stopMotion()
        joystick.unlock()
        val target = host?.currentState()?.position
        inRoamMode = false
        roamPanel.visibility = GONE
        actions.visibility = GONE
        expanded = false
        capsuleText.text = "正在回归跑道…"
        if (target != null) {
            // 先平滑飞回跑道暂停点，避免瞬移
            FreeRoamController.teleport(target.lat, target.lng)
            takeoverRunnable?.let { removeCallbacks(it) }
            val takeover = Runnable {
                FreeRoamController.detachKeepProviders()
                host?.endManualOverride()
                if (host?.currentState()?.phase == RunPhase.PAUSED) {
                    host.pauseResume()
                }
            }
            takeoverRunnable = takeover
            postDelayed(takeover, 900)
        } else {
            FreeRoamController.detachKeepProviders()
            host?.endManualOverride()
            if (host?.currentState()?.phase == RunPhase.PAUSED) {
                host.pauseResume()
            }
        }
        post { host?.relayoutOverlay() }
        scheduleIdleDim()
    }

    private fun confirmStopSimulation() {
        host?.setOverlayHidden(true)
        AlertDialog.Builder(context)
            .setTitle("结束模拟定位？")
            .setMessage("将停止巡航与虚拟定位，移除测试定位并恢复手机真实 GPS。")
            .setPositiveButton("结束并关闭") { _, _ -> host?.stopSimulation() }
            .setNegativeButton("取消") { _, _ -> host?.setOverlayHidden(false) }
            .setOnCancelListener { host?.setOverlayHidden(false) }
            .showOverlaid()
    }

    // ---------------- 闲置半隐 / 长按隐身 ----------------

    private fun scheduleIdleDim() {
        idleRunnable?.let { removeCallbacks(it) }
        val runnable = Runnable {
            if (actions.visibility == VISIBLE || roamPanel.visibility == VISIBLE) {
                animate().cancel()
                alpha = FULL_ALPHA
            } else {
                animate().alpha(DIM_ALPHA).setDuration(400).start()
            }
        }
        idleRunnable = runnable
        postDelayed(runnable, IDLE_DIM_DELAY_MS)
    }

    private fun ghostHide() {
        ghostRunnable?.let { removeCallbacks(it) }
        ghostHidden = true
        pauseAnimationLoops()
        animate().alpha(0f).setDuration(150).start()
        val runnable = Runnable {
            ghostHidden = false
            animate().alpha(FULL_ALPHA).setDuration(200).start()
            resumeAnimationLoops()
            scheduleIdleDim()
        }
        ghostRunnable = runnable
        postDelayed(runnable, GHOST_HIDE_MS)
    }

    private fun startPulse() {
        if (balancedRender || beamPaused || ghostHidden) return
        pulseAnimator?.cancel()
        val animator = ObjectAnimator.ofInt(this, "pulseAlpha", 255, 70)
        animator.duration = 1100
        animator.repeatCount = ObjectAnimator.INFINITE
        animator.repeatMode = ObjectAnimator.REVERSE
        animator.interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        pulseAnimator = animator
        animator.start()
    }

    @Keep
    fun setPulseAlpha(alpha: Int) = ring.setPulseAlpha(alpha)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            animate().cancel()
            alpha = FULL_ALPHA
            scheduleIdleDim()
        }
        return false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startPulse()
        startBeam()
        scheduleIdleDim()
    }

    override fun onDetachedFromWindow() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        stopBeam()
        idleRunnable?.let { removeCallbacks(it) }
        ghostRunnable?.let { removeCallbacks(it) }
        // 手动接管收尾回调与高度过渡动画同样纳入 detach 清理：
        // 窗口移除后回调若仍触发 host?.endManualOverride()，会在空窗口上误操作
        takeoverRunnable?.let { removeCallbacks(it) }
        takeoverRunnable = null
        if (inRoamMode) {
            FreeRoamController.detachKeepProviders()
            inRoamMode = false
        }
        morphAnimator?.cancel()
        morphAnimator = null
        super.onDetachedFromWindow()
    }

    /**
     * 锁屏 / 后台巡航：暂停边缘流光，不再申请任何帧。
     * 与 GlassRenderGate 同一思路——看不见的时候一点电都不该花。
     */
    fun setBeamPaused(paused: Boolean) {
        if (beamPaused == paused) return
        beamPaused = paused
        if (paused) {
            pauseAnimationLoops()
        } else {
            resumeAnimationLoops()
        }
    }

    private fun pauseAnimationLoops() {
        beamScheduled = false
        android.view.Choreographer.getInstance().removeFrameCallback(beamFrameCallback)
        pulseAnimator?.pause()
    }

    private fun resumeAnimationLoops() {
        if (balancedRender || beamPaused || ghostHidden || !isAttachedToWindow) return
        beamStartAtMs = 0L
        beamLastFrameMs = 0L
        scheduleBeamFrame()
        val pulse = pulseAnimator
        if (pulse != null && pulse.isPaused) {
            pulse.resume()
        } else if (pulse == null) {
            startPulse()
        }
    }
}
