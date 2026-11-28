package com.zcshou.gogogo.run.ui.home

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zcshou.gogogo.GoApplication
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.data.db.RunRouteEntity
import com.zcshou.gogogo.run.data.repository.RunRepository
import com.zcshou.gogogo.run.diagnostics.CrashJournal
import com.zcshou.gogogo.run.engine.FreeRoamController
import com.zcshou.gogogo.run.engine.PaceStrategy
import com.zcshou.gogogo.run.engine.RunPhase
import com.zcshou.gogogo.run.engine.RunRuntimeState
import com.zcshou.gogogo.run.engine.TrackMathUtils
import com.zcshou.gogogo.run.guard.EnvGuardManager
import com.zcshou.gogogo.run.guard.RunScheduleNotifier
import com.zcshou.gogogo.run.guard.SetupGuard
import com.zcshou.gogogo.run.service.RunSessionHolder
import com.zcshou.gogogo.run.service.RunSessionService
import com.zcshou.gogogo.run.service.RoamService
import com.zcshou.gogogo.run.ui.glass.GlassDialogs
import com.zcshou.gogogo.run.ui.glass.OverlayGlassStyle
import com.zcshou.gogogo.run.ui.overlay.FloatingCapsuleView
import com.zcshou.gogogo.run.ui.splash.LaunchPolicy
import com.zcshou.gogogo.run.ui.splash.LaunchSplashOverlayView
import com.zcshou.gogogo.run.ui.theme.ThemeHelper
import com.zcshou.gogogo.run.ui.widget.LiquidTabBar
import com.zcshou.gogogo.run.ui.widget.NavGlyphDrawable
import com.zcshou.gogogo.run.ui.widget.NavTabView
import com.zcshou.gogogo.run.update.UpdateConfig
import com.zcshou.gogogo.run.update.UpdateDialog
import com.zcshou.gogogo.run.update.UpdateManager
import com.zcshou.gogogo.run.update.UpdatePrefs
import com.zcshou.service.ServiceGo
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * 新版首页：我的跑圈 / 环境体检双 Tab。
 * 引擎托管在 RunSessionService（定时/后台可跑），本页只发命令、订阅状态、挂载胶囊。
 */
class RunHomeActivity : AppCompatActivity(), RunHost {

    companion object {
        private const val DETOUR_METERS = 150.0
        private const val REQ_NOTIFICATIONS = 1001
        private const val STATE_TAB = "selected_tab"
        private const val STATE_STAR_SHOWN = "star_prompt_shown"
        private const val STATE_UPDATE_SHOWN = "update_prompt_shown"
        private const val STATE_CRASH_SHOWN = "crash_prompt_shown"
        private const val TAB_AUTO = "tab_auto"
        private const val TAB_ROAM = "tab_roam"
        private const val TAB_ENV = "tab_env"
        private const val TAB_SETTINGS = "tab_settings"
        /** 自动弹出引导的次数上限：未配置也不会每次开 App 都弹。 */
        private const val MAX_AUTO_GUIDE_SHOWS = 3
        private const val KEY_GUIDE_SHOWN_COUNT = "auto_guide_count"
        const val EXTRA_SHORTCUT_ACTION = "shortcut_action"
        /**
         * 欢迎页交接标记：用户刚在欢迎页看完循环呼吸的徽记，
         * 进入首页时不要再播一遍 1050ms 的「云迹破壳」。
         */
        const val EXTRA_SKIP_LAUNCH_SPLASH = "skip_launch_splash"
        private const val ACTION_QUICK_RUN = "default_run"
        private const val ACTION_ROAM = "roam"
        private const val ACTION_STOP = "stop"
        private val DETOUR_OPTIONS = listOf(
            "东侧 150m" to 90.0,
            "南侧 150m" to 180.0,
            "西侧 150m" to 270.0,
            "北侧 150m" to 0.0
        )
    }

    private lateinit var repository: RunRepository
    private lateinit var homeStatus: TextView
    private lateinit var tabRoutes: NavTabView
    private lateinit var tabRoam: NavTabView
    private lateinit var tabEnv: NavTabView
    private lateinit var tabSettings: NavTabView
    private lateinit var bottomTabs: View
    private lateinit var tabBar: LiquidTabBar
    private lateinit var navGlassView: com.example.liquidglass.LiquidGlassView
    private lateinit var navContainerView: FrameLayout
    private lateinit var navShadowView: com.zcshou.gogogo.run.ui.widget.NavShadowView
    private lateinit var navMiniGlass: com.example.liquidglass.LiquidGlassView
    private lateinit var navMini: TextView
    private var navMinimized = false
    private var navMinimizeFraction = 0f
    private var navFractionSpring: androidx.dynamicanimation.animation.SpringAnimation? = null
    private var selectedTab = 0
    private var lastState = RunRuntimeState()

    private var latestState = RunRuntimeState()
    private var updateDialog: AlertDialog? = null
    private var starDialog: AlertDialog? = null
    private var supportDialog: AlertDialog? = null
    private var crashDialog: AlertDialog? = null
    /** 同一会话已经打扰过的弹窗，支持邀请要让位，避免连着弹两个。 */
    private var starPromptShown = false
    private var updatePromptShown = false
    private var crashPromptShown = false
    private var pendingLaunchAfterWifi: (() -> Unit)? = null

    private var capsule: FloatingCapsuleView? = null
    private var windowManager: WindowManager? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var pendingStartAfterSetup: (() -> Unit)? = null

    /** 启动遮罩（「云迹破壳 · 跑者入画」）；null 表示当前没有演出。 */
    private var launchSplash: LaunchSplashOverlayView? = null

    /** 遮罩是否正在覆盖首页：决定引导弹窗要不要避让。 */
    private var launchSplashCovering = false

    /** 遮罩期间被压下的配置引导；破壳结束后再补排期。 */
    private var pendingSetupGuideAfterSplash = false

    private var launchEntranceStarted = false
    private lateinit var homeContent: View
    private val launchSprings = mutableListOf<SpringAnimation>()

