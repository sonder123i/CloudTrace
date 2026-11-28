package com.zcshou.gogogo.run.update

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.ui.glass.GlassDialogs
import com.zcshou.gogogo.run.ui.widget.MaxHeightScrollView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 应用更新弹窗（View 体系，不走 Compose）。
 *
 * 面板复用应用统一的玻璃弹窗与 96% 可读性内芯；按钮文案/动作随
 * [UpdateManager.state] 实时切换：下载 / 暂停 / 继续 / 安装。
 */
object UpdateDialog {

    fun show(activity: Activity, onDismiss: () -> Unit = {}): AlertDialog {
        val manager = UpdateManager.getInstance(activity)
        val view = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_app_update, null, false)
        val title = view.findViewById<TextView>(R.id.updateTitle)
        val version = view.findViewById<TextView>(R.id.updateVersion)
        val status = view.findViewById<TextView>(R.id.updateStatus)
        val progress = view.findViewById<ProgressBar>(R.id.updateProgress)
        val size = view.findViewById<TextView>(R.id.updateSize)
        val forceNote = view.findViewById<TextView>(R.id.updateForceNote)
        val notesScroll = view.findViewById<MaxHeightScrollView>(R.id.updateNotesScroll)
        notesScroll.maxHeight = (160 * activity.resources.displayMetrics.density).toInt()
        val notesLabel = view.findViewById<TextView>(R.id.updateNotesLabel)
        val notes = view.findViewById<TextView>(R.id.updateNotes)
        val cancelDownload = view.findViewById<TextView>(R.id.updateCancelDownload)

        var positiveAction: (() -> Unit)? = null
        var negativeAction: (() -> Unit)? = null
        var positiveText = "检查更新"
        var negativeText = "关闭"
        var positiveEnabled = true

        val dialog = GlassDialogs.builder(activity)
            .setView(view)
            .setPositiveButton(positiveText, null)
            .setNegativeButton(negativeText, null)
            .create()

        fun applyButtons() {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let {
                it.text = positiveText
                it.isEnabled = positiveEnabled
                it.setOnClickListener { positiveAction?.invoke() }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.let {
                it.text = negativeText
                it.setOnClickListener { negativeAction?.invoke() }
            }
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        dialog.setOnShowListener {
            applyButtons()
            cancelDownload.setOnClickListener {
                manager.pauseDownload(cancel = true)
            }
        }
        dialog.setOnDismissListener {
            scope.cancel()
            // 仅在非配置重建（如屏幕旋转）导致的关闭时清除弹窗标记，避免旋转后弹窗消失
            if (!activity.isChangingConfigurations) {
                manager.hideDialog()
            }
            onDismiss()
        }

        scope.launch {
            manager.state.collect { state ->
                val manifest = state.manifest
                val currentCode = manager.getCurrentVersionCode()
                val currentName = manager.getCurrentVersionName()
                val canDownload = manifest != null &&
                    manifest.isInstallCandidate(currentCode, Build.VERSION.SDK_INT)
                val showProgress = state.active ||
                    state.phase in setOf(
                        UpdateManager.Phase.PAUSED,
                        UpdateManager.Phase.READY
                    )

                title.text = when {
                    state.phase == UpdateManager.Phase.READY -> "更新已就绪"
                    canDownload -> "发现新版本"
                    else -> "应用更新"
                }
                version.text = when {
                    manifest != null && (canDownload || showProgress) ->
                        "v$currentName  →  v${manifest.versionName}"
                    else -> "当前版本 v$currentName"
                }
                status.text = state.message.ifBlank {
                    if (UpdateConfig.enabled) "点击下方按钮检查更新" else "当前构建未配置更新源"
                }
                status.setTextColor(
                    activity.getColor(
                        if (state.errorCode.isNotBlank()) R.color.pf_bad
                        else R.color.pf_text_secondary
                    )
                )

                val noteSections = manifest?.releaseNotes
                    ?.let(UpdateNotesFormatter::sections)
                    .orEmpty()
                val showNotes = noteSections.isNotEmpty() && (canDownload || showProgress)
                notes.text = renderNotes(noteSections)
                notesLabel.visibility = if (showNotes) View.VISIBLE else View.GONE
                notesScroll.visibility = if (showNotes) View.VISIBLE else View.GONE
                progress.visibility = if (showProgress) View.VISIBLE else View.GONE
                progress.progress = if (state.phase == UpdateManager.Phase.READY) 100
                else state.progress
                size.visibility = if (canDownload || showProgress) View.VISIBLE else View.GONE
                size.text = manifest?.let {
                    val line = state.mirror?.name
                    String.format(
                        Locale.US,
                        "%.1f MiB%s",
                        it.size / 1048576.0,
                        if (line.isNullOrBlank()) "" else " · $line"
                    )
                }.orEmpty()
                forceNote.visibility = if (canDownload && manifest?.forceUpdate == true) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
                cancelDownload.visibility =
                    if (state.active || state.phase == UpdateManager.Phase.PAUSED) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }

                positiveEnabled = state.check != UpdateManager.Check.CHECKING ||
                    state.active || state.phase != UpdateManager.Phase.IDLE
                positiveText = when {
                    state.phase == UpdateManager.Phase.READY -> "安装"
                    state.active -> "暂停下载"
                    state.phase == UpdateManager.Phase.PAUSED -> "继续下载"
                    canDownload -> "下载更新"
                    state.check == UpdateManager.Check.CHECKING -> "检查中…"
                    else -> "重新检查"
                }
                negativeText = when {
                    state.active -> "后台继续"
                    canDownload -> "稍后"
                    else -> "关闭"
                }

                positiveAction = {
                    when {
                        state.phase == UpdateManager.Phase.READY -> manager.installReady()
                        state.active -> manager.pauseDownload()
                        state.phase == UpdateManager.Phase.PAUSED -> manager.startDownload()
                        canDownload -> startDownload(activity, manager)
                        else -> manager.checkForUpdate(manual = true)
                    }
                }
                negativeAction = {
                    if (state.active) manager.hideDialog() else manager.dismiss()
                    dialog.dismiss()
                }
                if (dialog.isShowing) applyButtons()
            }
        }

        dialog.show()
        return dialog
    }

    private fun startDownload(activity: Activity, manager: UpdateManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST
            )
        }
        manager.startDownload()
    }

    /** 小节标题加粗，条目保持一行一条，长说明最多滚动 160dp。 */
    private fun renderNotes(sections: List<UpdateNotesFormatter.Section>): CharSequence {
        val out = SpannableStringBuilder()
        sections.forEachIndexed { index, section ->
            if (index > 0) out.append("\n\n")
            val title = section.title
            if (!title.isNullOrBlank()) {
                val start = out.length
                out.append(title)
                out.setSpan(
                    StyleSpan(Typeface.BOLD),
                    start,
                    out.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                if (section.lines.isNotEmpty()) out.append('\n')
            }
            section.lines.forEachIndexed { lineIndex, line ->
                out.append(line)
                if (lineIndex != section.lines.lastIndex) out.append('\n')
            }
        }
        return out
    }

    private const val NOTIFICATION_PERMISSION_REQUEST = 9802
}
