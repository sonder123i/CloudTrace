package com.zcshou.gogogo.run.ui.glass

import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import com.example.liquidglass.LiquidGlassView
import com.zcshou.gogogo.run.data.prefs.DisplayPrefs
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.max

/**
 * V6.4 光学玻璃刷新闸门（单节拍共享采样 + 质量分级）。
 *
 * 液态玻璃库默认每块玻璃在 onDraw 末尾 `invalidate()` 自循环，N 块面板就会
 * 各自抢帧重拍背景。这里统一关掉自循环，改为集中驱动：
 *
 * - **33ms（30fps）节拍上限**：拖动/缩放再快也不会把 GPU 拖满；
 *   省电平衡档自动放宽到 [BALANCED_FRAME_MS]（15fps）；
 * - **互动窗口 + 冷却**：每次 [requestRefresh] 顺延窗口，最后一次交互结束后
 *   [COOLDOWN_MS] 内收尾一帧即停，静止**严格 0fps**；
 * - **两级作用域**：地图页只重绘地图面板（[requestMapRefresh]），
 *   不牵连首页静态页的玻璃；首页滚动/底栏拖动才刷新全部；
 * - Activity onPause / 锁屏立即 [setActive] false：后台巡航不再刷新着色器、
 *   连倾角传感器一并注销。
 */
object GlassRenderGate {

    /** 光学档刷新节拍上限：33ms ≈ 30fps。 */
    private const val OPTICAL_FRAME_MS = 33L

    /** 省电平衡档：66ms ≈ 15fps，够看出地图在动即可。 */
    private const val BALANCED_FRAME_MS = 66L

    /** 最后一次交互之后继续刷新的冷却窗口。 */
    private const val COOLDOWN_MS = 180L

    private val views = Collections.newSetFromMap(
        WeakHashMap<LiquidGlassView, Boolean>()
    )

    /** 地图页面板子集（顶栏 / 控制台 / 落点卡 / 预览条）。 */
    private val mapViews = Collections.newSetFromMap(
        WeakHashMap<LiquidGlassView, Boolean>()
    )

    private var active = true
    private var scheduled = false

    private var allUntilMs = 0L
    private var mapUntilMs = 0L
    private var lastFrameAt = 0L

    /**
     * 省电平衡档标记。**只缓存布尔，不持 Context** —— 静态单例持有 Context
     * 会被 lint 判为泄漏，而且每次 requestRefresh 都去读一次 Prefs 也没必要。
     */
    @Volatile
    private var balancedQuality = false

    /** API 36 色彩管线快路径是否可用（探测一次，异常即永久关闭）。 */
    private val api36ColorPipeline: Boolean by lazy { GlassApi36Support.probe() }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            scheduled = false
            if (!active) return
            val now = SystemClock.uptimeMillis()
            val allActive = now < allUntilMs
            val mapActive = now < mapUntilMs
            if (!allActive && !mapActive) {
                // 冷却结束：按刚刚活跃的作用域收尾一帧，确保最终位置清晰，然后归零
                val hadAll = allUntilMs > 0L
                allUntilMs = 0L
                mapUntilMs = 0L
                if (hadAll) invalidateAll() else invalidateMaps()
                lastFrameAt = now
                return
            }
            if (now - lastFrameAt >= frameIntervalMs()) {
                lastFrameAt = now
                if (allActive) invalidateAll() else invalidateMaps()
            }
            schedule()
        }
    }

    private fun frameIntervalMs(): Long =
        if (balancedQuality) BALANCED_FRAME_MS else OPTICAL_FRAME_MS

    // ---------------------------------------------------------------- 注册

    fun register(view: LiquidGlassView) {
        // 统一在这里对齐光学参数，调用方无需再各自试数
        OpticalGlassTuning.apply(view)
        synchronized(views) { views.add(view) }
        balancedQuality = DisplayPrefs.quality(view.context) == DisplayPrefs.RenderQuality.BALANCED
        // 自循环交给我们统一驱动，避免每块玻璃各抢一帧
        view.enableDynamicBackground = false
        view.collectFrameStats = false
        // 省电平衡档不挂传感器：倾角高光在静态磨砂上毫无意义，只会白耗电
        if (view is CausticGlassView) {
            view.setSensorLightEnabled(isSensorAllowed(view))
        }
        requestRefresh(80)
    }

    /** 地图页面板：额外登记到子集，交互时只重绘这些。 */
    fun registerMapPanel(view: LiquidGlassView) {
        register(view)
        synchronized(mapViews) { mapViews.add(view) }
    }

    fun unregister(view: LiquidGlassView) {
        synchronized(views) { views.remove(view) }
        synchronized(mapViews) { mapViews.remove(view) }
    }

    // ---------------------------------------------------------------- 刷新请求

    /** 交互期间请求刷新（全量玻璃）；重复调用会顺延窗口，结束后冷却到 0fps。 */
    fun requestRefresh(durationMs: Long = COOLDOWN_MS) {
        val until = SystemClock.uptimeMillis() + max(durationMs, COOLDOWN_MS)
        allUntilMs = max(allUntilMs, until)
        if (active) schedule()
    }

    /** 地图相机移动：只刷新地图面板，首页静态玻璃完全不受牵连。 */
    fun requestMapRefresh(durationMs: Long = COOLDOWN_MS) {
        val until = SystemClock.uptimeMillis() + max(durationMs, COOLDOWN_MS)
        mapUntilMs = max(mapUntilMs, until)
        if (active) schedule()
    }

    /** Activity / Fragment 前后台切换。 */
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value) {
            val snapshot = synchronized(views) { views.toList() }
            snapshot.firstOrNull()?.let {
                balancedQuality =
                    DisplayPrefs.quality(it.context) == DisplayPrefs.RenderQuality.BALANCED
            }
            for (view in snapshot) {
                (view as? CausticGlassView)?.setSensorLightEnabled(isSensorAllowed(view))
            }
            requestRefresh(320L)
        } else {
            scheduled = false
            allUntilMs = 0L
            mapUntilMs = 0L
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            // 后台/锁屏时连触摸焦散与倾角传感器一并熄灭：严格 0 帧、0 唤醒
            val snapshot = synchronized(views) { views.toList() }
            for (view in snapshot) {
                (view as? CausticGlassView)?.let {
                    it.cancelCaustics()
                    it.setSensorLightEnabled(false)
                }
            }
        }
    }

    /** 供设置页 / 调试展示：API 36 色彩管线是否已启用。 */
    fun isApi36ColorPipelineActive(): Boolean = api36ColorPipeline

    // ---------------------------------------------------------------- 内部

    private fun isSensorAllowed(view: LiquidGlassView): Boolean =
        active && DeepRunGlassManager.tier(view.context) != DeepRunGlassManager.Tier.BALANCED_MATTE

    private fun invalidateAll() {
        val snapshot = synchronized(views) { views.toList() }
        for (view in snapshot) {
            if (view.isAttachedToWindow && view.isShown) view.invalidate()
        }
    }

    private fun invalidateMaps() {
        val snapshot = synchronized(mapViews) { mapViews.toList() }
        for (view in snapshot) {
            if (view.isAttachedToWindow && view.isShown) view.invalidate()
        }
    }

    private fun schedule() {
        if (scheduled) return
        scheduled = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }
}

