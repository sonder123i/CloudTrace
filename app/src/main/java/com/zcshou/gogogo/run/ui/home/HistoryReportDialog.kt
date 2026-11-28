package com.zcshou.gogogo.run.ui.home

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zcshou.gogogo.R
import com.zcshou.gogogo.mascot.MascotEggResolver
import com.zcshou.gogogo.mascot.MascotEggStore
import com.zcshou.gogogo.mascot.MascotMode
import com.zcshou.gogogo.mascot.MascotPrefs
import com.zcshou.gogogo.run.data.db.RunRecordEntity
import com.zcshou.gogogo.run.data.db.RunRouteEntity
import com.zcshou.gogogo.run.data.repository.RunRepository
import com.zcshou.gogogo.run.engine.RunSplitCodec
import com.zcshou.gogogo.run.engine.TrackJsonCodec
import com.zcshou.gogogo.run.engine.TrackMathUtils
import com.zcshou.gogogo.run.ui.glass.OverlayGlassStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/**
 * 本地战报中心：累计概览 + 月历热力 + 全量历史列表 → 单条复盘（动态回放 + 海报）。
 *
 * 全部数据只来自本机 Room，不联网、不上传。
 */
object HistoryReportDialog {

    fun show(context: Context, repository: RunRepository) {
        val ownerScope = (context as? LifecycleOwner)?.lifecycleScope
        val ownedScope = if (ownerScope == null) {
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        } else {
            null
        }
        val scope = ownerScope ?: ownedScope!!
        scope.launch {
            if (!isContextAlive(context)) {
                ownedScope?.cancel()
                return@launch
            }
            val records = repository.observeRecords().first()
            if (!isContextAlive(context)) {
                ownedScope?.cancel()
                return@launch
            }
            if (records.isEmpty()) {
                showEmpty(context, ownedScope)
                return@launch
            }
            showCenter(context, repository, records, scope, ownedScope)
        }
    }

    private fun showEmpty(context: Context, ownedScope: CoroutineScope?) {
        AlertDialog.Builder(context)
            .setTitle("本地历史战报")
            .setMessage("还没有完成记录，跑完第一圈后这里会自动生成战报。")
            .setPositiveButton("知道了", null)
            .create()
            .apply {
                if (ownedScope != null) setOnDismissListener { ownedScope.cancel() }
                show()
            }
    }

    private fun showCenter(
        context: Context,
        repository: RunRepository,
        records: List<RunRecordEntity>,
        scope: CoroutineScope,
        ownedScope: CoroutineScope?
    ) {
        if (!isContextAlive(context)) {
            ownedScope?.cancel()
            return
        }
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_history_center, null)
        val statTotalKm = view.findViewById<TextView>(R.id.statTotalKm)
        val statRuns = view.findViewById<TextView>(R.id.statRuns)
        val statStreak = view.findViewById<TextView>(R.id.statStreak)
        val statLongest = view.findViewById<TextView>(R.id.statLongest)
        val monthLabel = view.findViewById<TextView>(R.id.monthLabel)
        val heatmap = view.findViewById<HistoryCalendarHeatmapView>(R.id.heatmap)
        val filterAll = view.findViewById<TextView>(R.id.filterAll)
        val filterCompleted = view.findViewById<TextView>(R.id.filterCompleted)
        val recordCountLabel = view.findViewById<TextView>(R.id.recordCountLabel)
        val emptyLabel = view.findViewById<TextView>(R.id.historyEmpty)
        val dayHint = view.findViewById<TextView>(R.id.dayFilterHint)
        val list = view.findViewById<RecyclerView>(R.id.historyList)

        val samples = RunStatsCalculator.toSamples(records)
        val zone = ZoneId.systemDefault()
        val completed = records.filter { it.status == "COMPLETED" }

        val adapter = HistoryRecordAdapter { record ->
            scope.launch { openDetail(context, repository, record, scope, ownedScope) }
        }
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        list.isNestedScrollingEnabled = false

        var onlyCompleted = false
        var selectedDay = -1
        var currentMonth = YearMonth.now()

