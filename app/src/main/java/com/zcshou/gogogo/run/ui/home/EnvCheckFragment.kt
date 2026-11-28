package com.zcshou.gogogo.run.ui.home

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.liquidglass.ScrollEdgeBlurView
import com.zcshou.gogogo.R
import com.zcshou.gogogo.mascot.MascotDialogueEngine
import com.zcshou.gogogo.mascot.MascotMode
import com.zcshou.gogogo.mascot.MascotPeekView
import com.zcshou.gogogo.mascot.MascotPrefs
import com.zcshou.gogogo.mascot.MascotState
import com.zcshou.gogogo.mascot.MascotTouchProxy
import com.zcshou.gogogo.run.guard.EnvGuardManager
import com.zcshou.gogogo.run.guard.SetupGuard
import com.zcshou.gogogo.run.ui.glass.GlassDialogs
import com.zcshou.gogogo.run.ui.glass.GlassFeedback
import com.zcshou.gogogo.run.ui.glass.GlassRenderGate
import com.zcshou.gogogo.run.ui.widget.StatusOrbView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Tab 3：体检。
 *
 * 这一页只回答一个问题：现在能不能开跑。
 * 顶部状态卡给出必需/可选的就绪数量，下面按「开跑必需」「可选增强」分组，
 * 未完成的项右侧直接给动作按钮；偏好、高级项和应用信息都移到设置页。
 */
class EnvCheckFragment : Fragment() {

    private data class CheckItem(
        val root: View,
        val checker: () -> Boolean,
        val okHintRes: Int,
        val todoHintRes: Int,
        val actionRes: Int
    )

    private val items = mutableListOf<CheckItem>()
    private lateinit var mascot: MascotPeekView
    private var mascotBubbleShown = false
    private var lastGlassRefreshAt = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_env_check, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        items.clear()
        mascot = view.findViewById(R.id.checkupMascot)
        // 鲸鲸已经贴近状态栏，气泡再往上会被顶出屏幕，改在卡片内向下展开。
        mascot.setBubbleBelow(true)
        MascotTouchProxy.install(view.findViewById(R.id.envScroll), mascot)
        view.findViewById<View>(R.id.checkupGuideLink).setOnClickListener {
            openIntent(SetupGuideActivity.intent(requireContext()))
        }
        view.findViewById<View>(R.id.btnCheckupRecheck).setOnClickListener { refresh() }
        view.findViewById<View>(R.id.checkupTrustLine).setOnClickListener { showTrustDialog() }

