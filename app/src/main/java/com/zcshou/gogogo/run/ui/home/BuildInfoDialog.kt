package com.zcshou.gogogo.run.ui.home

import android.app.Activity
import android.graphics.Typeface
import android.os.Build
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import com.zcshou.gogogo.BuildConfig
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.diagnostics.CrashJournal
import com.zcshou.gogogo.run.diagnostics.Diagnostics
import com.zcshou.gogogo.run.ui.glass.GlassDialogs
import com.zcshou.gogogo.run.ui.glass.GlassFeedback

/**
 * 构建信息：开源项目的透明窗口，也方便核对更新包签名。
 *
 * 显示 versionName/versionCode、构建类型、签名证书 SHA-256、更新源、日志目录，
 * 一键复制后可随问题反馈一起发送。
 */
object BuildInfoDialog {

    fun show(activity: Activity) {
        val text = buildText(activity)
        val content = TextView(activity).apply {
            this.text = text
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(activity.getColor(R.color.pf_text_secondary))
            setTextIsSelectable(true)
            val pad = dp(activity, 20)
            setPadding(pad, dp(activity, 8), pad, dp(activity, 8))
        }
        val scroll = ScrollView(activity).apply {
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                minOf(
                    dp(activity, 340),
                    (activity.resources.displayMetrics.heightPixels * 0.55f).toInt()
                )
            )
        }

        GlassDialogs.builder(activity)
            .setTitle(R.string.build_info_title)
            .setView(scroll)
            .setPositiveButton(R.string.build_copy) { _, _ ->
                if (Diagnostics.copy(activity, text)) {
                    GlassFeedback.toast(activity, activity.getString(R.string.build_copied))
                }
            }
            .setNegativeButton(R.string.build_close, null)
            .create()
            .show()
    }

    private fun buildText(activity: Activity): String {
        val logDir = java.io.File(activity.filesDir, Diagnostics.LOG_DIR)
        val recentCrash = CrashJournal.read(activity)
        return buildString {
            appendLine("版本名称: ${BuildConfig.VERSION_NAME}")
            appendLine("版本号: ${BuildConfig.VERSION_CODE}")
            appendLine("构建类型: ${BuildConfig.BUILD_TYPE}")
            appendLine("包名: ${activity.packageName}")
            appendLine("签名 SHA-256: ${Diagnostics.signerSha256(activity)}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} / ${Build.BRAND}")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString(",")}")
            appendLine("安装来源: ${Diagnostics.installerPackage(activity)}")
            appendLine("更新源: ${BuildConfig.UPDATE_MANIFEST_URL}")
            appendLine("备用更新源: ${BuildConfig.UPDATE_BACKUP_URL}")
            appendLine("日志目录: ${logDir?.absolutePath ?: "不可用"}")
            if (recentCrash != null) {
                appendLine("最近崩溃: ${CrashJournal.formatTime(CrashJournal.lastCrashAt(activity))}")
            }
        }
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}