        fun refreshChips(activeDay: Int) {
            styleChip(filterAll, !onlyCompleted)
            styleChip(filterCompleted, onlyCompleted)
            dayHint.visibility = if (activeDay > 0) android.view.View.VISIBLE else android.view.View.GONE
            if (activeDay > 0) {
                dayHint.text = "已筛选 ${currentMonth.monthValue}月${activeDay}日 · 再点同日取消"
            }
        }

        fun refresh() {
            statTotalKm.text = String.format(
                Locale.US,
                "%.2f",
                completed.sumOf { it.actualDistanceMeters } / 1000.0
            )
            statRuns.text = completed.size.toString()
            val now = System.currentTimeMillis()
            statStreak.text = "${RunStatsCalculator.currentStreak(samples, now, zone)} 天"
            statLongest.text = "${RunStatsCalculator.longestStreak(samples, zone)} 天"

            monthLabel.text = "${currentMonth.year}年${currentMonth.monthValue}月"
            heatmap.setMonth(
                currentMonth.year,
                currentMonth.monthValue,
                RunStatsCalculator.monthMeters(samples, currentMonth.year, currentMonth.monthValue, zone),
                selectedDay
            )
            refreshChips(selectedDay)

            val filtered = records.asSequence()
                .filter { !onlyCompleted || it.status == "COMPLETED" }
                .filter { selectedDay < 0 || dayOfMonth(it.startedAt, zone) == selectedDay }
                .toList()
            adapter.submit(filtered)
            recordCountLabel.text = "${filtered.size} 条"
            emptyLabel.visibility = if (filtered.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            list.visibility = if (filtered.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        }

        filterAll.setOnClickListener {
            if (onlyCompleted) {
                onlyCompleted = false
                refresh()
            }
        }
        filterCompleted.setOnClickListener {
            if (!onlyCompleted) {
                onlyCompleted = true
                refresh()
            }
        }
        heatmap.setOnDayClickListener { day ->
            selectedDay = if (selectedDay == day) -1 else day
            refresh()
        }
        view.findViewById<TextView>(R.id.monthPrev).setOnClickListener {
            currentMonth = currentMonth.minusMonths(1)
            selectedDay = -1
            refresh()
        }
        view.findViewById<TextView>(R.id.monthNext).setOnClickListener {
            currentMonth = currentMonth.plusMonths(1)
            selectedDay = -1
            refresh()
        }
        refresh()

        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .setPositiveButton("关闭", null)
            .create()
        applyGlassWindow(context, dialog) {
            if (ownedScope != null) ownedScope.cancel()
        }
        dialog.show()
    }

    private fun openDetail(
        context: Context,
        repository: RunRepository,
        record: RunRecordEntity,
        scope: CoroutineScope,
        ownerScope: CoroutineScope?
    ) {
        scope.launch {
            val route = repository.getRoute(record.routeId)
            val points = withContext(Dispatchers.Default) {
                route?.let { TrackJsonCodec.decode(it.denseWaypointsJson) } ?: emptyList()
            }
            showDetail(context, record, route, points, ownerScope)
        }
    }

    /**
     * @param ownerScope 宿主生命周期 scope；为 null 时本弹窗自建 scope，并在关闭时取消。
     */
    private fun showDetail(
        context: Context,
        record: RunRecordEntity,
        route: RunRouteEntity?,
        points: List<TrackMathUtils.LatLng>,
        ownerScope: CoroutineScope?
    ) {
        if (!isContextAlive(context)) return
        // 每个复盘弹窗独占自己的兜底 scope：关闭它不能顺带掐死背后的战报中心
        val ownedScope = if (ownerScope == null) {
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        } else {
            null
        }
        val scope: CoroutineScope = ownerScope ?: ownedScope!!
        val lapLength = route?.singleLapMeters?.takeIf { it > 1.0 }
            ?: points.let { TrackMathUtils.cumulativeDistances(it).lastOrNull() }
        val laps = if (lapLength != null && lapLength > 1.0) {
            ceil(record.actualDistanceMeters / lapLength).toInt().coerceAtLeast(1)
        } else {
            1
        }
        val splits = RunSplitCodec.decode(record.splitsJson)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_history_replay, null)
        view.findViewById<TextView>(R.id.historyReplayTitle).text =
            if (route != null) route.name else "路线已删除 · ${record.routeName}"
        view.findViewById<TextView>(R.id.historyReplaySummary).text = String.format(
            Locale.US,
            "%s · %.2f km · 用时 %s · 平均配速 %s · 爬升 %.1f m · %s · %d 圈分层",
            formatDate(record.startedAt),
            record.actualDistanceMeters / 1000.0,
            formatDuration(record.durationSeconds),
            if (record.averagePaceSec > 0) formatPace(record.averagePaceSec) else "--",
            record.elevationGainMeters,
            if (record.status == "COMPLETED") "已完成" else "已中止",
            laps
        )
        val replay = view.findViewById<RunReplayView>(R.id.historyReplayView)
        replay.bind(
            points = points,
            laps = laps,
            averagePaceSec = record.averagePaceSec,
            seed = record.id,
            splits = splits
        )

        val playButton = view.findViewById<TextView>(R.id.replayPlay)
        val seekBar = view.findViewById<SeekBar>(R.id.replaySeek)
        val progressLabel = view.findViewById<TextView>(R.id.replayProgress)
        var syncingSeek = false

        fun renderPlayState(playing: Boolean) {
            playButton.text = if (playing) "暂停" else "播放"
        }
        fun renderProgress(progress: Float) {
            val km = record.actualDistanceMeters / 1000.0 * progress
            progressLabel.text = String.format(Locale.US, "%.2f / %.2f km · %d%%",
                km, record.actualDistanceMeters / 1000.0, (progress * 100).toInt())
            syncingSeek = true
            seekBar.progress = (progress * seekBar.max).toInt()
            syncingSeek = false
        }

        replay.setProgressListener { progress, playing ->
            renderPlayState(playing)
            renderProgress(progress)
        }
        renderPlayState(false)
        renderProgress(0f)

        playButton.setOnClickListener { replay.toggleReplay() }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || syncingSeek) return
                replay.seekTo(progress.toFloat() / seekBar.max)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = replay.pauseReplay()

            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        val speedViews = listOf(
            view.findViewById<TextView>(R.id.replaySpeed1) to 1f,
            view.findViewById<TextView>(R.id.replaySpeed2) to 2f,
            view.findViewById<TextView>(R.id.replaySpeed4) to 4f
        )
        fun renderSpeeds(active: Float) {
            speedViews.forEach { (chip, value) ->
                styleChip(chip, value == active)
            }
        }
        speedViews.forEach { (chip, value) ->
            chip.setOnClickListener {
                replay.setSpeed(value)
                renderSpeeds(value)
            }
        }
        replay.setSpeed(1f)
        renderSpeeds(1f)

