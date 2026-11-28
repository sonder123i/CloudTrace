package com.zcshou.gogogo.run.ui.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.data.prefs.DisplayPrefs
import com.zcshou.gogogo.run.data.prefs.RoamSpeeds
import com.zcshou.gogogo.run.data.prefs.RunPrefs
import com.zcshou.gogogo.run.engine.FreeRoamController
import com.zcshou.gogogo.run.engine.TrackMathUtils
import com.zcshou.gogogo.run.ui.glass.OverlayGlassStyle
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 原版式常驻漫游摇杆：顶部拖动条 + 定位/隐藏，中间大球摇杆，
 * 四档速度（长按改数值），底部状态与结束。由 RoamService 托管。
 */
class RoamHubView(context: Context) : LinearLayout(context) {

    var dragHandler: ((Int, Int, Boolean) -> Unit)? = null
    var onLocate: (() -> Unit)? = null
    var onHide: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    /** 悬浮窗内不能直接弹 Toast（会挂在窗口下层），统一交由 RoamService 转达 */
    var onToast: ((String) -> Unit)? = null
    /** 面板高度变化后通知宿主重排窗口，避免超出屏幕或触摸区错位 */
    var onContentSizeChanged: (() -> Unit)? = null

    private val joystick: TacticalJoystickView
    private val statusText: TextView
    private val dragBar: TextView
    private val lockButton: ImageView
    private val quickScroll: HorizontalScrollView
    private val quickRow: LinearLayout
    private val quickToggle: TextView
    private var quickExpanded = false
    private val gearChips = mutableListOf<Pair<TextView, RoamSpeeds.Gear>>()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var selectedGear = RoamSpeeds.Gear.RUN
    private var gearSpeed = 3.6f
    private var lastBearing = 0f
    private val hubPanel: CapsuleShellView
    private var hubLook: Boolean? = null

    private val balancedRender = DisplayPrefs.quality(context) == DisplayPrefs.RenderQuality.BALANCED
    private var beamScheduled = false
    private var beamStartAtMs = 0L
    private var beamLastFrameMs = 0L
    /** 0 = 跟随屏幕刷新率满帧；彗尾是连续位移，30fps 下跳步明显，绘制成本又低（见 FloatingCapsuleView 注释）。 */
    private var beamMinFrameMs = 0L

