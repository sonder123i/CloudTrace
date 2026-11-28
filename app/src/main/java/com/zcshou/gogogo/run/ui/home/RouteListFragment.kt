package com.zcshou.gogogo.run.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.zcshou.gogogo.R
import com.zcshou.gogogo.mascot.MascotDialogueEngine
import com.zcshou.gogogo.mascot.MascotEgg
import com.zcshou.gogogo.mascot.MascotEggCardView
import com.zcshou.gogogo.mascot.MascotMode
import com.zcshou.gogogo.mascot.MascotPeekView
import com.zcshou.gogogo.mascot.MascotPrefs
import com.zcshou.gogogo.mascot.MascotState
import com.zcshou.gogogo.run.BrandConfig
import com.zcshou.gogogo.run.data.db.RunRouteEntity
import com.zcshou.gogogo.run.data.db.RunScheduleEntity
import com.zcshou.gogogo.run.data.repository.RunRepository
import com.zcshou.gogogo.run.engine.RouteQrCodec
import com.zcshou.gogogo.run.engine.RouteShareCodec
import com.zcshou.gogogo.run.engine.RunPhase
import com.zcshou.gogogo.run.engine.TrackJsonCodec
import com.zcshou.gogogo.run.engine.TrackMathUtils
import com.zcshou.gogogo.run.guard.RunScheduleAlarmManager
import com.zcshou.gogogo.run.guard.RunShortcutManager
import com.zcshou.gogogo.run.guard.SetupGuard
import com.zcshou.gogogo.run.service.RunSessionHolder
import com.zcshou.gogogo.run.ui.editor.RouteEditorActivity
import com.example.liquidglass.ScrollEdgeBlurView
import com.zcshou.gogogo.run.ui.editor.RouteSources
import com.zcshou.gogogo.run.ui.glass.DeepRunGlassManager
import com.zcshou.gogogo.run.ui.glass.GlassDialogs
import com.zcshou.gogogo.run.ui.glass.GlassRenderGate
import com.zcshou.gogogo.run.ui.overlay.RouteThumbnailView
import com.zcshou.gogogo.run.ui.widget.LensChipTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Tab 1：云迹 —— 本周概览 + 横向路线切换条 + 当前主路线卡 + 路线工坊。
 */
class RouteListFragment : Fragment() {

    private companion object {
        const val PREFS = "pf_home"
        const val KEY_SELECTED = "selected_route_id"
    }

    private lateinit var repository: RunRepository
    private lateinit var emptyView: View
    private lateinit var mascot: MascotPeekView
    private lateinit var eggCard: MascotEggCardView
    private lateinit var mascotSpace: View
    private lateinit var miniMascot: com.zcshou.gogogo.mascot.MascotMiniBadgeView
    private lateinit var mainCard: View
    private lateinit var chipScroll: View
    private lateinit var chipScrollView: HorizontalScrollView
    private lateinit var chipRow: LinearLayout
    private lateinit var chipTrack: LensChipTrack
    private lateinit var weekKm: TextView
    private lateinit var weekCount: TextView
    private lateinit var weekGoalRing: com.zcshou.gogogo.run.ui.widget.WeeklyGoalRingView
    private lateinit var weekStreakBadge: TextView
    private var lastWeekMeters = 0.0
    private lateinit var setupBanner: TextView
    private lateinit var routeCountLabel: TextView
    private lateinit var routeListHeader: View
    private lateinit var workshopTitle: View
    private lateinit var workshopCard: View

    private lateinit var thumb: RouteThumbnailView
    private lateinit var nameText: TextView
    private lateinit var metaText: TextView
    private lateinit var scheduleText: TextView
    private lateinit var skipChip: TextView
    private lateinit var quickStartBtn: TextView
    private lateinit var shareBtn: View
    private lateinit var tuneBtn: View
    private lateinit var menuBtn: View

    private var routes: List<RunRouteEntity> = emptyList()
    private var schedules: List<RunScheduleEntity> = emptyList()
    private var selectedRouteId = 0L
    private var pendingExport: RunRouteEntity? = null
    private var lastRunPhase: RunPhase? = null
    private var lastStepsWarning = false
    private var lastGlassRefreshAt = 0L
    private var lastThumbKey: String? = null
    private var lastShortcutSignature: List<Pair<Long, String>>? = null
    private var thumbDecodeJob: kotlinx.coroutines.Job? = null