    private val setupLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                pendingStartAfterSetup?.invoke()
            }
            pendingStartAfterSetup = null
            refreshSetupBanner()
        }

    /** 开跑前预请求运行时定位权限（targetSdk 34 的 location FGS 硬前提） */
    private var pendingRunStart: (() -> Unit)? = null
    private val runLocationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val start = pendingRunStart
        pendingRunStart = null
        if (grants.values.any { it } && start != null) {
            start()
        } else {
            toast("缺少定位权限，系统要求授权后才能开跑")
        }
    }

    fun openSetupWizard(onReady: (() -> Unit)? = null) {
        if (isFinishing) return
        pendingStartAfterSetup = onReady
        runCatching {
            setupLauncher.launch(Intent(this, SetupWizardActivity::class.java))
        }.onFailure {
            pendingStartAfterSetup = null
            toast("无法打开配置向导")
        }
    }

    private fun refreshSetupBanner() {
        (supportFragmentManager.findFragmentByTag(TAB_AUTO) as? RouteListFragment)
            ?.refreshSetupBanner()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.apply(this)
        super.onCreate(savedInstanceState)
        // 合规兜底：静态快捷方式/小部件可直达本页、绕过欢迎页。
        // 未同意隐私政策前不落 KEY_ONBOARDED、不进首页，先送去欢迎页补同意；
        // 同意后由 WelcomeActivity 正常回到这里。
        if (!GoApplication.hasPrivacyConsent(this)) {
            startActivity(Intent(this, com.zcshou.gogogo.WelcomeActivity::class.java))
            finish()
            return
        }
        // 进入首页即代表已通过欢迎页，之后不再重复展示
        getSharedPreferences(GoApplication.PREF_AGREEMENT, MODE_PRIVATE).edit()
            .putBoolean(GoApplication.PREF_ONBOARDED, true)
            .apply()
        // 每次真正进入首页记一次打开：Star 邀请靠它判断「是不是常客」，
        // 转屏恢复不重复计数，否则一次冷启动会被记成好几次。
        if (savedInstanceState == null) StarPrompt.noteLaunch(this)
        // 转屏后不要重新弹已经出现过的软提醒。
        starPromptShown = savedInstanceState?.getBoolean(STATE_STAR_SHOWN, false) ?: false
        updatePromptShown = savedInstanceState?.getBoolean(STATE_UPDATE_SHOWN, false) ?: false
        crashPromptShown = savedInstanceState?.getBoolean(STATE_CRASH_SHOWN, false) ?: false
        ThemeHelper.enableEdgeToEdge(this)
        setContentView(R.layout.activity_run_home)
        // 启动动效策略必须先于各个 handle*Intent 判定：它们会把 extra removeExtra 掉。
        val launchMode = LaunchPolicy.decide(buildLaunchInput(savedInstanceState))
        // 让 maybeShowSetupGuide() 在演出期间只登记、不排期。
        launchSplashCovering = LaunchPolicy.coversHome(launchMode)
        // 不再给整页加 systemBars padding：地图需要真正铺到四边，
        // 顶部 inset 由各页面自己消化，底部 inset 只抬高悬浮导航胶囊。
        repository = RunRepository(applicationContext)
        homeStatus = findViewById(R.id.homeStatus)
        tabRoutes = findViewById(R.id.tabRoutes)
        tabRoam = findViewById(R.id.tabRoam)
        tabEnv = findViewById(R.id.tabEnv)
        tabSettings = findViewById(R.id.tabSettings)
        navContainerView = findViewById(R.id.bottomTabs)
        bottomTabs = navContainerView
        navGlassView = findViewById<com.example.liquidglass.LiquidGlassView>(R.id.navGlass)
        navShadowView =
            findViewById<com.zcshou.gogogo.run.ui.widget.NavShadowView>(R.id.navShadow)
        navMiniGlass =
            findViewById<com.example.liquidglass.LiquidGlassView>(R.id.navMiniGlass)
        navMini = findViewById(R.id.navMini)
        navMini.setOnClickListener { expandNav() }
        navMiniGlass.setOnClickListener { expandNav() }
        // 注意：必须在这里取，不能写进下面的 apply{} —— apply 的接收者是 View，
        // 里面的 findViewById 会绑到 navGlassView 自己身上，找不到必然返回 null，
        // 于是 backdropSource 一直是空，库就退化成"整窗捕获"，把导航条自己也画进背景。
        val homeContent = findViewById<android.view.View>(R.id.fragmentContainer)
        this.homeContent = homeContent
        navMiniGlass.apply {
            backdropSource = homeContent
            com.zcshou.gogogo.run.ui.glass.GlassRenderGate.register(this)
            com.zcshou.gogogo.run.ui.glass.OpticalGlassTuning.apply(
                this,
                bevelDp = com.zcshou.gogogo.run.ui.glass.OpticalGlassTuning.NAV_BEVEL_DP,
                refractionDp = com.zcshou.gogogo.run.ui.glass.OpticalGlassTuning.NAV_REFRACTION_DP
            )
            enablePressEffect = false
            enableShadow = false
            enableAdaptiveTint = false
        }
        navGlassView.apply {
            // 导航胶囊折射底层页面：与旧版保持同一父级/同一 source 关系
            backdropSource = homeContent
            com.zcshou.gogogo.run.ui.glass.GlassRenderGate.register(this)
            // 小尺寸胶囊单独收窄折射带，避免整块都是边缘
            com.zcshou.gogogo.run.ui.glass.OpticalGlassTuning.apply(
                this,
                bevelDp = com.zcshou.gogogo.run.ui.glass.OpticalGlassTuning.NAV_BEVEL_DP,
                refractionDp = com.zcshou.gogogo.run.ui.glass.OpticalGlassTuning.NAV_REFRACTION_DP
            )
            // 按压/抬起由 LiquidTabBar 驱动，玻璃本身不再缩放，避免与透镜分层错位
            enablePressEffect = false
        }
        tabBar = LiquidTabBar(
            container = navContainerView,
            glass = navGlassView,
            tabHost = findViewById(R.id.tabHost),
            tabs = listOf(tabRoutes, tabRoam, tabEnv, tabSettings)
        ) { index -> selectTab(index) }
        applyBottomBarInsets()

        // 锁屏时停掉悬浮胶囊的边缘流光（跨应用悬浮窗不受本页前后台影响）
        runCatching {
            ContextCompat.registerReceiver(
                this,
                screenReceiver,
                android.content.IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        tabRoutes.setOnClickListener { selectTab(0) }
        tabRoam.setOnClickListener { selectTab(1) }
        tabEnv.setOnClickListener { selectTab(2) }
        tabSettings.setOnClickListener { selectTab(3) }
        applyNavTabTints()

        // 独立导航投影：跟随玻璃宽度、底边距与 Tab 行高度
        val homeRootView = findViewById<View>(R.id.homeRoot)
        fun updateNavShadow() {
            val glassHeight = if (navGlassView.height > 0) {
                navGlassView.height
            } else {
                (LiquidTabBar.NavMetrics.TRACK_DP * resources.displayMetrics.density).toInt()
            }
            val bottomInset = (homeRootView.height - navContainerView.bottom).coerceAtLeast(0)
            // 圆角与玻璃一致（36dp），否则挖掉的轮廓和实际轨道对不上，边缘会留一圈灰
            navShadowView.update(
                navContainerView.width,
                glassHeight,
                bottomInset,
                (36f * resources.displayMetrics.density).toInt()
            )
        }
        navContainerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateNavShadow()
        }
        homeRootView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateNavShadow()
        }
        navContainerView.post { updateNavShadow() }
        updateNavMiniContent()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, RouteListFragment(), TAB_AUTO)
                .add(R.id.fragmentContainer, FreeRoamFragment(), TAB_ROAM)
                .add(R.id.fragmentContainer, EnvCheckFragment(), TAB_ENV)
                .add(R.id.fragmentContainer, SettingsFragment(), TAB_SETTINGS)
                .commitNow()
        }
        selectTab(savedInstanceState?.getInt(STATE_TAB) ?: 0)
        if (intent.getBooleanExtra(RoamService.EXTRA_OPEN_ROAM, false)) {
            intent.removeExtra(RoamService.EXTRA_OPEN_ROAM)
            selectTab(1)
        }

        observeSession()
        handleAutoStartIntent(intent)
        handleShortcutIntent(intent)
        handleQuickActionShortcut(intent)
        maybeShowSetupGuide()
        observeUpdates()
        handleUpdateIntent(intent)
        maybeShowCrashReport()
        maybeInviteStar()
        maybeInviteSupport()
        findViewById<View>(R.id.homeRoot).postDelayed({
            if (!isFinishing && !isDestroyed && UpdateConfig.enabled &&
                UpdatePrefs.autoCheck(this)
            ) {
                UpdateManager.getInstance(this).checkForUpdate(manual = false)
            }
        }, 1500)

        // 遮罩最后挂：此时首页数据、Tab、底栏都已经就位，动画只负责「破壳露出」
        mountLaunchSplash(launchMode)
    }

    /**
     * 自动检查只负责“有更新时提示”；手动入口（体检页/关于页）会带 manualRequest，
     * 即使配置引导还没完成也允许弹窗。
     */
    private fun observeUpdates() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                UpdateManager.getInstance(this@RunHomeActivity).state.collect { state ->
                    if (!state.showDialog || updateDialog != null) return@collect
                    // 慢网下更新检查可能晚于其他软提醒返回，别把弹窗叠到它们上面。
                    val canShow = (state.manualRequest ||
                        SetupGuard.isReady(this@RunHomeActivity)) &&
                        starDialog?.isShowing != true && supportDialog?.isShowing != true &&
                            crashDialog?.isShowing != true
                    if (canShow) {
                        updatePromptShown = true
                        updateDialog = UpdateDialog.show(this@RunHomeActivity) {
                            updateDialog = null
                        }
                    }
                }
            }
        }
    }

    private fun handleUpdateIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(UpdateManager.EXTRA_SHOW_UPDATE, false) == true) {
            intent.removeExtra(UpdateManager.EXTRA_SHOW_UPDATE)
            UpdateManager.getInstance(this).showDownload()
        }
    }

    // ---------------- 启动动效（云迹破壳 · 跑者入画） ----------------

    /**
     * 把平台状态翻译成纯逻辑输入。
     *
     * 这里只在 `onCreate` 调一次，且**必须早于** handle*Intent——
     * 那几个方法会把 extra 移除，晚读就永远判定成「常规冷启动」。
     */
    private fun buildLaunchInput(savedInstanceState: Bundle?): LaunchPolicy.Input {
        val animatorScale = runCatching {
            Settings.Global.getFloat(
                contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            )
        }.getOrDefault(1f)
        val powerSave = runCatching {
            (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
        }.getOrDefault(false)
        val skipSplash = intent.getBooleanExtra(EXTRA_SKIP_LAUNCH_SPLASH, false)
        // 用完即弃：避免后续 resume / 复用同一 Intent 时被误判成「永远跳过」
        if (skipSplash) intent.removeExtra(EXTRA_SKIP_LAUNCH_SPLASH)
        return LaunchPolicy.Input(
            hasSavedInstanceState = savedInstanceState != null,
            hasShortcutAction = intent.hasExtra(EXTRA_SHORTCUT_ACTION),
            hasShortcutRouteId = intent.hasExtra(
                com.zcshou.gogogo.run.guard.RunShortcutManager.EXTRA_SHORTCUT_ROUTE_ID
            ),
            hasAutoRouteId = intent.hasExtra(RunScheduleNotifier.EXTRA_AUTO_ROUTE_ID),
            openRoam = intent.getBooleanExtra(RoamService.EXTRA_OPEN_ROAM, false),
            showUpdate = intent.getBooleanExtra(UpdateManager.EXTRA_SHOW_UPDATE, false),
            skipLaunchSplash = skipSplash,
            animatorScale = animatorScale,
            renderSuppressed = com.zcshou.gogogo.run.ui.glass.DeepRunGlassManager.isRenderSuppressed,
            powerSaveMode = powerSave
        )
    }

    private fun mountLaunchSplash(mode: LaunchPolicy.Mode) {
        if (isFinishing || isDestroyed) {
            launchSplashCovering = false
            return
        }
        when (mode) {
            LaunchPolicy.Mode.FULL_SPLASH -> {
                prepareLaunchEntrance()
                // 启动路径上的任何异常都不能让用户停在「半透明首页」：失败就当没演过
                val mounted = runCatching { addFullSplashOverlay() }.getOrDefault(false)
                if (!mounted) {
                    launchSplashCovering = false
                    clearLaunchEntrance()
                }
            }
            LaunchPolicy.Mode.FAST_CUT -> {
                // 直达场景只做极简淡入：不挂遮罩、不拦截任何点击
                launchSplashCovering = false
                homeContent.alpha = 0.86f
                homeContent.animate()
                    .alpha(1f)
                    .setDuration(LaunchPolicy.FAST_CUT_DURATION_MS)
                    .start()
            }
            LaunchPolicy.Mode.NONE -> launchSplashCovering = false
        }
    }

    private fun addFullSplashOverlay(): Boolean {
        val homeRoot = findViewById<View>(R.id.homeRoot) as? FrameLayout ?: return false
        val overlay = LaunchSplashOverlayView(this).apply {
            // 破壳那一刻首页同步落位；遮罩移除后再恢复静态 0fps
            onRevealStart = { startLaunchEntrance() }
            onFinished = { onLaunchSplashFinished() }
        }
        homeRoot.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        launchSplash = overlay
        return true
    }

    /** 首页先「退后半步」，等破壳时再弹回原位。 */
    private fun prepareLaunchEntrance() {
        launchEntranceStarted = false
        homeContent.animate().cancel()
        homeContent.alpha = 0.15f
        homeContent.translationY = dp(14).toFloat()
        navContainerView.translationY = dp(24).toFloat()
        navGlassView.translationY = dp(24).toFloat()
        navShadowView.translationY = dp(24).toFloat()
    }

    /** 底部导航弹性升起（阻尼 0.76 / 刚度 360），内容区同步淡入上浮。 */
    private fun startLaunchEntrance() {
        if (launchEntranceStarted) return
        launchEntranceStarted = true
        homeContent.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(380L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        launchSprings.forEach { runCatching { it.cancel() } }
        launchSprings.clear()
        launchSprings.add(springRise(navContainerView))
        launchSprings.add(springRise(navGlassView))
        launchSprings.add(springRise(navShadowView))
    }

    private fun springRise(view: View): SpringAnimation =
        SpringAnimation(view, DynamicAnimation.TRANSLATION_Y, 0f).apply {
            setSpring(
                SpringForce(0f).apply {
                    dampingRatio = 0.76f
                    stiffness = 360f
                }
            )
            start()
        }

    /** 立即把所有入场位移归零：既用于收尾，也用于点击快进 / 退到后台。 */
    private fun clearLaunchEntrance() {
        if (!::homeContent.isInitialized) return
        launchSprings.forEach { runCatching { it.cancel() } }
        launchSprings.clear()
        homeContent.animate().cancel()
        homeContent.alpha = 1f
        homeContent.translationY = 0f
        if (::navContainerView.isInitialized) {
            navContainerView.translationY = 0f
            navGlassView.translationY = 0f
            navShadowView.translationY = 0f
        }
    }

    private fun onLaunchSplashFinished() {
        launchSplash = null
        launchSplashCovering = false
        // 不在此处强行 cancel 尚未落定完的底部弹簧，让 0.76 阻尼的最后一点回弹余韵自然完成
        // 底栏玻璃的采样画面停在入场动画的中间帧，这里补一帧确保落位清晰
        com.zcshou.gogogo.run.ui.glass.GlassRenderGate.requestRefresh(160L)
        if (pendingSetupGuideAfterSplash) {
            pendingSetupGuideAfterSplash = false
            // 让遮罩彻底移除后再排期，避免 BottomSheet 与最后一帧抢同一帧
            maybeShowSetupGuide(delayMs = 200L)
        }
    }

    /**
     * 停掉启动演出并复位首页。
     *
     * `onPause` / `onDestroy` 都必须走到这里：遮罩是挂在 Activity 的 View 树上的，
     * 退到后台后继续跑帧只会白耗电；直接从事件里同步移除也容易踩到 dispatch 期间改树。
     */
    private fun finishLaunchExperience() {
        val overlay = launchSplash
        launchSplash = null
        launchSplashCovering = false
        pendingSetupGuideAfterSplash = false
        overlay?.apply {
            onRevealStart = null
            onFinished = null
            runCatching { finishNow() }
        }
        clearLaunchEntrance()
    }

    private fun maybeShowSetupGuide(delayMs: Long = 700L) {
        if (SetupGuard.isReady(this)) return
        // 破壳演出期间绝不弹：既挡画面，也会让用户看不清发生了什么
        if (launchSplashCovering) {
            pendingSetupGuideAfterSplash = true
            return
        }
        val prefs = getSharedPreferences("pf_guide", MODE_PRIVATE)
        // 曾经是「弹过一次就永不再弹」：用户随手关掉 X 之后，除非自己发现首页那条
        // 红条，否则再也见不到引导，开跑时才被挡回来 —— 那时上下文已经丢了。
        //
        // 现在改成「未配置就每次启动都提醒」，但设上限：提醒 3 次仍未配置就停，
        // 避免变成每次开 App 都弹的骚扰。首页红条始终保留作为入口。
        val shown = prefs.getInt(KEY_GUIDE_SHOWN_COUNT, 0)
        if (shown >= MAX_AUTO_GUIDE_SHOWS) return
        prefs.edit().putInt(KEY_GUIDE_SHOWN_COUNT, shown + 1).apply()
        window.decorView.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            // 破壳结束到引导弹出之间用户可能已经切走，别把 BottomSheet 盖到别的界面上
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@postDelayed
            if (!SetupGuard.isReady(this)) {
                SetupWizardBottomSheet.newInstance()
                    .show(supportFragmentManager, "setup_guide")
            }
        }, delayMs)
    }

    /**
     * GitHub Star 邀请：等首页稳定展示后再问，并让位给配置向导与更新弹窗。
     *
     * 频率由 [StarPrompt] 自己把控：打开够多次、用够多天之后才会出现一次，
     * 两次之间至少隔两周，总共最多两次；点过 Star 或「不再提示」后永久静默。
     * 关于页保留常驻入口，用户拒绝后仍可主动去点。
     */
    private fun maybeInviteStar() {
        if (crashPromptShown || CrashJournal.pending(this)) return
        if (!StarPrompt.isInvitePending(this)) return
        window.decorView.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            // 2.6 秒内用户可能已经切到别的页面/应用，别把弹窗盖到别的界面上。
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@postDelayed
            if (!SetupGuard.isReady(this)) return@postDelayed
            if (crashPromptShown) return@postDelayed
            if (updateDialog?.isShowing == true || starDialog?.isShowing == true ||
                crashDialog?.isShowing == true
            ) return@postDelayed
            if (supportFragmentManager.findFragmentByTag("setup_guide") != null) return@postDelayed
            starPromptShown = true
            starDialog = StarPrompt.show(this) { starDialog = null }
        }, 2600)
    }

    /**
     * 上次崩溃的恢复提示：只在下一次启动提示一次，且优先于 Star / 支持邀请。
     *
     * 崩溃记录由 [CrashJournal] 在进程退出前写本地文件；这里只负责提示与跳转，
     * 不自动上报任何内容。用户点「导出诊断信息」会进入问题反馈页。
     */
    private fun maybeShowCrashReport() {
        if (!CrashJournal.pending(this)) return
        window.decorView.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@postDelayed
            if (!CrashJournal.pending(this)) return@postDelayed
            if (updateDialog?.isShowing == true || starDialog?.isShowing == true ||
                supportDialog?.isShowing == true || crashDialog?.isShowing == true
            ) return@postDelayed
            if (supportFragmentManager.findFragmentByTag("setup_guide") != null) return@postDelayed
            crashPromptShown = true
            // 标记已提示，避免每次启动重复打扰；记录本身保留 7 天供诊断包读取。
            CrashJournal.markPrompted(this)
            crashDialog = showCrashDialog()
        }, 1500)
    }

    private fun showCrashDialog(): AlertDialog {
        val body = getString(R.string.crash_body) +
            "\n\n上次崩溃：${CrashJournal.formatTime(CrashJournal.lastCrashAt(this))}"
        val dialog = GlassDialogs.builder(this)
            .setTitle(R.string.crash_title)
            .setMessage(body)
            .setPositiveButton(R.string.crash_export) { _, _ ->
                runCatching { startActivity(FeedbackActivity.intent(this)) }
            }
            .setNegativeButton(R.string.crash_ack, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.transformationMethod = null
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.transformationMethod = null
        }
        dialog.setOnDismissListener { crashDialog = null }
        dialog.show()
        return dialog
    }

    /**
     * 「支持云迹」邀请：只有真实完成过多次跑步、跑够里程后才出现。
     *
     * 比 Star 邀请更克制：延迟 6 秒、只在首页空闲时触发；同一会话弹过 Star / 更新，
     * 或正在跑步，都不再打扰。是否满足条件由 [SupportPrompt] 依据历史记录判定。
     */
    private fun maybeInviteSupport() {
        if (crashPromptShown || CrashJournal.pending(this) ||
            starPromptShown || updatePromptShown
        ) return
        if (!SupportPrompt.canPrompt(this)) return
        window.decorView.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@postDelayed
            if (!SetupGuard.isReady(this)) return@postDelayed
            if (latestState.phase != RunPhase.IDLE) return@postDelayed
            if (crashPromptShown || starPromptShown || updatePromptShown) return@postDelayed
            if (updateDialog?.isShowing == true || starDialog?.isShowing == true ||
                supportDialog?.isShowing == true || crashDialog?.isShowing == true
            ) return@postDelayed
            if (supportFragmentManager.findFragmentByTag("setup_guide") != null) return@postDelayed
            lifecycleScope.launch {
                val stats = runCatching { repository.usageStats() }.getOrNull() ?: return@launch
                if (!SupportPrompt.isEligible(this@RunHomeActivity, stats)) return@launch
                if (isFinishing || isDestroyed) return@launch
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                if (latestState.phase != RunPhase.IDLE) return@launch
                if (crashPromptShown || starPromptShown || updatePromptShown) return@launch
                if (updateDialog?.isShowing == true || starDialog?.isShowing == true ||
                    supportDialog?.isShowing == true || crashDialog?.isShowing == true
                ) return@launch
                supportDialog = SupportPrompt.show(this@RunHomeActivity, stats) {
                    supportDialog = null
                }
            }
        }, 6000)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(RoamService.EXTRA_OPEN_ROAM, false)) {
            intent.removeExtra(RoamService.EXTRA_OPEN_ROAM)
            selectTab(1)
        }
        handleAutoStartIntent(intent)
        handleShortcutIntent(intent)
        handleQuickActionShortcut(intent)
        handleUpdateIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_TAB, selectedTab)
        outState.putBoolean(STATE_STAR_SHOWN, starPromptShown)
        outState.putBoolean(STATE_UPDATE_SHOWN, updatePromptShown)
        outState.putBoolean(STATE_CRASH_SHOWN, crashPromptShown)
        super.onSaveInstanceState(outState)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN &&
            capsule?.collapseIfExpanded() == true
        ) {
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        com.zcshou.gogogo.run.ui.glass.GlassRenderGate.setActive(true)
        // 回前台重算玻璃层级缓存：系统省电模式/高对比度开关可能在后台被切换
        com.zcshou.gogogo.run.ui.glass.DeepRunGlassManager.refreshAll()
        // 悬浮胶囊主要显示在别的 App 之上，所以「本页在前台」并不是流光该跑的条件，
        // 只要没锁屏就继续跑（见下面的 screenReceiver）。
        setCapsuleBeamPaused(false)
        val pending = pendingLaunchAfterWifi
        if (pending != null && !EnvGuardManager.isWifiActivelyConnected(this)) {
            pendingLaunchAfterWifi = null
            pending()
        }
    }

    /**
     * 锁屏 / 亮屏：锁屏后流光没有任何观众，必须停掉帧循环。
     * 注意这里只监听 SCREEN_OFF/ON——不能拿 Activity 的 onPause 当条件，
     * 因为胶囊是跨应用悬浮窗，App 退到后台时它恰恰还在屏幕上。
     */
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> setCapsuleBeamPaused(true)
                Intent.ACTION_SCREEN_ON -> setCapsuleBeamPaused(false)
            }
        }
    }

    private fun setCapsuleBeamPaused(paused: Boolean) {
        capsule?.setBeamPaused(paused)
    }

    override fun onPause() {
        // 启动遮罩不能跟着退到后台继续跑帧：先收尾再关渲染闸门
        finishLaunchExperience()
        // 锁屏 / 切后台 / 跨应用巡航：立即停掉玻璃着色器刷新
        com.zcshou.gogogo.run.ui.glass.GlassRenderGate.setActive(false)
        super.onPause()
    }

    override fun onDestroy() {
        finishLaunchExperience()
        runCatching { updateDialog?.dismiss() }
        updateDialog = null
        runCatching { starDialog?.dismiss() }
        starDialog = null
        runCatching { supportDialog?.dismiss() }
        supportDialog = null
        runCatching { crashDialog?.dismiss() }
        crashDialog = null
        runCatching { detourDialog?.dismiss() }
        detourDialog = null
        runCatching { unregisterReceiver(screenReceiver) }
        hideCapsule()
        super.onDestroy()
    }

    private var detourDialog: AlertDialog? = null
    private var relayoutRunnable: Runnable? = null
    private var relayoutBoundsRunnable: Runnable? = null

    // ---------------- RunHost ----------------

    override fun startRun(
        route: RunRouteEntity,
        targetKm: Float,
        paceSecPerKm: Int,
        laneDrift: Boolean,
        cooldown: Boolean,
        reverseDirection: Boolean,
        paceStrategy: PaceStrategy
    ) {
        if (!SetupGuard.isReady(this)) {
            openSetupWizard {
                startRun(route, targetKm, paceSecPerKm, laneDrift, cooldown, reverseDirection, paceStrategy)
            }
            return
        }
        // targetSdk 34：location 型前台服务必须已授予运行时定位权限，
        // 否则 RunSessionService.startForeground 会抛 SecurityException
        if (!EnvGuardManager.hasRuntimeLocation(this)) {
            pendingRunStart = {
                startRun(route, targetKm, paceSecPerKm, laneDrift, cooldown, reverseDirection, paceStrategy)
            }
            runLocationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }
        val launch: () -> Unit = {
            ensureNotificationAndRecognitionPermissions()
            RoamService.stop(this)
            ContextCompat.startForegroundService(
                this,
                RunSessionService.startIntent(
                    this, route.id, targetKm, paceSecPerKm, laneDrift, cooldown,
                    linkedPkg = com.zcshou.gogogo.run.data.prefs.RunPrefs.targetApp(this),
                    reverseDirection = reverseDirection,
                    paceStrategy = paceStrategy.name
                )
            )
            // 记忆本次参数，支撑下次 1 步直达
            lifecycleScope.launch {
                runCatching {
                    repository.updateRoute(
                        route.copy(
                            lastTargetDistanceKm = targetKm,
                            lastTargetPaceSecPerKm = paceSecPerKm,
                            lastSmartJitterEnabled = laneDrift
                        )
                    )
                }
            }
            toast("已发车")
            Unit
        }
        if (EnvGuardManager.isWifiActivelyConnected(this)) {
            // 伴侣模式开启时，鲸鲸同步冒一句 Wi-Fi 调侃（不改变原有拦截流程）
            maybeMascotWifiBubble()
            com.zcshou.gogogo.run.ui.glass.GlassDialogs.builder(this)
                .setTitle("当前已连接 Wi-Fi")
                .setMessage(wifiWarningMessage())
                .setPositiveButton("去关闭 Wi-Fi") { _, _ ->
                    pendingLaunchAfterWifi = launch
                    startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                }
                .setNegativeButton("忽略并直接开跑") { _, _ -> launch() }
                .setOnCancelListener { pendingLaunchAfterWifi = null }
                .show()
        } else {
            launch()
        }
    }

    /** 拦截弹窗正文；伴侣模式开启时附一句鲸鲸的提醒 */
    private fun wifiWarningMessage(): String {
        val base = "Wi-Fi 可能让定位闪回真实位置，建议暂时关闭后再开跑。"
        if (com.zcshou.gogogo.mascot.MascotPrefs.mode(this) ==
            com.zcshou.gogogo.mascot.MascotMode.OFF
        ) {
            return base
        }
        val line = com.zcshou.gogogo.mascot.MascotDialogueEngine.onSystemEvent(
            com.zcshou.gogogo.mascot.MascotDialogueEngine.State.WARNING_WIFI,
            bypassCooldown = true
        ) ?: return base
        return "$base\n\n鲸鲸：$line"
    }

    /**
     * 首页有迷你角标时让它直接冒泡；否则静默（不为了说一句话去新建视图）。
     */
    private fun maybeMascotWifiBubble() {
        val badge = findViewById<com.zcshou.gogogo.mascot.MascotMiniBadgeView>(
            R.id.homeMiniMascot
        ) ?: return
        if (badge.visibility != View.VISIBLE) return
        val line = com.zcshou.gogogo.mascot.MascotDialogueEngine.onSystemEvent(
            com.zcshou.gogogo.mascot.MascotDialogueEngine.State.WARNING_WIFI,
            bypassCooldown = true
        ) ?: return
        badge.showBubble(line, 4_000L)
    }

    override fun pauseResume() {
        sendCommand(RunSessionService.ACTION_TOGGLE_PAUSE)
    }

    override fun adjustPace(paceSecPerKm: Int) {
        if (!RunSessionHolder.sessionActive) return
        ContextCompat.startForegroundService(
            this,
            RunSessionService.adjustPaceIntent(this, paceSecPerKm)
        )
    }

    override fun adjustTargetKm(km: Float) {
        if (!RunSessionHolder.sessionActive) return
        ContextCompat.startForegroundService(
            this,
            RunSessionService.adjustTargetIntent(this, km * 1000.0)
        )
    }

    override fun pickDetour() {
        val position = latestState.position
        if (position == null) {
            toast("尚未获得当前位置")
            return
        }
        val view = layoutInflater.inflate(R.layout.dialog_detour_map, null)
        val mapView = view.findViewById<com.baidu.mapapi.map.MapView>(R.id.detourMap)
        val map = mapView.map
        com.zcshou.gogogo.run.ui.theme.MapStyleHelper.applyForTheme(mapView)
        map.setOnMapLoadedCallback {
            com.zcshou.gogogo.run.ui.theme.MapStyleHelper.applyForTheme(mapView)
        }
        val bd = com.zcshou.gogogo.run.engine.CoordTransformUtil.wgs84ToBd09(position.lat, position.lng)
        val center = com.baidu.mapapi.model.LatLng(bd.lat, bd.lng)
        runCatching {
            map.setMapStatus(com.baidu.mapapi.map.MapStatusUpdateFactory.newLatLngZoom(center, 18f))
            map.addOverlay(com.baidu.mapapi.map.MarkerOptions().position(center).title("当前位置"))
        }
        runCatching { detourDialog?.dismiss() }
        val dialog = AlertDialog.Builder(this)
            .setTitle("临时插点 · 300m 范围")
            .setView(view)
            .setNegativeButton("取消", null)
            .create()
        detourDialog = dialog
        map.setOnMapClickListener(object : com.baidu.mapapi.map.BaiduMap.OnMapClickListener {
            override fun onMapClick(tapped: com.baidu.mapapi.model.LatLng) {
                val wgs = com.zcshou.gogogo.run.engine.CoordTransformUtil
                    .bd09ToWgs84(tapped.latitude, tapped.longitude)
                ContextCompat.startForegroundService(
                    this@RunHomeActivity,
                    RunSessionService.detourIntent(this@RunHomeActivity, wgs.lat, wgs.lng)
                )
                toast("已插入绕路点，立即出发")
                dialog.dismiss()
            }

            override fun onMapPoiClick(poi: com.baidu.mapapi.map.MapPoi) = Unit
        })
        dialog.setOnShowListener { runCatching { mapView.onResume() } }
        dialog.setOnDismissListener {
            runCatching { map.setOnMapLoadedCallback(null) }
            runCatching { mapView.onPause() }
            runCatching { mapView.onDestroy() }
            if (detourDialog === dialog) detourDialog = null
        }
        dialog.show()
    }

    override fun finishEarly() {
        sendCommand(RunSessionService.ACTION_FINISH_EARLY)
    }

    override fun abortRun() {
        sendCommand(RunSessionService.ACTION_ABORT)
    }

    override fun isServiceReady(): Boolean = RunSessionHolder.sessionActive

    override fun currentState(): RunRuntimeState = latestState

    override fun relayoutOverlay() {
        val view = capsule ?: return
        val wm = windowManager ?: return
        val params = overlayParams ?: return
        relayoutRunnable?.let { view.removeCallbacks(it) }
        relayoutBoundsRunnable?.let { view.removeCallbacks(it) }
        val boundsTask = Runnable {
            params.x = params.x.coerceIn(0, (screenWidth() - view.width).coerceAtLeast(0))
            params.y = params.y.coerceIn(
                statusBarHeight(),
                (screenHeight() - view.height - navBarHeight()).coerceAtLeast(statusBarHeight())
            )
            runCatching { wm.updateViewLayout(view, params) }
        }
        val layoutTask = Runnable {
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            runCatching { wm.updateViewLayout(view, params) }
            view.postDelayed(boundsTask, 40)
        }
        relayoutBoundsRunnable = boundsTask
        relayoutRunnable = layoutTask
        view.postDelayed(layoutTask, 60)
    }

    override fun stopSimulation() {
        sendCommand(RunSessionService.ACTION_ABORT)
        FreeRoamController.release()
        RoamService.stop(this)
        runCatching { stopService(Intent(this, ServiceGo::class.java)) }
        hideCapsule()
        notifySimulationStopped()
        toast("模拟定位已停止，真实 GPS 已恢复")
    }

    override fun beginManualOverride() {
        sendCommand(RunSessionService.ACTION_MANUAL_TAKEOVER)
    }

    override fun endManualOverride() {
        sendCommand(RunSessionService.ACTION_MANUAL_RELEASE)
    }

    override fun setOverlayHidden(hidden: Boolean) {
        val view = capsule ?: return
        view.visibility = if (hidden) android.view.View.GONE else android.view.View.VISIBLE
        if (!hidden) relayoutOverlay()
    }

    private fun notifySimulationStopped() {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            val channelId = "pf_notice"
            manager.createNotificationChannel(
                NotificationChannel(channelId, "${getString(R.string.app_name)}提示", NotificationManager.IMPORTANCE_LOW)
            )
            val notification = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("模拟定位已停止")
                .setContentText("真实 GPS 已恢复")
                .setAutoCancel(true)
                .build()
            manager.notify(99, notification)
        }
    }

    // ---------------- 内部 ----------------

    private fun sendCommand(action: String) {
        if (!RunSessionHolder.sessionActive) return
        ContextCompat.startForegroundService(this, RunSessionService.commandIntent(this, action))
    }

    private fun observeSession() {
        lifecycleScope.launch {
            // 悬浮胶囊是常驻窗口：退到后台（onStop）后窗口仍在屏幕上，里程/配速必须继续刷新。
            // 之前跟随 STARTED 会导致离开 App 后胶囊冻结在旧数字，看起来像模拟已停止。
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                RunSessionHolder.state.collect { state ->
                    latestState = state
                    renderHeader(state)
                    renderCapsule(state)
                }
            }
        }
    }

    /**
     * 通知 + 计步两个软权限合并成一次 requestPermissions 弹窗：
     * 同一帧内连发两次请求时，部分系统版本第二次会顶掉/取消第一次，
     * Android 13+ 的 POST_NOTIFICATIONS 弹窗可能永远不出现。
     * 不阻塞开跑：本次运行未授权的功能按各自降级路径静默禁用，
     * 授权后下一次运行自动生效。
     *
     * 计步守护默认关闭，此时**不再申请 ACTIVITY_RECOGNITION**：
     * 「身体活动」是敏感权限，没打算用就不该向用户要。
     */
    private fun ensureNotificationAndRecognitionPermissions() {
        val stepGuardEnabled =
            com.zcshou.gogogo.run.data.prefs.AdvancedPrefs.isStepGuardEnabled(this)
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@RunHomeActivity, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (stepGuardEnabled &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this@RunHomeActivity, Manifest.permission.ACTIVITY_RECOGNITION) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_NOTIFICATIONS)
        }
    }

    /** 桌面快捷方式 1 步开跑 */
    private fun handleShortcutIntent(intent: Intent?) {
        val routeId = intent?.getLongExtra(
            com.zcshou.gogogo.run.guard.RunShortcutManager.EXTRA_SHORTCUT_ROUTE_ID, -1
        ) ?: return
        if (routeId <= 0) return
        intent.removeExtra(com.zcshou.gogogo.run.guard.RunShortcutManager.EXTRA_SHORTCUT_ROUTE_ID)
        lifecycleScope.launch {
            val route = repository.getRoute(routeId) ?: return@launch
            startRun(
                route,
                route.lastTargetDistanceKm,
                route.lastTargetPaceSecPerKm,
                route.lastSmartJitterEnabled,
                true
            )
        }
    }

    /** 桌面固定快捷方式：一键发车 / 自由漫游 / 停止模拟 */
    private fun handleQuickActionShortcut(intent: Intent?) {
        val action = intent?.getStringExtra(EXTRA_SHORTCUT_ACTION) ?: return
        intent.removeExtra(EXTRA_SHORTCUT_ACTION)
        when (action) {
            ACTION_ROAM -> selectTab(1)
            ACTION_STOP -> stopSimulation()
            ACTION_QUICK_RUN -> lifecycleScope.launch {
                val route = repository.observeRoutes().firstOrNull()?.firstOrNull()
                if (route == null) {
                    toast("请先创建一条跑圈路线")
                    return@launch
                }
                startRun(
                    route,
                    route.lastTargetDistanceKm,
                    route.lastTargetPaceSecPerKm,
                    route.lastSmartJitterEnabled,
                    true
                )
            }
        }
    }

    /** 定时降级通知点按进入时自动开跑 */
    private fun handleAutoStartIntent(intent: Intent?) {
        val routeId = intent?.getLongExtra(RunScheduleNotifier.EXTRA_AUTO_ROUTE_ID, -1) ?: return
        if (routeId <= 0) return
        val km = intent.getFloatExtra(RunScheduleNotifier.EXTRA_AUTO_KM, 3.0f)
        val pace = intent.getIntExtra(RunScheduleNotifier.EXTRA_AUTO_PACE, 340)
        val linkedPkg = intent.getStringExtra(RunScheduleNotifier.EXTRA_AUTO_LINKED_PKG)
        intent.removeExtra(RunScheduleNotifier.EXTRA_AUTO_ROUTE_ID)
        lifecycleScope.launch {
            val route = repository.getRoute(routeId) ?: return@launch
            if (!SetupGuard.isReady(this@RunHomeActivity)) {
                openSetupWizard()
                return@launch
            }
            if (linkedPkg != null) {
                // 定时任务绑定了跑步 App：直接带包名下发给服务
                RoamService.stop(this@RunHomeActivity)
                ContextCompat.startForegroundService(
                    this@RunHomeActivity,
                    RunSessionService.startIntent(
                        this@RunHomeActivity, route.id, km, pace, true, true, linkedPkg
                    )
                )
                toast("已发车，正在拉起关联 App")
            } else {
                startRun(route, km, pace, true, true)
            }
        }
    }

    private fun applyBottomBarInsets() {
        // 与 LiquidTabBar.NavMetrics 同源：轨道 72 + 上下各 8dp 留白。
        // 迷你胶囊 48dp 高，落在同一条轨道的中线上，所以它的底距要多 12dp。
        val barBase = dp(LiquidTabBar.NavMetrics.BLOCK_PADDING_DP.toInt())
        val miniBase = barBase + dp(((LiquidTabBar.NavMetrics.TRACK_DP - 48f) / 2f).toInt())
        val statusBase = dp((LiquidTabBar.NavMetrics.BLOCK_PADDING_DP * 2 +
            LiquidTabBar.NavMetrics.TRACK_DP).toInt())
        ViewCompat.setOnApplyWindowInsetsListener(bottomTabs) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (target.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.bottomMargin = barBase + bars.bottom
                target.layoutParams = lp
            }
            // 玻璃背景、导航容器与迷你胶囊共用同一个底边距
            findViewById<View>(R.id.navGlass)?.let { glassView ->
                (glassView.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    lp.bottomMargin = barBase + bars.bottom
                    glassView.layoutParams = lp
                }
            }
            findViewById<View>(R.id.navMiniGlass)?.let { mini ->
                (mini.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    lp.bottomMargin = miniBase + bars.bottom
                    mini.layoutParams = lp
                }
            }
            (homeStatus.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.bottomMargin = statusBase + bars.bottom
                homeStatus.layoutParams = lp
            }
            insets
        }
        ViewCompat.requestApplyInsets(bottomTabs)
    }

    /**
     * 底栏三格取色。未选中态是 `dr_text_unselected` 再乘 50% 透明 —— 与 capybara
     * 的 `onSurfaceVariant.copy(alpha = 0.5f)` 同一档，透镜滑过去时才"点亮"。
     * 选中态图标取强调色、文字取中性前景（文字比图标更沉，避免整格发亮）。
     */
    private fun applyNavTabTints() {
        val unselected = androidx.core.graphics.ColorUtils.setAlphaComponent(
            getColor(R.color.dr_text_unselected), 128
        )
        val accent = getColor(R.color.pf_volt)
        val labelSelected = getColor(R.color.pf_text_primary)
        listOf(tabRoutes, tabRoam, tabEnv, tabSettings).forEach { tab ->
            tab.tintUnselected = unselected
            tab.tintAccent = accent
            tab.tintLabelSelected = labelSelected
        }
    }

    private fun selectTab(index: Int) {
        selectedTab = index
        val tags = arrayOf(TAB_AUTO, TAB_ROAM, TAB_ENV, TAB_SETTINGS)
        supportFragmentManager.beginTransaction().apply {
            tags.forEachIndexed { i, tag ->
                val fragment = supportFragmentManager.findFragmentByTag(tag)
                    ?: return@forEachIndexed
                if (i == index) show(fragment) else hide(fragment)
            }
            commit()
        }
        // 图标/文字的点亮与字重交叉淡入由 NavTabView 按透镜权重逐帧插值，
        // 这里只把透镜推过去；Tab 自身不携带任何实心底托。
        tabBar.select(index)
        renderHeader(lastState)
        updateNavMiniContent()
        if (navMinimized) expandNav()
        com.zcshou.gogogo.run.ui.glass.GlassRenderGate.requestRefresh(320L)
    }

    /** 首页列表滚动：向下收成迷你胶囊，向上展开。 */
    fun onHomeScrolled(dy: Int, scrollY: Int) {
        if (selectedTab != 0) return
        if (navMinimized) {
            if (dy < -6) expandNav()
        } else if (dy > 6 && scrollY > dp(40)) {
            minimizeNav()
        }
    }

    private fun minimizeNav() {
        if (navMinimized) return
        navMinimized = true
        animateNavFraction(1f)
    }

    fun expandNav() {
        if (!navMinimized) return
        navMinimized = false
        animateNavFraction(0f)
    }

    private fun animateNavFraction(target: Float) {
        navFractionSpring?.cancel()
        navFractionSpring = SpringAnimation(FloatValueHolder(navMinimizeFraction)).apply {
            setSpring(
                SpringForce(target).apply {
                    dampingRatio = 0.82f
                    stiffness = 320f
                }
            )
            addUpdateListener { _, value, _ -> applyNavFraction(value) }
            start()
        }
    }

    private fun applyNavFraction(raw: Float) {
        if (!::navContainerView.isInitialized || !::navMiniGlass.isInitialized) return
        val f = raw.coerceIn(0f, 1f)
        navMinimizeFraction = f
        val alpha = (1f - f * 1.6f).coerceIn(0f, 1f)
        val scaleX = 1f - 0.82f * f
        val scaleY = 1f - 0.30f * f
        listOf(navContainerView, navGlassView).forEach { view ->
            view.pivotX = view.width / 2f
            view.pivotY = view.height / 2f
            view.scaleX = scaleX
            view.scaleY = scaleY
            view.alpha = alpha
            view.visibility = if (alpha <= 0.02f) View.INVISIBLE else View.VISIBLE
        }
        navShadowView.alpha = (1f - f).coerceIn(0f, 1f)
        val miniAlpha = ((f - 0.35f) / 0.65f).coerceIn(0f, 1f)
        val showMini = miniAlpha > 0.01f
        if (showMini && navMiniGlass.visibility != View.VISIBLE) {
            // 迷你胶囊从透明里浮出时重拍一次背景，保证第一帧就是真实折射而不是空白
            com.zcshou.gogogo.run.ui.glass.GlassRenderGate.requestRefresh(320L)
        }
        navMiniGlass.alpha = miniAlpha
        navMiniGlass.scaleX = 0.55f + 0.45f * f
        navMiniGlass.scaleY = 0.55f + 0.45f * f
        navMiniGlass.visibility = if (showMini) View.VISIBLE else View.GONE
    }

    private fun updateNavMiniContent() {
        if (!::navMini.isInitialized) return
        val tab = when (selectedTab) {
            0 -> tabRoutes
            1 -> tabRoam
            2 -> tabEnv
            else -> tabSettings
        }
        navMini.text = tab.label
        // 迷你胶囊的图标与格子同源：把同一份 NavGlyph 画成 20dp 的 Drawable。
        // compound drawable 的 tintList 对自绘 Drawable 不生效，直接设色。
        val color = getColor(R.color.pf_volt)
        val glyph = NavGlyphDrawable(resources.displayMetrics.density, tab.spec, dp(20))
        glyph.setGlyphTint(color)
        navMini.setCompoundDrawablesRelativeWithIntrinsicBounds(glyph, null, null, null)
        navMini.setTextColor(color)
        TextViewCompat.setCompoundDrawableTintList(navMini, ColorStateList.valueOf(color))
        if (::navMiniGlass.isInitialized && navMiniGlass.isShown) {
            com.zcshou.gogogo.run.ui.glass.GlassRenderGate.requestRefresh(160L)
        }
    }

    private fun renderHeader(state: RunRuntimeState) {
        lastState = state
        val idle = state.phase == RunPhase.IDLE || state.phase == RunPhase.FINISHED
        homeStatus.visibility =
            if (idle || selectedTab == 1) android.view.View.GONE else android.view.View.VISIBLE
        homeStatus.text = when (state.phase) {
            RunPhase.IDLE -> "待命 · 选择路线后一键开跑"
            RunPhase.ENV_GUARD -> "发车前体检…"
            RunPhase.PRE_START_SETTLE -> String.format(
                java.util.Locale.US,
                "预热中 · %d s 后起跑 · 请切到跑步 App 准备",
                state.settleRemainSec
            )
            RunPhase.FINISHED -> "已结束 · ${state.message.orEmpty()}"
            else -> String.format(
                java.util.Locale.US,
                "%s · 已跑 %.2f / %.2f km · 配速 %d'%02d\"",
                when (state.phase) {
                    RunPhase.RUNNING -> "跑圈中"
                    RunPhase.PAUSED -> "已暂停"
                    RunPhase.DETOUR_POINT -> "绕路打卡"
                    RunPhase.COOLDOWN_WALK -> "慢走收尾"
                    else -> state.phase.name
                },
                state.accumulatedKm,
                state.targetKm,
                state.paceSecPerKm / 60,
                state.paceSecPerKm % 60
            )
        }
    }

    private fun renderCapsule(state: RunRuntimeState) {
        val active = state.phase == RunPhase.ENV_GUARD ||
            state.phase == RunPhase.PRE_START_SETTLE ||
            state.phase == RunPhase.RUNNING ||
            state.phase == RunPhase.DETOUR_POINT ||
            state.phase == RunPhase.PAUSED ||
            state.phase == RunPhase.COOLDOWN_WALK
        if (active) showCapsule() else hideCapsule()
        capsule?.update(state)
    }

    private fun showCapsule() {
        if (capsule != null) return
        if (!Settings.canDrawOverlays(this)) return
        val view = FloatingCapsuleView(ThemeHelper.themedContext(this), this)
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val prefs = getSharedPreferences("pf_overlay", Context.MODE_PRIVATE)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 1f
            x = dp(16)
            y = prefs.getInt("y", dp(140))
                .coerceIn(statusBarHeight(), screenHeight() - dp(120))
        }
        view.dragHandler = { dx, dy, end ->
            params.x = (params.x + dx).coerceIn(0, (screenWidth() - view.width).coerceAtLeast(0))
            params.y = (params.y + dy).coerceIn(
                statusBarHeight(),
                (screenHeight() - view.height - navBarHeight()).coerceAtLeast(statusBarHeight())
            )
            runCatching { wm.updateViewLayout(view, params) }
            if (end) snapToEdge(view, wm, params)
        }
        // 悬浮窗不主动开启 blurBehindRadius（Android 12+ 的 Dim Layer 会吞掉窗外触摸），
        // 但监听系统跨窗模糊可用性：不可用时胶囊自动切到 94% 高密度磨砂，杜绝透字。
        val crossWindowBlur = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { wm.isCrossWindowBlurEnabled }.getOrDefault(false)
        } else {
            false
        }
        view.setCrossWindowBlurEnabled(crossWindowBlur)
        runCatching { wm.addView(view, params) }.onSuccess {
            capsule = view
            windowManager = wm
            overlayParams = params
            view.post {
                if (prefs.getInt("side", 1) == 1 && params.x < screenWidth() / 2) {
                    params.x = (screenWidth() - view.width - dp(8)).coerceAtLeast(dp(8))
                    runCatching { wm.updateViewLayout(view, params) }
                }
                relayoutOverlay()
            }
        }
    }

    /** 松手后弹簧吸附到最近左右边缘，并记住纵向高度 */
    private fun snapToEdge(
        view: android.view.View,
        wm: WindowManager,
        params: WindowManager.LayoutParams
    ) {
        OverlayGlassStyle.springSnapToEdgeX(view, wm, params) {
            val side = if (params.x + view.width / 2 < screenWidth() / 2) 0 else 1
            getSharedPreferences("pf_overlay", Context.MODE_PRIVATE).edit()
                .putInt("side", side)
                .putInt("y", params.y)
                .apply()
        }
    }

    private fun hideCapsule() {
        val view = capsule ?: return
        relayoutRunnable?.let { view.removeCallbacks(it) }
        relayoutBoundsRunnable?.let { view.removeCallbacks(it) }
        relayoutRunnable = null
        relayoutBoundsRunnable = null
        OverlayGlassStyle.cancelSnap(view)
        runCatching { windowManager?.removeView(view) }
        capsule = null
        windowManager = null
        overlayParams = null
    }

    private fun screenWidth(): Int = resources.displayMetrics.widthPixels

    private fun screenHeight(): Int = resources.displayMetrics.heightPixels

    private fun statusBarHeight(): Int =
        resources.getIdentifier("status_bar_height", "dimen", "android")
            .takeIf { it > 0 }?.let { resources.getDimensionPixelSize(it) } ?: dp(24)

    private fun navBarHeight(): Int =
        resources.getIdentifier("navigation_bar_height", "dimen", "android")
            .takeIf { it > 0 }?.let { resources.getDimensionPixelSize(it) } ?: dp(48)

    private fun toast(message: String) =
        com.zcshou.gogogo.run.ui.glass.GlassFeedback.toast(this, message)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}