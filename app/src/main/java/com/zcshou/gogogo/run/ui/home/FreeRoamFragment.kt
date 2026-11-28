package com.zcshou.gogogo.run.ui.home

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Point
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.BitmapDescriptor
import com.baidu.mapapi.map.BitmapDescriptorFactory
import com.baidu.mapapi.map.MapStatus
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.Marker
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.map.LogoPosition
import com.baidu.mapapi.map.TextureMapView
import com.baidu.mapapi.model.LatLng as BdLatLng
import com.baidu.mapapi.search.sug.SuggestionSearch
import com.baidu.mapapi.search.sug.SuggestionSearchOption
import com.google.android.material.button.MaterialButton
import com.zcshou.gogogo.R
import com.zcshou.gogogo.mascot.MapMascotOverlayView
import com.zcshou.gogogo.mascot.MascotDialogueEngine
import com.zcshou.gogogo.mascot.MascotPrefs
import com.zcshou.gogogo.run.data.db.RunRouteEntity
import com.zcshou.gogogo.run.data.prefs.RoamSpeeds
import com.zcshou.gogogo.run.data.prefs.RunPrefs
import com.zcshou.gogogo.run.data.repository.RunRepository
import com.zcshou.gogogo.run.engine.CoordInputParser
import com.zcshou.gogogo.run.engine.CoordTransformUtil
import com.zcshou.gogogo.run.engine.FreeRoamController
import com.zcshou.gogogo.run.engine.TrackMathUtils
import com.zcshou.gogogo.run.engine.toWgsJson
import com.zcshou.gogogo.run.guard.RealLocationSampler
import com.zcshou.gogogo.run.service.RoamService
import com.zcshou.gogogo.run.service.RunSessionHolder
import com.zcshou.gogogo.run.ui.editor.RouteSources
import com.zcshou.gogogo.run.ui.glass.GlassDialogs
import com.zcshou.gogogo.run.ui.overlay.FiberPositionDot
import com.zcshou.gogogo.run.ui.overlay.FiberTargetPin
import com.zcshou.gogogo.run.ui.overlay.FiberTrail
import com.zcshou.gogogo.run.ui.theme.MapStyleHelper
import com.zcshou.gogogo.run.ui.theme.ThemeHelper
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tab 2：自由漫游 —— 全屏地图、搜索、长按空降、荧光尾迹。
 */
class FreeRoamFragment : Fragment() {

    /** 漫游图层：与路线编辑器同源的暗夜 / 标准 / 卫星三档 */
    private enum class RoamLayer { DARK, NORMAL, SAT }

    private lateinit var mapView: TextureMapView
    private lateinit var baiduMap: BaiduMap
    /** 地图引擎就绪后重套一次皮肤；随 Fragment 销毁一并取消，避免对已销毁的 MapView 调用。 */
    private val reapplySkin = Runnable {
        applyRoamLayer(mapLayer)
        repositionMapAttribution()
    }
    private lateinit var coordText: TextView
    private lateinit var hint: View
    private lateinit var targetBar: View
    private lateinit var targetText: TextView
    private lateinit var searchInput: EditText
    private lateinit var recordButton: MaterialButton
    private lateinit var followChip: MaterialButton
    private lateinit var console: View
    private lateinit var consoleChip: TextView
    private lateinit var favScroll: View
    private lateinit var favRow: LinearLayout
    private lateinit var smoothSwitch: Switch
    private lateinit var repository: RunRepository
    private lateinit var layerDark: TextView
    private lateinit var layerNormal: TextView
    private lateinit var layerSat: TextView
    private var mapLayer = RoamLayer.DARK

    private var autoLocated = false

    private val suggestionSearchDelegate = lazy { SuggestionSearch.newInstance() }
    private val suggestionSearch by suggestionSearchDelegate
    private var targetMarker: Marker? = null
    private var trailOverlay: FiberTrail? = null
    private var positionMarker: Marker? = null
    private var positionIcon: BitmapDescriptor? = null
    private lateinit var mapMascot: MapMascotOverlayView
    private var lastMascotPos: TrackMathUtils.LatLng? = null
    private var lastMascotBearing = 0f
    private var lastMascotMoving = false
    private var mapRunLineShown = false
    private var pendingTarget: TrackMathUtils.LatLng? = null
    private var lastTrailSeq = -1L
    private var centeredOnce = false

    private var followEnabled = true
    private var lastFollowAt = 0L
    private var lastHudPos: TrackMathUtils.LatLng? = null
    private var roamMeters = 0.0
    private var roamStartedAt = 0L