/**
 * API 36 色彩管线快路径探测。
 *
 * 上游 `GlassRuntimeEffects` 是 **Kotlin `internal`**，本工程无法直接调用；
 * 这里用同一套平台 API（`RuntimeShader` + `RuntimeColorFilter`）自建等价实现：
 * 只有在 API 36 且 AGSL 色彩滤镜真的能编译通过时才启用，否则继续走本地
 * AGSL 配方里的鲜艳度计算。任何异常都被 `runCatching` 吃掉并永久降级。
 *
 * 真正的平台调用隔离在 [PlatformColorEffects] 里：类加载发生在 `SDK_INT >= 36`
 * 判断之后，API 27–35 永远不会解析 `RuntimeColorFilter` 相关符号。
 */
internal object GlassApi36Support {

    @Volatile
    private var probed = false

    @Volatile
    private var available = false

    fun probe(): Boolean {
        if (probed) return available
        probed = true
        if (Build.VERSION.SDK_INT < 36) return false
        available = PlatformColorEffects.isSupported()
        return available
    }

    /** 鲜艳度滤镜；不可用时返回 null，调用方保持默认合成。 */
    fun vibrancyFilter(): android.graphics.ColorFilter? {
        // 显式 SDK 判断放在最外层：既保证 API 27–35 不解析平台符号，也让 lint 的
        // NewApi 数据流能证明这里安全（probe() 内部的同款判断 lint 看不到）。
        if (Build.VERSION.SDK_INT < 36) return null
        return if (probe()) PlatformColorEffects.vibrancy() else null
    }
}

/**
 * 平台运行期色彩效果（AGSL ColorFilter）。
 *
 * 只在 API 36 且已确认可编译的前提下才会被加载执行。
 */
@androidx.annotation.RequiresApi(36)
private object PlatformColorEffects {

    /** RuntimeColorFilter 的入口签名固定为 `half4 main(half4 color)`。 */
    private const val VIBRANCY_AGSL = """
        half4 main(half4 color) {
            half l = dot(color.rgb, half3(0.2126, 0.7152, 0.0722));
            half3 rgb = mix(half3(l), color.rgb, half(1.18));
            rgb = rgb * half3(half(1.03));
            return half4(rgb, color.a);
        }
    """

    private var vibrancy: android.graphics.RuntimeColorFilter? = null
    private var vibrancyResolved = false

    fun isSupported(): Boolean = runCatching {
        // RuntimeColorFilter 直接吃 AGSL 源码；能在本机编译通过才算可用
        android.graphics.RuntimeColorFilter(VIBRANCY_AGSL) != null
    }.getOrDefault(false)

    fun vibrancy(): android.graphics.ColorFilter? {
        if (vibrancyResolved) return vibrancy
        vibrancyResolved = true
        vibrancy = runCatching {
            android.graphics.RuntimeColorFilter(VIBRANCY_AGSL)
        }.getOrNull()
        return vibrancy
    }
}
