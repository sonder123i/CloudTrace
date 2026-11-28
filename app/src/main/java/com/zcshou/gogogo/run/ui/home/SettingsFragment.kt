package com.zcshou.gogogo.run.ui.home

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.liquidglass.ScrollEdgeBlurView
import com.zcshou.gogogo.BuildConfig
import com.zcshou.gogogo.R
import com.zcshou.gogogo.mascot.MascotDialogueEngine
import com.zcshou.gogogo.mascot.MascotEgg
import com.zcshou.gogogo.mascot.MascotEggCardView
import com.zcshou.gogogo.mascot.MascotMode
import com.zcshou.gogogo.mascot.MascotPeekView
import com.zcshou.gogogo.mascot.MascotPrefs
import com.zcshou.gogogo.mascot.MascotState
import com.zcshou.gogogo.run.data.prefs.AdvancedPrefs
import com.zcshou.gogogo.run.data.prefs.DisplayPrefs
import com.zcshou.gogogo.run.data.prefs.RunPrefs
import com.zcshou.gogogo.run.guard.EnvGuardManager
import com.zcshou.gogogo.run.guard.ShizukuWifiHelper
import com.zcshou.gogogo.run.ui.glass.DeepRunGlassManager
import com.zcshou.gogogo.run.ui.glass.GlassDialogs
import com.zcshou.gogogo.run.ui.glass.GlassFeedback
import com.zcshou.gogogo.run.ui.glass.GlassRenderGate
import com.zcshou.gogogo.run.ui.theme.ThemeHelper
import com.zcshou.gogogo.run.update.UpdateConfig
import com.zcshou.gogogo.run.update.UpdateManager
import kotlinx.coroutines.launch

/**
 * Tab 4：设置。
 *
 * 体检页只留环境检查；偏好、高级项和应用入口集中在这里。
 * 独立成页后空间足够，高级项直接展开，不再折叠。
 */
class SettingsFragment : Fragment() {

    private lateinit var updateRow: View
    private lateinit var mascotModeGroup: RadioGroup
    private lateinit var mascotModeHint: TextView
    private lateinit var mascotGallery: View
    private lateinit var mascotPreview: MascotPeekView
    private lateinit var mascotPreviewLabel: TextView
    private lateinit var settingsEggCard: MascotEggCardView
    private var bindingMascot = false
    private var lastGlassRefreshAt = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_run_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val root = view
        bindRow(root, R.id.rowTargetApp, R.string.settings_target_app) { pickTargetApp() }
        bindRow(root, R.id.rowTheme, R.string.settings_theme) { showThemeDialog() }
        bindRow(root, R.id.rowRenderQuality, R.string.settings_render) { showRenderQualityDialog() }
        root.findViewById<View>(R.id.btnSettingsGuide).setOnClickListener {
            runCatching { startActivity(UsageGuideActivity.intent(requireContext())) }
        }
        updateRow = bindRow(root, R.id.rowUpdate, R.string.settings_update) { onUpdateClick() }
        bindRow(root, R.id.rowFeedback, R.string.settings_feedback, R.string.settings_feedback_sub) {
            runCatching { startActivity(FeedbackActivity.intent(requireContext())) }
        }
        bindRow(root, R.id.rowChangelog, R.string.settings_changelog, R.string.settings_changelog_sub) {
            runCatching { startActivity(ChangelogActivity.intent(requireContext())) }
        }
        bindRow(root, R.id.rowAbout, R.string.settings_about, R.string.settings_about_sub) {
            runCatching { startActivity(Intent(requireContext(), AboutActivity::class.java)) }
        }

        root.findViewById<TextView>(R.id.settingsFooter).apply {
            text = getString(R.string.settings_footer, BuildConfig.VERSION_NAME)
            setOnClickListener { BuildInfoDialog.show(requireActivity()) }
        }
        root.findViewById<View>(R.id.rowSupport).setOnClickListener {
            runCatching { startActivity(SupportActivity.intent(requireContext())) }
        }

