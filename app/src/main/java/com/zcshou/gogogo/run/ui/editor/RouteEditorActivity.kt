package com.zcshou.gogogo.run.ui.editor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import com.zcshou.gogogo.run.ui.widget.VernierSeekBar
import com.zcshou.gogogo.run.ui.widget.TabTintAnimator
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.lifecycle.lifecycleScope
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.BitmapDescriptor
import com.baidu.mapapi.map.BitmapDescriptorFactory
import com.baidu.mapapi.map.MapStatus
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.LogoPosition
import com.baidu.mapapi.map.TextureMapView
import com.baidu.mapapi.map.Marker
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.model.LatLng as BdLatLng
import com.baidu.mapapi.search.core.SearchResult
import com.baidu.mapapi.search.geocode.GeoCodeResult
import com.baidu.mapapi.search.geocode.GeoCoder
import com.baidu.mapapi.search.geocode.OnGetGeoCoderResultListener
import com.baidu.mapapi.search.geocode.ReverseGeoCodeOption
import com.baidu.mapapi.search.geocode.ReverseGeoCodeResult
import com.baidu.mapapi.search.route.BikingRouteResult
import com.baidu.mapapi.search.route.DrivingRouteResult
import com.baidu.mapapi.search.route.IndoorRouteResult
import com.baidu.mapapi.search.route.IntegralRouteResult
import com.baidu.mapapi.search.route.MassTransitRouteResult
import com.baidu.mapapi.search.route.OnGetRoutePlanResultListener
import com.baidu.mapapi.search.route.PlanNode
import com.baidu.mapapi.search.route.RoutePlanSearch
import com.baidu.mapapi.search.route.TransitRouteResult
import com.baidu.mapapi.search.route.WalkingRoutePlanOption
import com.baidu.mapapi.search.route.WalkingRouteResult
import com.baidu.mapapi.search.sug.SuggestionSearch
import com.baidu.mapapi.search.sug.SuggestionSearchOption
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.data.db.RunRouteEntity
import com.zcshou.gogogo.run.data.repository.RunRepository
import com.zcshou.gogogo.run.engine.CoordTransformUtil
import com.zcshou.gogogo.run.engine.TrackJsonCodec
import com.zcshou.gogogo.run.engine.TrackMathUtils
import com.zcshou.gogogo.run.engine.TrackTemplates
import com.zcshou.gogogo.run.engine.TrackMathUtils.LatLng as Wgs
import com.zcshou.gogogo.run.engine.toWgsJson
import com.zcshou.gogogo.run.guard.RealLocationSampler
import com.zcshou.gogogo.run.ui.glass.DeepRunGlassManager
import com.zcshou.gogogo.run.ui.glass.GlassDialogs
import com.zcshou.gogogo.run.ui.glass.LiquidGlassContainer
import com.zcshou.gogogo.run.ui.overlay.FiberTrail
import com.zcshou.gogogo.run.ui.theme.MapStyleHelper
import com.zcshou.gogogo.run.ui.theme.ThemeHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 建圈工作台 V3.3：
 * 三模式（操场套模 / 逐点连线 / 自由手绘）+ 屏幕中心准星 + 1m 微调盘 +
 * 双态手绘锁 + 逐点可拖拽节点 + 全链路防闪退 + 草稿恢复 + 3s 两圈试跑。
 * 展示用 BD-09，存储与计算全部 WGS-84。
 */
class RouteEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INITIAL_MODE = "initial_mode"
        const val MODE_PRESET = "preset"
        const val MODE_TRACK = "track"
        const val MODE_FREE = "free"

        private const val DRAFT_PREFS = "pf_route_draft"
        private const val DRAFT_KEY = "draft"
        private const val SEARCH_PREFS = "pf_editor_search"
        private const val SEARCH_KEY = "history"
        private const val SEGMENT_TIMEOUT_MS = 3_000L
        private const val PREVIEW_DURATION_MS = 3_000L

        /** GPX 导入大小上限：10MB（正常 GPX 远小于此，防超大 XML 全量读入内存） */
        private const val MAX_GPX_BYTES = 10 * 1024 * 1024
    }

    private enum class EditMode { PRESET, TRACK, FREE, GPX }
    private enum class PresetStage { ALIGN, LOCKED }
    private enum class MapLayer { DARK, NORMAL, SAT }

    private data class SearchPlace(val name: String, val lat: Double, val lng: Double)

    private lateinit var repository: RunRepository
    private lateinit var mapView: TextureMapView
    private lateinit var baiduMap: BaiduMap
    private lateinit var editorTopPanel: View
    private lateinit var reticle: OpticalReticleView
    private lateinit var lockBorder: View
    private lateinit var freehandLockChip: TextView
    private lateinit var searchInput: EditText
    private lateinit var searchHistoryRow: LinearLayout
    private lateinit var lapText: TextView
    private lateinit var status: TextView
    private lateinit var gpxInfo: TextView
    private lateinit var modeGuide: TextView
    private lateinit var modePreset: TextView
    private lateinit var modeTrack: TextView
    private lateinit var modeFree: TextView
    private lateinit var mercuryTabs: MercuryTabStrip
    private lateinit var panelPreset: View
    private lateinit var panelSnap: LinearLayout
    private lateinit var switchSnap: Switch
    private lateinit var switchClose: Switch
    private lateinit var panelPresetAlign: LinearLayout
    private lateinit var panelPresetLocked: LiquidGlassContainer
    private lateinit var editorBottomDock: View
    private lateinit var dockHeader: View
    private lateinit var dockActionRow: View
    private lateinit var dockSaveRow: View
    private lateinit var presetAlignSeekRow: View
    private lateinit var lapSeekAlign: VernierSeekBar
    private lateinit var lapSeekLock: VernierSeekBar
    private lateinit var lapValueAlign: TextView
    private lateinit var lapValueLock: TextView
    private lateinit var rotateSeek: VernierSeekBar
    private lateinit var rotateValue: TextView
    private lateinit var btnLock: TextView
    private lateinit var btnUndo: TextView
    private lateinit var btnClear: TextView
    private lateinit var btnSave: TextView
    private lateinit var btnPreview: View
    private lateinit var layerDark: TextView
    private lateinit var layerNormal: TextView
    private lateinit var layerSat: TextView
    private lateinit var layerSwitcher: View
    private lateinit var btnLocateMe: View

    private val suggestionSearch by lazy { SuggestionSearch.newInstance() }
    private val routeSearch by lazy { RoutePlanSearch.newInstance() }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mode = EditMode.PRESET
    private var presetStage = PresetStage.ALIGN
    private var presetId = "400"
    private var targetLapMeters = 400.0
    private var rotationBase = 0.0
    private var rotationDeg = 0.0
    private var centerWgs: Wgs? = null
    private var mapReady = false
    private var mapLayer = MapLayer.DARK

    private var trackMeters = 400
    private val anchors = mutableListOf<Wgs>()
    private val freehandStrokes = mutableListOf<List<Wgs>>()
    private var currentStroke = mutableListOf<Wgs>()
    private var lastStrokeEndScreen: Point? = null
    private var drawingStroke = false
    private var freehandLocked = true
    private var autoCloseLoop = true
    private var roadSnapEnabled = false

    private var densePoints: List<Wgs> = emptyList()
    private var lapMeters = 0.0
    private var isClosed = false
    private var isClockwise = true
    private var buildMode = "PRESET_TRACK"
    private var sourceHint = "400m 田径场"
    private var systemTopInset = 0
    private var systemBottomInset = 0

    private var chainSegments: List<Pair<Wgs, Wgs>> = emptyList()
    private var chainIndex = 0
    private var roadBusy = false
    private var waitingSegment = false
    private var roadToken = 0
    private var roadFallback: (() -> Unit)? = null
    private var segmentTimeoutJob: Job? = null

    private var mainPolyline: FiberTrail? = null
    private var freehandPolyline: FiberTrail? = null
    private val anchorMarkers = mutableListOf<Marker>()
    private val distanceMarkers = mutableListOf<Marker>()
    private var previewMarker: Marker? = null
    private var previewJob: Job? = null
    private var lockPulse: android.animation.ValueAnimator? = null

    private var updatingSliders = false
    private var updatingSwitches = false
    private var lastHapticBucket = Int.MIN_VALUE
    private var draftSuppressed = false
    private var restoringDraft = false

    private val numberIconCache = mutableMapOf<Int, BitmapDescriptor>()
    private val distanceIconCache = mutableMapOf<String, BitmapDescriptor>()
    private val baseLapCache = mutableMapOf<String, Double>()

    private val presetChips by lazy {
        mapOf(
            "400" to findViewById<TextView>(R.id.preset400),
            "300" to findViewById<TextView>(R.id.preset300),
            "200" to findViewById<TextView>(R.id.preset200),
            "rect500" to findViewById<TextView>(R.id.presetRect),
            "eight" to findViewById<TextView>(R.id.presetEight)
        )
    }

    private val openGpx = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val (tooLarge, text) = withContext(Dispatchers.IO) {
                readGpxBounded(uri)
            }
            when {
                tooLarge -> toast("GPX 文件超过 10MB，请精简后再导入")
                text.isNullOrBlank() -> toast("读取文件失败")
                else -> applyGpx(text)
            }
        }
    }

    /**
     * GPX 文件大小预检：任意大的 XML 全量 readText 进内存会 OOM/卡死。
     * 读流时按字节截断在 MAX_GPX_BYTES + 1，超出即判定超限（与更新清单的
     * MANIFEST_LIMIT 同一套思路）。
     */
    private suspend fun readGpxBounded(uri: android.net.Uri): Pair<Boolean, String?> =
        withContext(Dispatchers.IO) {
            runCatching {
                contentResolver.openInputStream(uri)?.use { input -> readBounded(input) }
            }.getOrNull() ?: (false to null)
        }

    /**
     * 截断读取：API 27–32 没有 InputStream#readNBytes（API 33 才加入，
     * 未开 core library desugaring 时调用会 NoSuchMethodError），这里手写等价实现。
     *
     * @return 是否超限 + 内容；超限时内容为 null
     */
    private fun readBounded(input: java.io.InputStream): Pair<Boolean, String?> {
        val buffer = ByteArray(MAX_GPX_BYTES + 1)
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return if (total > MAX_GPX_BYTES) true to null
        else false to String(buffer, 0, total, Charsets.UTF_8)
    }

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) locateMe() else toast("未授权定位权限")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_route_editor)
        repository = RunRepository(applicationContext)

        bindViews()
        applyEditorInsets()
        setupMap()
        setupModeBar()
        setupPresetPanel()
        setupSwitches()
        setupSliders()
        setupSearch()
        setupRouteSearch()
        bindClicks()

        applyLayer(if (ThemeHelper.isDark(this)) MapLayer.DARK else MapLayer.NORMAL)
        updatePresetChip("400")
        setMode(parseInitialMode(intent.getStringExtra(EXTRA_INITIAL_MODE)), userChange = false)
        renderSearchHistory()

        if (savedInstanceState == null) {
            if (!restoreDraftIfNeeded()) {
                locateMe(auto = true)
            }
        } else {
            locateMe(auto = true)
        }
    }

    private fun bindViews() {
        mapView = findViewById(R.id.editMapView)
        baiduMap = mapView.map
        editorTopPanel = findViewById(R.id.editorTopPanel)
        (editorTopPanel as? com.example.liquidglass.LiquidGlassView)?.apply {
            backdropSource = mapView
            com.zcshou.gogogo.run.ui.glass.GlassRenderGate.register(this)
            com.zcshou.gogogo.run.ui.glass.OpticalGlassTuning.applyMapPanel(this)
        }
        reticle = findViewById(R.id.opticalReticle)
        lockBorder = findViewById(R.id.mapLockBorder)
        freehandLockChip = findViewById(R.id.freehandLockChip)
        searchInput = findViewById(R.id.searchInput)
        searchHistoryRow = findViewById(R.id.searchHistoryRow)
        lapText = findViewById(R.id.lapMetersText)
        status = findViewById(R.id.editorStatus)
        gpxInfo = findViewById(R.id.gpxInfo)
        modeGuide = findViewById(R.id.modeGuide)
        modePreset = findViewById(R.id.modePreset)
        modeTrack = findViewById(R.id.modeTrack)
        modeFree = findViewById(R.id.modeFree)
        panelPreset = findViewById(R.id.panelPreset)
        panelSnap = findViewById(R.id.panelSnap)
        switchSnap = findViewById(R.id.switchSnap)
        switchClose = findViewById(R.id.switchClose)
        panelPresetAlign = findViewById(R.id.panelPresetAlign)
        panelPresetLocked = findViewById(R.id.panelPresetLocked)
        editorBottomDock = findViewById(R.id.editorBottomDock)
        (editorBottomDock as? com.example.liquidglass.LiquidGlassView)?.apply {
            backdropSource = mapView
            com.zcshou.gogogo.run.ui.glass.GlassRenderGate.register(this)
            com.zcshou.gogogo.run.ui.glass.OpticalGlassTuning.applyMapPanel(this)
        }
        dockHeader = findViewById(R.id.dockHeader)
        dockActionRow = findViewById(R.id.dockActionRow)
        dockSaveRow = findViewById(R.id.dockSaveRow)
        presetAlignSeekRow = findViewById(R.id.presetAlignSeekRow)
        lapSeekAlign = findViewById(R.id.lapSeekAlign)
        lapSeekLock = findViewById(R.id.lapSeekLock)
        lapValueAlign = findViewById(R.id.lapValueAlign)
        lapValueLock = findViewById(R.id.lapValueLock)
        rotateSeek = findViewById(R.id.rotateSeek)
        rotateValue = findViewById(R.id.rotateValue)
        btnLock = findViewById(R.id.btnLock)
        btnUndo = findViewById(R.id.btnUndo)
        btnClear = findViewById(R.id.btnClear)
        btnSave = findViewById(R.id.btnSave)
        btnPreview = findViewById(R.id.btnPreview)
        layerDark = findViewById(R.id.layerDark)
        layerNormal = findViewById(R.id.layerNormal)
        layerSat = findViewById(R.id.layerSat)
        layerSwitcher = findViewById(R.id.layerSwitcher)
        btnLocateMe = findViewById(R.id.btnLocateMe)
    }

    // ---------------- 地图初始化 ----------------

    /** 全屏沉浸式：顶部面板/图层条避开状态栏，底部坞站避开导航栏，顶部加 36dp 渐隐保护层。 */
    private fun applyEditorInsets() {
        val root = findViewById<View>(R.id.editorRoot)
        val scrim = findViewById<View>(R.id.editorTopScrim)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemTopInset = bars.top
            systemBottomInset = bars.bottom
            setTopMargin(editorTopPanel, dp(10) + bars.top)
            setTopMargin(layerSwitcher, dp(210) + bars.top)
            setTopMargin(freehandLockChip, dp(205) + bars.top)
            setBottomMargin(editorBottomDock, dp(10) + bars.bottom)
            setBottomMargin(btnLocateMe, dp(240) + bars.bottom)
            scrim.layoutParams?.let {
                it.height = bars.top + dp(12)
                scrim.layoutParams = it
            }
            repositionMapAttribution()
            root.post { refreshSideControlsAvoidance() }
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(root)
    }

    private fun setTopMargin(view: View, px: Int) {
        (view.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.topMargin = px
            view.layoutParams = it
        }
    }

    private fun setBottomMargin(view: View, px: Int) {
        (view.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.bottomMargin = px
            view.layoutParams = it
        }
    }

    /** Logo / 比例尺固定在底部控制栏正上方 12dp 的左下角，不遮挡坞站与面板。 */
    private fun repositionMapAttribution() {
        if (!::editorBottomDock.isInitialized || !::mapView.isInitialized) return
        runCatching {
            val dockLoc = IntArray(2)
            editorBottomDock.getLocationOnScreen(dockLoc)
            val mapLoc = IntArray(2)
            mapView.getLocationOnScreen(mapLoc)
            val dockTop = dockLoc[1] - mapLoc[1]
            if (dockTop <= 0 || mapView.height <= 0) return
            mapView.setLogoPosition(LogoPosition.logoPostionleftBottom)
            // 底部内边距把 Logo 从地图底边抬到底部控制栏上方 12dp
            mapView.setPadding(0, 0, 0, (mapView.height - dockTop + dp(12)).coerceAtLeast(0))
            mapView.showScaleControl(true)
            // 比例尺控件锚定在坞站上方 12dp（控件自身高约 28dp）
            mapView.setScaleControlPosition(Point(dp(16), dockTop - dp(12) - dp(28)))
        }
    }

    private fun setupMap() {
        MapStyleHelper.applyForTheme(mapView)
        mapView.showZoomControls(false)
        // 底部被建圈坞站占满：Logo / 比例尺放到顶部面板下方，避免被坞站遮住
        repositionMapAttribution()
        baiduMap.setOnMapLoadedCallback {
            mapReady = true
            repositionMapAttribution()
            // 地图引擎就绪后强制重套皮肤，确保深色模式不会残留亮白底图
            MapStyleHelper.applyForTheme(mapView)
            updateCrosshair()
        }
        mapView.postDelayed({
            if (!mapReady) {
                mapReady = runCatching { baiduMap.mapStatus != null }.getOrDefault(false)
                updateCrosshair()
            }
        }, 1_500)

        baiduMap.setOnMapClickListener(object : BaiduMap.OnMapClickListener {
            override fun onMapClick(latLng: BdLatLng) = onMapTap(latLng)
            override fun onMapPoiClick(poi: com.baidu.mapapi.map.MapPoi) = Unit
        })
        baiduMap.setOnMarkerDragListener(object : BaiduMap.OnMarkerDragListener {
            override fun onMarkerDragStart(marker: Marker) = Unit
            override fun onMarkerDrag(marker: Marker) = Unit
            override fun onMarkerDragEnd(marker: Marker) {
                val title = marker.title ?: return
                if (!title.startsWith("anchor:")) return
                val index = title.substringAfter(':').toIntOrNull() ?: return
                if (mode != EditMode.TRACK || index !in anchors.indices) return
                anchors[index] = toWgs(marker.position)
                markEdited()
                rebuildTrackDense()
            }
        })
        baiduMap.setOnMarkerClickListener { marker ->
            val title = marker.title ?: return@setOnMarkerClickListener false
            if (title.startsWith("anchor:")) {
                val index = title.substringAfter(':').toIntOrNull()
                if (index != null && index in anchors.indices) showAnchorBubble(marker, index)
                true
            } else {
                false
            }
        }
        baiduMap.setOnMapTouchListener { event -> onMapTouch(event) }
        baiduMap.setOnMapStatusChangeListener(object : BaiduMap.OnMapStatusChangeListener {
            override fun onMapStatusChangeStart(status: MapStatus) = Unit

            override fun onMapStatusChangeStart(status: MapStatus, reason: Int) {
                // 手指拖动/缩放期间把玻璃采样降到 0.25x，避免每帧重拍背景
                DeepRunGlassManager.setMapInteracting(true)
            }

            override fun onMapStatusChange(status: MapStatus) {
                com.zcshou.gogogo.run.ui.glass.GlassRenderGate.requestRefresh(260L)
                if (mode == EditMode.PRESET && presetStage == PresetStage.ALIGN) updateCrosshair()
            }

            override fun onMapStatusChangeFinish(status: MapStatus) {
                DeepRunGlassManager.setMapInteracting(false)
                if (mode == EditMode.PRESET && presetStage == PresetStage.ALIGN) updateCrosshair()
                refreshDockBackdrop()
            }
        })
    }

    private fun setupModeBar() {
        val tabHost = findViewById<FrameLayout>(R.id.modeTabHost)
        val tabStrip = findViewById<LinearLayout>(R.id.modeTabs)
        mercuryTabs = MercuryTabStrip(tabHost, tabStrip, listOf(modePreset, modeTrack, modeFree))

        modePreset.setOnClickListener { setMode(EditMode.PRESET) }
        modeTrack.setOnClickListener { setMode(EditMode.TRACK) }
        modeFree.setOnClickListener { setMode(EditMode.FREE) }
        findViewById<TextView>(R.id.btnGpx).setOnClickListener {
            openGpx.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*"))
        }
        layerDark.setOnClickListener { applyLayer(MapLayer.DARK) }
        layerNormal.setOnClickListener { applyLayer(MapLayer.NORMAL) }
        layerSat.setOnClickListener { applyLayer(MapLayer.SAT) }
    }

    private fun setupPresetPanel() {
        presetChips.forEach { (id, chip) ->
            chip.setOnClickListener { selectPreset(id) }
        }
        // V4.0：微调垫 / 快捷角度统一走相机光圈环式的 CLOCK_TICK 机械棘轮手感
        fun tickNudge(view: View, bearing: Double) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            nudgeCenter(bearing)
        }
        fun tickAngle(view: View, angle: Double) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            setQuickAngle(angle)
        }
        findViewById<View>(R.id.dpUp).setOnClickListener { tickNudge(it, 0.0) }
        findViewById<View>(R.id.dpRight).setOnClickListener { tickNudge(it, 90.0) }
        findViewById<View>(R.id.dpDown).setOnClickListener { tickNudge(it, 180.0) }
        findViewById<View>(R.id.dpLeft).setOnClickListener { tickNudge(it, 270.0) }
        findViewById<TextView>(R.id.btnAngle0).setOnClickListener { tickAngle(it, 0.0) }
        findViewById<TextView>(R.id.btnAngle90).setOnClickListener { tickAngle(it, 90.0) }
        findViewById<TextView>(R.id.btnUnlock).setOnClickListener {
            presetStage = PresetStage.ALIGN
            updateModeUi()
            updateCrosshair()
            refreshUi()
            toast("已解锁：拖动地图重新对准操场")
        }
        btnLock.setOnClickListener {
            performLockHaptic()
            lockPreset()
        }
    }

    private fun setupSwitches() {
        switchSnap.setOnCheckedChangeListener { _, checked ->
            if (updatingSwitches) return@setOnCheckedChangeListener
            roadSnapEnabled = checked
            markEdited()
            when (mode) {
                EditMode.TRACK -> rebuildTrackDense()
                EditMode.FREE -> recomputeFreehandDense(applySnap = true)
                else -> Unit
            }
        }
        switchClose.setOnCheckedChangeListener { _, checked ->
            if (updatingSwitches) return@setOnCheckedChangeListener
            autoCloseLoop = checked
            markEdited()
            if (mode == EditMode.TRACK) rebuildTrackDense()
        }
    }

    private fun setupSliders() {
        bindSeek(lapSeekAlign) { progress -> setTargetLap(200.0 + progress) }
        bindSeek(lapSeekLock) { progress -> setTargetLap(200.0 + progress) }
        bindSeek(rotateSeek) { progress ->
            val offset = progress - 45
            rotationDeg = rotationBase + offset
            rotateValue.text = String.format(Locale.US, "%.0f°", normalizeAngle(rotationDeg))
            markEdited()
            if (presetStage == PresetStage.LOCKED) rebuildPreset() else updateCrosshair()
        }
    }

    private fun bindClicks() {
        findViewById<TextView>(R.id.btnSearch).setOnClickListener { doSearch() }
        findViewById<View>(R.id.btnLocateMe).setOnClickListener { locateMe() }
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        btnUndo.setOnClickListener { undo() }
        findViewById<TextView>(R.id.btnReverse).setOnClickListener { reverseDirection() }
        btnClear.setOnClickListener { clearAll() }
        btnSave.setOnClickListener { saveRoute() }
        btnPreview.setOnClickListener { previewRun() }
        freehandLockChip.setOnClickListener { toggleFreehandLock() }
        searchInput.setOnEditorActionListener { _, _, _ ->
            doSearch()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        com.zcshou.gogogo.run.ui.glass.GlassRenderGate.setActive(true)
        mapView.onResume()
    }

    override fun onPause() {
        // 锁屏 / 切后台：立即停掉地图面板的光学着色器刷新
        com.zcshou.gogogo.run.ui.glass.GlassRenderGate.setActive(false)
        super.onPause()
        persistDraftIfNeeded()
        mapView.onPause()
    }

    override fun onDestroy() {
        stopPreview()
        segmentTimeoutJob?.cancel()
        lockPulse?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { suggestionSearch.destroy() }
        runCatching { routeSearch.destroy() }
        mapView.onDestroy()
        super.onDestroy()
    }

    // ---------------- 模式切换 ----------------

    private fun parseInitialMode(value: String?): EditMode = when (value) {
        MODE_TRACK -> EditMode.TRACK
        MODE_FREE -> EditMode.FREE
        else -> EditMode.PRESET
    }

    private fun setMode(next: EditMode, userChange: Boolean = true) {
        if (mode == next && !userChange) {
            updateModeUi()
            refreshUi()
            return
        }
        val previousFree = mode == EditMode.FREE
        if (mode != next) stopPreview()
        mode = next
        if (next == EditMode.FREE && !previousFree) {
            freehandLocked = true
            mainHandler.removeCallbacks(restoreFreehandLockGestures)
        }
        if (next == EditMode.PRESET && presetStage == PresetStage.LOCKED && centerWgs == null) {
            presetStage = PresetStage.ALIGN
        }
        if (userChange) markEdited()
        updateModeUi()
        refreshDenseForMode()
    }

    private fun updateModeUi() {
        styleModeChip(modePreset, mode == EditMode.PRESET)
        styleModeChip(modeTrack, mode == EditMode.TRACK)
        styleModeChip(modeFree, mode == EditMode.FREE)
        mercuryTabs.select(
            when (mode) {
                EditMode.TRACK -> 1
                EditMode.FREE -> 2
                else -> 0
            }
        )

        panelPreset.visibility = if (mode == EditMode.PRESET) View.VISIBLE else View.GONE
        panelSnap.visibility =
            if (mode == EditMode.TRACK || mode == EditMode.FREE) View.VISIBLE else View.GONE
        switchClose.visibility = if (mode == EditMode.TRACK) View.VISIBLE else View.GONE
        gpxInfo.visibility = if (mode == EditMode.GPX) View.VISIBLE else View.GONE

        reticle.visibility =
            if (mode == EditMode.PRESET && presetStage == PresetStage.ALIGN) View.VISIBLE else View.GONE
        freehandLockChip.visibility = if (mode == EditMode.FREE) View.VISIBLE else View.GONE
        lockBorder.visibility =
            if (mode == EditMode.FREE && freehandLocked) View.VISIBLE else View.GONE
        updateFreehandLockUi()

        panelPresetAlign.visibility =
            if (mode == EditMode.PRESET && presetStage == PresetStage.ALIGN) View.VISIBLE else View.GONE
        updateDockVisibility(
            mode == EditMode.PRESET && presetStage == PresetStage.LOCKED
        )

        // 套模未锁定：底部只留一枚悬浮「锁定」胶囊，隐藏尺寸滑杆/D-Pad/撤销/保存行，
        // 避免提前占住地图并把「无法保存」暴露给用户。
        val slimLockOnly = mode == EditMode.PRESET && presetStage == PresetStage.ALIGN
        presetAlignSeekRow.visibility = if (slimLockOnly) View.GONE else View.VISIBLE
        // 光学玻璃坞站常驻；slim 状态下内容只剩锁定胶囊，玻璃自动包裹成小面板
        val dockPad = if (slimLockOnly) dp(6) else dp(12)
        editorBottomDock.setPadding(dockPad, dockPad, dockPad, dockPad)
        (editorBottomDock.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.width = if (slimLockOnly) {
                FrameLayout.LayoutParams.WRAP_CONTENT
            } else {
                FrameLayout.LayoutParams.MATCH_PARENT
            }
            lp.gravity = if (slimLockOnly) {
                android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            } else {
                android.view.Gravity.BOTTOM
            }
            editorBottomDock.layoutParams = lp
        }
        dockHeader.visibility = if (slimLockOnly) View.GONE else View.VISIBLE
        dockActionRow.visibility = if (slimLockOnly) View.GONE else View.VISIBLE
        dockSaveRow.visibility = if (slimLockOnly) View.GONE else View.VISIBLE
        btnLock.visibility = if (slimLockOnly) View.VISIBLE else View.GONE
        // 未锁定时只留一枚居中的悬浮胶囊，把 70% 视野还给地图
        (btnLock.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.width = if (slimLockOnly) {
                LinearLayout.LayoutParams.WRAP_CONTENT
            } else {
                LinearLayout.LayoutParams.MATCH_PARENT
            }
            lp.gravity = android.view.Gravity.CENTER_HORIZONTAL
            btnLock.layoutParams = lp
        }

        modeGuide.text = when (mode) {
            EditMode.PRESET -> "拖动地图对准操场中心，选尺寸后锁定微调"
            EditMode.TRACK -> "依次点击路线拐角自动连成闭环"
            EditMode.FREE -> "锁定后单指一笔画完，松手自动平滑"
            EditMode.GPX -> "GPX 轨迹已导入（WGS-84），可直接保存"
        }
        btnUndo.text = when (mode) {
            EditMode.FREE -> "撤销一笔"
            else -> "撤销"
        }
        btnClear.text = when (mode) {
            EditMode.FREE -> "清空重画"
            else -> "清空"
        }
        updateMapGestures()
        updateSwitches()
        updateSliderPositions()
        updateCrosshair()
    }

    private fun styleModeChip(chip: TextView, on: Boolean) {
        // 背景交给 MercuryTabStrip 的双层玻璃透镜；文字与图标平滑点亮为曜石黑 / 纯白
        chip.background = null
        TabTintAnimator.apply(chip, on)
        chip.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    /** 锁定坞站：弹簧滑入 + 地图快照作为玻璃背景。 */
    private fun updateDockVisibility(visible: Boolean) {
        if (!visible) {
            panelPresetLocked.visibility = View.GONE
            refreshSideControlsAvoidance()
            return
        }
        if (panelPresetLocked.visibility == View.VISIBLE) return
        val density = resources.displayMetrics.density
        panelPresetLocked.alpha = 0f
        panelPresetLocked.translationY = 48f * density
        panelPresetLocked.visibility = View.VISIBLE
        refreshSideControlsAvoidance()
        panelPresetLocked.post {
            panelPresetLocked.animate().alpha(1f).setDuration(160L).start()
            SpringAnimation(panelPresetLocked, SpringAnimation.TRANSLATION_Y, 0f).apply {
                spring.dampingRatio = 0.72f
                spring.stiffness = 380f
                start()
            }
            refreshDockBackdrop()
        }
    }

    /** V5.0：定位按钮与图层条纵向错开；坞站上移时把两者一起抬到面板下方，互不遮挡。 */
    private fun refreshSideControlsAvoidance() {
        editorBottomDock.post {
            // 坞站高度/位置变化后，同步把地图 Logo 与比例尺重新贴回控制栏上方
            repositionMapAttribution()
            val gap = dp(12)
            val dockLoc = IntArray(2)
            editorBottomDock.getLocationOnScreen(dockLoc)
            val dockTop = dockLoc[1]

            val layerLoc = IntArray(2)
            layerSwitcher.getLocationOnScreen(layerLoc)
            val baseLayerTop = layerLoc[1] - layerSwitcher.translationY.toInt()

            val locateLoc = IntArray(2)
            btnLocateMe.getLocationOnScreen(locateLoc)
            val baseLocateTop = locateLoc[1] - btnLocateMe.translationY.toInt()
            val locateHeight = btnLocateMe.height
            if (locateHeight <= 0 || layerSwitcher.height <= 0) return@post

            val needsLift = baseLocateTop + locateHeight + gap > dockTop
            if (!needsLift) {
                layerSwitcher.animate().translationY(0f).setDuration(160L).start()
                btnLocateMe.animate().translationY(0f).setDuration(160L).start()
                return@post
            }

            val panelLoc = IntArray(2)
            editorTopPanel.getLocationOnScreen(panelLoc)
            val panelBottom = panelLoc[1] + editorTopPanel.height
            val stackTop = panelBottom + dp(8)
            val maxLayerTop = dockTop - gap - layerSwitcher.height
            var finalLayerTop = minOf(maxLayerTop, stackTop + locateHeight + dp(10))
            var finalLocateTop = finalLayerTop - dp(10) - locateHeight
            if (finalLocateTop < stackTop) {
                finalLocateTop = stackTop
                finalLayerTop = finalLocateTop + locateHeight + dp(10)
            }

            layerSwitcher.animate()
                .translationY((finalLayerTop - baseLayerTop).toFloat())
                .setDuration(160L)
                .start()
            btnLocateMe.animate()
                .translationY((finalLocateTop - baseLocateTop).toFloat())
                .setDuration(160L)
                .start()
        }
    }

    /** V4.1：玻璃坞站改用静态磨砂，不再截取百度地图快照（避免与地图抢 GPU）。 */
    private fun refreshDockBackdrop() = Unit

    private fun updateSwitches() {
        updatingSwitches = true
        switchSnap.isChecked = roadSnapEnabled
        switchClose.isChecked = autoCloseLoop
        updatingSwitches = false
    }

    private fun updateMapGestures() {
        val freehandLock = mode == EditMode.FREE && freehandLocked
        runCatching {
            baiduMap.uiSettings.setScrollGesturesEnabled(!freehandLock)
            baiduMap.uiSettings.setRotateGesturesEnabled(!freehandLock)
            baiduMap.uiSettings.setOverlookingGesturesEnabled(false)
            baiduMap.uiSettings.setZoomGesturesEnabled(true)
        }
    }

    private fun refreshDenseForMode() {
        cancelRoadSearch()
        when (mode) {
            EditMode.PRESET -> {
                if (presetStage == PresetStage.LOCKED) rebuildPreset()
                else {
                    densePoints = emptyList()
                    lapMeters = 0.0
                    refreshUi()
                }
            }
            EditMode.TRACK -> rebuildTrackDense()
            EditMode.FREE -> recomputeFreehandDense(applySnap = false)
            EditMode.GPX -> refreshUi()
        }
    }

    // ---------------- 操场套模：中心准星 + 锁定 + 微调 ----------------

    private fun updateCrosshair() {
        val show = mode == EditMode.PRESET && presetStage == PresetStage.ALIGN
        reticle.showTrack = show
        reticle.templatePoints = TrackTemplates.template(presetId).localPoints
        reticle.metersScale = targetLapMeters / baseLapOf(presetId)
        reticle.rotationDeg = rotationDeg
        reticle.pxPerMeter = pxPerMeter()
        reticle.offsetX = 0f
        reticle.offsetY = 0f
        reticle.invalidate()
    }

    private fun pxPerMeter(): Float {
        if (!mapReady) return 1f
        return runCatching {
            val projection = baiduMap.projection
            val centerBd = baiduMap.mapStatus.target ?: return 1f
            val p1 = projection.toScreenLocation(centerBd)
            val w = toWgs(centerBd)
            val north = TrackMathUtils.destinationPoint(w, 0.0, 40.0)
            val p2 = projection.toScreenLocation(toBd(north))
            val px = hypot((p2.x - p1.x).toDouble(), (p2.y - p1.y).toDouble())
            (px / 40.0).toFloat().takeIf { it > 0.0001f } ?: 1f
        }.getOrDefault(1f)
    }

    /** 锁定合焦：短促沉稳的 CONFIRM 震动，模拟光学镜头锁焦。 */
    private fun performLockHaptic() {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        btnLock.performHapticFeedback(constant)
    }

    private fun lockPreset() {
        if (!mapReady) {
            toast("地图尚未加载完成，请稍候")
            return
        }
        val w = mapView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val h = mapView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val center = projectedWgs(Point(w / 2, h / 2)) ?: run {
            toast("地图坐标尚未就绪，请稍候重试")
            return
        }
        centerWgs = center
        presetStage = PresetStage.LOCKED
        markEdited()
        updateModeUi()
        rebuildPreset()
        refreshDockBackdrop()
        toast("跑道已锁定：可用方向键每次挪 1m 精调")
    }

    private fun selectPreset(id: String) {
        presetId = id
        updatePresetChip(id)
        sourceHint = TrackTemplates.labelOf(id)
        val base = baseLapOf(id)
        targetLapMeters = base.coerceIn(200.0, 500.0)
        updateSliderPositions()
        markEdited()
        if (presetStage == PresetStage.LOCKED) rebuildPreset() else updateCrosshair()
        toast("已选择：${TrackTemplates.labelOf(id)}")
    }

    private fun updatePresetChip(selected: String) {
        presetChips.forEach { (id, chip) ->
            val on = id == selected
            chip.setBackgroundResource(if (on) R.drawable.bg_tab_selected else R.drawable.bg_secondary_button)
            chip.setTextColor(getColor(if (on) R.color.pf_volt else R.color.pf_text_secondary))
            chip.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    private fun baseLapOf(id: String): Double = baseLapCache.getOrPut(id) {
        val local = TrackTemplates.template(id).localPoints
        var sum = 0.0
        for (i in 1 until local.size) {
            val dx = local[i].first - local[i - 1].first
            val dy = local[i].second - local[i - 1].second
            sum += hypot(dx, dy)
        }
        sum
    }

    private fun rebuildPreset() {
        val center = centerWgs ?: return
        val template = TrackTemplates.template(presetId)
        val scale = (targetLapMeters / baseLapOf(presetId)).coerceIn(0.2, 4.0)
        var points = TrackTemplates.transform(
            template.localPoints, center.lat, center.lng, scale, rotationDeg
        )
        if (!isClockwise) points = points.reversed()
        densePoints = points
        lapMeters = TrackMathUtils.cumulativeDistances(points).last()
        isClosed = true
        buildMode = "PRESET_TRACK"
        sourceHint = template.label
        refreshUi()
    }

    private fun setTargetLap(value: Double) {
        targetLapMeters = value.coerceIn(200.0, 500.0).roundToInt().toDouble()
        updateSliderPositions()
        markEdited()
        if (presetStage == PresetStage.LOCKED) rebuildPreset() else updateCrosshair()
    }

    private fun setQuickAngle(base: Double) {
        rotationBase = base
        rotationDeg = base
        lastHapticBucket = (abs(rotationDeg - rotationBase) / 5).toInt()
        updateSliderPositions()
        markEdited()
        if (presetStage == PresetStage.LOCKED) rebuildPreset() else updateCrosshair()
    }

    private fun nudgeCenter(bearing: Double) {
        val center = centerWgs ?: run {
            toast("请先锁定跑道")
            return
        }
        centerWgs = TrackMathUtils.destinationPoint(center, bearing, 1.0)
        markEdited()
        rebuildPreset()
    }

    private fun updateSliderPositions() {
        updatingSliders = true
        val lapProgress = (targetLapMeters - 200.0).roundToInt().coerceIn(0, 300)
        lapSeekAlign.progress = lapProgress
        lapSeekLock.progress = lapProgress
        lapValueAlign.text = String.format(Locale.US, "%.0fm", targetLapMeters)
        lapValueLock.text = String.format(Locale.US, "%.0fm", targetLapMeters)
        val offset = (rotationDeg - rotationBase).coerceIn(-45.0, 45.0)
        rotateSeek.progress = (offset + 45).roundToInt().coerceIn(0, 90)
        rotateValue.text = String.format(Locale.US, "%.0f°", normalizeAngle(rotationDeg))
        updatingSliders = false
    }

    private fun normalizeAngle(angle: Double): Double {
        var a = angle % 360.0
        if (a > 180) a -= 360
        if (a < -180) a += 360
        return a
    }

    private fun bindSeek(seek: SeekBar, onChange: (Int) -> Unit) {
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !updatingSliders) onChange(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    // ---------------- 地图交互：逐点 / 手绘 ----------------

    private fun onMapTap(latLng: BdLatLng) {
        if (!mapReady) return
        when (mode) {
            EditMode.PRESET -> {
                toast("拖动地图对准后点「锁定跑道并微调」")
            }
            EditMode.TRACK -> {
                anchors.add(toWgs(latLng))
                markEdited()
                rebuildTrackDense()
            }
            EditMode.FREE, EditMode.GPX -> Unit
        }
    }

    private fun onMapTouch(event: MotionEvent) {
        if (mode != EditMode.FREE || !freehandLocked) return
        try {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (event.pointerCount >= 2) {
                        cancelOrFinishCurrentStroke()
                        beginGesturePassthrough()
                        return
                    }
                    if (!mapReady) return
                    val point = Point(event.x.toInt(), event.y.toInt())
                    val wgs = projectedWgs(point) ?: return
                    startOrContinueStroke(point, wgs)
                    drawingStroke = true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    cancelOrFinishCurrentStroke()
                    drawingStroke = false
                    beginGesturePassthrough()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2) {
                        cancelOrFinishCurrentStroke()
                        drawingStroke = false
                        beginGesturePassthrough()
                        return
                    }
                    if (!drawingStroke) return
                    appendStrokePoint(event.x, event.y)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (drawingStroke) finishStroke()
                    drawingStroke = false
                    endGesturePassthrough()
                }
            }
        } catch (t: Throwable) {
            drawingStroke = false
            toast("手绘轨迹出现异常，已跳过本笔")
        }
    }

    private fun startOrContinueStroke(screen: Point, wgs: Wgs) {
        val end = lastStrokeEndScreen
        val nearEnd = end != null && hypot((end.x - screen.x).toDouble(), (end.y - screen.y).toDouble()) < dp(40)
        currentStroke = if (nearEnd && freehandStrokes.isNotEmpty()) {
            freehandStrokes.removeAt(freehandStrokes.lastIndex).toMutableList()
        } else {
            mutableListOf()
        }
        currentStroke.add(wgs)
        updateFreehandLivePolyline()
    }

    private fun appendStrokePoint(x: Float, y: Float) {
        if (!mapReady) return
        val point = Point(x.toInt(), y.toInt())
        val wgs = projectedWgs(point) ?: return
        val last = currentStroke.lastOrNull()
        if (last == null || TrackMathUtils.distanceMeters(last, wgs) >= 1.2) {
            currentStroke.add(wgs)
            updateFreehandLivePolyline()
        }
    }

    private fun finishStroke() {
        if (currentStroke.size < 2) {
            currentStroke.clear()
            updateFreehandLivePolyline()
            return
        }
        val raw = currentStroke.toList()
        var smoothed = TrackMathUtils.smooth3(raw)
        var simplified = RouteSources.simplifyRdp(smoothed, 1.0)
        if (simplified.size < 2) simplified = smoothed
        if (simplified.size < 2) simplified = raw
        freehandStrokes.add(simplified)
        lastStrokeEndScreen = runCatching {
            baiduMap.projection.toScreenLocation(toBd(raw.last()))
        }.getOrNull()
        currentStroke.clear()
        markEdited()
        recomputeFreehandDense(applySnap = true)
    }

    /** 双指落下时：短笔直接丢弃，长笔收尾，避免捏合缩放留下乱线 */
    private fun cancelOrFinishCurrentStroke() {
        if (currentStroke.size >= 8) {
            finishStroke()
        } else {
            currentStroke.clear()
            updateFreehandLivePolyline()
        }
    }

    private fun beginGesturePassthrough() {
        mainHandler.removeCallbacks(restoreFreehandLockGestures)
        runCatching {
            baiduMap.uiSettings.setScrollGesturesEnabled(true)
            baiduMap.uiSettings.setRotateGesturesEnabled(true)
        }
    }

    private fun endGesturePassthrough() {
        mainHandler.removeCallbacks(restoreFreehandLockGestures)
        mainHandler.postDelayed(restoreFreehandLockGestures, 320)
    }

    private val restoreFreehandLockGestures = Runnable {
        if (mode == EditMode.FREE && freehandLocked) {
            runCatching {
                baiduMap.uiSettings.setScrollGesturesEnabled(false)
                baiduMap.uiSettings.setRotateGesturesEnabled(false)
            }
        }
    }

    private fun toggleFreehandLock() {
        freehandLocked = !freehandLocked
        updateFreehandLockUi()
        updateMapGestures()
        updateStatusText()
        if (!freehandLocked) {
            mainHandler.removeCallbacks(restoreFreehandLockGestures)
        }
    }

    private fun updateFreehandLockUi() {
        val active = mode == EditMode.FREE && freehandLocked
        lockBorder.visibility = if (active) View.VISIBLE else View.GONE
        freehandLockChip.text =
            if (freehandLocked) "地图已锁定 · 单指画线" else "自由拖动视野"
        val lockColor = getColor(
            if (freehandLocked) R.color.pf_volt else R.color.pf_text_secondary
        )
        freehandLockChip.setTextColor(lockColor)
        freehandLockChip.compoundDrawableTintList =
            android.content.res.ColorStateList.valueOf(lockColor)
        if (active) {
            if (lockPulse == null) {
                lockPulse = android.animation.ValueAnimator.ofFloat(0.35f, 1f).apply {
                    duration = 1_400
                    repeatMode = android.animation.ValueAnimator.REVERSE
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    addUpdateListener { lockBorder.alpha = it.animatedValue as Float }
                }.also { it.start() }
            }
        } else {
            lockPulse?.cancel()
            lockPulse = null
            lockBorder.alpha = 1f
        }
    }

    private fun combinedFreehand(): List<Wgs> {
        val out = ArrayList<Wgs>()
        freehandStrokes.forEach { out.addAll(it) }
        out.addAll(currentStroke)
        return out
    }

    private fun updateFreehandLivePolyline() {
        val raw = combinedFreehand()
        if (raw.size < 2) {
            freehandPolyline?.remove()
            freehandPolyline = null
            return
        }
        val bdPoints = raw.map { toBd(it) }
        val overlay = freehandPolyline
        if (overlay == null) {
            freehandPolyline = FiberTrail.create(
                baiduMap, bdPoints,
                resources.displayMetrics.density,
                ThemeHelper.isDark(this)
            )
        } else {
            overlay.update(bdPoints)
        }
    }

    private fun recomputeFreehandDense(applySnap: Boolean) {
        val raw = combinedFreehand()
        if (raw.size < 2) {
            densePoints = emptyList()
            lapMeters = 0.0
            isClosed = false
            buildMode = "FREE_DRAW"
            sourceHint = "手绘路线"
            refreshUi()
            return
        }
        var smoothed = TrackMathUtils.smooth3(raw)
        var simplified = RouteSources.simplifyRdp(smoothed, 1.0)
        if (simplified.size < 2) simplified = smoothed
        val (points, closed) = RouteSources.closeLoopIfNeeded(simplified, 60.0)
        densePoints = points
        isClosed = closed
        lapMeters = TrackMathUtils.cumulativeDistances(points).last()
        isClockwise = RouteSources.isClockwise(points)
        buildMode = "FREE_DRAW"
        sourceHint = "手绘路线"
        if (applySnap && roadSnapEnabled) {
            startFreehandSnap(points)
        } else {
            refreshUi()
        }
    }

    private fun startFreehandSnap(base: List<Wgs>) {
        if (base.size < 2) {
            refreshUi()
            return
        }
        val nodes = RouteSources.resample(base, 120.0)
        if (nodes.size < 2) {
            refreshUi()
            return
        }
        val step = ceil(nodes.size / 8.0).toInt().coerceAtLeast(1)
        val waypoints = nodes.filterIndexed { index, _ -> index % step == 0 }.toMutableList()
        if (waypoints.size < 2 || waypoints.last() != nodes.last()) waypoints.add(nodes.last())
        rebuildRoadRoute(waypoints) {
            densePoints = base
            isClosed = RouteSources.closeLoopIfNeeded(base, 60.0).second
            lapMeters = TrackMathUtils.cumulativeDistances(base).last()
            buildMode = "FREE_DRAW"
            sourceHint = "手绘路线"
            refreshUi()
        }
    }

    // ---------------- 逐点连线 ----------------

    private fun rebuildTrackDense() {
        cancelRoadSearch()
        if (anchors.size < 2) {
            densePoints = emptyList()
            lapMeters = 0.0
            isClosed = false
            buildMode = "TRACK_SPLINE"
            sourceHint = "逐点连线 ${anchors.size} 点"
            refreshUi()
            return
        }
        if (roadSnapEnabled) {
            rebuildRoadRoute(anchors.toList()) {
                buildTrackSpline(anchors.toList())
                toastQuiet("当前区域暂无步道数据，已自动按直线连接")
            }
            return
        }
        buildTrackSpline(anchors.toList())
    }

    private fun buildTrackSpline(points: List<Wgs>) {
        if (points.size < 2) {
            densePoints = emptyList()
            lapMeters = 0.0
            refreshUi()
            return
        }
        val canClose = autoCloseLoop && points.size >= 3
        val dense = if (canClose) {
            TrackMathUtils.catmullRomClosed(points, 2.0)
        } else {
            TrackMathUtils.catmullRomOpen(points, 2.0)
        }
        densePoints = dense
        isClosed = canClose
        lapMeters = TrackMathUtils.cumulativeDistances(dense).last()
        isClockwise = RouteSources.isClockwise(dense)
        buildMode = "TRACK_SPLINE"
        sourceHint = "逐点连线 ${points.size} 点"
        refreshUi()
    }

    private fun showAnchorBubble(marker: Marker, index: Int) {
        if (!mapReady) return
        val point = runCatching {
            baiduMap.projection.toScreenLocation(marker.position)
        }.getOrNull() ?: return
        val bubble = TextView(this).apply {
            text = "删除第 ${index + 1} 个点"
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_delete, 0, 0, 0)
            compoundDrawablePadding = dp(5)
            val ink = getColor(R.color.pf_volt)
            setTextColor(ink)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(ink)
            textSize = 12f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = ContextCompat.getDrawable(this@RouteEditorActivity, R.drawable.bg_bubble)
        }
        val popup = PopupWindow(
            bubble,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
        }
        bubble.setOnClickListener {
            popup.dismiss()
            if (index in anchors.indices) {
                anchors.removeAt(index)
                markEdited()
                rebuildTrackDense()
                toast("已删除第 ${index + 1} 个点")
            }
        }
        popup.showAtLocation(mapView, Gravity.NO_GRAVITY, point.x - dp(62), point.y - dp(50))
    }

    // ---------------- 路网吸附（3s 超时降级） ----------------

    private fun setupRouteSearch() {
        routeSearch.setOnGetRoutePlanResultListener(object : OnGetRoutePlanResultListener {
            override fun onGetWalkingRouteResult(result: WalkingRouteResult?) {
                if (!roadBusy || !waitingSegment) return
                waitingSegment = false
                segmentTimeoutJob?.cancel()
                runCatching {
                    if (result != null && result.error == SearchResult.ERRORNO.NO_ERROR) {
                        result.routeLines?.firstOrNull()?.allStep?.forEach { step ->
                            step.wayPoints?.forEach { pt -> roadPathWgsAdd(pt) }
                        }
                    }
                }
                chainIndex++
                requestNextSegment(roadToken)
            }

            override fun onGetTransitRouteResult(p0: TransitRouteResult?) = Unit
            override fun onGetMassTransitRouteResult(p0: MassTransitRouteResult?) = Unit
            override fun onGetDrivingRouteResult(p0: DrivingRouteResult?) = Unit
            override fun onGetIndoorRouteResult(p0: IndoorRouteResult?) = Unit
            override fun onGetBikingRouteResult(p0: BikingRouteResult?) = Unit
            override fun onGetIntegralRouteResult(p0: IntegralRouteResult?) = Unit
        })
    }

    private val roadPathWgs = mutableListOf<Wgs>()

    private fun roadPathWgsAdd(pt: BdLatLng) {
        val w = toWgs(pt)
        if (roadPathWgs.isEmpty() || TrackMathUtils.distanceMeters(roadPathWgs.last(), w) > 0.5) {
            roadPathWgs.add(w)
        }
    }

    private fun rebuildRoadRoute(points: List<Wgs>, fallback: (() -> Unit)? = null) {
        if (points.size < 2) {
            fallback?.invoke()
            return
        }
        cancelRoadSearch()
        roadFallback = fallback
        roadBusy = true
        roadPathWgs.clear()
        chainSegments = buildList {
            addAll(points.zipWithNext())
            if (autoCloseLoop && points.size >= 3) add(points.last() to points.first())
        }
        chainIndex = 0
        waitingSegment = false
        val token = ++roadToken
        requestNextSegment(token)
    }

    private fun requestNextSegment(token: Int) {
        if (token != roadToken) return
        if (chainIndex >= chainSegments.size) {
            finishRoadRoute(token)
            return
        }
        val (a, b) = chainSegments[chainIndex]
        val option = WalkingRoutePlanOption()
            .from(PlanNode.withLocation(toBd(a)))
            .to(PlanNode.withLocation(toBd(b)))
        waitingSegment = true
        segmentTimeoutJob?.cancel()
        segmentTimeoutJob = lifecycleScope.launch {
            delay(SEGMENT_TIMEOUT_MS)
            if (token == roadToken && waitingSegment) {
                waitingSegment = false
                chainIndex++
                requestNextSegment(token)
            }
        }
        val ok = runCatching { routeSearch.walkingSearch(option) }.getOrDefault(false)
        if (!ok) {
            waitingSegment = false
            segmentTimeoutJob?.cancel()
            chainIndex++
            requestNextSegment(token)
        }
    }

    private fun finishRoadRoute(token: Int) {
        if (token != roadToken) return
        roadBusy = false
        waitingSegment = false
        segmentTimeoutJob?.cancel()
        if (roadPathWgs.size < 3) {
            val fallback = roadFallback
            roadFallback = null
            toastQuiet("当前区域暂无步道数据，已自动按直线连接")
            fallback?.invoke()
            return
        }
        val resampled = RouteSources.resample(roadPathWgs.toList())
        val (points, closed) = if (autoCloseLoop) {
            RouteSources.closeLoopIfNeeded(resampled, 30.0)
        } else {
            resampled to false
        }
        densePoints = points
        isClosed = closed
        lapMeters = TrackMathUtils.cumulativeDistances(points).last()
        isClockwise = RouteSources.isClockwise(points)
        buildMode = "ROAD_SNAP"
        sourceHint = if (mode == EditMode.FREE) "手绘路网贴合" else "路网贴合 ${anchors.size} 拐角"
        roadFallback = null
        refreshUi()
    }

    private fun cancelRoadSearch() {
        roadToken++
        roadBusy = false
        waitingSegment = false
        segmentTimeoutJob?.cancel()
        roadFallback = null
    }

    // ---------------- 图层 ----------------

    private fun applyLayer(layer: MapLayer) {
        mapLayer = layer
        runCatching {
            when (layer) {
                MapLayer.DARK -> {
                    baiduMap.setMapType(BaiduMap.MAP_TYPE_NORMAL)
                    MapStyleHelper.applyDark(mapView)
                }
                MapLayer.NORMAL -> {
                    baiduMap.setMapType(BaiduMap.MAP_TYPE_NORMAL)
                    MapStyleHelper.applyLight(mapView)
                }
                MapLayer.SAT -> {
                    MapStyleHelper.disable(mapView)
                    baiduMap.setMapType(BaiduMap.MAP_TYPE_SATELLITE)
                }
            }
        }
        val selected = ContextCompat.getDrawable(this, R.drawable.bg_tab_selected)
        val clear = ColorDrawable(Color.TRANSPARENT)
        listOf(layerDark, layerNormal, layerSat).forEach { it.background = clear }
        listOf(layerDark, layerNormal, layerSat).forEach {
            it.setTextColor(getColor(R.color.pf_text_secondary))
        }
        val active = when (layer) {
            MapLayer.DARK -> layerDark
            MapLayer.NORMAL -> layerNormal
            MapLayer.SAT -> layerSat
        }
        active.background = selected
        active.setTextColor(getColor(R.color.pf_volt))
    }

    // ---------------- 搜索 ----------------

    private fun setupSearch() {
        suggestionSearch.setOnGetSuggestionResultListener { result ->
            // 百度回调可能在页面销毁后异步到达，先判活再弹窗，防 BadTokenException
            if (isFinishing || isDestroyed) return@setOnGetSuggestionResultListener
            val all = result?.allSuggestions.orEmpty()
            if (all.isEmpty()) {
                toast("没有搜到结果")
                return@setOnGetSuggestionResultListener
            }
            val labels = all.map { info ->
                listOfNotNull(info.key, info.district, info.address).joinToString(" · ")
            }.toTypedArray()
            GlassDialogs.builder(this)
                .setTitle("选择地点")
                .setItems(labels) { _, which ->
                    val info = all[which]
                    val pt = info.pt ?: return@setItems
                    baiduMap.setMapStatus(MapStatusUpdateFactory.newLatLngZoom(pt, 18f))
                    val w = toWgs(pt)
                    rememberSearch(info.key ?: info.address ?: "地点", w)
                    updateCrosshair()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun doSearch() {
        val kw = searchInput.text.toString().trim()
        if (kw.isEmpty()) {
            toast("请输入搜索关键词")
            return
        }
        suggestionSearch.requestSuggestion(SuggestionSearchOption().keyword(kw).city("全国"))
        hideKeyboard()
    }

    private fun rememberSearch(name: String, point: Wgs) {
        val history = loadSearchHistory().toMutableList()
        history.removeAll { it.name == name }
        history.add(0, SearchPlace(name, point.lat, point.lng))
        while (history.size > 3) history.removeAt(history.lastIndex)
        val array = JSONArray()
        history.forEach { place ->
            array.put(
                JSONObject()
                    .put("name", place.name)
                    .put("lat", place.lat)
                    .put("lng", place.lng)
            )
        }
        getSharedPreferences(SEARCH_PREFS, MODE_PRIVATE).edit()
            .putString(SEARCH_KEY, array.toString()).apply()
        renderSearchHistory()
    }

    private fun loadSearchHistory(): List<SearchPlace> = runCatching {
        val raw = getSharedPreferences(SEARCH_PREFS, MODE_PRIVATE).getString(SEARCH_KEY, null)
            ?: return emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SearchPlace(name, obj.optDouble("lat"), obj.optDouble("lng"))
        }
    }.getOrDefault(emptyList())

    private fun renderSearchHistory() {
        searchHistoryRow.removeAllViews()
        val history = loadSearchHistory()
        searchHistoryRow.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
        history.forEach { place ->
            val chip = TextView(this).apply {
                text = place.name
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_clock_line, 0, 0, 0)
                compoundDrawablePadding = dp(4)
                textSize = 10f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = dp(120)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(4), dp(10), dp(4))
                val ink = getColor(R.color.pf_text_secondary)
                setTextColor(ink)
                compoundDrawableTintList = android.content.res.ColorStateList.valueOf(ink)
                background = ContextCompat.getDrawable(this@RouteEditorActivity, R.drawable.bg_secondary_button)
                setOnClickListener {
                    searchInput.setText(place.name)
                    centerOn(Wgs(place.lat, place.lng))
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            searchHistoryRow.addView(chip, lp)
        }
    }

    private fun hideKeyboard() {
        runCatching {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
        }
    }

    // ---------------- 撤销 / 反转 / 清空 ----------------

    private fun undo() {
        when (mode) {
            EditMode.PRESET -> toast("操场套模请用微调盘或「重新对准」")
            EditMode.TRACK -> {
                if (anchors.isNotEmpty()) anchors.removeAt(anchors.lastIndex)
                markEdited()
                rebuildTrackDense()
                toast("已撤销上一个节点")
            }
            EditMode.FREE -> {
                when {
                    currentStroke.isNotEmpty() -> currentStroke.clear()
                    freehandStrokes.isNotEmpty() -> freehandStrokes.removeAt(freehandStrokes.lastIndex)
                    else -> {
                        toast("没有可撤销的笔画")
                        return
                    }
                }
                markEdited()
                recomputeFreehandDense(applySnap = true)
                toast("已撤销一笔")
            }
            EditMode.GPX -> toast("GPX 轨迹不支持撤销，可清空重画")
        }
    }

    private fun reverseDirection() {
        if (densePoints.size < 3) {
            toast("请先生成轨迹")
            return
        }
        isClockwise = !isClockwise
        if (mode == EditMode.PRESET && presetStage == PresetStage.LOCKED) {
            rebuildPreset()
        } else {
            densePoints = densePoints.reversed()
            refreshUi()
        }
        markEdited()
        toast(if (isClockwise) "已切换为顺时针" else "已切换为逆时针")
    }

    private fun clearAll() {
        val hasContent = anchors.isNotEmpty() || freehandStrokes.isNotEmpty() ||
            currentStroke.isNotEmpty() || densePoints.size >= 2
        if (hasContent) {
            GlassDialogs.builder(this)
                .setTitle("清空当前轨迹？")
                .setMessage("清空后需要重新画线；已保存的路线不受影响。")
                .setPositiveButton("清空") { _, _ -> doClearAll() }
                .setNegativeButton("取消", null)
                .show()
        } else {
            doClearAll()
        }
    }

    private fun doClearAll() {
        cancelRoadSearch()
        anchors.clear()
        freehandStrokes.clear()
        currentStroke.clear()
        lastStrokeEndScreen = null
        densePoints = emptyList()
        lapMeters = 0.0
        isClosed = false
        isClockwise = true
        stopPreview()
        if (mode == EditMode.PRESET) {
            presetStage = PresetStage.ALIGN
            centerWgs = null
        }
        markEdited()
        updateModeUi()
        refreshUi()
        toast("已清空")
    }

    // ---------------- 预览 / 保存 ----------------

    private fun previewRun() {
        if (densePoints.size < 3) {
            toast("请先生成轨迹")
            return
        }
        stopPreview()
        val points = densePoints
        val cumulative = TrackMathUtils.cumulativeDistances(points)
        val total = cumulative.last()
        if (total <= 0.0) return
        val icon = ghostIcon()
        previewJob = lifecycleScope.launch {
            try {
                val started = SystemClock.elapsedRealtime()
                while (true) {
                    val elapsed = SystemClock.elapsedRealtime() - started
                    val fraction = (elapsed / PREVIEW_DURATION_MS.toDouble()).coerceIn(0.0, 1.0)
                    val distance = total * 2.0 * fraction
                    val lapIndex = (distance / total).toInt().coerceAtMost(1)
                    var point = TrackMathUtils.interpolate(points, cumulative, distance)
                    if (lapIndex == 1) {
                        val bearing = TrackMathUtils.bearingAt(points, cumulative, distance)
                        point = TrackMathUtils.offsetPoint(point, bearing, 1.0)
                    }
                    val bd = toBd(point)
                    val marker = previewMarker
                    if (marker == null) {
                        previewMarker = runCatching {
                            baiduMap.addOverlay(
                                MarkerOptions().position(bd).icon(icon).anchor(0.5f, 0.5f).zIndex(50)
                            ) as? Marker
                        }.getOrNull()
                    } else {
                        runCatching { marker.position = bd }
                    }
                    if (fraction >= 1.0) break
                    delay(40)
                }
                delay(350)
            } finally {
                previewMarker?.remove()
                previewMarker = null
            }
        }
    }

    private fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        previewMarker?.remove()
        previewMarker = null
    }

    private fun ghostIcon(): BitmapDescriptor {
        val size = dp(22).coerceAtLeast(22)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = getColor(R.color.pf_volt)
        paint.alpha = 0x33
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.alpha = 255
        paint.color = getColor(R.color.pf_volt)
        canvas.drawCircle(size / 2f, size / 2f, size * 0.34f, paint)
        paint.color = getColor(R.color.pf_canvas)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.10f
        paint.color = getColor(R.color.pf_canvas)
        canvas.drawCircle(size / 2f, size / 2f, size * 0.34f, paint)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private fun saveRoute() {
        if (mode == EditMode.PRESET && presetStage == PresetStage.ALIGN) {
            toast("请先点「锁定跑道并微调」再保存")
            return
        }
        if (densePoints.size < 2 || lapMeters < 20.0) {
            toast("请至少标记 2 个点形成有效路线")
            return
        }
        val center = densePoints[densePoints.size / 2]
        status.text = "正在获取地标名称…"
        lifecycleScope.launch {
            val poi = withTimeoutOrNull(2_500) { reverseName(center) }
            if (isFinishing || isDestroyed) return@launch
            val autoName = if (poi != null) {
                String.format(Locale.US, "%s · %.0fm", poi, lapMeters)
            } else {
                defaultRouteName()
            }
            showSaveDialog(autoName)
        }
    }

    private suspend fun reverseName(center: Wgs): String? = withTimeoutOrNull(2_500) {
        suspendCancellableCoroutine { cont ->
            val coder = GeoCoder.newInstance()
            val listener = object : OnGetGeoCoderResultListener {
                override fun onGetGeoCodeResult(result: GeoCodeResult?) = Unit

                override fun onGetReverseGeoCodeResult(result: ReverseGeoCodeResult?) {
                    val name = runCatching {
                        result?.poiList?.firstOrNull { !it.name.isNullOrBlank() }?.name
                            ?: result?.formattedPoiAddress?.takeIf { it.isNotBlank() }
                            ?: result?.address?.takeIf { it.isNotBlank() }
                    }.getOrNull()
                    runCatching { coder.destroy() }
                    if (cont.isActive) cont.resume(name)
                }
            }
            coder.setOnGetGeoCodeResultListener(listener)
            val requested = runCatching {
                coder.reverseGeoCode(
                    ReverseGeoCodeOption()
                        .location(toBd(center))
                        .newVersion(1)
                        .radius(150)
                )
            }.getOrDefault(false)
            if (!requested) {
                runCatching { coder.destroy() }
                if (cont.isActive) cont.resume(null)
            }
            cont.invokeOnCancellation { runCatching { coder.destroy() } }
        }
    }

    private fun defaultRouteName(): String = when (mode) {
        EditMode.PRESET -> "${TrackTemplates.labelOf(presetId)} · ${lapMeters.roundToInt()}m"
        EditMode.TRACK -> "逐点路线 · ${lapMeters.roundToInt()}m"
        EditMode.FREE -> "手绘路线 · ${lapMeters.roundToInt()}m"
        EditMode.GPX -> sourceHint
    }

    private fun showSaveDialog(defaultName: String) {
        // 弹窗出现时隐藏底部栏「正在获取地标名称…」，避免被弹窗盖住一半
        status.visibility = View.GONE
        val view = layoutInflater.inflate(R.layout.dialog_save_route, null)
        val input = view.findViewById<EditText>(R.id.saveRouteName).apply {
            setText(defaultName)
            setSelection(text.length)
        }
        view.findViewById<TextView>(R.id.saveRouteMeta).text = String.format(
            Locale.US,
            "单圈 %.0f m · %s · 基准海拔 %.0f m",
            lapMeters,
            if (isClosed) "闭环" else "未闭环",
            sampleAltitude()
        )

        val dialog = android.app.Dialog(this).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(view)
            setCanceledOnTouchOutside(true)
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            val width = (resources.displayMetrics.widthPixels * 0.88f).toInt()
            window?.setLayout(width, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        view.findViewById<View>(R.id.btnSaveCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btnSaveConfirm).setOnClickListener {
            val name = input.text.toString().trim().ifEmpty { defaultName }
            persistRoute(name)
            dialog.dismiss()
        }
        dialog.setOnDismissListener {
            status.visibility = View.VISIBLE
            updateStatusText()
        }
        dialog.show()
        input.requestFocus()
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
    }

    private fun persistRoute(name: String) {
        val altitude = sampleAltitude()
        val entity = RunRouteEntity(
            name = name,
            buildMode = buildMode,
            singleLapMeters = lapMeters,
            isClosedLoop = isClosed,
            isClockwise = isClockwise,
            denseWaypointsJson = densePoints.toWgsJson(),
            anchorNodesJson = if (mode == EditMode.TRACK) TrackJsonCodec.encode(anchors) else "[]",
            baseAltitudeMeters = altitude
        )
        lifecycleScope.launch {
            val id = repository.saveRoute(entity)
            toast(String.format(Locale.US, "已保存 #%d：%.0f m · 海拔 %.0f m", id, lapMeters, altitude))
            status.text = String.format(Locale.US, "已保存 #%d · %s · 单圈 %.0f m", id, name, lapMeters)
            draftSuppressed = true
            clearDraft()
        }
    }

    // ---------------- UI 刷新 / 绘制 ----------------

    private fun refreshUi() {
        if (mode == EditMode.PRESET && presetStage == PresetStage.ALIGN) {
            renderLapTelemetry(String.format(Locale.US, "目标 %.0fm", targetLapMeters))
        } else {
            renderLapTelemetry(
                if (densePoints.size < 2) {
                    "单圈 --m"
                } else {
                    String.format(Locale.US, "单圈 %.0fm", lapMeters)
                }
            )
        }
        updateStatusText()
        updateSaveState()
        renderOverlays()
    }

    /**
     * 精密仪器排版：核心数字 34sp Bold，前缀标签与单位后缀缩至 ~11sp 并转为次要色，
     * 带 tnum 的等宽数字在跳秒时不会左右抖动。
     */
    private fun renderLapTelemetry(raw: String) {
        val spannable = android.text.SpannableString(raw)
        val firstDigit = raw.indexOfFirst { it.isDigit() }
        if (firstDigit > 0) {
            spannable.setSpan(
                android.text.style.RelativeSizeSpan(0.33f),
                0, firstDigit,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(getColor(R.color.pf_text_secondary)),
                0, firstDigit,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        val lastDigit = raw.indexOfLast { it.isDigit() }
        val suffixStart = when {
            lastDigit >= 0 && lastDigit < raw.length - 1 -> lastDigit + 1
            raw.endsWith("m") -> raw.length - 1
            else -> -1
        }
        if (suffixStart > 0) {
            spannable.setSpan(
                android.text.style.RelativeSizeSpan(0.33f),
                suffixStart, raw.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(getColor(R.color.pf_text_secondary)),
                suffixStart, raw.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        lapText.text = spannable
    }

    private fun updateStatusText() {
        val base = when (mode) {
            EditMode.PRESET -> if (presetStage == PresetStage.ALIGN) {
                "拖动地图对准操场"
            } else {
                "${TrackTemplates.labelOf(presetId)} · 已锁定 · 箭头 1m 微调"
            }
            EditMode.TRACK -> if (anchors.size < 3) {
                "已标记 ${anchors.size} 点 · 至少 3 点成圈"
            } else {
                "已标记 ${anchors.size} 点 · ${if (buildMode == "ROAD_SNAP") "路网贴合" else "平滑连线"}"
            }
            EditMode.FREE -> if (freehandLocked) "单指画线 · 双指缩放" else "自由拖动视野"
            EditMode.GPX -> "GPX 轨迹 · ${densePoints.size} 点"
        }
        status.text = if (densePoints.size < 2 && mode != EditMode.PRESET) "$base · 至少 2 点" else base
    }

    private fun updateSaveState() {
        val valid = densePoints.size >= 2 && lapMeters >= 20.0 &&
            !(mode == EditMode.PRESET && presetStage == PresetStage.ALIGN)
        btnSave.isEnabled = valid
        btnSave.alpha = if (valid) 1f else 0.45f
        btnSave.text = when {
            valid -> "保存路线"
            mode == EditMode.PRESET && presetStage == PresetStage.ALIGN -> "锁定后可保存"
            else -> "至少 2 点"
        }
        btnPreview.isEnabled = densePoints.size >= 3
        btnPreview.alpha = if (densePoints.size >= 3) 1f else 0.45f
    }

    private fun renderOverlays() {
        if (previewMarker != null || previewJob != null) stopPreview()
        runCatching { baiduMap.clear() }
        mainPolyline = null
        freehandPolyline = null
        previewMarker = null
        anchorMarkers.clear()
        distanceMarkers.clear()
        when (mode) {
            EditMode.PRESET -> {
                if (presetStage == PresetStage.LOCKED) mainPolyline = drawPolyline(densePoints)
            }
            EditMode.TRACK -> {
                drawAnchorMarkers()
                drawDistanceLabels()
                mainPolyline = drawPolyline(densePoints)
            }
            EditMode.FREE -> {
                val raw = combinedFreehand()
                val display = if (currentStroke.isNotEmpty() || densePoints.isEmpty()) raw else densePoints
                freehandPolyline = if (display.size >= 2) drawPolyline(display) else null
            }
            EditMode.GPX -> mainPolyline = drawPolyline(densePoints)
        }
    }

    private fun drawPolyline(points: List<Wgs>): FiberTrail? {
        if (points.size < 2) return null
        return FiberTrail.create(
            baiduMap, points.map { toBd(it) },
            resources.displayMetrics.density,
            ThemeHelper.isDark(this)
        )
    }

    private fun drawAnchorMarkers() {
        anchors.forEachIndexed { index, wgs ->
            val marker = runCatching {
                baiduMap.addOverlay(
                    MarkerOptions()
                        .position(toBd(wgs))
                        .icon(numberedIcon(index + 1))
                        .anchor(0.5f, 0.5f)
                        .draggable(true)
                        .title("anchor:$index")
                        .zIndex(30)
                ) as? Marker
            }.getOrNull() ?: return@forEachIndexed
            anchorMarkers.add(marker)
        }
    }

    private fun drawDistanceLabels() {
        if (anchors.size < 2) return
        val segments = ArrayList<Pair<Wgs, Wgs>>()
        for (i in 0 until anchors.size - 1) segments.add(anchors[i] to anchors[i + 1])
        if (autoCloseLoop && anchors.size >= 3) segments.add(anchors.last() to anchors.first())
        segments.forEachIndexed { index, (a, b) ->
            val meters = TrackMathUtils.distanceMeters(a, b)
            if (meters < 1.0) return@forEachIndexed
            val mid = Wgs((a.lat + b.lat) / 2.0, (a.lng + b.lng) / 2.0)
            val label = String.format(Locale.US, "%.0fm", meters)
            val marker = runCatching {
                baiduMap.addOverlay(
                    MarkerOptions()
                        .position(toBd(mid))
                        .icon(distanceIcon(label))
                        .anchor(0.5f, 0.5f)
                        .title("dist:$index")
                        .zIndex(20)
                ) as? Marker
            }.getOrNull() ?: return@forEachIndexed
            distanceMarkers.add(marker)
        }
    }

    private fun numberedIcon(number: Int): BitmapDescriptor = numberIconCache.getOrPut(number) {
        val size = dp(28).coerceAtLeast(28)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = getColor(R.color.pf_volt)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - dp(1), paint)
        paint.color = getColor(R.color.pf_canvas)
        paint.textSize = size * 0.44f
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        val metrics = paint.fontMetrics
        val baseline = size / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(if (number > 99) "·" else number.toString(), size / 2f, baseline, paint)
        BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private fun distanceIcon(label: String): BitmapDescriptor =
        distanceIconCache.getOrPut(label) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = dp(10).toFloat().coerceAtLeast(20f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
                color = 0xFFF2F5F8.toInt()
            }
            val width = (paint.measureText(label) + dp(12)).toInt().coerceAtLeast(dp(34))
            val height = dp(19).coerceAtLeast(19)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xCC0B0E11.toInt()
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(rect, dp(9).toFloat(), dp(9).toFloat(), bg)
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF2C3545.toInt()
                style = Paint.Style.STROKE
                strokeWidth = dp(1).toFloat()
            }
            canvas.drawRoundRect(rect, dp(9).toFloat(), dp(9).toFloat(), stroke)
            val metrics = paint.fontMetrics
            canvas.drawText(label, width / 2f, height / 2f - (metrics.ascent + metrics.descent) / 2f, paint)
            BitmapDescriptorFactory.fromBitmap(bitmap)
        }

    // ---------------- 定位 / 海拔 ----------------

    private fun locateMe(auto: Boolean = false) {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            if (!auto) locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        lifecycleScope.launch {
            if (!auto) status.text = "正在获取真实位置…"
            val loc = RealLocationSampler.sample(this@RouteEditorActivity)
            if (loc == null) {
                if (!auto) toast("暂未获取到真实位置")
                updateStatusText()
                return@launch
            }
            centerOn(Wgs(loc.latitude, loc.longitude))
            updateCrosshair()
            updateStatusText()
            if (!auto) toast("已定位到当前真实位置")
        }
    }

    private fun centerOn(point: Wgs) {
        runCatching {
            baiduMap.setMapStatus(MapStatusUpdateFactory.newLatLngZoom(toBd(point), 18f))
        }
    }

    private fun sampleAltitude(): Double {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return 50.0
        return runCatching {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc != null && loc.hasAltitude()) loc.altitude else 50.0
        }.getOrDefault(50.0)
    }

    // ---------------- GPX ----------------

    private fun applyGpx(xml: String) {
        val parsed = RouteSources.parseGpx(xml)
        if (parsed.points.size < 3) {
            toast("GPX 中未找到有效轨迹点")
            return
        }
        anchors.clear()
        freehandStrokes.clear()
        currentStroke.clear()
        val resampled = RouteSources.resample(parsed.points)
        val (closedPoints, closed) = RouteSources.closeLoopIfNeeded(resampled, 40.0)
        mode = EditMode.GPX
        densePoints = closedPoints
        isClosed = closed
        isClockwise = true
        lapMeters = TrackMathUtils.cumulativeDistances(closedPoints).last()
        buildMode = "GPX_IMPORT"
        sourceHint = parsed.trackName ?: "GPX 路线"
        gpxInfo.text = String.format(
            Locale.US,
            "原始 %d 点 → 加密 %d 点，%.0f m%s",
            parsed.points.size,
            closedPoints.size,
            lapMeters,
            if (closed) "" else "（非闭环）"
        )
        markEdited()
        updateModeUi()
        refreshUi()
        if (densePoints.isNotEmpty()) centerOn(densePoints.first())
        toast("GPX 已导入：${String.format(Locale.US, "%.0f m", lapMeters)}")
    }

    // ---------------- 草稿 ----------------

    private fun markEdited() {
        draftSuppressed = false
    }

    private fun hasDraftContent(): Boolean =
        anchors.isNotEmpty() || freehandStrokes.isNotEmpty() || currentStroke.size >= 2 ||
            (mode == EditMode.GPX && densePoints.size >= 2) ||
            (mode == EditMode.PRESET && presetStage == PresetStage.LOCKED && centerWgs != null)

    private fun persistDraftIfNeeded() {
        if (restoringDraft) return
        if (draftSuppressed || !hasDraftContent()) {
            clearDraft()
            return
        }
        runCatching {
            val obj = JSONObject()
            obj.put("mode", mode.name)
            obj.put("stage", presetStage.name)
            obj.put("preset", presetId)
            obj.put("lap", targetLapMeters)
            obj.put("rot", rotationDeg)
            obj.put("cw", isClockwise)
            obj.put("close", autoCloseLoop)
            obj.put("snap", roadSnapEnabled)
            centerWgs?.let {
                obj.put("clat", it.lat)
                obj.put("clng", it.lng)
            }
            obj.put("anchors", pointsToJson(anchors))
            val strokes = JSONArray()
            freehandStrokes.forEach { strokes.put(pointsToJson(it)) }
            obj.put("strokes", strokes)
            if (mode == EditMode.GPX) {
                obj.put("dense", pointsToJson(densePoints))
                obj.put("build", buildMode)
                obj.put("hint", sourceHint)
            }
            getSharedPreferences(DRAFT_PREFS, MODE_PRIVATE).edit()
                .putString(DRAFT_KEY, obj.toString()).apply()
        }
    }

    private fun clearDraft() {
        getSharedPreferences(DRAFT_PREFS, MODE_PRIVATE).edit().remove(DRAFT_KEY).apply()
    }

    private fun readDraft(): JSONObject? = runCatching {
        val raw = getSharedPreferences(DRAFT_PREFS, MODE_PRIVATE).getString(DRAFT_KEY, null)
            ?: return null
        val obj = JSONObject(raw)
        if (obj.length() == 0) null else obj
    }.getOrNull()

    private fun restoreDraftIfNeeded(): Boolean {
        val obj = readDraft() ?: return false
        GlassDialogs.builder(this)
            .setTitle("检测到未保存的路线草稿")
            .setMessage("上次退出前正在编辑的轨迹还在，是否继续编辑？")
            .setCancelable(false)
            .setPositiveButton("继续编辑") { _, _ -> restoreDraft(obj) }
            .setNegativeButton("丢弃") { _, _ ->
                clearDraft()
                locateMe(auto = true)
            }
            .show()
        return true
    }

    private fun restoreDraft(obj: JSONObject) {
        restoringDraft = true
        try {
            mode = runCatching { EditMode.valueOf(obj.optString("mode", EditMode.PRESET.name)) }
                .getOrDefault(EditMode.PRESET)
            presetStage = runCatching {
                PresetStage.valueOf(obj.optString("stage", PresetStage.ALIGN.name))
            }.getOrDefault(PresetStage.ALIGN)
            presetId = obj.optString("preset", "400").takeIf { it in TrackTemplates.ids } ?: "400"
            targetLapMeters = obj.optDouble("lap", 400.0).coerceIn(200.0, 500.0)
            rotationDeg = obj.optDouble("rot", 0.0)
            val nearest = Math.round(rotationDeg / 90.0) * 90.0
            rotationBase = nearest
            isClockwise = obj.optBoolean("cw", true)
            autoCloseLoop = obj.optBoolean("close", true)
            roadSnapEnabled = obj.optBoolean("snap", false)

            val lat = obj.optDouble("clat", Double.NaN)
            val lng = obj.optDouble("clng", Double.NaN)
            centerWgs = if (lat.isNaN() || lng.isNaN()) null else Wgs(lat, lng)

            anchors.clear()
            anchors.addAll(readPoints(obj.optJSONArray("anchors")))
            freehandStrokes.clear()
            readStrokes(obj.optJSONArray("strokes")).forEach { freehandStrokes.add(it) }
            currentStroke.clear()
            lastStrokeEndScreen = null

            updatePresetChip(presetId)
            updateModeUi()
            when (mode) {
                EditMode.PRESET -> {
                    if (presetStage == PresetStage.LOCKED && centerWgs != null) {
                        rebuildPreset()
                        centerOn(centerWgs!!)
                    } else {
                        presetStage = PresetStage.ALIGN
                        updateModeUi()
                        densePoints = emptyList()
                        lapMeters = 0.0
                        refreshUi()
                    }
                }
                EditMode.TRACK -> {
                    densePoints = emptyList()
                    lapMeters = 0.0
                    rebuildTrackDense()
                    anchors.firstOrNull()?.let { centerOn(it) }
                }
                EditMode.FREE -> {
                    recomputeFreehandDense(applySnap = false)
                    densePoints.firstOrNull()?.let { centerOn(it) }
                }
                EditMode.GPX -> {
                    densePoints = readPoints(obj.optJSONArray("dense"))
                    isClosed = RouteSources.closeLoopIfNeeded(densePoints, 40.0).second
                    lapMeters = if (densePoints.size >= 2) {
                        TrackMathUtils.cumulativeDistances(densePoints).last()
                    } else 0.0
                    buildMode = obj.optString("build", "GPX_IMPORT")
                    sourceHint = obj.optString("hint", "GPX 路线")
                    gpxInfo.text = String.format(Locale.US, "草稿恢复 %d 点", densePoints.size)
                    refreshUi()
                    densePoints.firstOrNull()?.let { centerOn(it) }
                }
            }
            draftSuppressed = false
            toast("草稿已恢复")
        } finally {
            restoringDraft = false
        }
    }

    private fun pointsToJson(points: List<Wgs>): JSONArray = JSONArray().apply {
        points.forEach { point ->
            put(JSONArray().put(point.lat).put(point.lng))
        }
    }

    private fun readPoints(array: JSONArray?): List<Wgs> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val pair = array.optJSONArray(index) ?: return@mapNotNull null
            val lat = pair.optDouble(0, Double.NaN)
            val lng = pair.optDouble(1, Double.NaN)
            if (lat.isNaN() || lng.isNaN()) null else Wgs(lat, lng)
        }
    }

    private fun readStrokes(array: JSONArray?): List<List<Wgs>> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val points = readPoints(array.optJSONArray(index))
            points.takeIf { it.size >= 2 }
        }
    }

    // ---------------- 投影安全 / 工具 ----------------

    private fun projectedWgs(point: Point): Wgs? {
        if (!mapReady) return null
        val width = mapView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val height = mapView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        if (point.x < 0 || point.y < 0 || point.x >= width || point.y >= height) return null
        return runCatching {
            val bd = baiduMap.projection.fromScreenLocation(point) ?: return null
            val w = CoordTransformUtil.bd09ToWgs84(bd.latitude, bd.longitude)
            Wgs(w.lat, w.lng)
        }.getOrNull()
    }

    private fun toBd(p: Wgs): BdLatLng {
        val b = CoordTransformUtil.wgs84ToBd09(p.lat, p.lng)
        return BdLatLng(b.lat, b.lng)
    }

    private fun toWgs(p: BdLatLng): Wgs {
        val w = CoordTransformUtil.bd09ToWgs84(p.latitude, p.longitude)
        return Wgs(w.lat, w.lng)
    }

    private var lastToast: String? = null
    private var lastToastAt = 0L

    private fun toastQuiet(message: String) {
        val now = SystemClock.elapsedRealtime()
        if (message == lastToast && now - lastToastAt < 4_000) return
        lastToast = message
        lastToastAt = now
        toast(message)
    }

    private fun toast(message: String) =
        com.zcshou.gogogo.run.ui.glass.GlassFeedback.toast(this, message)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