    private var recording = false
    private var recordStart = 0L
    private var lastRecordLabel = 0L
    private val recorded = mutableListOf<TrackMathUtils.LatLng>()
    private var lastRecordedPoint: TrackMathUtils.LatLng? = null

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) locateReal() else toast("未授权定位权限")
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_free_roam, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        mapView = view.findViewById(R.id.roamMap)
        baiduMap = mapView.map
        mapMascot = view.findViewById(R.id.mapMascot)
        MapStyleHelper.applyForTheme(mapView)
        // 地图引擎就绪后重套一次皮肤，确保深色模式不会残留亮白底图
        baiduMap.setOnMapLoadedCallback {
            applyRoamLayer(mapLayer)
            repositionMapAttribution()
        }
        mapView.postDelayed(reapplySkin, 900L)
        // 比例尺 / Logo 贴底挂在「漫游控制台」正上方 12dp，不再悬空停在屏幕上半部分
        repositionMapAttribution()
        coordText = view.findViewById(R.id.roamCoord)
        hint = view.findViewById(R.id.roamHint)
        targetBar = view.findViewById(R.id.roamTargetBar)
        targetText = view.findViewById(R.id.roamTargetText)
        searchInput = view.findViewById(R.id.roamSearchInput)
        recordButton = view.findViewById(R.id.btnRoamRecord)
        followChip = view.findViewById(R.id.btnFollow)
        console = view.findViewById(R.id.roamConsole)
        consoleChip = view.findViewById(R.id.roamConsoleChip)
        // 地图页三块光学玻璃共享同一次地图刷新节拍（见 GlassRenderGate）
        // 背景改由 MapBackdropCapture 接管：同帧只截一次地图，并做去蓝校色
        listOf(R.id.roamTopPanel, R.id.roamConsole, R.id.roamTargetBar).forEach { id ->
            (view.findViewById<View>(id) as? com.example.liquidglass.LiquidGlassView)?.apply {
                com.zcshou.gogogo.run.ui.glass.MapBackdropCapture.bind(this, mapView)
                com.zcshou.gogogo.run.ui.glass.GlassRenderGate.registerMapPanel(this)
                com.zcshou.gogogo.run.ui.glass.OpticalGlassTuning.applyMapPanel(this)
            }
        }
        // 圆形定位按钮：小尺寸控件只对齐刷新节拍与折射参数，
        // 不加地图染色（46dp 里再蒙一层雾会把十字准星泡糊）。
        (view.findViewById<View>(R.id.btnLocateMe) as? com.example.liquidglass.LiquidGlassView)?.apply {
            com.zcshou.gogogo.run.ui.glass.MapBackdropCapture.bind(this, mapView)
            com.zcshou.gogogo.run.ui.glass.MapBackdropCapture.applyBlurProfile(this)
            com.zcshou.gogogo.run.ui.glass.GlassRenderGate.registerMapPanel(this)
            enablePressEffect = false
            enableShadow = false
        }
        favScroll = view.findViewById(R.id.roamFavScroll)
        favRow = view.findViewById(R.id.roamFavRow)
        smoothSwitch = view.findViewById(R.id.switchSmoothWalk)
        layerDark = view.findViewById(R.id.roamLayerDark)
        layerNormal = view.findViewById(R.id.roamLayerNormal)
        layerSat = view.findViewById(R.id.roamLayerSat)
        repository = RunRepository(requireContext())
        positionIcon = FiberPositionDot.icon(requireContext())

        // 图层：默认跟随当前昼夜主题，用户点选后以用户选择为准
        layerDark.setOnClickListener { applyRoamLayer(RoamLayer.DARK) }
        layerNormal.setOnClickListener { applyRoamLayer(RoamLayer.NORMAL) }
        layerSat.setOnClickListener { applyRoamLayer(RoamLayer.SAT) }
        applyRoamLayer(if (ThemeHelper.isDark(requireContext())) RoamLayer.DARK else RoamLayer.NORMAL)

        mapView.showZoomControls(false)
        applyMapInsets(view)

        baiduMap.setOnMapClickListener(object : BaiduMap.OnMapClickListener {
            override fun onMapClick(point: BdLatLng) = dropTarget(point)
            override fun onMapPoiClick(poi: com.baidu.mapapi.map.MapPoi) = dropTarget(poi.position)
        })
        baiduMap.setOnMapLongClickListener(object : BaiduMap.OnMapLongClickListener {
            override fun onMapLongClick(point: BdLatLng) = dropTarget(point)
        })
        baiduMap.setOnMapTouchListener { event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_MOVE && followEnabled) {
                followEnabled = false
                updateFollowChip()
            }
        }
        baiduMap.setOnMapStatusChangeListener(object : BaiduMap.OnMapStatusChangeListener {
            override fun onMapStatusChangeStart(status: MapStatus) = Unit

            override fun onMapStatusChangeStart(status: MapStatus, reason: Int) = Unit

            override fun onMapStatusChange(status: MapStatus) {
                com.zcshou.gogogo.run.ui.glass.GlassRenderGate.requestMapRefresh(260L)
                repositionMapMascot()
            }

            override fun onMapStatusChangeFinish(status: MapStatus) {
                repositionMapMascot()
            }
        })

        view.findViewById<TextView>(R.id.btnRoamTeleport).setOnClickListener { confirmTeleport() }
        view.findViewById<TextView>(R.id.btnRoamCancel).setOnClickListener { clearTarget() }
        view.findViewById<View>(R.id.btnRoamSearch).setOnClickListener { doSearch() }
        view.findViewById<View>(R.id.btnRoamLocate).setOnClickListener { locateReal() }
        view.findViewById<View>(R.id.btnLocateMe).setOnClickListener { locateReal() }
        followChip.setOnClickListener {
            followEnabled = !followEnabled
            updateFollowChip()
            if (followEnabled) {
                FreeRoamController.state.value.position?.let { followCamera(it, force = true) }
            }
        }
        hint.setOnClickListener {
            RoamService.start(requireContext())
            toast("悬浮摇杆已显示")
        }
        recordButton.setOnClickListener { if (recording) stopRecording() else startRecording() }
        view.findViewById<TextView>(R.id.btnRoamStopSim).setOnClickListener {
            (activity as? RunHost)?.stopSimulation()
            toast("模拟定位已停止，真实 GPS 已恢复")
        }
        view.findViewById<View>(R.id.btnRoamCollapsePanel).setOnClickListener { collapseConsole() }
        view.findViewById<View>(R.id.btnRoamConsoleCollapse).setOnClickListener { collapseConsole() }
        consoleChip.setOnClickListener { expandConsole() }
        view.findViewById<TextView>(R.id.btnRoamStar).setOnClickListener { saveFavorite() }
        smoothSwitch.isChecked = RunPrefs.isSmoothWalkEnabled(requireContext())
        smoothSwitch.setOnCheckedChangeListener { _, checked ->
            RunPrefs.setSmoothWalkEnabled(requireContext(), checked)
        }
        searchInput.setOnEditorActionListener { _, _, _ ->
            doSearch()
            true
        }

        setupSearch()
        centerOnDevice()
        updateFollowChip()
        renderFavorites()
        autoLocateOnce()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                FreeRoamController.state.collect { state ->
                    val pos = state.position
                    updateHud(pos, state)
                    updatePositionMarker(pos)
                    updateMapMascot(pos)
                    if (pos != null) {
                        if (!centeredOnce || followEnabled) {
                            followCamera(pos, force = !centeredOnce)
                        }
                        centeredOnce = true
                    }
                    drawTrail(state.trail, state.trailSeq)
                    trackRecording(pos)
                }
            }
        }
    }

    private fun updateHud(pos: TrackMathUtils.LatLng?, state: FreeRoamController.RoamState) {
        if (pos == null) {
            roamMeters = 0.0
            roamStartedAt = 0L
            lastHudPos = null
            coordText.text = "未接管 · 点击地图落点开始漫游"
            return
        }
        if (roamStartedAt == 0L) roamStartedAt = SystemClock.elapsedRealtime()
        val last = lastHudPos
        if (last != null) {
            val d = TrackMathUtils.distanceMeters(last, pos)
            if (d in 0.2..30.0) roamMeters += d
        }
        lastHudPos = pos
        // 精简为单行三段，避免过长文案触发 ellipsize 把信息挤没
        coordText.text = String.format(
            Locale.US,
            "%.4f, %.4f · 漫游 %.0fm · %s",
            pos.lat, pos.lng, roamMeters,
            if (state.speedMps > 0.05) String.format(Locale.US, "%.1f m/s", state.speedMps) else "待命"
        )
    }

    private fun updatePositionMarker(pos: TrackMathUtils.LatLng?) {
        if (pos == null) {
            positionMarker?.remove()
            positionMarker = null
            return
        }
        val bd = toBd(pos)
        val marker = positionMarker
        if (marker == null) {
            positionMarker = baiduMap.addOverlay(
                MarkerOptions()
                    .position(bd)
                    .icon(positionIcon ?: BitmapDescriptorFactory.fromResource(R.drawable.icon_gcoding))
                    .anchor(0.5f, 0.5f)
                    .zIndex(20)
            ) as? Marker
        } else {
            marker.position = bd
        }
    }

    private fun updateMapMascot(pos: TrackMathUtils.LatLng?) {
        if (!isAdded || !::mapMascot.isInitialized) return
        if (pos == null) {
            mapMascot.hide()
            lastMascotPos = null
            lastMascotMoving = false
            mapRunLineShown = false
            return
        }
        val previous = lastMascotPos
        val moved = previous != null && (previous.lat != pos.lat || previous.lng != pos.lng)
        if (moved) {
            lastMascotBearing = TrackMathUtils.bearingDegrees(previous, pos).toFloat()
        }
        lastMascotMoving = moved
        lastMascotPos = pos

        if (!isResumed || isHidden || !MascotPrefs.isFull(requireContext())) {
            mapMascot.hide()
            return
        }
        val point = baiduMap.projection.toScreenLocation(toBd(pos))
        mapMascot.showAt(point.x, point.y, lastMascotBearing, lastMascotMoving)
        if (moved && !mapRunLineShown) {
            mapRunLineShown = true
            val line = MascotDialogueEngine.onSystemEvent(
                MascotDialogueEngine.State.RUNNING,
                bypassCooldown = true
            )
            if (line != null) mapMascot.showBubble(line)
        }
    }

    private fun repositionMapMascot() {
        if (!isAdded || !::mapMascot.isInitialized) return
        if (!isResumed || isHidden || !MascotPrefs.isFull(requireContext())) {
            mapMascot.hide()
            return
        }
        val pos = lastMascotPos ?: return
        val point = baiduMap.projection.toScreenLocation(toBd(pos))
        mapMascot.showAt(point.x, point.y, lastMascotBearing, lastMascotMoving)
    }

    private fun followCamera(pos: TrackMathUtils.LatLng, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastFollowAt < 200) return
        lastFollowAt = now
        baiduMap.setMapStatus(MapStatusUpdateFactory.newLatLng(toBd(pos)))
    }

    private fun updateFollowChip() {
        followChip.text = if (followEnabled) "跟随中" else "自由视角"
        val color = requireContext().getColor(
            if (followEnabled) R.color.pf_text_primary else R.color.pf_text_secondary
        )
        followChip.setTextColor(color)
        followChip.backgroundTintList = ColorStateList.valueOf(
            requireContext().getColor(
                if (followEnabled) R.color.pf_volt_a18 else R.color.pf_secondary_surface
            )
        )
        followChip.iconTint = ColorStateList.valueOf(color)
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) {
            runCatching { mapView.onResume() }
            // 从后台/其它页回来时同步当前图层（卫星图不会被重新盖上暗色矩阵）
            if (::baiduMap.isInitialized) applyRoamLayer(mapLayer)
            if (::mapMascot.isInitialized) repositionMapMascot()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            runCatching { mapView.onResume() }
            autoLocateOnce()
            renderFavorites()
            applyRoamLayer(mapLayer)
            if (::mapMascot.isInitialized) repositionMapMascot()
        } else {
            if (::mapMascot.isInitialized) mapMascot.hide()
            runCatching { mapView.onPause() }
        }
    }

    override fun onPause() {
        if (::mapMascot.isInitialized) mapMascot.hide()
        runCatching { mapView.onPause() }
        super.onPause()
    }

    override fun onDestroyView() {
        mapView.removeCallbacks(reapplySkin)
        runCatching {
            baiduMap.setOnMapLoadedCallback(null)
            baiduMap.setOnMapClickListener(null)
            baiduMap.setOnMapLongClickListener(null)
            baiduMap.setOnMapTouchListener(null)
            baiduMap.setOnMapStatusChangeListener(null)
        }
        if (suggestionSearchDelegate.isInitialized()) {
            runCatching { suggestionSearch.destroy() }
        }
        if (::mapMascot.isInitialized) mapMascot.release()
        runCatching { positionIcon?.recycle() }
        positionIcon = null
        trailOverlay = null
        targetMarker = null
        positionMarker = null
        // 释放本页绑定的共享背景捕获帧，避免整帧位图滞留到下次绑定；
        // 只清自己的源，别清其它页面（首页地图可能还在任务栈下方）的帧
        com.zcshou.gogogo.run.ui.glass.MapBackdropCapture.release(mapView)
        runCatching { mapView.onDestroy() }
        super.onDestroyView()
    }

    /**
     * 全屏地图的 insets：地图本身铺满四边，
     * 只有搜索框、控制台、定位按钮等浮层避让状态栏/导航栏与底部悬浮导航。
     */
    private fun applyMapInsets(root: View) {
        val topPanel = root.findViewById<View>(R.id.roamTopPanel)
        val layerBar = root.findViewById<View>(R.id.roamLayerBar)
        val consoleView = root.findViewById<View>(R.id.roamConsole)
        val chipView = root.findViewById<View>(R.id.roamConsoleChip)
        val hintView = root.findViewById<View>(R.id.roamHint)
        val locateView = root.findViewById<View>(R.id.btnLocateMe)
        val targetView = root.findViewById<View>(R.id.roamTargetBar)
        val scrim = root.findViewById<View>(R.id.roamTopScrim)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            setTopMargin(topPanel, dp(10) + bars.top)
            // 顶栏 50dp + 上下留白：图层胶囊紧贴在搜索面板下方
            setTopMargin(layerBar, dp(68) + bars.top)
            setBottomMargin(consoleView, dp(82) + bars.bottom)
            setBottomMargin(chipView, dp(92) + bars.bottom)
            setBottomMargin(hintView, dp(322) + bars.bottom)
            setBottomMargin(locateView, dp(332) + bars.bottom)
            setBottomMargin(targetView, dp(390) + bars.bottom)
            scrim.layoutParams?.let {
                it.height = bars.top + dp(12)
                scrim.layoutParams = it
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /**
     * 百度地图 Logo / 比例尺固定在底部「漫游控制台」卡片正上方 12dp 的左下角；
     * 控制台收起时以迷你胶囊为基准（即悬浮导航栏上方 12dp），不再悬空停留在屏幕上半部分。
     * 必须在 mapLoaded / 控制台收展后再次调用，否则样式引擎重置会把控件拉回默认位置。
     */
    private fun repositionMapAttribution() {
        if (!::console.isInitialized || !::consoleChip.isInitialized) return
        runCatching {
            val anchor = if (console.visibility == View.VISIBLE) console else consoleChip
            if (anchor.visibility != View.VISIBLE) return
            val anchorLoc = IntArray(2)
            anchor.getLocationOnScreen(anchorLoc)
            val mapLoc = IntArray(2)
            mapView.getLocationOnScreen(mapLoc)
            val anchorTop = anchorLoc[1] - mapLoc[1]
            if (anchorTop <= 0 || mapView.height <= 0) return
            mapView.setLogoPosition(LogoPosition.logoPostionleftBottom)
            // 底部内边距把 Logo 从地图底边抬高到锚点上方 12dp
            mapView.setPadding(0, 0, 0, mapView.height - anchorTop + dp(12))
            mapView.showScaleControl(true)
            // 比例尺控件锚定在锚点上方 12dp（控件自身高约 28dp，取顶部坐标）
            mapView.setScaleControlPosition(Point(dp(16), anchorTop - dp(12) - dp(28)))

            // 定位按钮跟随控制台高度上移，与面板顶部保持 12dp，避免展开后被压住
            view?.findViewById<View>(R.id.btnLocateMe)?.let { locate ->
                setBottomMargin(locate, mapView.height - anchorTop + dp(12))
            }
        }
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

    // ---------------- 交互 ----------------

    private fun showTarget(bd: BdLatLng, lat: Double, lng: Double) {
        targetMarker?.remove()
        targetMarker = baiduMap.addOverlay(
            MarkerOptions()
                .position(bd)
                .icon(FiberTargetPin.icon(requireContext()))
                .title("空降目标")
        ) as? Marker
        targetText.text = String.format(Locale.US, "目标：%.5f, %.5f", lat, lng)
        targetBar.visibility = View.VISIBLE
        hint.visibility = View.GONE
        // 面板从 GONE 转 VISIBLE：闸门不会给不可见视图派发帧，这里主动补一次采样
        com.zcshou.gogogo.run.ui.glass.GlassRenderGate.requestRefresh(220L)
    }

    private fun clearTarget() {
        pendingTarget = null
        targetMarker?.remove()
        targetMarker = null
        targetBar.visibility = View.GONE
        hint.visibility = View.VISIBLE
    }

    private fun confirmTeleport() {
        val target = pendingTarget ?: return
        if (RunSessionHolder.sessionActive) {
            toast("自动跑圈进行中，请先结束当前跑圈")
            return
        }
        RoamService.start(requireContext())
        FreeRoamController.attach(requireContext())
        val from = FreeRoamController.state.value.position
        val distance = from?.let { TrackMathUtils.distanceMeters(it, target) } ?: 0.0
        if (RunPrefs.isSmoothWalkEnabled(requireContext()) && from != null && distance <= 300.0) {
            val speed = RoamSpeeds.speed(requireContext(), RoamSpeeds.Gear.RUN)
            FreeRoamController.glideTo(target.lat, target.lng, speed.toDouble())
            toast(String.format(Locale.US, "平滑走过去 · 约 %.0f m", distance))
        } else {
            FreeRoamController.teleport(target.lat, target.lng)
            toast("已空降到该位置")
        }
        centerMap(target.lat, target.lng, 17f)
        clearTarget()
    }

    private fun locateReal(silent: Boolean = false) {
        val ctx = context ?: return
        val hasFine = ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            if (!silent) locationPermission.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            if (!silent && isAdded) toast("正在获取真实位置…")
            val loc = RealLocationSampler.sample(ctx.applicationContext)
            if (!isAdded || view == null) return@launch
            if (loc == null) {
                if (!silent) toast("暂未获取到真实位置，请检查定位权限")
                return@launch
            }
            centerMap(loc.latitude, loc.longitude, 17.5f)
            if (loc.hasAltitude()) {
                FreeRoamController.updateBaseAltitude(loc.altitude)
            }
            if (!silent) toast("已定位到当前真实位置")
        }
    }

    private fun autoLocateOnce() {
        if (autoLocated) return
        autoLocated = true
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) locateReal(silent = true) else centerOnDevice()
    }

    private fun collapseConsole() {
        console.visibility = View.GONE
        consoleChip.visibility = View.VISIBLE
        view?.post { repositionMapAttribution() }
    }

    private fun expandConsole() {
        console.visibility = View.VISIBLE
        consoleChip.visibility = View.GONE
        view?.post { repositionMapAttribution() }
    }

    private fun saveFavorite() {
        val point = pendingTarget ?: FreeRoamController.state.value.position
        if (point == null) {
            toast("还没有可收藏的位置，请先在地图上落点")
            return
        }
        val defaultName = String.format(Locale.US, "地点 %.4f,%.4f", point.lat, point.lng)
        val input = EditText(requireContext()).apply {
            com.zcshou.gogogo.run.ui.widget.DialogStyler.styleInput(this)
            setText(defaultName)
            setSelection(text.length)
        }
        GlassDialogs.builder(requireContext())
            .setTitle("收藏这个地点")
            .setMessage("最多保存 24 个常用地标；长按胶囊可置顶到摇杆快捷条")
            .setView(input)
            .setPositiveButton("收藏") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { defaultName }
                RunPrefs.addFavorite(
                    requireContext(),
                    RunPrefs.Favorite(name.take(12), point.lat, point.lng)
                )
                renderFavorites()
                toast("已收藏「$name」")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renderFavorites() {
        favRow.removeAllViews()
        val favorites = RunPrefs.favorites(requireContext())
        favScroll.visibility = if (favorites.isEmpty()) View.GONE else View.VISIBLE
        favorites.forEach { favorite ->
            val chip = TextView(requireContext()).apply {
                text = if (favorite.pinned) "★ ${favorite.name}" else favorite.name
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = dp(120)
                gravity = android.view.Gravity.CENTER
                setPadding(dp(10), dp(5), dp(10), dp(5))
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_star_line, 0, 0, 0)
                compoundDrawablePadding = dp(5)
                val chipColor = ContextCompat.getColor(requireContext(), R.color.pf_text_primary)
                setTextColor(chipColor)
                compoundDrawableTintList = ColorStateList.valueOf(chipColor)
                background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_secondary_button)
                setOnClickListener { centerMap(favorite.lat, favorite.lng, 17f) }
                setOnLongClickListener {
                    showFavoriteMenu(favorite)
                    true
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            favRow.addView(chip, lp)
        }
    }

    /** 长按常用地标：置顶 / 改标签 / 删除（置顶项会出现在悬浮摇杆快捷条最前） */
    private fun showFavoriteMenu(favorite: RunPrefs.Favorite) {
        val actions = arrayOf(
            if (favorite.pinned) "取消置顶" else "置顶到摇杆快捷条",
            "修改标签（当前：${favorite.tag}）",
            "删除地标"
        )
        GlassDialogs.builder(requireContext())
            .setTitle(favorite.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        RunPrefs.setFavoritePinned(requireContext(), favorite.name, !favorite.pinned)
                        renderFavorites()
                        toast(if (favorite.pinned) "已取消置顶" else "已置顶，摇杆面板里可直接飞跃")
                    }
                    1 -> showFavoriteTagDialog(favorite)
                    else -> {
                        RunPrefs.removeFavorite(requireContext(), favorite.name)
                        renderFavorites()
                        toast("已删除「${favorite.name}」")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showFavoriteTagDialog(favorite: RunPrefs.Favorite) {
        val input = EditText(requireContext()).apply {
            com.zcshou.gogogo.run.ui.widget.DialogStyler.styleInput(this)
            setText(favorite.tag)
            setSelection(text.length)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        GlassDialogs.builder(requireContext())
            .setTitle("地标标签")
            .setMessage("如「公园」「公司」「校园」，最多 6 个字")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                RunPrefs.setFavoriteTag(requireContext(), favorite.name, input.text.toString())
                renderFavorites()
                toast("标签已更新")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 录制为路线 ----------------

    private fun startRecording() {
        if (RunSessionHolder.sessionActive) {
            toast("自动跑圈进行中，暂不可录制")
            return
        }
        RoamService.start(requireContext())
        FreeRoamController.attach(requireContext())
        recorded.clear()
        lastRecordedPoint = null
        recording = true
        recordStart = SystemClock.elapsedRealtime()
        lastRecordLabel = 0
        recordButton.text = "结束录制 · 0s"
        val danger = requireContext().getColor(R.color.pf_bad)
        recordButton.setTextColor(danger)
        recordButton.iconTint = ColorStateList.valueOf(danger)
        toast("开始录制：用摇杆漫游或空降移动")
    }

    private fun trackRecording(pos: TrackMathUtils.LatLng?) {
        if (!recording) return
        if (pos != null) {
            val last = lastRecordedPoint
            if (last == null || TrackMathUtils.distanceMeters(last, pos) >= 0.6) {
                recorded.add(pos)
                lastRecordedPoint = pos
            }
        }
        val secs = (SystemClock.elapsedRealtime() - recordStart) / 1000
        if (secs != lastRecordLabel) {
            lastRecordLabel = secs
            recordButton.text = "结束录制 · ${secs}s · ${recorded.size} 点"
        }
    }

    private fun stopRecording() {
        recording = false
        recordButton.text = "录制为路线"
        val idleInk = ContextCompat.getColor(requireContext(), R.color.pf_text_primary)
        recordButton.setTextColor(idleInk)
        recordButton.iconTint = ColorStateList.valueOf(idleInk)
        if (recorded.size < 8) {
            toast("轨迹太短，请多移动一会儿再结束")
            return
        }
        val smoothed = TrackMathUtils.smooth3(recorded.toList())
        var path = RouteSources.simplifyRdp(smoothed, 0.8)
        if (path.size < 3) path = smoothed
        var closed = RouteSources.closeLoopIfNeeded(path, 60.0).second
        if (!closed && path.size >= 3) {
            // 单程漫游：沿原路折返补成闭环，保证巡航可循环
            path = path + path.reversed().drop(1)
            closed = true
        }
        if (path.size < 3) {
            toast("录制点太少，无法成圈")
            return
        }
        val meters = TrackMathUtils.cumulativeDistances(path).last()
        val clockwise = RouteSources.isClockwise(path)
        val defaultName = SimpleDateFormat("MM-dd HH:mm 漫游", Locale.CHINA).format(Date())
        val input = EditText(requireContext()).apply {
            com.zcshou.gogogo.run.ui.widget.DialogStyler.styleInput(this)
            setText(defaultName)
            setSelection(text.length)
        }
        GlassDialogs.builder(requireContext())
            .setTitle("保存漫游路线")
            .setMessage(
                String.format(
                    Locale.US,
                    "单圈 %.0f m · %d 点 · %s",
                    meters, path.size, if (closed) "闭环" else "未闭环"
                )
            )
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { defaultName }
                val entity = RunRouteEntity(
                    name = name,
                    buildMode = "ROAM_RECORD",
                    singleLapMeters = meters,
                    isClosedLoop = closed,
                    isClockwise = clockwise,
                    denseWaypointsJson = path.toWgsJson(),
                    anchorNodesJson = "[]",
                    baseAltitudeMeters = FreeRoamController.state.value.baseAltitude
                )
                viewLifecycleOwner.lifecycleScope.launch {
                    val id = repository.saveRoute(entity)
                    toast(String.format(Locale.US, "已保存 #%d：%.0f m，可在自动跑圈中使用", id, meters))
                    FreeRoamController.clearTrail()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 搜索 ----------------

    private fun setupSearch() {
        suggestionSearch.setOnGetSuggestionResultListener { result ->
            // 回调可能在 Fragment 销毁后到达，requireContext() 会抛 IllegalStateException
            if (!isAdded) return@setOnGetSuggestionResultListener
            val all = result?.allSuggestions.orEmpty()
            if (all.isEmpty()) {
                toast("没有搜到结果")
                return@setOnGetSuggestionResultListener
            }
            val labels = all.map { info ->
                listOfNotNull(info.key, info.district, info.address).joinToString(" · ")
            }.toTypedArray()
            GlassDialogs.builder(requireContext())
                .setTitle("选择地点")
                .setItems(labels) { _, which ->
                    val pt = all[which].pt ?: return@setItems
                    baiduMap.setMapStatus(MapStatusUpdateFactory.newLatLngZoom(pt, 17f))
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun doSearch() {
        val kw = searchInput.text.toString().trim()
        if (kw.isEmpty()) {
            toast("请输入搜索关键词或经纬度")
            return
        }
        // 直接粘贴经纬度：不走百度文本联想，按坐标系换算后落标预览
        when (val coord = CoordInputParser.parse(kw)) {
            is CoordInputParser.Result.Parsed -> {
                hideKeyboard()
                showCoordDatumChooser(coord)
                return
            }
            CoordInputParser.Result.OutOfRange -> {
                toast("坐标越界：纬度需在 -90~90、经度需在 -180~180")
                return
            }
            CoordInputParser.Result.NotCoordinate -> Unit
        }
        suggestionSearch.requestSuggestion(
            SuggestionSearchOption().keyword(kw).city("全国")
        )
        hideKeyboard()
    }

    /** 坐标落标前先确认坐标系：大陆三种坐标系偏差可达数百米 */
    private fun showCoordDatumChooser(coord: CoordInputParser.Result.Parsed) {
        val title = String.format(
            Locale.US,
            if (coord.swapped) {
                "疑似经度在前，已纠正为 %.5f, %.5f"
            } else {
                "坐标 %.5f, %.5f"
            },
            coord.lat,
            coord.lng
        )
        val options = arrayOf(
            "按 WGS-84（原始 GPS）空降",
            "按 GCJ-02（高德 / 腾讯）纠偏空降",
            "按 BD-09（百度拾取）纠偏空降"
        )
        GlassDialogs.builder(requireContext())
            .setTitle(title)
            .setItems(options) { _, which ->
                val wgs = when (which) {
                    0 -> TrackMathUtils.LatLng(coord.lat, coord.lng)
                    1 -> CoordTransformUtil.gcj02ToWgs84(coord.lat, coord.lng)
                        .let { TrackMathUtils.LatLng(it.lat, it.lng) }
                    else -> CoordTransformUtil.bd09ToWgs84(coord.lat, coord.lng)
                        .let { TrackMathUtils.LatLng(it.lat, it.lng) }
                }
                dropTargetAtWgs(wgs, which)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 按 WGS-84 落点（内部唯一事实来源），地图侧再换算成 BD-09 渲染 */
    private fun dropTargetAtWgs(wgs: TrackMathUtils.LatLng, datum: Int) {
        pendingTarget = wgs
        val bd = CoordTransformUtil.wgs84ToBd09(wgs.lat, wgs.lng)
        showTarget(BdLatLng(bd.lat, bd.lng), wgs.lat, wgs.lng)
        centerMap(wgs.lat, wgs.lng, 17f)
        val label = when (datum) {
            0 -> "WGS-84"
            1 -> "GCJ-02 已纠偏"
            else -> "BD-09 已纠偏"
        }
        toast("已按 $label 落标，点「空降到此位置」出发")
    }

    /** 图层切换：卫星图必须摘掉自定义颜色矩阵，否则整张卫星底片会被反相 */
    private fun applyRoamLayer(layer: RoamLayer) {
        mapLayer = layer
        if (!::baiduMap.isInitialized) return
        runCatching {
            when (layer) {
                RoamLayer.DARK -> {
                    baiduMap.setMapType(BaiduMap.MAP_TYPE_NORMAL)
                    MapStyleHelper.applyDark(mapView)
                }
                RoamLayer.NORMAL -> {
                    baiduMap.setMapType(BaiduMap.MAP_TYPE_NORMAL)
                    MapStyleHelper.applyLight(mapView)
                }
                RoamLayer.SAT -> {
                    MapStyleHelper.disable(mapView)
                    baiduMap.setMapType(BaiduMap.MAP_TYPE_SATELLITE)
                }
            }
        }
        renderLayerChips()
    }

    private fun renderLayerChips() {
        if (!::layerDark.isInitialized) return
        val ctx = context ?: return
        listOf(
            layerDark to RoamLayer.DARK,
            layerNormal to RoamLayer.NORMAL,
            layerSat to RoamLayer.SAT
        ).forEach { (chip, candidate) ->
            val on = candidate == mapLayer
            chip.isSelected = on
            chip.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (on) R.color.pf_on_primary else R.color.pf_text_secondary
                )
            )
        }
    }

    private fun hideKeyboard() {
        runCatching {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE)
                as InputMethodManager
            imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
        }
    }

    // ---------------- 地图绘制 ----------------

    private fun centerOnDevice() {
        val real = lastDeviceLocation() ?: return
        centerMap(real.first, real.second, 16f)
    }

    private fun centerMap(lat: Double, lng: Double, zoom: Float) {
        val bd = CoordTransformUtil.wgs84ToBd09(lat, lng)
        baiduMap.setMapStatus(
            MapStatusUpdateFactory.newLatLngZoom(BdLatLng(bd.lat, bd.lng), zoom)
        )
    }

    private fun drawTrail(trail: List<TrackMathUtils.LatLng>, trailSeq: Long) {
        if (trailSeq == lastTrailSeq) return
        lastTrailSeq = trailSeq
        if (trail.size < 2) {
            trailOverlay?.remove()
            trailOverlay = null
            return
        }
        val bdPoints = trail.map { toBd(it) }
        val overlay = trailOverlay
        if (overlay == null) {
            trailOverlay = FiberTrail.create(
                baiduMap, bdPoints,
                resources.displayMetrics.density,
                ThemeHelper.isDark(requireContext())
            )
        } else {
            overlay.update(bdPoints)
        }
    }

    private fun toBd(p: TrackMathUtils.LatLng): BdLatLng {
        val b = CoordTransformUtil.wgs84ToBd09(p.lat, p.lng)
        return BdLatLng(b.lat, b.lng)
    }

    private fun dropTarget(point: BdLatLng) {
        val wgs = CoordTransformUtil.bd09ToWgs84(point.latitude, point.longitude)
        pendingTarget = TrackMathUtils.LatLng(wgs.lat, wgs.lng)
        showTarget(point, wgs.lat, wgs.lng)
    }

    private fun lastDeviceLocation(): Pair<Double, Double>? {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        return runCatching {
            val lm = requireContext()
                .getSystemService(Context.LOCATION_SERVICE) as LocationManager
            (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))
                ?.let { it.latitude to it.longitude }
        }.getOrNull()
    }

    private fun toast(message: String) =
        com.zcshou.gogogo.run.ui.glass.GlassFeedback.toast(requireContext(), message)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