        mascotModeGroup = root.findViewById(R.id.mascotModeGroup)
        mascotModeHint = root.findViewById(R.id.mascotModeHint)
        mascotGallery = root.findViewById(R.id.btnMascotGallery)
        mascotPreview = root.findViewById(R.id.settingsMascotPreview)
        mascotPreviewLabel = root.findViewById(R.id.settingsMascotPreviewLabel)
        settingsEggCard = root.findViewById(R.id.settingsEggCard)
        root.findViewById<android.widget.TextView>(R.id.btnMascotGallery)?.setOnClickListener {
            com.zcshou.gogogo.mascot.MascotGalleryDialog.show(requireContext())
        }
        mascotModeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (bindingMascot) return@setOnCheckedChangeListener
            val mode = when (checkedId) {
                R.id.mascotModeLight -> MascotMode.LIGHT
                R.id.mascotModeFull -> MascotMode.FULL
                else -> MascotMode.OFF
            }
            MascotPrefs.setMode(requireContext(), mode)
            applyMascot(mode, announce = true)
        }

        setupAdvanced(root)
        observeUpdateState()
        applyScrollInsets(root)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refresh()
    }

    /** 设置项不是状态行，隐藏状态点并把标题左对齐。 */
    private fun bindRow(
        root: View,
        includeId: Int,
        titleRes: Int,
        subtitleRes: Int? = null,
        onClick: () -> Unit
    ): View {
        val row = root.findViewById<View>(includeId)
        row.findViewById<View>(R.id.envDot).visibility = View.GONE
        val column = row.findViewById<View>(R.id.envTitle).parent as? View
        (column?.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.marginStart = 0
            it.leftMargin = 0
        }
        column?.requestLayout()
        row.findViewById<TextView>(R.id.envTitle).text = getString(titleRes)
        if (subtitleRes != null) {
            row.findViewById<TextView>(R.id.envSubtitle).text = getString(subtitleRes)
        }
        row.setOnClickListener { onClick() }
        return row
    }

    private fun refresh() {
        if (!isAdded) return
        val root = view ?: return
        val context = requireContext()

        val targetPkg = RunPrefs.targetApp(context)
        root.findViewById<View>(R.id.rowTargetApp).findViewById<TextView>(R.id.envSubtitle).text =
            if (targetPkg == null) {
                getString(R.string.settings_target_app_none)
            } else {
                getString(R.string.settings_target_app_bound, appLabel(targetPkg))
            }

        root.findViewById<View>(R.id.rowTheme).findViewById<TextView>(R.id.envSubtitle).text =
            when (ThemeHelper.themeMode(context)) {
                ThemeHelper.ThemeMode.DARK -> "曜石黑 · 深色（OLED 纯黑省电）"
                ThemeHelper.ThemeMode.LIGHT -> "日光白 · 浅色（户外强光防眩）"
                ThemeHelper.ThemeMode.SYSTEM -> "跟随系统昼夜切换"
            }

        val quality = DisplayPrefs.quality(context)
        val tier = DeepRunGlassManager.tier(context)
        val downgrade = DeepRunGlassManager.downgradeReason(context)
        // 副标题必须反映「实际激活的管线」，而不是用户选了什么：
        // 选了液态玻璃但系统开了省电，这里会写「高透静态磨砂（系统省电模式）」。
        root.findViewById<View>(R.id.rowRenderQuality).findViewById<TextView>(R.id.envSubtitle).text =
            when {
                quality == DisplayPrefs.RenderQuality.BALANCED -> "省电平衡 · ${tier.title}"
                downgrade == null -> "液态玻璃 · ${tier.title}"
                else -> "液态玻璃 · ${tier.title}（$downgrade）"
            }

        val scanOn = EnvGuardManager.isWifiScanAlwaysAvailable(context)
        root.findViewById<View>(R.id.rowWifiScan).findViewById<TextView>(R.id.envSubtitle).text =
            if (scanOn) "系统「始终允许扫描」开启，仅提示，不影响开跑" else "后台扫描已关闭"

        root.findViewById<View>(R.id.rowInjectInterval).findViewById<TextView>(R.id.envSubtitle).text =
            "当前 ${AdvancedPrefs.injectionIntervalMs(context)}ms 注入间隔 · 心跳 ≤500ms，200ms 更实时，1000ms 更省电"

        val shizukuRow = root.findViewById<View>(R.id.rowShizuku)
        val switch = shizukuRow.findViewById<SwitchCompat>(R.id.advancedSwitch)
        val enabled = AdvancedPrefs.isShizukuAutoWifiEnabled(context)
        if (switch.isChecked != enabled && EnvGuardManager.isShizukuReady()) {
            switch.isChecked = enabled
        }
        shizukuRow.findViewById<TextView>(R.id.envSubtitle).text = when {
            !ShizukuWifiHelper.isAvailable() -> "未安装 Shizuku（普通用户无需开启）"
            !EnvGuardManager.isShizukuReady() -> "已安装未授权：点击开关可申请授权"
            enabled -> "已开启：开跑前自动关 Wi-Fi，跑完恢复"
            else -> "已就绪：开启后才会自动关 Wi-Fi"
        }

        updateRow.findViewById<TextView>(R.id.envSubtitle).text =
            updateSubtitle(UpdateManager.getInstance(context).state.value)

        refreshMascot()
    }

    private fun refreshMascot() {
        if (!isAdded) return
        val mode = MascotPrefs.mode(requireContext())
        bindingMascot = true
        mascotModeGroup.check(
            when (mode) {
                MascotMode.OFF -> R.id.mascotModeOff
                MascotMode.LIGHT -> R.id.mascotModeLight
                MascotMode.FULL -> R.id.mascotModeFull
            }
        )
        bindingMascot = false
        applyMascot(mode, announce = false)
        refreshGalleryLabel()
    }

    /** 图鉴入口按钮上的「已收集 X/10」 */
    private fun refreshGalleryLabel() {
        val root = view ?: return
        root.findViewById<android.widget.TextView>(R.id.btnMascotGallery)?.text =
            "鲸鲸图鉴 · ${com.zcshou.gogogo.mascot.MascotEggStore.progressText(requireContext())}"
    }

    private fun applyMascot(mode: MascotMode, announce: Boolean) {
        if (!isAdded) return
        mascotPreviewLabel.visibility = if (mode == MascotMode.OFF) View.GONE else View.VISIBLE
        // 纯净模式下鲸鲸完全不出现，下面这段「OFF 时…LIGHT…FULL…」的说明和
        // 图鉴入口就成了「讲一个被隐藏的东西」，只剩占位。两者一起收起。
        mascotModeHint.visibility = if (mode == MascotMode.OFF) View.GONE else View.VISIBLE
        mascotGallery.visibility = if (mode == MascotMode.OFF) View.GONE else View.VISIBLE
        mascotPreview.bind(mode, MascotState.AVATAR, MascotDialogueEngine.State.IDLE)
        if (announce && mode == MascotMode.OFF) {
            settingsEggCard.showFor(MascotEgg.SULKING, 1800L)
            com.zcshou.gogogo.mascot.MascotEggStore.unlock(requireContext(), MascotEgg.SULKING)
        } else {
            settingsEggCard.hideImmediately()
        }
        if (announce && mode != MascotMode.OFF) {
            // 首次开启伴侣模式：解锁「初次见面」
            com.zcshou.gogogo.mascot.MascotEggStore.unlock(requireContext(), MascotEgg.WELCOME_ARMS)
            val line = MascotDialogueEngine.onSystemEvent(
                MascotDialogueEngine.State.IDLE,
                bypassCooldown = true
            )
            if (line != null) mascotPreview.showBubble(line)
        }
        refreshGalleryLabel()
    }

    private fun observeUpdateState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                UpdateManager.getInstance(requireContext()).state.collect { state ->
                    if (view == null) return@collect
                    updateRow.findViewById<TextView>(R.id.envSubtitle).text =
                        updateSubtitle(state)
                }
            }
        }
    }

    /** 全屏沉浸式：ScrollView 自己消化状态栏/导航栏 inset。 */
    private fun applyScrollInsets(root: View) {
        val scroll = root.findViewById<View>(R.id.settingsScroll)
        val scrim = root.findViewById<View>(R.id.settingsTopScrim)
        val edgeBlur = root.findViewById<ScrollEdgeBlurView>(R.id.settingsTopEdgeBlur)
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

    private fun refreshGlassOnScroll() {
        val now = SystemClock.uptimeMillis()
        if (now - lastGlassRefreshAt < 80) return
        lastGlassRefreshAt = now
        GlassRenderGate.requestRefresh(160L)
    }

    private fun onUpdateClick() {
        val context = requireContext()
        val manager = UpdateManager.getInstance(context)
        if (!UpdateConfig.enabled) {
            GlassFeedback.toast(context, "当前构建未配置更新源")
            return
        }
        val state = manager.state.value
        if (state.active ||
            state.phase == UpdateManager.Phase.PAUSED ||
            state.phase == UpdateManager.Phase.READY ||
            state.check == UpdateManager.Check.AVAILABLE ||
            state.check == UpdateManager.Check.CACHED
        ) {
            manager.showDownload()
        } else {
            manager.checkForUpdate(manual = true)
        }
    }

    private fun setupAdvanced(root: View) {
        bindRow(root, R.id.rowWifiScan, R.string.settings_wifi_scan) {
            GlassFeedback.toast(
                requireContext(),
                "可在系统「WLAN 高级设置」中关闭「始终允许扫描」",
                long = true
            )
            openIntent(Intent(Settings.ACTION_WIFI_SETTINGS))
        }

        bindRow(root, R.id.rowInjectInterval, R.string.settings_inject) {
            showInjectionIntervalDialog()
        }

        val shizukuRow = root.findViewById<View>(R.id.rowShizuku)
        shizukuRow.findViewById<TextView>(R.id.envTitle).text =
            getString(R.string.settings_shizuku)
        val switch = shizukuRow.findViewById<SwitchCompat>(R.id.advancedSwitch)
        switch.isChecked = AdvancedPrefs.isShizukuAutoWifiEnabled(requireContext())
        switch.setOnCheckedChangeListener { button, checked ->
            val ctx = context ?: return@setOnCheckedChangeListener
            if (checked && !EnvGuardManager.isShizukuReady()) {
                button.isChecked = false
                if (ShizukuWifiHelper.isAvailable()) {
                    ShizukuWifiHelper.requestPermission(7001)
                    GlassFeedback.toast(ctx, "请在 Shizuku 弹窗中允许授权后重试")
                } else {
                    GlassFeedback.toast(ctx, "未安装 Shizuku，无法启用该实验功能")
                }
                return@setOnCheckedChangeListener
            }
            AdvancedPrefs.setShizukuAutoWifiEnabled(ctx, checked)
        }

        // 计步守护：默认关闭。开启后开跑期间若虚拟里程在走、硬件步数长期不动，
        // 胶囊会提示「请放入口袋」。
        val stepRow = root.findViewById<View>(R.id.rowStepGuard)
        stepRow.findViewById<TextView>(R.id.envTitle).text =
            getString(R.string.settings_step_guard)
        stepRow.findViewById<TextView>(R.id.envSubtitle).text =
            getString(R.string.settings_step_guard_desc)
        val stepSwitch = stepRow.findViewById<SwitchCompat>(R.id.advancedSwitch)
        stepSwitch.isChecked = AdvancedPrefs.isStepGuardEnabled(requireContext())
        stepSwitch.setOnCheckedChangeListener { _, checked ->
            val ctx = context ?: return@setOnCheckedChangeListener
            AdvancedPrefs.setStepGuardEnabled(ctx, checked)
            GlassFeedback.toast(
                ctx,
                if (checked) "已开启计步守护：下次开跑生效" else "已关闭计步守护"
            )
        }
    }

    private fun showThemeDialog() {
        val context = requireContext()
        val modes = arrayOf(
            ThemeHelper.ThemeMode.DARK,
            ThemeHelper.ThemeMode.LIGHT,
            ThemeHelper.ThemeMode.SYSTEM
        )
        val labels = arrayOf("曜石黑（深色）", "日光白（浅色）", "跟随系统")
        val checked = modes.indexOf(ThemeHelper.themeMode(context)).coerceAtLeast(0)
        GlassDialogs.builder(context)
            .setTitle(R.string.settings_theme)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                ThemeHelper.applyThemeMode(context, modes[which])
                view?.post { if (isAdded) refresh() }
            }
            .show()
    }

    private fun showRenderQualityDialog() {
        val context = requireContext()
        val qualities = arrayOf(
            DisplayPrefs.RenderQuality.OPTICAL,
            DisplayPrefs.RenderQuality.BALANCED
        )
        val labels = arrayOf("液态玻璃（静态画布共享折射）", "省电平衡（零采样磨砂）")
        val checked = qualities.indexOf(DisplayPrefs.quality(context)).coerceAtLeast(0)
        GlassDialogs.builder(context)
            .setTitle(R.string.settings_render)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                DisplayPrefs.setQuality(context, qualities[which])
                // 立刻重算层级、作废旧渐变与旧画布快照，并顺延一次玻璃刷新节拍
                DeepRunGlassManager.refreshAll()
                GlassRenderGate.requestRefresh(320L)
                refresh()
                GlassFeedback.toast(
                    context,
                    "已切换：${DeepRunGlassManager.tier(context).title}"
                )
            }
            .show()
    }

    private fun pickTargetApp() {
        val context = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val apps = com.zcshou.gogogo.run.guard.InstalledAppHelper.loadLaunchableApps(context)
            if (!isAdded || view == null) return@launch
            val uiCtx = requireContext()
            val labels = (listOf("不绑定") + apps.map { it.second }).toTypedArray()
            val current = RunPrefs.targetApp(uiCtx)
            val checked = apps.indexOfFirst { it.first == current } + 1
            GlassDialogs.builder(uiCtx)
                .setTitle("开跑时自动拉起的目标 App")
                .setSingleChoiceItems(labels, if (checked > 0) checked else 0) { dialog, which ->
                    RunPrefs.setTargetApp(uiCtx, if (which == 0) null else apps[which - 1].first)
                    dialog.dismiss()
                    refresh()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun showInjectionIntervalDialog() {
        val context = requireContext()
        val current = AdvancedPrefs.injectionIntervalMs(context).toInt()
        val label = TextView(context).apply {
            text = "当前 ${current}ms"
            setTextColor(context.getColor(R.color.pf_text_primary))
            textSize = 13f
        }
        val seek = android.widget.SeekBar(context).apply {
            max = 800
            progress = current - 200
        }
        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
            addView(label)
            addView(seek)
        }
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                bar: android.widget.SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                label.text = "当前 ${200 + progress}ms"
            }

            override fun onStartTrackingTouch(bar: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: android.widget.SeekBar?) = Unit
        })
        GlassDialogs.builder(context)
            .setTitle(R.string.settings_inject)
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                AdvancedPrefs.setInjectionIntervalMs(context, (200 + seek.progress).toLong())
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun appLabel(pkg: String): String =
        com.zcshou.gogogo.run.guard.InstalledAppHelper.appLabel(requireContext(), pkg)

    private fun updateSubtitle(state: UpdateManager.State): String {
        val current = "当前版本 v${BuildConfig.VERSION_NAME}"
        return when {
            state.active -> "正在下载 ${state.progress}% · 点击查看"
            state.phase == UpdateManager.Phase.PAUSED ->
                "已暂停 ${state.progress}% · 点击继续"
            state.phase == UpdateManager.Phase.READY -> "安装包已就绪 · 点击安装"
            state.phase == UpdateManager.Phase.FAILED ->
                state.message.ifBlank { "下载失败 · 点击重试" }
            state.check == UpdateManager.Check.CHECKING -> "正在检查更新…"
            state.check == UpdateManager.Check.AVAILABLE ->
                "发现 v${state.manifest?.versionName} · 点击查看"
            state.check == UpdateManager.Check.UP_TO_DATE -> "已是最新 · v${BuildConfig.VERSION_NAME}"
            state.check == UpdateManager.Check.CACHED ->
                state.message.ifBlank { "显示上次验证的更新信息" }
            state.check == UpdateManager.Check.FAILED ->
                state.message.ifBlank { "检查失败 · 点击重试" }
            else -> "$current · 点击检查更新"
        }
    }

    private fun openIntent(intent: Intent) {
        runCatching { startActivity(intent) }
            .onFailure { GlassFeedback.toast(requireContext(), "跳转失败，请手动到系统设置中配置") }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}