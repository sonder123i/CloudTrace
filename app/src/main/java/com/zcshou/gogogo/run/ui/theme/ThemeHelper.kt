package com.zcshou.gogogo.run.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.view.View
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.ui.glass.DeepRunGlassManager

/**
 * V4.0 双生主题：深海光学（Abyssal Optics · 暗）/ 冰川棱镜（Glacial Prism · 亮）/ 跟随系统。
 *
 * 重要：本工程一直是「手动 setTheme」切换，不依赖系统 uiMode。但 V4.0 的昼夜 Token
 * 放在 res/values-night 下，必须让 uiMode 与用户选择保持一致，否则会出现
 * 「手动选浅色 + 系统深色」错误命中 night Token 的经典错色。
 * 因此每次切换主题时都用 [applyGlobalNightMode] 把 AppCompat 的默认夜间模式对齐过去。
 */
object ThemeHelper {

    enum class ThemeMode {
        /** 深海光学（暗色） */
        DARK,

        /** 冰川棱镜（浅色） */
        LIGHT,

        /** 跟随系统 */
        SYSTEM
    }

    private const val PREFS = "pf_prefs"
    private const val KEY_DARK = "dark_theme"
    private const val KEY_THEME_MODE = "theme_mode"

    /**
     * 进程启动瞬间的**系统**昼夜模式。
     *
     * 必须在 [applyGlobalNightMode] 之前采集（见 GoApplication.onCreate）：
     * AppCompat 会把应用级 Resources 的 uiMode 改成用户选择，之后再读就只剩「应用选择」了。
     * 保留它是为了对齐系统 SplashWindow——那一层由系统进程按系统 uiMode 解析，
     * 拿不到应用内的手动选择。
     */
    @Volatile
    private var systemNightMode: Boolean? = null

    /** Application.onCreate 里最先调用，采集真正的系统昼夜。 */
    fun captureSystemNightMode(context: Context) {
        systemNightMode = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /** 系统（而非应用内选择）当前是否深色；未采集时退回当前资源配置。 */
    fun isSystemDark(context: Context): Boolean = systemNightMode
        ?: ((context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun themeMode(context: Context): ThemeMode {
        val stored = prefs(context).getString(KEY_THEME_MODE, null)
        if (stored != null) {
            runCatching { ThemeMode.valueOf(stored) }.getOrNull()?.let { return it }
        }
        // 迁移旧版布尔开关：默认浅色（日光白）。
        //
        // 曾经这里兜底 true（暗色）。暗色适合 OLED 省电，但首页/体检/路线这些
        // 页面都是照浅色玻璃调的：暗色下透镜边缘高光、卡片描边全部压到看不见，
        // 观感反而更糊。浅色作为默认值，用户在体检页仍可一键切到曜石黑。
        return if (prefs(context).getBoolean(KEY_DARK, false)) ThemeMode.DARK else ThemeMode.LIGHT
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        prefs(context).edit()
            .putString(KEY_THEME_MODE, mode.name)
            .putBoolean(KEY_DARK, isDarkFor(context, mode))
            .apply()
    }

    /**
     * 写入主题并同步全局夜间模式。AppCompat 会在 uiMode 真正变化时自动重建 Activity，
     * 调用方不要再手动 recreate，否则会二次重建。
     */
    fun applyThemeMode(context: Context, mode: ThemeMode) {
        setThemeMode(context, mode)
        applyGlobalNightMode(context)
        DeepRunGlassManager.refreshAll()
    }

    fun isDark(context: Context): Boolean = isDarkFor(context, themeMode(context))

    private fun isDarkFor(context: Context, mode: ThemeMode): Boolean = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /** 供 Application 启动时调用，保证进程内资源限定符与用户选择一致。 */
    fun applyGlobalNightMode(context: Context) {
        val nightMode = when (themeMode(context)) {
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != nightMode) {
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }
    }

    /** 兼容旧调用点。 */
    fun setDark(context: Context, dark: Boolean) {
        setThemeMode(context, if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
        applyGlobalNightMode(context)
    }

    fun apply(activity: AppCompatActivity) {
        activity.setTheme(
            if (isDark(activity)) R.style.Theme_PaceFlow else R.style.Theme_PaceFlow_Light
        )
    }

    /**
     * 给悬浮窗（Service 托管的 View）用的主题化 Context。
     * Service 的资源 uiMode 跟随系统，而不是 App 内手动选择的主题；直接 inflate 会命中
     * 错误昼夜 Token（暗色 App 里出现白色悬浮盘）。这里对齐 uiMode 并套上对应 Theme。
     */
    fun themedContext(context: Context): Context {
        val nightYes = isDark(context)
        val config = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (nightYes) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val base = context.createConfigurationContext(config)
        return ContextThemeWrapper(
            base,
            if (nightYes) R.style.Theme_PaceFlow else R.style.Theme_PaceFlow_Light
        )
    }

    /** 沉浸式 Edge-to-Edge（兼容 API 27+，不依赖 activity 1.8 的 enableEdgeToEdge） */
    fun enableEdgeToEdge(activity: AppCompatActivity) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
    }

    /** 把状态栏高度加到目标 View 顶部内边距，避免内容被状态栏遮挡 */
    fun padTopForStatusBar(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            target.setPadding(target.paddingLeft, top, target.paddingRight, target.paddingBottom)
            insets
        }
    }

    /** 状态栏 + 导航栏双向避让（全屏地图/首页统一使用） */
    fun padForSystemBars(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            target.setPadding(target.paddingLeft, bars.top, target.paddingRight, bars.bottom)
            insets
        }
    }
}