        val posterPayload = {
            ShareCardRenderer.Payload(
                startedAt = record.startedAt,
                distanceKm = record.actualDistanceMeters / 1000.0,
                durationSec = record.durationSeconds,
                averagePaceSec = record.averagePaceSec,
                elevationGainMeters = record.elevationGainMeters,
                points = points,
                laps = laps,
                isClosedLoop = route?.isClosedLoop ?: true,
                splits = splits,
                strategyLabel = strategyLabel(record.paceStrategy),
                mascotCaption = mascotCaption(context, record)
            )
        }
        view.findViewById<android.view.View>(R.id.btnSharePoster).setOnClickListener {
            scope.launch {
                val bitmap = withContext(Dispatchers.Default) {
                    runCatching { ShareCardRenderer.render(posterPayload()) }.getOrNull()
                }
                if (!isContextAlive(context)) return@launch
                if (bitmap == null) {
                    toast(context, "海报生成失败")
                    return@launch
                }
                if (!ShareCardRenderer.share(context, bitmap)) {
                    toast(context, "没有可用的分享应用")
                }
            }
        }
        view.findViewById<android.view.View>(R.id.btnSavePoster).setOnClickListener {
            scope.launch {
                val bitmap = withContext(Dispatchers.Default) {
                    runCatching { ShareCardRenderer.render(posterPayload()) }.getOrNull()
                }
                if (!isContextAlive(context)) return@launch
                if (bitmap == null) {
                    toast(context, "海报生成失败")
                    return@launch
                }
                val result = ShareCardRenderer.saveToGallery(context, bitmap)
                if (!isContextAlive(context)) return@launch
                toast(context, result ?: "保存失败，请检查存储空间")
            }
        }

        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .setPositiveButton("关闭", null)
            .create()
        // 监听器只允许注册一次：ownedScope 清理与跨窗模糊反注册必须合并，
        // 否则后一次 setOnDismissListener 会覆盖前一次，留下泄漏的模糊监听
        applyGlassWindow(context, dialog) {
            replay.release()
            if (ownedScope != null) ownedScope.cancel()
        }
        dialog.show()
    }

    /**
     * 跨窗液态玻璃窗口 + 唯一清理入口。
     * ROM 关闭跨窗模糊时回退实体底板，严禁出现「无模糊且全透明导致文字重叠」。
     */
    private fun applyGlassWindow(
        context: Context,
        dialog: AlertDialog,
        onDismiss: () -> Unit
    ) {
        var windowManager: WindowManager? = null
        var token: Any? = null
        dialog.window?.let { window ->
            val density = context.resources.displayMetrics.density
            val lp = window.attributes
            OverlayGlassStyle.enableBlurBehind(lp, 36f * density)
            window.attributes = lp
            window.setBackgroundDrawableResource(R.drawable.bg_glass_dialog)
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            windowManager = wm
            token = wm?.let {
                OverlayGlassStyle.watchCrossWindowBlur(it) { enabled ->
                    window.setBackgroundDrawableResource(
                        if (enabled) R.drawable.bg_glass_dialog else R.drawable.bg_hub_panel_solid
                    )
                }
            }
        }
        dialog.setOnDismissListener {
            onDismiss()
            OverlayGlassStyle.unwatchCrossWindowBlur(windowManager, token)
        }
    }

    private fun styleChip(chip: TextView, active: Boolean) {
        chip.isSelected = active
        chip.setTextColor(
            chip.context.getColor(
                if (active) R.color.pf_on_primary else R.color.pf_text_secondary
            )
        )
    }

    private fun strategyLabel(strategy: String): String? = when (strategy) {
        "NEGATIVE_SPLIT" -> "前慢后快"
        "INTERVAL" -> "间歇变速"
        else -> null
    }

    private fun mascotCaption(context: Context, record: RunRecordEntity): String? {
        if (MascotPrefs.mode(context) == MascotMode.OFF) return null
        if (record.status != "COMPLETED") return null
        val endMs = record.startedAt + record.durationSeconds * 1000L
        val endHour = Calendar.getInstance().apply { timeInMillis = endMs }.get(Calendar.HOUR_OF_DAY)
        val egg = MascotEggResolver.forReport(
            averagePaceSecPerKm = record.averagePaceSec,
            durationSeconds = record.durationSeconds,
            endHour = endHour
        )
        // 战报里命中彩蛋即解锁图鉴；只有首次解锁才值得打扰，其余静默
        val firstTime = MascotEggStore.unlock(context, egg)
        return if (firstTime || egg.caption.isNotBlank()) egg.caption else null
    }

    private fun dayOfMonth(millis: Long, zone: ZoneId): Int =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().dayOfMonth

    private fun toast(context: Context, message: String) {
        com.zcshou.gogogo.run.ui.glass.GlassFeedback.toast(context, message, long = true)
    }

    /** 弹窗宿主可能在挂起查询期间销毁，恢复执行时必须先校验窗口 token。 */
    private fun isContextAlive(context: Context): Boolean {
        val activity = context as? Activity ?: return true
        return !activity.isFinishing && !activity.isDestroyed
    }

    private fun formatDate(millis: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(millis))

    private fun formatDuration(seconds: Int): String {
        val safe = seconds.coerceAtLeast(0)
        val h = safe / 3600
        val m = (safe % 3600) / 60
        val s = safe % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun formatPace(secPerKm: Int): String =
        String.format(Locale.US, "%d'%02d\"", secPerKm / 60, secPerKm % 60)
}
