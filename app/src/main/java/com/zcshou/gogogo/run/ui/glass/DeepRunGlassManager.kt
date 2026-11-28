package com.zcshou.gogogo.run.ui.glass

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.zcshou.gogogo.run.data.prefs.DisplayPrefs
import com.zcshou.gogogo.run.service.RenderGate
import java.util.Collections
import java.util.WeakHashMap

/**
 * V6.4 玻璃材质调度中心（真实分层 + 光学管线闸门）。
 *
 * 职责：
 * 1. 维护同屏玻璃控件注册表，主题 / 画质切换时统一刷新材质与静态画布快照；
 * 2. 按「API 等级 + 用户画质 + 系统省电 / 高对比度 + 巡航冻结」实时判定激活层级，
 *    给 `LiquidGlassContainer` / `CausticGlassView` / 设置页文案提供**唯一真相**。
 *
 * | Tier | 触发条件 | 管线 |
 * |------|----------|------|
 * | [Tier.OPTICAL_AGSL] | API 33+ · 液态玻璃档 · 无系统省电 / 高对比度 | 静态画布共享 AGSL 折射 |
 * | [Tier.OPTICAL_LENS] | API 27–32 · 液态玻璃档 | QWEA0 玻璃管线（共享刷新节拍） |
 * | [Tier.BALANCED_MATTE] | 省电平衡 / 系统省电 / 高对比度 / 巡航冻结 | 零采样静态磨砂 |
 *
 * 本 object 同时是 run/service.RenderGate 的实现方：服务层（RunSessionService）
 * 只依赖接口发布「巡航/锁屏冻结渲染」，不反向 import UI 包。
 */
object DeepRunGlassManager : RenderGate {

    init {
        // object 与应用同生命周期，注册一次即可；服务层经 RenderGate 接口调用本实现
        RenderGate.register(this)
    }

    /** 材质层级：与 [tier] 的实时判定一一对应。 */
    enum class Tier(val title: String, val detail: String) {
        /** API 33+ 光学档：静态画布共享折射，滚动零截屏。 */
        OPTICAL_AGSL("光学液态折射", "静态画布共享折射 · 边缘透镜 · 滚动零截屏"),

        /** API 27–32 光学档：QWEA0 玻璃管线，按交互节拍刷新。 */
        OPTICAL_LENS("液态玻璃折射", "背景磨砂 + 边缘折射 · 共享刷新节拍"),

        /** 省电档：零采样静态磨砂 + 镜面切边。 */
        BALANCED_MATTE("高透静态磨砂", "零采样磨砂 · 镜面切边 · 省电不卡顿")
    }

    private val views = Collections.newSetFromMap(
        WeakHashMap<LiquidGlassContainer, Boolean>()
    )
    private val suppressionListeners = Collections.newSetFromMap(
        WeakHashMap<() -> Unit, Boolean>()
    )

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var tierCache: Tier? = null

    /** 逐帧绘制只读这一个 volatile 布尔，绝不在 `draw()` 里读 Prefs / Secure 设置。 */
    @Volatile
    private var opticalActive: Boolean = false

    /** 巡航 RUNNING 或锁屏期间为 true。 */
    @Volatile
    var isRenderSuppressed: Boolean = false
        private set

    private var suppressReason: String = ""

    // ---------------------------------------------------------------- 层级判定

    fun tier(context: Context): Tier {
        tierCache?.let { return it }
        return computeTier(context)
    }

    /** 光学管线当前是否激活（AGSL 静态画布折射）。 */
    fun isOpticalPipelineActive(): Boolean = opticalActive

    /** 设置页副标题用：材质 + 可能的系统级降级提示。 */
    fun describe(context: Context): String {
        val current = tier(context)
        val reason = downgradeReason(context)
        return if (reason == null) current.detail else "${current.title}（$reason）"
    }

    fun downgradeReason(context: Context): String? = when {
        isPowerSave(context) -> "系统省电模式"
        isHighContrastText(context) -> "系统高对比度文字"
        DisplayPrefs.quality(context) == DisplayPrefs.RenderQuality.BALANCED -> "省电平衡"
        else -> null
    }

    private fun computeTier(context: Context): Tier {
        appContext = context.applicationContext
        val pipeline = GlassPipelineRules.resolve(
            sdkInt = Build.VERSION.SDK_INT,
            balancedQuality = DisplayPrefs.quality(context) == DisplayPrefs.RenderQuality.BALANCED,
            powerSave = isPowerSave(context),
            highContrast = isHighContrastText(context)
        )
        val current = when (pipeline) {
            GlassPipeline.OPTICAL_AGSL -> Tier.OPTICAL_AGSL
            GlassPipeline.OPTICAL_LENS -> Tier.OPTICAL_LENS
            GlassPipeline.BALANCED_MATTE -> Tier.BALANCED_MATTE
        }
        tierCache = current
        opticalActive = GlassPipelineRules.isOpticalActive(pipeline, isRenderSuppressed)
        return current
    }

    // ---------------------------------------------------------------- 省电标记

    /**
     * 进入 RUNNING / 锁屏时调用 true，恢复前台或结束后调用 false。
     * 冻结期间光学管线整体让位给静态材质：不采样、不刷新着色器、0fps。
     */
    override fun setRenderSuppressed(suppressed: Boolean, reason: String) {
        if (isRenderSuppressed == suppressed) return
        isRenderSuppressed = suppressed
        suppressReason = if (suppressed) reason else ""
        opticalActive = !suppressed && tierCache == Tier.OPTICAL_AGSL
        val listeners = synchronized(suppressionListeners) { suppressionListeners.toList() }
        listeners.forEach { it() }
    }

    fun addSuppressionListener(listener: () -> Unit) {
        synchronized(suppressionListeners) { suppressionListeners.add(listener) }
    }

    fun removeSuppressionListener(listener: () -> Unit) {
        synchronized(suppressionListeners) { suppressionListeners.remove(listener) }
    }

    fun suppressReason(): String = suppressReason

    // ---------------------------------------------------------------- 视图注册表

    internal fun register(view: LiquidGlassContainer) {
        synchronized(views) { views.add(view) }
        // 首个容器挂载时把层级算出来，保证 draw() 里的 volatile 读有值
        runCatching { tier(view.context) }
    }

    internal fun unregister(view: LiquidGlassContainer) {
        synchronized(views) { views.remove(view) }
    }

    /**
     * 画质 / 主题切换后统一刷新：
     * 1. 重算层级（synchronous，设置页文案与绘制读到同一结果）；
     * 2. 作废 [GlassLensRenderer] 的渐变缓存（Token 变了）；
     * 3. 作废所有静态画布快照（深浅色底图不同）；
     * 4. 让每个玻璃控件重新读色并重绘。
     */
    fun refreshAll() {
        appContext?.let { computeTier(it) }
        GlassLensRenderer.onThemeChanged()
        StaticBackdropProvider.invalidateAll()
        forEachView { it.onGlassEnvironmentChanged() }
    }

    internal fun forEachView(action: (LiquidGlassContainer) -> Unit) {
        val snapshot = synchronized(views) { views.toList() }
        snapshot.forEach(action)
    }

    // ---------------------------------------------------------------- 环境探测

    private fun isPowerSave(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isPowerSaveMode == true
    }.getOrDefault(false)

    private fun isHighContrastText(context: Context): Boolean = runCatching {
        // 系统「高对比度文字」开关（Settings.Secure 无公开常量，用官方键名）
        Settings.Secure.getInt(
            context.contentResolver,
            "high_text_contrast_enabled",
            0
        ) == 1
    }.getOrDefault(false)
}