    private val beamFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            beamScheduled = false
            if (balancedRender || !isAttachedToWindow || visibility != VISIBLE) return
            val now = frameTimeNanos / 1_000_000L
            if (beamStartAtMs == 0L) beamStartAtMs = now
            if (beamLastFrameMs == 0L || now - beamLastFrameMs >= beamMinFrameMs) {
                beamLastFrameMs = now
                val elapsed = now - beamStartAtMs
                val t = elapsed / 1000.0
                val breath = ((sin(t * 2.0 * Math.PI / 3.7) + 1.0) * 0.5).toFloat().coerceIn(0f, 1f)
                hubPanel.beam?.let { beam ->
                    beam.phase = (elapsed % 4200L) / 4200f
                    beam.ringPhase = (elapsed % 8900L) / 8900f
                    beam.breath = breath
                }
                hubPanel.invalidate()
            }
            scheduleBeam()
        }
    }

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        LayoutInflater.from(context).inflate(R.layout.roam_hub, this, true)
        hubPanel = findViewById(R.id.hubPanel)
        val density = resources.displayMetrics.density
        hubPanel.beam = BorderBeamDrawable(
            context,
            cornerRadiusPx = 24f * density,
            borderWidthPx = 1.4f * density
        ).apply { intensity = 0.78f }

        joystick = findViewById(R.id.roamHubJoystick)
        statusText = findViewById(R.id.roamHubStatus)
        dragBar = findViewById(R.id.roamDragBar)
        lockButton = findViewById(R.id.btnRoamHubLock)
        quickScroll = findViewById(R.id.roamQuickScroll)
        quickRow = findViewById(R.id.roamQuickRow)
        quickToggle = findViewById(R.id.btnRoamQuickToggle)

        attachWindowDrag(dragBar)

        val locateBtn = findViewById<View>(R.id.btnRoamHubLocate)
        val hideBtn = findViewById<View>(R.id.btnRoamHubHide)
        val stopBtn = findViewById<TextView>(R.id.btnRoamHubStop)
        locateBtn.setOnClickListener {
            hubPanel.beam?.triggerBurst()
            onLocate?.invoke()
        }
        hideBtn.setOnClickListener { onHide?.invoke() }
        stopBtn.setOnClickListener { confirmStop() }
        lockButton.setOnClickListener {
            hubPanel.beam?.triggerBurst()
            joystick.toggleLockFromButton()
            updateLockChip()
        }
        OverlayGlassStyle.attachTactilePress(lockButton, locateBtn, hideBtn, stopBtn, quickToggle)
        quickToggle.setOnClickListener { setQuickExpanded(!quickExpanded) }

        setupGears()
        gearSpeed = RoamSpeeds.speed(context, selectedGear)
        joystick.listener = object : TacticalJoystickView.Listener {
            override fun onSteer(bearingDeg: Float, strength: Float) {
                lastBearing = bearingDeg
                if (strength <= 0.02f) {
                    FreeRoamController.stopMotion()
                } else {
                    FreeRoamController.attach(context)
                    FreeRoamController.drive(
                        bearingDeg.toDouble(),
                        (gearSpeed * strength).toDouble()
                    )
                }
                refreshStatus()
            }

            override fun onLockChanged(locked: Boolean) {
                updateLockChip()
                refreshStatus()
            }
        }
        updateLockChip()
        applyPanelGlass(translucent = true)
        refreshStatus()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleBeam()
    }

    override fun onDetachedFromWindow() {
        stopBeam()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) scheduleBeam() else stopBeam()
    }

    private fun scheduleBeam() {
        if (balancedRender || beamScheduled || !isAttachedToWindow || visibility != VISIBLE) return
        beamScheduled = true
        Choreographer.getInstance().postFrameCallback(beamFrameCallback)
    }

    private fun stopBeam() {
        beamScheduled = false
        Choreographer.getInstance().removeFrameCallback(beamFrameCallback)
    }

    /** V6.6：跨窗模糊可用时半透，被 ROM 禁用时换成更实的兜底，杜绝透字。 */
    fun setCrossWindowBlurEnabled(enabled: Boolean) {
        if (hubLook == enabled) return
        hubLook = enabled
        applyPanelGlass(enabled)
    }

    /**
     * 摇杆盘底片：棱镜虹彩玻璃。
     * 悬浮窗采不到下层像素，材质厚度只能靠薄膜 + 镜面高光 + 双层微光细边画出来。
     */
    private fun applyPanelGlass(translucent: Boolean) {
        val radiusPx = 24f * resources.displayMetrics.density
        hubPanel.setOutlineCornerRadius(radiusPx)
        hubPanel.background = PrismGlassDrawable(context, radiusPx)
            .also { it.alpha = if (translucent) 240 else 255 }
    }

    private fun updateLockChip() {
        lockButton.setImageResource(
            if (joystick.isLocked) R.drawable.ic_lock_line else R.drawable.ic_lock_open_line
        )
        val color = context.getColor(
            if (joystick.isLocked) R.color.dr_overlay_ice else R.color.dr_overlay_text_secondary
        )
        lockButton.imageTintList = ColorStateList.valueOf(color)
    }

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

    private fun setupGears() {
        gearChips.clear()
        gearChips += findViewById<TextView>(R.id.roamGearWalk) to RoamSpeeds.Gear.WALK
        gearChips += findViewById<TextView>(R.id.roamGearRun) to RoamSpeeds.Gear.RUN
        gearChips += findViewById<TextView>(R.id.roamGearBike) to RoamSpeeds.Gear.BIKE
        gearChips += findViewById<TextView>(R.id.roamGearCar) to RoamSpeeds.Gear.CAR
        gearChips.forEach { (view, gear) ->
            OverlayGlassStyle.attachTactilePress(view)
            view.setOnClickListener {
                selectedGear = gear
                gearSpeed = RoamSpeeds.speed(context, gear)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                hubPanel.beam?.triggerBurst()
                paintGears()
                refreshStatus()
                if (joystick.isLocked) {
                    FreeRoamController.attach(context)
                    FreeRoamController.drive(lastBearing.toDouble(), gearSpeed.toDouble())
                }
            }
            view.setOnLongClickListener {
                editGearSpeed(gear)
                true
            }
        }
        paintGears()
    }

    private fun paintGears() {
        gearChips.forEach { (view, gear) ->
            val on = gear == selectedGear
            view.setTextColor(
                context.getColor(
                    if (on) R.color.dr_overlay_ice else R.color.dr_overlay_text_secondary
                )
            )
            view.setTypeface(
                null,
                if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
            view.setBackgroundResource(
                if (on) R.drawable.bg_overlay_chip_selected else 0
            )
        }
    }

    fun updateStatus(speedMps: Double) {
        refreshStatus(speedMps)
    }

    // ---------------- 快捷飞跃地标 ----------------

    /**
     * 展开/折叠快捷地标条：面板固定尺寸，展开时补 32dp（30dp 条 + 4dp 间距），
     * 并通知宿主重排悬浮窗，防止窗口比屏幕高时触摸区域错位。
     */
    private fun setQuickExpanded(expanded: Boolean) {
        quickExpanded = expanded
        if (expanded) refreshQuickFavorites()
        quickScroll.visibility = if (expanded) View.VISIBLE else View.GONE
        quickToggle.setTextColor(
            context.getColor(
                if (expanded) R.color.dr_overlay_ice else R.color.dr_overlay_text_secondary
            )
        )
        val height = dp(if (expanded) PANEL_HEIGHT_EXPANDED_DP else PANEL_HEIGHT_DP)
        hubPanel.layoutParams = hubPanel.layoutParams.apply { this.height = height }
        hubPanel.requestLayout()
        requestLayout()
        post { onContentSizeChanged?.invoke() }
    }

    /** 置顶地标排在最前，最多显示 6 个，避免横向条无限拉长 */
    private fun refreshQuickFavorites() {
        quickRow.removeAllViews()
        val favorites = RunPrefs.favoritesForQuickJump(context).take(QUICK_JUMP_LIMIT)
        if (favorites.isEmpty()) {
            val empty = TextView(context).apply {
                text = "还没有收藏地标：在漫游页长按星标收藏"
                setTextColor(context.getColor(R.color.dr_overlay_text_secondary))
                textSize = 10f
                maxLines = 1
            }
            quickRow.addView(empty)
            return
        }
        favorites.forEach { favorite ->
            val chip = TextView(context).apply {
                text = if (favorite.pinned) "★ ${favorite.name}" else favorite.name
                textSize = 10f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = dp(96)
                gravity = android.view.Gravity.CENTER
                setPadding(dp(9), dp(4), dp(9), dp(4))
                setTextColor(context.getColor(R.color.dr_overlay_text_secondary))
                background = androidx.core.content.ContextCompat.getDrawable(
                    context, R.drawable.bg_overlay_tile
                )
                setOnClickListener { confirmQuickJump(favorite) }
            }
            quickRow.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
            )
        }
    }

    /** 误触保护：单击先确认，确认后按「平滑前往 / 空降」规则移动 */
    private fun confirmQuickJump(favorite: RunPrefs.Favorite) {
        val from = FreeRoamController.state.value.position
        val distance = from?.let {
            TrackMathUtils.distanceMeters(it, TrackMathUtils.LatLng(favorite.lat, favorite.lng))
        }
        val message = if (distance != null) {
            String.format(Locale.US, "距离约 %.0f m，确认后开始移动", distance)
        } else {
            "将直接空降到该位置"
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("飞跃到「${favorite.name}」")
            .setMessage(message)
            .setPositiveButton("出发") { _, _ -> jumpTo(favorite) }
            .setNegativeButton("取消", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    private fun jumpTo(favorite: RunPrefs.Favorite) {
        FreeRoamController.stopMotion()
        FreeRoamController.attach(context)
        val from = FreeRoamController.state.value.position
        val distance = from?.let {
            TrackMathUtils.distanceMeters(it, TrackMathUtils.LatLng(favorite.lat, favorite.lng))
        }
        if (RunPrefs.isSmoothWalkEnabled(context) && distance != null && distance <= 300.0) {
            FreeRoamController.glideTo(favorite.lat, favorite.lng, gearSpeed.toDouble())
            onToast?.invoke(
                String.format(Locale.US, "平滑前往「%s」· 约 %.0f m", favorite.name, distance)
            )
        } else {
            FreeRoamController.teleport(favorite.lat, favorite.lng)
            onToast?.invoke("已空跳到「${favorite.name}」")
        }
        hubPanel.beam?.triggerBurst()
        refreshStatus()
    }

    private fun refreshStatus(speedMps: Double = FreeRoamController.state.value.speedMps) {
        val lock = if (joystick.isLocked) "锁定 · " else ""
        val speed = String.format(Locale.US, "%.1f m/s", if (speedMps > 0.05) speedMps else gearSpeed)
        statusText.text = "$lock${selectedGear.label} $speed"
    }

    private fun editGearSpeed(gear: RoamSpeeds.Gear) {
        FreeRoamController.stopMotion()
        joystick.unlock()
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
        val dialog = AlertDialog.Builder(context)
            .setTitle("${gear.label}速度（m/s）")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val value = input.text.toString().toFloatOrNull() ?: return@setPositiveButton
                RoamSpeeds.setSpeed(context, gear, value)
                if (gear == selectedGear) gearSpeed = value.coerceIn(0.2f, 40f)
                refreshStatus()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    private fun confirmStop() {
        FreeRoamController.stopMotion()
        joystick.unlock()
        val dialog = AlertDialog.Builder(context)
            .setTitle("结束模拟定位？")
            .setMessage("将停止漫游并移除虚拟定位，恢复手机真实 GPS。")
            .setPositiveButton("结束并关闭") { _, _ -> onStop?.invoke() }
            .setNegativeButton("取消", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** 与 roam_hub.xml 中 hubPanel 固定高度一致 */
        const val PANEL_HEIGHT_DP = 258
        /** 展开快捷地标条后的高度：258 + 30（条）+ 4（间距）- 2（余量） */
        const val PANEL_HEIGHT_EXPANDED_DP = 290
        const val QUICK_JUMP_LIMIT = 6
    }
}