    private val qrImagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            handleQrImage(uri)
        }

    private val exportGpxLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->            val route = pendingExport
            pendingExport = null
            if (uri == null || route == null) return@registerForActivityResult
            val appContext = context?.applicationContext ?: return@registerForActivityResult
            val scope = viewLifecycleOwner.lifecycleScope
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val points = TrackJsonCodec.decode(route.denseWaypointsJson)
                        // 有真实海拔剖面就写进 <ele>，让导出的 GPX 在其它软件里也带地形
                        val profile = RouteSources.parseElevationProfileJson(
                            route.elevationProfileJson
                        )?.takeIf { it.size == points.size }
                        val gpx = RouteSources.toGpx(route.name, points, profile)
                        appContext.contentResolver.openOutputStream(uri)?.use {
                            it.write(gpx.toByteArray(Charsets.UTF_8))
                        } ?: error("empty stream")
                    }.isSuccess
                }
                if (isAdded) {
                    toast(if (ok) "已导出 GPX：${route.name}" else "GPX 导出失败")
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_route_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        repository = RunRepository(requireContext())
        emptyView = view.findViewById(R.id.routeEmpty)
        mascot = view.findViewById(R.id.homeMascot)
        // 首页上方紧挨周统计，气泡放下面，只临时遮住卡片的装饰鱼，不挡数据。
        mascot.setBubbleBelow(true)
        eggCard = view.findViewById(R.id.homeEggCard)
        mascotSpace = view.findViewById(R.id.homeMascotSpace)
        miniMascot = view.findViewById(R.id.homeMiniMascot)
        mascot.onPokeCombo = {
            eggCard.showFor(MascotEgg.POKE_PRESSURE, 2500L)
            com.zcshou.gogogo.mascot.MascotEggStore.unlock(requireContext(), MascotEgg.POKE_PRESSURE)
        }
        miniMascot.onPokeCombo = {
            eggCard.showFor(MascotEgg.POKE_PRESSURE, 2500L)
            com.zcshou.gogogo.mascot.MascotEggStore.unlock(requireContext(), MascotEgg.POKE_PRESSURE)
        }
        mainCard = view.findViewById(R.id.routeMainCard)
        chipScroll = view.findViewById(R.id.routeChipScroll)
        chipScrollView = view.findViewById(R.id.routeChipScrollView)
        chipRow = view.findViewById(R.id.routeChipRow)
        chipTrack = LensChipTrack(chipRow)
        weekKm = view.findViewById(R.id.weekKm)
        weekCount = view.findViewById(R.id.weekCount)
        weekGoalRing = view.findViewById(R.id.weekGoalRing)
        weekStreakBadge = view.findViewById(R.id.weekStreakBadge)
        setupBanner = view.findViewById(R.id.setupBanner)
        routeCountLabel = view.findViewById(R.id.routeCountLabel)
        routeListHeader = view.findViewById(R.id.routeListHeader)
        workshopTitle = view.findViewById(R.id.routeWorkshopTitle)
        workshopCard = view.findViewById(R.id.workshopCard)

        thumb = mainCard.findViewById(R.id.routeThumb)
        nameText = mainCard.findViewById(R.id.routeName)
        metaText = mainCard.findViewById(R.id.routeMeta)
        scheduleText = mainCard.findViewById(R.id.routeSchedule)
        skipChip = mainCard.findViewById(R.id.routeSkipChip)
        quickStartBtn = mainCard.findViewById(R.id.btnQuickStart)
        shareBtn = mainCard.findViewById(R.id.btnShareCode)
        tuneBtn = mainCard.findViewById(R.id.btnTune)
        menuBtn = mainCard.findViewById(R.id.routeMenu)

        selectedRouteId = requireContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_SELECTED, 0L)

        applyScrollInsets(view)

        view.findViewById<TextView>(R.id.btnNewRoute).setOnClickListener {
            openEditor(RouteEditorActivity.MODE_PRESET)
        }
        view.findViewById<TextView>(R.id.btnQuickPreset).setOnClickListener {
            openEditor(RouteEditorActivity.MODE_PRESET)
        }
        view.findViewById<TextView>(R.id.btnQuickFree).setOnClickListener {
            openEditor(RouteEditorActivity.MODE_FREE)
        }
        view.findViewById<TextView>(R.id.btnEmptyCreate).setOnClickListener {
            openEditor(RouteEditorActivity.MODE_PRESET)
        }
        view.findViewById<View>(R.id.btnImportShare).setOnClickListener { importShareCode() }
        view.findViewById<View>(R.id.btnEmptyImport).setOnClickListener { importShareCode() }
        view.findViewById<View>(R.id.btnEmptyUsage).setOnClickListener {
            runCatching {
                startActivity(UsageGuideActivity.intent(requireContext()))
            }
        }
        setupBanner.setOnClickListener {
            // 一步一屏的引导比完整向导更「无脑」：点一下就直接进当前该做的那一步。
            runCatching {
                startActivity(SetupGuideActivity.intent(requireContext()))
            }.onFailure {
                (activity as? RunHomeActivity)?.openSetupWizard()
            }
        }
        view.findViewById<View>(R.id.weekCard).setOnClickListener {
            HistoryReportDialog.show(requireContext(), repository)
        }
        view.findViewById<View>(R.id.weekCard).setOnLongClickListener {
            showWeeklyGoalDialog()
            true
        }
        view.findViewById<View>(R.id.homeSupportCard).setOnClickListener {
            // 主动点进赞赏页等同于已经知道入口，之后不再弹邀请打扰。
            SupportPrompt.optOut(requireContext())
            runCatching { startActivity(SupportActivity.intent(requireContext())) }
        }
        refreshSetupBanner()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    repository.observeRoutes().collect {
                        routes = it
                        val signature = it.take(4).map { r -> r.id to r.name }
                        if (signature != lastShortcutSignature) {
                            lastShortcutSignature = signature
                            val appCtx = requireContext().applicationContext
                            launch(Dispatchers.IO) {
                                RunShortcutManager.syncShortcuts(appCtx, it)
                            }
                        }
                        render()
                    }
                }
                launch {
                    repository.observeSchedules().collect {
                        schedules = it
                        render()
                    }
                }
                launch {
                    val weekStart = weekStartMillis()
                    combine(
                        repository.observeCompletedMetersSince(weekStart),
                        repository.observeCompletedCountSince(weekStart)
                    ) { meters, count -> meters to count }
                        .collect { (meters, count) ->
                            lastWeekMeters = meters
                            weekKm.text = String.format(Locale.US, "%.2f km", meters / 1000.0)
                            weekCount.text = "$count 次"
                            refreshWeekGoal()
                        }
                }
                launch {
                    // 连续打卡徽章：同一天多次只算一天；1 天不显示（没有信息量）
                    repository.observeCompletedTimestamps().collect { timestamps ->
                        val samples = timestamps.map {
                            RunStatsCalculator.DaySample(it, 0.0, true)
                        }
                        val streak = RunStatsCalculator.currentStreak(
                            samples,
                            System.currentTimeMillis()
                        )
                        if (streak >= 2) {
                            weekStreakBadge.visibility = View.VISIBLE
                            weekStreakBadge.text = "🔥 ${streak}天"
                        } else {
                            weekStreakBadge.visibility = View.GONE
                        }
                    }
                }
                launch {
                    // V4.0：声呐只在相位切换时触发（state 每个 tick 都会发新值，
                    // 直接 collect 会让 5s 扫描每秒被重置）。省电冻结由 RunSessionService 统一写入。
                    RunSessionHolder.state.collect { state ->
                        // 步数告警：鲸鲸同步切成调侃提醒（只在翻转时刷新，避免每拍重绑）
                        if (state.stepsWarning != lastStepsWarning) {
                            lastStepsWarning = state.stepsWarning
                            refreshMascot()
                            if (state.stepsWarning && MascotPrefs.mode(requireContext()) != MascotMode.OFF) {
                                miniMascot.showBubble("哦鲸鲸…手机没在兜里吧？步数没动哦")
                            }
                        }
                        if (state.phase == lastRunPhase) return@collect
                        lastRunPhase = state.phase
                        when (state.phase) {
                            RunPhase.PRE_START_SETTLE -> {
                                thumb.startSonar(5000)
                                thumb.setCometRunning(false)
                            }
                            RunPhase.RUNNING,
                            RunPhase.PAUSED,
                            RunPhase.DETOUR_POINT,
                            RunPhase.COOLDOWN_WALK,
                            RunPhase.ENV_GUARD -> {
                                thumb.stopSonar()
                                thumb.setCometRunning(false)
                            }
                            else -> {
                                thumb.stopSonar()
                                thumb.setCometRunning(true)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSetupBanner()
        // 页面可见时恢复巡航光点；相位为巡航中则不启动（0fps）
        val phase = lastRunPhase
        thumb.setCometRunning(phase == null || phase == RunPhase.IDLE || phase == RunPhase.FINISHED)
        refreshMascot()
    }

    override fun onPause() {
        if (::mascot.isInitialized) mascot.pause()
        if (::miniMascot.isInitialized) miniMascot.pause()
        thumb.setCometRunning(false)
        super.onPause()
    }

    /**
     * 首页全屏沉浸式布局：
     * ScrollView 自己消化状态栏与导航栏 inset，内容可以滚到状态栏/悬浮导航下方，
     * 由底部玻璃导航栏产生真实的模糊透视；同时保证最后一项不被导航栏遮挡。
     */
    private fun applyScrollInsets(root: View) {
        val scroll = root.findViewById<View>(R.id.homeScroll)
        val scrim = root.findViewById<View>(R.id.topScrim)
        val edgeBlur = root.findViewById<ScrollEdgeBlurView>(R.id.topEdgeBlur)
        edgeBlur.edge = ScrollEdgeBlurView.Edge.TOP
        edgeBlur.maxBlurRadius = 22f * resources.displayMetrics.density
        edgeBlur.bindScrollView(scroll)
        val baseTop = dp(6)
        // 与 LiquidTabBar.NavMetrics.contentInsetDp 同源：96dp 是 20:9 手势条下的基线，
        // 各页再各自加 bars.bottom。改轨道几何必须同步这里。
        val baseBottom = dp(96)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            target.setPadding(
                target.paddingLeft,
                baseTop + bars.top,
                target.paddingRight,
                baseBottom + bars.bottom
            )
            // 模糊带比状态栏略高：静止时隐藏，滚动后内容进入该带才会得到完整的渐进虚化
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
        scroll.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            // 静止时大标题保持清晰；一旦内容开始滚入状态栏再开启 Scroll Edge 模糊
            edgeBlur.visibility = if (scrollY > dp(6)) View.VISIBLE else View.INVISIBLE
            refreshGlassOnScroll()
            (activity as? RunHomeActivity)?.onHomeScrolled(scrollY - oldScrollY, scrollY)
        }
        ViewCompat.requestApplyInsets(scroll)
    }

    /**
     * 滚动时让玻璃重新采样背景：底部导航与页面卡片会实时糊住滑过的内容，
     * 80ms 节流避免每个滚动帧都做一次整窗快照。
     */
    private fun refreshGlassOnScroll() {
        val now = SystemClock.uptimeMillis()
        if (now - lastGlassRefreshAt < 80) return
        lastGlassRefreshAt = now
        // 与编辑器 / 自由漫游的 onMapStatusChange 同款：滚动期间顺延玻璃刷新窗口
        GlassRenderGate.requestRefresh(160L)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            if (::mascot.isInitialized) mascot.pause()
            if (::miniMascot.isInitialized) miniMascot.pause()
            thumb.setCometRunning(false)
        } else {
            refreshSetupBanner()
            val phase = lastRunPhase
            thumb.setCometRunning(
                phase == null || phase == RunPhase.IDLE || phase == RunPhase.FINISHED
            )
            refreshMascot()
        }
    }

    fun refreshSetupBanner() {
        if (!::setupBanner.isInitialized) return
        val state = SetupGuard.check(requireContext())
        setupBanner.visibility = if (state.ready) View.GONE else View.VISIBLE
        if (!state.ready) {
            // 「3 项」而不是「授权」：其中开发者选项并不是权限，叫「授权」会让用户
            // 去系统权限页找，找不到就放弃了。统一成「配置」。
            setupBanner.text = "还差 ${state.missing.size} 项基础配置 · 点此完成 >"
        }
    }

    private fun weekStartMillis(): Long = Calendar.getInstance().apply {
        firstDayOfWeek = Calendar.MONDAY
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 周目标环：达成率由本机周里程 / RunPrefs.weeklyGoalKm 计算 */
    private fun refreshWeekGoal() {
        if (!::weekGoalRing.isInitialized) return
        val goal = com.zcshou.gogogo.run.data.prefs.RunPrefs.weeklyGoalKm(requireContext())
        weekGoalRing.setProgress(RunStatsCalculator.weeklyGoalRatio(lastWeekMeters, goal))
        weekGoalRing.contentDescription = String.format(
            Locale.US,
            "周目标 %.1f km，已完成 %.2f km",
            goal,
            lastWeekMeters / 1000.0
        )
    }

    /** 长按周概览条：直接改本周跑量目标 */
    private fun showWeeklyGoalDialog() {
        val context = requireContext()
        val current = com.zcshou.gogogo.run.data.prefs.RunPrefs.weeklyGoalKm(context)
        val input = EditText(context).apply {
            com.zcshou.gogogo.run.ui.widget.DialogStyler.styleInput(this)
            setText(String.format(Locale.US, "%.0f", current))
            setSelection(text.length)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        GlassDialogs.builder(context)
            .setTitle("本周跑量目标（公里）")
            .setMessage("只影响本机的周进度环，不上传服务器")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val km = input.text.toString().toFloatOrNull()?.coerceIn(1f, 200f)
                if (km == null) {
                    toast("请输入 1~200 之间的公里数")
                    return@setPositiveButton
                }
                com.zcshou.gogogo.run.data.prefs.RunPrefs.setWeeklyGoalKm(context, km)
                refreshWeekGoal()
                toast(String.format(Locale.US, "周目标已设为 %.1f km", km))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun render() {
        val selected = routes.firstOrNull { it.id == selectedRouteId } ?: routes.firstOrNull()
        if (selected != null && selected.id != selectedRouteId) {
            selectedRouteId = selected.id
            persistSelection()
        }
        val empty = routes.isEmpty()
        routeCountLabel.text = if (empty) "尚未添加" else "${routes.size} 条路线"
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        mainCard.visibility = if (selected == null) View.GONE else View.VISIBLE
        chipScroll.visibility = if (empty) View.GONE else View.VISIBLE
        // 空状态卡片已经承载了「新建 / 导入口令」两个入口，重复入口全部收起
        routeListHeader.visibility = if (empty) View.GONE else View.VISIBLE
        workshopTitle.visibility = if (empty) View.GONE else View.VISIBLE
        workshopCard.visibility = if (empty) View.GONE else View.VISIBLE
        renderChips(selected?.id ?: 0L)
        selected?.let { bindMainCard(it) }
        refreshMascot()
    }

    /**
     * 鲸鲸在场规则：
     * - 空状态（还没有路线）：保留 72dp 大探头，作为「快建条路线」的情感入口；
     * - 有路线：大探头收起，但大标题旁的 32dp 迷你角标常驻 —— 历史实现只判断
     *   `emptyView.visibility == VISIBLE`，导致用户建了第一条路线后鲸鲸彻底消失。
     * - `MascotMode.OFF`：两者都 GONE 且不占位。
     */
    private fun refreshMascot() {
        if (!isAdded || !::mascot.isInitialized) return
        val context = requireContext()
        val mode = MascotPrefs.mode(context)
        val empty = emptyView.visibility == View.VISIBLE
        val showBig = mode != MascotMode.OFF && empty

        // 固定 72dp 透明占位：鲸鲸落在这个区域内才收得到触摸，卡片只下移一个身高。
        mascotSpace.visibility = if (showBig) View.VISIBLE else View.GONE
        if (!showBig) {
            eggCard.hideImmediately()
            mascot.bind(MascotMode.OFF, MascotState.PEEK_IDLE)
        }

        val dialogue = dialogueState()
        val visual = if (MascotDialogueEngine.isLateNight()) {
            MascotState.SLEEP
        } else {
            MascotState.PEEK_IDLE
        }
        if (showBig) {
            mascot.bind(mode, visual, MascotDialogueEngine.contextState(dialogue))
        }
        // 迷你角标：有路线时才出现，与大探头互斥，避免同屏两只鲸
        if (mode != MascotMode.OFF && !empty) {
            miniMascot.bind(mode, MascotDialogueEngine.contextState(dialogue), visual)
        } else {
            miniMascot.bind(MascotMode.OFF, dialogue)
        }
    }

    /** 台词语境：环境未就绪 > 步数告警 > 深夜 > 待机 */
    private fun dialogueState(): MascotDialogueEngine.State {
        val context = requireContext()
        return when {
            !SetupGuard.isReady(context) -> MascotDialogueEngine.State.WARNING_ENV
            lastStepsWarning -> MascotDialogueEngine.State.LAZY
            else -> MascotDialogueEngine.State.IDLE
        }
    }

    private fun renderChips(selectedId: Long) {
        // 选中态切换时复用已有 chip，不触发重新布局，透镜弹簧才能连续滑动
        val reuse = chipRow.childCount == routes.size && routes.isNotEmpty()
        if (!reuse) chipRow.removeAllViews()
        val chips = ArrayList<TextView>(routes.size)
        var selectedIndex = 0
        routes.forEachIndexed { index, route ->
            val on = route.id == selectedId
            if (on) selectedIndex = index
            val chip = if (reuse) {
                chipRow.getChildAt(index) as TextView
            } else {
                TextView(requireContext()).apply {
                    textSize = 11f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setPadding(dp(13), dp(7), dp(13), dp(7))
                    // 选中透镜由 LensChipTrack 绘制，chip 自身只保留曜石黑 / 纯白字色
                    background = null
                    setOnClickListener { v ->
                        val id = v.tag as? Long ?: return@setOnClickListener
                        if (selectedRouteId == id) return@setOnClickListener
                        selectedRouteId = id
                        persistSelection()
                        render()
                    }
                    // 长按胶囊直接弹出该路线的快捷菜单（置顶 / 编辑 / 改标签 / 删除）
                    setOnLongClickListener { v ->
                        val id = v.tag as? Long ?: return@setOnLongClickListener false
                        val route = routes.firstOrNull { it.id == id }
                            ?: return@setOnLongClickListener false
                        if (route.id != selectedRouteId) {
                            selectedRouteId = route.id
                            persistSelection()
                            render()
                        }
                        showRouteMenu(route)
                        true
                    }
                }
            }
            chip.tag = route.id
            // 置顶路线芯片左侧带一颗小星标，一眼分辨组织顺序
            chip.text = if (route.isPinned) {
                "★ ${chipLabel(route.name, route.singleLapMeters)}"
            } else {
                chipLabel(route.name, route.singleLapMeters)
            }
            chip.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (on) R.color.pf_volt else R.color.pf_text_secondary
                )
            )
            chip.setTypeface(
                null,
                if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
            if (!reuse) {
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
                chipRow.addView(chip, lp)
            }
            chips.add(chip)
        }
        chipTrack.bind(chips, selectedIndex, animate = reuse)
        // 让选中的路线透镜完整落在轨道可视区内（两侧各预留 16dp，避免贴边截断）
        val selectedChip = chips.getOrNull(selectedIndex)
        if (selectedChip != null) {
            chipRow.post {
                val viewport = chipScrollView.width
                if (viewport <= 0) return@post
                val trailing = dp(16)
                val current = chipScrollView.scrollX
                val target = when {
                    selectedChip.left < current ->
                        (selectedChip.left - trailing).coerceAtLeast(0)
                    selectedChip.right + trailing > current + viewport ->
                        selectedChip.right + trailing - viewport
                    else -> current
                }
                val maxScroll = (chipRow.width - viewport).coerceAtLeast(0)
                chipScrollView.smoothScrollTo(target.coerceIn(0, maxScroll), 0)
            }
        }
    }

    private fun bindMainCard(route: RunRouteEntity) {
        val schedule = schedules.firstOrNull { it.routeId == route.id }
        // V4.1：示范路线标题统一精简为「400m 标准田径场」，兼容旧库中带「（示范）」「· 示范」的名字，
        // 单行不再出现省略号截断。
        nameText.text = if (route.buildMode == "DEMO") {
            route.name
                .replace(" · 示范", "")
                .replace("（示范）", "")
                .replace("(示范)", "")
                .trim()
        } else {
            route.name
        }
        metaText.text = String.format(
            Locale.US,
            "单圈 %.0f m · %s · %s",
            route.singleLapMeters,
            when (route.buildMode) {
                "ROAD_SNAP" -> "路网贴合"
                "GPX_IMPORT" -> "GPX 导入"
                "ROAM_RECORD" -> "漫游录制"
                "PRESET_TRACK" -> "操场套模"
                "FREE_DRAW" -> "自由手绘"
                "DEMO" -> "示范跑道"
                "SHARE_CODE" -> "口令导入"
                else -> "逐点连线"
            },
            if (route.isClockwise) "顺时针" else "逆时针"
        )
        val thumbKey = "${route.id}:${route.denseWaypointsJson.hashCode()}:${route.denseWaypointsJson.length}"
        if (thumbKey != lastThumbKey) {
            lastThumbKey = thumbKey
            thumbDecodeJob?.cancel()
            val json = route.denseWaypointsJson
            thumbDecodeJob = viewLifecycleOwner.lifecycleScope.launch {
                val decoded = withContext(Dispatchers.Default) { TrackJsonCodec.decode(json) }
                if (isAdded && view != null) {
                    thumb.setRoute(decoded)
                }
            }
        }
        quickStartBtn.text = String.format(
            Locale.US,
            "开始跑圈 · %.1f km · %d'%02d\"",
            route.lastTargetDistanceKm,
            route.lastTargetPaceSecPerKm / 60,
            route.lastTargetPaceSecPerKm % 60
        )
        scheduleText.text = scheduleLine(schedule)
        bindSkipChip(route, schedule)
        quickStartBtn.setOnClickListener { quickStart(route) }
        (quickStartBtn as? com.zcshou.gogogo.run.ui.widget.SpecularButton)
            ?.sonarOnLongPress = true
        shareBtn.setOnClickListener { shareRouteCode(route) }
        mainCard.findViewById<View>(R.id.routeHeader).setOnClickListener {
            openRoutePreview(route)
        }
        tuneBtn.setOnClickListener {
            QuickStartBottomSheet.newInstance(route.id, route.name)
                .show(parentFragmentManager, "quick_start")
        }
        scheduleText.setOnClickListener { showScheduleDialog(route, schedule) }
        menuBtn.setOnClickListener { showRouteMenu(route) }
    }

    /**
     * 定时摘要：重复日 + 下一次的真实倒计时。
     * 用 jitterMinutes = 0 取最早候选时刻做展示（实际触发会再加 0..N 分钟随机延迟），
     * 避免每次重新渲染都随机抖动一分钟以上。
     */
    private fun scheduleLine(schedule: RunScheduleEntity?): String {
        if (schedule?.isScheduleEnabled != true) return "定时：未配置（点击设置）"
        val next = RunScheduleAlarmManager
            .computeNextTrigger(schedule, jitterMinutes = 0)
        val countdown = RunScheduleAlarmManager
            .formatNextCountdown(next, System.currentTimeMillis())
        val days = RunScheduleAlarmManager
            .describeRepeatDays(schedule.repeatDaysMask)
        // 前置时钟图标已经表达「定时」，这里省掉前缀，保证单行不折行
        val jitter = if (schedule.windowJitterMinutes > 0) {
            " ±${schedule.windowJitterMinutes}min"
        } else {
            ""
        }
        return "$days$jitter · $countdown"
    }

    /** 「跳过本次 / 已跳过今天」芯片：只对当前启用的定时计划生效 */
    private fun bindSkipChip(route: RunRouteEntity, schedule: RunScheduleEntity?) {
        if (schedule?.isScheduleEnabled != true) {
            skipChip.visibility = View.GONE
            return
        }
        skipChip.visibility = View.VISIBLE
        val skippedToday = schedule.skipDateIso ==
            RunScheduleAlarmManager.todayIso()
        skipChip.text = if (skippedToday) "已跳过今天" else "跳过本次"
        skipChip.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (skippedToday) R.color.pf_text_primary else R.color.pf_text_secondary
            )
        )
        skipChip.setOnClickListener { toggleSkipToday(route, schedule, skippedToday) }
    }

    private fun toggleSkipToday(
        route: RunRouteEntity,
        schedule: RunScheduleEntity,
        currentlySkipped: Boolean
    ) {
        val context = requireContext().applicationContext
        val updated = if (currentlySkipped) {
            schedule.copy(skipDateIso = null)
        } else {
            schedule.copy(
                skipDateIso = RunScheduleAlarmManager.todayIso()
            )
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repository.updateSchedule(updated)
            // 立即重排系统闹钟：跳过今天时下一次会顺延到下一个允许日
            val next = RunScheduleAlarmManager
                .scheduleNext(context, updated)
            val message = when {
                currentlySkipped -> "已恢复今天的定时"
                next == null -> "已跳过今天这一次"
                else -> "已跳过今天这一次，下次 ${formatTime(next)}"
            }
            if (isAdded) toast(message)
        }
    }

    private fun openRoutePreview(route: RunRouteEntity) {
        startActivity(
            Intent(requireContext(), RoutePreviewActivity::class.java)
                .putExtra(RoutePreviewActivity.EXTRA_ROUTE_ID, route.id)
        )
    }

    private fun showRouteMenu(route: RunRouteEntity) {
        val popup = PopupMenu(requireContext(), menuBtn)
        popup.menu.add(0, 6, 0, "查看路线")
        popup.menu.add(0, 7, 1, "编辑路线")
        popup.menu.add(
            0, 8, 2,
            if (route.isPinned) "取消置顶" else "置顶到最前"
        )
        popup.menu.add(0, 9, 3, "改分类标签")
        popup.menu.add(0, 1, 4, "重命名")
        popup.menu.add(0, 2, 5, "复制一份")
        popup.menu.add(0, 3, 6, "导出 GPX 备份")
        popup.menu.add(0, 5, 7, getString(R.string.route_share_copy, BrandConfig.SHARE_SCHEME))
        popup.menu.add(0, 4, 8, "删除路线")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                6 -> openRoutePreview(route)
                7 -> editRoute(route)
                8 -> toggleRoutePinned(route)
                9 -> editRouteTag(route)
                1 -> renameRoute(route)
                2 -> duplicateRoute(route)
                3 -> exportRoute(route)
                4 -> deleteRouteWithUndo(route)
                5 -> copyShareCode(route)
            }
            true
        }
        popup.show()
    }

    /** 置顶 / 取消置顶：只改单列，置顶路线在 DAO 排序里永远靠前 */
    private fun toggleRoutePinned(route: RunRouteEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            repository.setRoutePinned(route.id, !route.isPinned)
            if (isAdded) {
                toast(if (route.isPinned) "已取消置顶" else "已置顶「${route.name}」")
            }
        }
    }

    /** 分类标签：操场 / 公园 / 校园 / 测试，或任意 6 字内自定义 */
    private fun editRouteTag(route: RunRouteEntity) {
        val presets = arrayOf("操场", "公园", "校园", "测试", "不设置")
        val input = EditText(requireContext()).apply {
            com.zcshou.gogogo.run.ui.widget.DialogStyler.styleInput(this)
            setText(route.tag)
            setSelection(text.length)
            hint = "如「公园」，最多 6 字"
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        GlassDialogs.builder(requireContext())
            .setTitle("分类标签 · ${route.name}")
            .setSingleChoiceItems(presets, -1) { _, which ->
                val tag = if (which == presets.lastIndex) "" else presets[which]
                applyRouteTag(route, tag)
            }
            .setView(input)
            .setPositiveButton("保存自定义") { _, _ ->
                applyRouteTag(route, input.text.toString().trim().take(6))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyRouteTag(route: RunRouteEntity, tag: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            repository.setRouteTag(route.id, tag)
            if (isAdded) {
                toast(if (tag.isEmpty()) "已清除标签" else "标签已设为「$tag」")
            }
        }
    }

    /** 二次编辑：把既有路线载回编辑器微调节点 / 切起点 / 反转方向 */
    private fun editRoute(route: RunRouteEntity) {
        runCatching {
            startActivity(RouteEditorActivity.editIntent(requireContext(), route.id))
        }.onFailure {
            toast("无法打开编辑器")
        }
    }

    private fun renameRoute(route: RunRouteEntity) {
        val input = EditText(requireContext()).apply {
            com.zcshou.gogogo.run.ui.widget.DialogStyler.styleInput(this)
            setText(route.name)
            setSelection(text.length)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        GlassDialogs.builder(requireContext())
            .setTitle("重命名路线")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch {
                    repository.updateRoute(route.copy(name = name))
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun duplicateRoute(route: RunRouteEntity) {
        val name = uniqueCopyName(route.name)
        viewLifecycleOwner.lifecycleScope.launch {
            val id = repository.saveRoute(
                route.copy(id = 0, name = name, createdAt = System.currentTimeMillis())
            )
            selectedRouteId = id
            persistSelection()
            toast("已复制为「$name」")
        }
    }

    private fun uniqueCopyName(base: String): String {
        val sibling = routes.count { it.name == "$base 副本" || it.name.startsWith("$base 副本") }
        return if (sibling == 0) "$base 副本" else "$base 副本${sibling + 1}"
    }

    private fun exportRoute(route: RunRouteEntity) {
        pendingExport = route
        val safe = route.name.replace(Regex("""[\\/:*?"<>|]"""), "_").take(40)
        val stamp = SimpleDateFormat("MMdd", Locale.US).format(Date())
        exportGpxLauncher.launch("$safe-$stamp.gpx")
    }

    private fun copyShareCode(route: RunRouteEntity) {
        val code = routeShareCode(route)
        if (code == null) {
            toast("轨迹数据不足，无法生成口令")
            return
        }
        // 口令与二维码二选一：同一条路线既能发文字，也能给对方扫图
        GlassDialogs.builder(requireContext())
            .setTitle("分享口令 · ${route.name}")
            .setItems(arrayOf("复制口令文本", "显示二维码卡片")) { _, which ->
                if (which == 0) {
                    if (copyShareText(route, code)) {
                        toast("口令已复制，好友粘贴到「导入口令」即可导入")
                    } else {
                        toast("口令复制失败")
                    }
                } else {
                    showRouteQrDialog(route, code)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 二维码卡片：离屏生成 Bitmap 后塞进弹窗；口令超长时回退提示，
     * 绝不硬挤成一张糊图（对方扫不出来更糟）。
     */
    private fun showRouteQrDialog(route: RunRouteEntity, code: String) {
        val view = layoutInflater.inflate(R.layout.dialog_route_qr, null)
        val qrImage = view.findViewById<android.widget.ImageView>(R.id.qrImage)
        val fallback = view.findViewById<android.widget.TextView>(R.id.qrFallback)
        val caption = view.findViewById<android.widget.TextView>(R.id.qrCaption)
        val dialog = GlassDialogs.builder(requireContext())
            .setTitle("路线二维码")
            .setView(view)
            .setNegativeButton("关闭", null)
            .create()
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                runCatching { RouteQrCodec.encode(code) }.getOrNull()
            }
            if (!isAdded) return@launch
            if (bitmap == null) {
                qrImage.visibility = View.GONE
                fallback.visibility = View.VISIBLE
                caption.text = String.format(Locale.US, "口令 %d 字符 · 超出二维码容量", code.length)
            } else {
                qrImage.setImageBitmap(bitmap)
                caption.text = String.format(
                    Locale.US,
                    "%s · 单圈 %.0f m · 口令 %d 字符",
                    if (route.isClockwise) "顺时针" else "逆时针",
                    route.singleLapMeters,
                    code.length
                )
            }
        }
        dialog.show()
    }

    /** 路线卡上的显式分享入口：直接拉起系统分享面板，发给微信 / QQ 等任意应用 */
    private fun shareRouteCode(route: RunRouteEntity) {
        val code = routeShareCode(route)
        if (code == null) {
            toast("轨迹数据不足，无法生成口令")
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "${getString(R.string.app_name)}跑圈口令 · ${route.name}")
            putExtra(Intent.EXTRA_TEXT, shareMessage(route, code))
        }
        val opened = runCatching {
            startActivity(Intent.createChooser(send, "分享跑圈口令"))
        }.isSuccess
        if (!opened) {
            if (copyShareText(route, code)) {
                toast("没有可分享的应用，口令已复制")
            } else {
                toast("没有可分享的应用，口令复制失败")
            }
        }
    }

    private fun routeShareCode(route: RunRouteEntity): String? {
        val points = TrackJsonCodec.decode(route.denseWaypointsJson)
        if (points.size < 2) return null
        return runCatching { RouteShareCodec.encode(route.name, points, route.isClockwise) }.getOrNull()
    }

    /** 分享文案带一句使用说明，好友复制整段即可被 [RouteShareCodec.decode] 识别 */
    private fun shareMessage(route: RunRouteEntity, code: String): String = buildString {
        val app = getString(R.string.app_name)
        append("【$app】跑圈路线「${route.name}」\n")
        append("复制整段口令，打开${app}首页点「导入口令」粘贴即可导入（不上传服务器）：\n")
        append(code)
    }

    private fun copyShareText(route: RunRouteEntity, code: String): Boolean {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(
            "${BrandConfig.SHARE_SCHEME} 路线口令",
            shareMessage(route, code)
        )
        // 口令内含完整路线坐标（位置历史），API 33+ 标记为敏感内容，避免被剪贴板读取方顺带收割
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = android.os.PersistableBundle(1).apply {
                putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        return runCatching { cm.setPrimaryClip(clip) }.isSuccess
    }

    private fun importShareCode() {
        // 好友口令常见路径是「聊天里复制整段 → 打开 App 导入」，先自动读取剪贴板省一步粘贴。
        val clipCode = readClipboardShareCode()
        val input = EditText(requireContext()).apply {
            com.zcshou.gogogo.run.ui.widget.DialogStyler.styleInput(this)
            hint = "粘贴 ${BrandConfig.SHARE_PREFIX}... 口令"
            if (clipCode != null) {
                setText(clipCode)
                setSelection(text.length)
            }
            setPadding(dp(16), dp(8), dp(16), dp(8))
            maxLines = 5
        }
        GlassDialogs.builder(requireContext())
            .setTitle("导入跑圈口令")
            .setMessage(
                if (clipCode != null) {
                    "已读取剪贴板中的口令，点「导入路线」即可。口令只包含路线坐标与名称，不上传服务器"
                } else {
                    "直接粘贴好友发来的整段聊天内容即可。口令只包含路线坐标与名称，不上传服务器"
                }
            )
            .setView(input)
            .setNeutralButton("从相册选二维码") { _, _ -> pickQrFromGallery() }
            .setPositiveButton("导入路线") { _, _ ->
                importShareText(input.text.toString())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 相册选图 → 解码二维码：GetContent 走系统选择器，零相机权限、零联网 */
    private fun pickQrFromGallery() {
        runCatching {
            qrImagePicker.launch("image/*")
        }.onFailure {
            toast("无法打开图片选择器")
        }
    }

    /** 从选中的图片里解出二维码口令并导入 */
    private fun handleQrImage(uri: android.net.Uri?) {
        if (uri == null) return
        val appContext = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val payload = withContext(Dispatchers.IO) {
                runCatching {
                    decodeQrSourceBitmap(appContext, uri)?.let { RouteQrCodec.decode(it) }
                }.getOrNull()
            }
            if (!isAdded) return@launch
            if (payload == null) {
                toast("这张图里没找到可识别的路线二维码")
                return@launch
            }
            importShareText(payload)
        }
    }

    /**
     * 相册图片先探尺寸再按需降采样解码：系统相册选择器可能选到 48MP 大图，
     * 直接整图解码是 ~190MB 的 ARGB_8888 分配，低内存设备会触发内存压力甚至 LMK。
     * 二维码在 2048px 内配合 TRY_HARDER 足够解出（RouteQrCodec 内部还有二次缩放兜底）。
     */
    private fun decodeQrSourceBitmap(
        context: android.content.Context,
        uri: android.net.Uri
    ): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) {
            sample *= 2
        }
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        }
    }

    /** 口令导入的唯一落库入口：文本粘贴与二维码解码共用同一套校验 */
    private fun importShareText(raw: String) {
        val shared = RouteShareCodec.decode(raw)
        if (shared == null) {
            toast("口令无效或已损坏，请粘贴完整口令")
            return
        }
        if (shared.points.size > 2000) {
            toast("路线点数过多，无法导入")
            return
        }
        val lap = TrackMathUtils.cumulativeDistances(shared.points).last()
        if (lap < 20.0) {
            toast("路线过短，无法导入")
            return
        }
        val name = uniqueImportName(shared.name)
        viewLifecycleOwner.lifecycleScope.launch {
            val id = repository.saveRoute(
                RunRouteEntity(
                    name = name,
                    buildMode = "SHARE_CODE",
                    singleLapMeters = lap,
                    isClosedLoop = true,
                    isClockwise = shared.clockwise,
                    denseWaypointsJson = TrackJsonCodec.encode(shared.points),
                    anchorNodesJson = "[]",
                    baseAltitudeMeters = 50.0
                )
            )
            selectedRouteId = id
            persistSelection()
            toast("已导入「$name」· ${String.format(Locale.US, "%.0f m", lap)}")
        }
    }

    private fun readClipboardShareCode(): String? = runCatching {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(requireContext())?.toString()
            ?: return@runCatching null
        RouteShareCodec.extractCode(text)
    }.getOrNull()

    private fun uniqueImportName(base: String): String {
        val name = base.take(24)
        if (routes.none { it.name == name }) return name
        return "$name（口令）"
    }

    private fun deleteRouteWithUndo(route: RunRouteEntity) {
        val removedSchedules = schedules.filter { it.routeId == route.id }
        viewLifecycleOwner.lifecycleScope.launch {
            repository.deleteRoute(route)
            removedSchedules.forEach { repository.deleteSchedule(it) }
            if (selectedRouteId == route.id) {
                selectedRouteId = 0L
                persistSelection()
            }
            Snackbar.make(requireView(), "已删除「${route.name}」", 5000)
                .setAction("撤销") {
                    viewLifecycleOwner.lifecycleScope.launch {
                        // saveRoute 是重新插入、会生成新 id：撤销时必须把关联的
                        // 定时计划回写到新 routeId，否则计划永久指向已删除的路线
                        val restoredId = repository.saveRoute(route)
                        removedSchedules.forEach { repository.saveSchedule(it.copy(routeId = restoredId)) }
                        selectedRouteId = restoredId
                        persistSelection()
                        toast("已恢复「${route.name}」")
                    }
                }
                .show()
        }
    }

    private fun persistSelection() {
        requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_SELECTED, selectedRouteId).apply()
    }

    private fun formatLap(meters: Double): String =
        if (meters >= 1000) String.format(Locale.US, "%.1fkm", meters / 1000.0)
        else String.format(Locale.US, "%.0fm", meters)

    /**
     * 胶囊文案去重：
     * 历史路线名可能已经包含「· 317m」，直接再拼一次会变成「317m · 317m」。
     * 先剥掉名称末尾重复的周长段，再决定是否补一次；名称里已经出现周长时不再追加。
     */
    private fun chipLabel(name: String, singleLapMeters: Double): String {
        val lap = formatLap(singleLapMeters)
        var base = name.trim()
        val suffix = " · $lap"
        while (base.endsWith(suffix)) {
            base = base.removeSuffix(suffix).trim()
        }
        if (base.isEmpty()) return lap
        return if (base.contains(lap)) base else "$base · $lap"
    }

    private fun openEditor(mode: String) {
        startActivity(
            Intent(requireContext(), RouteEditorActivity::class.java)
                .putExtra(RouteEditorActivity.EXTRA_INITIAL_MODE, mode)
        )
    }

    /** 1 步直达：用路线记忆参数直接发车，并回写为最近一次参数 */
    private fun quickStart(route: RunRouteEntity) {
        val host = activity as? RunHost ?: return
        host.startRun(
            route = route,
            targetKm = route.lastTargetDistanceKm,
            paceSecPerKm = route.lastTargetPaceSecPerKm,
            laneDrift = route.lastSmartJitterEnabled,
            cooldown = true
        )
    }

    private fun showScheduleDialog(route: RunRouteEntity, schedule: RunScheduleEntity?) {
        val context = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_schedule, null)
        val switchEnable = view.findViewById<android.widget.Switch>(R.id.swEnable)
        val switchDaily = view.findViewById<android.widget.Switch>(R.id.swDaily)
        val timePicker = view.findViewById<android.widget.TimePicker>(R.id.timePicker)
        val editJitter = view.findViewById<android.widget.EditText>(R.id.etJitter)
        val switchWifi = view.findViewById<android.widget.Switch>(R.id.swWifiIsolate)
        val tvLinkedApp = view.findViewById<android.widget.TextView>(R.id.tvLinkedApp)
        var linkedPkg = schedule?.linkedTargetAppPkg
        tvLinkedApp.text = if (linkedPkg == null) {
            "关联跑步 App：未选择（点击选择）"
        } else {
            "关联跑步 App：${appLabel(linkedPkg)}"
        }
        tvLinkedApp.setOnClickListener {
            showAppPicker(linkedPkg) { picked ->
                linkedPkg = picked
                tvLinkedApp.text = if (picked == null) {
                    "关联跑步 App：未选择（点击选择）"
                } else {
                    "关联跑步 App：${appLabel(picked)}"
                }
            }
        }

        val startMinutes = schedule?.windowStartMinutes ?: 1110
        switchEnable.isChecked = schedule?.isScheduleEnabled == true
        // 新建任务默认不勾「每天」，由星期编辑器预选周一~周五（与 changelog 口径一致）；
        // mask=0 仍表示历史数据的「每天」
        switchDaily.isChecked = schedule?.repeatDaysMask == 0
        timePicker.setIs24HourView(true)
        timePicker.hour = startMinutes / 60
        timePicker.minute = startMinutes % 60
        editJitter.setText((schedule?.windowJitterMinutes ?: 8).toString())
        switchWifi.isChecked = schedule?.autoIsolateWifi ?: true

        // 重复星期：bit0=周一 … bit6=周日。历史数据里 mask=0 表示「每天」，
        // 打开星期编辑器时先展开成 7 天全选，用户勾选后写回真实掩码。
        val dayViews = intArrayOf(
            R.id.day0, R.id.day1, R.id.day2, R.id.day3, R.id.day4, R.id.day5, R.id.day6
        ).map { view.findViewById<TextView>(it) }
        // 新任务预选周一~周五；历史「每天」（mask=0）展开后也回到这个默认
        var editingMask = schedule?.repeatDaysMask?.takeIf { it != 0 } ?: 0b0011111

        fun renderDays() {
            val editable = !switchDaily.isChecked
            dayViews.forEachIndexed { index, tv ->
                val on = (editingMask shr index) and 1 == 1
                tv.isSelected = on
                tv.isEnabled = editable
                tv.alpha = if (editable) 1f else 0.38f
                tv.setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (on) R.color.pf_on_primary else R.color.pf_text_secondary
                    )
                )
            }
        }
        dayViews.forEachIndexed { index, tv ->
            tv.setOnClickListener {
                editingMask = editingMask xor (1 shl index)
                renderDays()
            }
        }
        switchDaily.setOnCheckedChangeListener { _, _ -> renderDays() }
        renderDays()

        GlassDialogs.builder(context)
            .setTitle("定时开跑 · ${route.name}")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                val jitter = editJitter.text.toString().toIntOrNull()?.coerceIn(0, 60) ?: 8
                val startMin = timePicker.hour * 60 + timePicker.minute
                // 兜底：自定义周几被全部取消时回退「每天」，避免启用后永不触发
                val allOff = !switchDaily.isChecked && editingMask == 0
                val mask = if (switchDaily.isChecked || allOff) 0 else editingMask
                if (allOff) toast("未选择星期，已按每天执行")
                val entity = (schedule ?: RunScheduleEntity(
                    routeId = route.id,
                    title = route.name,
                    targetDistanceKm = route.lastTargetDistanceKm
                )).copy(
                    isScheduleEnabled = switchEnable.isChecked,
                    windowStartMinutes = startMin,
                    windowJitterMinutes = jitter,
                    repeatDaysMask = mask,
                    autoIsolateWifi = switchWifi.isChecked,
                    linkedTargetAppPkg = linkedPkg
                )
                viewLifecycleOwner.lifecycleScope.launch {
                    val id = if (entity.id == 0L) {
                        repository.saveSchedule(entity)
                    } else {
                        repository.updateSchedule(entity)
                        entity.id
                    }
                    val saved = entity.copy(id = id)
                    val next = RunScheduleAlarmManager
                        .scheduleNext(context, saved)
                    val message = when {
                        next == null -> "定时已关闭"
                        !com.zcshou.gogogo.run.guard.EnvGuardManager.canScheduleExactAlarms(context) ->
                            "已设定（近似）：${formatTime(next)}"
                        else -> "已设定：${formatTime(next)} 开跑"
                    }
                    com.zcshou.gogogo.run.ui.glass.GlassFeedback.toast(context, message, long = true)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 列出可启动的第三方 App 供关联（排除自身与系统组件） */
    private fun showAppPicker(current: String?, onPicked: (String?) -> Unit) {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val apps = com.zcshou.gogogo.run.guard.InstalledAppHelper.loadLaunchableApps(ctx)
            if (!isAdded || view == null) return@launch
            val uiCtx = requireContext()
            val labels = (listOf("不关联") + apps.map { it.second }).toTypedArray()
            val checked = apps.indexOfFirst { it.first == current } + 1
            GlassDialogs.builder(uiCtx)
                .setTitle("关联跑步 App（开跑时自动拉起）")
                .setSingleChoiceItems(labels, if (checked > 0) checked else 0) { dialog, which ->
                    onPicked(if (which == 0) null else apps[which - 1].first)
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun appLabel(pkg: String): String =
        com.zcshou.gogogo.run.guard.InstalledAppHelper.appLabel(requireContext(), pkg)

    private fun formatTime(millis: Long): String =
        java.text.SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(java.util.Date(millis))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) =
        com.zcshou.gogogo.run.ui.glass.GlassFeedback.toast(requireContext(), message)
}