        addItem(
            view, R.id.rowDev,
            { SetupGuard.check(requireContext()).devOptions },
            R.string.checkup_dev_ok,
            R.string.checkup_dev_todo,
            R.string.checkup_dev_action
        ) {
            // 开发者选项没有公开的一键开关，只能把人带到「关于手机」按口诀连点。
            GlassFeedback.toast(requireContext(), SetupGuard.locateVersionHint(), long = true)
            openIntent(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
        }
        addItem(
            view, R.id.rowMock,
            { EnvGuardManager.isMockLocationAllowed(requireContext()) },
            R.string.checkup_mock_ok,
            R.string.checkup_mock_todo,
            R.string.checkup_mock_action
        ) {
            if (!SetupGuard.check(requireContext()).devOptions) {
                GlassFeedback.toast(requireContext(), getString(R.string.checkup_mock_need_dev))
                openIntent(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
                return@addItem
            }
            GlassFeedback.toast(
                requireContext(),
                "往下滑到最底部 → 点「选择模拟位置信息应用」→ 选「云迹」",
                long = true
            )
            openIntent(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }
        addItem(
            view, R.id.rowOverlay,
            { EnvGuardManager.isOverlayAllowed(requireContext()) },
            R.string.checkup_overlay_ok,
            R.string.checkup_overlay_todo,
            R.string.checkup_overlay_action
        ) {
            // 侧载安装的「受限设置」要在应用信息里解除，走完整向导，不直接跳悬浮窗页。
            openIntent(Intent(requireContext(), SetupWizardActivity::class.java))
        }
        addItem(
            view, R.id.rowBattery,
            { EnvGuardManager.isIgnoringBatteryOptimizations(requireContext()) },
            R.string.checkup_battery_ok,
            R.string.checkup_battery_todo,
            R.string.checkup_battery_action
        ) {
            openIntent(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        addItem(
            view, R.id.rowWifi,
            { !EnvGuardManager.isWifiActivelyConnected(requireContext()) },
            R.string.checkup_wifi_ok,
            R.string.checkup_wifi_todo,
            R.string.checkup_wifi_action
        ) {
            openIntent(Intent(Settings.ACTION_WIFI_SETTINGS))
        }

        applyScrollInsets(view)
        refresh()
    }

    /** 全屏沉浸式：ScrollView 自己消化状态栏/导航栏 inset，底部留白防止最后一项被遮挡。 */
    private fun applyScrollInsets(root: View) {
        val scroll = root.findViewById<View>(R.id.envScroll)
        val scrim = root.findViewById<View>(R.id.topScrim)
        val edgeBlur = root.findViewById<ScrollEdgeBlurView>(R.id.topEdgeBlur)
        edgeBlur.edge = ScrollEdgeBlurView.Edge.TOP
        edgeBlur.maxBlurRadius = 22f * resources.displayMetrics.density
        edgeBlur.bindScrollView(scroll)
        val baseTop = dp(6)
        // 与 LiquidTabBar.NavMetrics.contentInsetDp 同源，改轨道几何必须同步这里。
        val baseBottom = dp(96)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            target.setPadding(
                target.paddingLeft,
                baseTop + bars.top,
                target.paddingRight,
                baseBottom + bars.bottom
            )
            edgeBlur.layoutParams?.let {
                it.height = bars.top + dp(36)
                edgeBlur.layoutParams = it
            }
            scrim.layoutParams?.let {
                it.height = bars.top + dp(12)
                scrim.layoutParams = it
            }
            insets
        }
        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            edgeBlur.visibility = if (scrollY > dp(6)) View.VISIBLE else View.INVISIBLE
            refreshGlassOnScroll()
        }
        ViewCompat.requestApplyInsets(scroll)
    }

    /** 滚动时实时更新玻璃背景采样（80ms 节流），保证底部导航的模糊透视连续。 */
    private fun refreshGlassOnScroll() {
        val now = SystemClock.uptimeMillis()
        if (now - lastGlassRefreshAt < 80) return
        lastGlassRefreshAt = now
        GlassRenderGate.requestRefresh(160L)
    }

    private var mascotBubbleRunnable: Runnable? = null

    override fun onResume() {
        super.onResume()
        if (!isHidden) refresh()
    }

    override fun onPause() {
        if (::mascot.isInitialized) mascot.pause()
        super.onPause()
    }

    override fun onDestroyView() {
        mascotBubbleRunnable?.let { if (::mascot.isInitialized) mascot.removeCallbacks(it) }
        mascotBubbleRunnable = null
        super.onDestroyView()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            refresh()
        } else if (::mascot.isInitialized) {
            mascot.pause()
        }
    }

    private fun addItem(
        root: View,
        includeId: Int,
        checker: () -> Boolean,
        okHintRes: Int,
        todoHintRes: Int,
        actionRes: Int,
        jump: () -> Unit
    ) {
        val row = root.findViewById<View>(includeId)
        row.findViewById<TextView>(R.id.envTitle).text = titleFor(includeId)
        row.setOnClickListener { jump() }
        items += CheckItem(row, checker, okHintRes, todoHintRes, actionRes)
    }

    private fun titleFor(includeId: Int): String = when (includeId) {
        R.id.rowDev -> getString(R.string.checkup_dev_title)
        R.id.rowMock -> getString(R.string.checkup_mock_title)
        R.id.rowOverlay -> getString(R.string.checkup_overlay_title)
        R.id.rowBattery -> getString(R.string.checkup_battery_title)
        R.id.rowWifi -> getString(R.string.checkup_wifi_title)
        else -> ""
    }

    private fun refresh() {
        val context = context ?: return
        val results = items.map { runCatching { it.checker() }.getOrDefault(false) }
        items.forEachIndexed { index, item ->
            val ok = results[index]
            val dot = item.root.findViewById<View>(R.id.envDot)
            // 前两项是开跑必需：缺失用红灯；后面是可选增强：缺失只用中性灰点。
            (dot as? StatusOrbView)?.setState(
                when {
                    ok -> true
                    index < 2 -> false
                    else -> null
                }
            )
            item.root.findViewById<TextView>(R.id.envSubtitle).text =
                getString(if (ok) item.okHintRes else item.todoHintRes)

            val action = item.root.findViewById<TextView>(R.id.envAction)
            if (ok) {
                action.text = getString(R.string.checkup_done)
                action.background = null
                action.setTextColor(context.getColor(R.color.pf_ok))
            } else {
                action.text = getString(item.actionRes)
                action.setBackgroundResource(R.drawable.bg_secondary_button)
                action.setTextColor(context.getColor(R.color.pf_text_primary))
            }
        }

        // 前两项是开跑必需，后三项是可选增强（复用结果，不再二次执行 IPC/系统查询）。
        val requiredOk = results.take(2).count { it }
        val optionalOk = results.drop(2).count { it }
        val requiredTotal = 2
        val optionalTotal = 3
        val missing = requiredTotal - requiredOk

        val root = view ?: return
        val title = root.findViewById<TextView>(R.id.checkupStatusTitle)
        val desc = root.findViewById<TextView>(R.id.checkupStatusDesc)
        val guide = root.findViewById<View>(R.id.checkupGuideLink)
        if (missing <= 0) {
            title.text = getString(R.string.checkup_ready_title)
            desc.text = getString(R.string.checkup_ready_desc)
            guide.visibility = View.GONE
            // 体检全绿 = 开发者选项已就绪：解锁「键盘侠鲸」（伴侣关闭时不解锁）
            if (MascotPrefs.mode(context) != MascotMode.OFF) {
                com.zcshou.gogogo.mascot.MascotEggStore.unlock(
                    context,
                    com.zcshou.gogogo.mascot.MascotEgg.KEYBOARD
                )
            }
        } else {
            title.text = getString(R.string.checkup_missing_title, missing)
            desc.text = getString(R.string.checkup_missing_desc)
            guide.visibility = View.VISIBLE
        }
        root.findViewById<TextView>(R.id.checkupMetrics).text =
            getString(R.string.checkup_metrics, requiredOk, requiredTotal, optionalOk, optionalTotal)
        refreshMascot(missing)
    }

    private fun refreshMascot(missing: Int) {
        if (!isAdded || !::mascot.isInitialized) return
        val context = requireContext()
        val mode = MascotPrefs.mode(context)
        if (mode == MascotMode.OFF) {
            mascot.bind(MascotMode.OFF, MascotState.PEEK_IDLE)
            return
        }

        val dialogue = when {
            missing > 0 -> MascotDialogueEngine.State.WARNING_ENV
            EnvGuardManager.isWifiActivelyConnected(context) ->
                MascotDialogueEngine.State.WARNING_WIFI
            else -> MascotDialogueEngine.State.IDLE
        }
        val visual = if (missing <= 0) MascotState.CHEER else MascotState.STARE
        mascot.bind(mode, visual, MascotDialogueEngine.contextState(dialogue))

        if (!mascotBubbleShown) {
            mascotBubbleShown = true
            mascotBubbleRunnable?.let { mascot.removeCallbacks(it) }
            val showTask = Runnable {
                if (!isAdded || view == null) return@Runnable
                mascot.showAutoBubble(bypassCooldown = true)
            }
            mascotBubbleRunnable = showTask
            mascot.postDelayed(showTask, 700L)
        }
    }

    private fun showTrustDialog() {
        GlassDialogs.builder(requireContext())
            .setTitle(R.string.checkup_trust_title)
            .setMessage(R.string.checkup_trust_body)
            .setPositiveButton(R.string.crash_ack, null)
            .show()
    }

    private fun openIntent(intent: Intent) {
        runCatching { startActivity(intent) }
            .onFailure {
                GlassFeedback.toast(requireContext(), "跳转失败，请手动到系统设置中配置")
            }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}