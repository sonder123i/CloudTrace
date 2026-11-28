package com.zcshou.gogogo.run.ui.home

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.baidu.mapapi.map.LogoPosition
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.MapStatus
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.map.TextureMapView
import com.baidu.mapapi.model.LatLng
import com.baidu.mapapi.model.LatLngBounds
import com.example.liquidglass.LiquidGlassView
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.data.db.RunRouteEntity
import com.zcshou.gogogo.run.data.repository.RunRepository
import com.zcshou.gogogo.run.engine.CoordTransformUtil
import com.zcshou.gogogo.run.engine.TrackJsonCodec
import com.zcshou.gogogo.run.engine.TrackMathUtils
import com.zcshou.gogogo.run.ui.editor.RouteEditorActivity
import com.zcshou.gogogo.run.ui.editor.RouteSources
import com.zcshou.gogogo.run.ui.glass.DeepRunGlassManager
import com.zcshou.gogogo.run.ui.glass.GlassRenderGate
import com.zcshou.gogogo.run.ui.glass.MapBackdropCapture
import com.zcshou.gogogo.run.ui.glass.OpticalGlassTuning
import com.zcshou.gogogo.run.ui.overlay.FiberPositionDot
import com.zcshou.gogogo.run.ui.overlay.FiberTrail
import com.zcshou.gogogo.run.ui.theme.MapStyleHelper
import com.zcshou.gogogo.run.ui.theme.ThemeHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 路线详情预览：**只读版建圈视图**。
 *
 * 与建圈器保持同一视觉语言：真实百度地图（同昼夜皮肤）、同款双层光纤轨迹、
 * 起点标记，自动缩放到整条路线；但不含搜索框、模式切换、D-Pad、锁定/保存等编辑控件。
 *
 * 入口：首页主路线卡标题区 / ⋮ 菜单「查看路线」。
 */
class RoutePreviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ROUTE_ID = "route_id"
        /** 试跑一圈的总时长与帧间隔 */
        private const val TEST_RUN_DURATION_MS = 4_000L
        private const val TEST_RUN_FRAME_MS = 40L
    }

    private lateinit var mapView: TextureMapView
    private var trail: FiberTrail? = null
    private var currentRoute: RunRouteEntity? = null
    private var testRunJob: Job? = null
    private var testRunMarker: com.baidu.mapapi.map.Marker? = null

    private val density get() = resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.apply(this)
        super.onCreate(savedInstanceState)
        ThemeHelper.enableEdgeToEdge(this)
        setContentView(R.layout.activity_route_preview)

        mapView = findViewById(R.id.previewMap)
        mapView.showZoomControls(false)
        MapStyleHelper.applyForTheme(mapView)
        applyInsets()
        bindGlassPanels()

        findViewById<ImageView>(R.id.btnPreviewClose).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.btnPreviewEdit).setOnClickListener {
            val route = currentRoute ?: return@setOnClickListener
            runCatching {
                startActivity(RouteEditorActivity.editIntent(this, route.id))
                finish()
            }.onFailure {
                com.zcshou.gogogo.run.ui.glass.GlassFeedback.toast(this, "无法打开编辑器")
            }
        }
        findViewById<ImageView>(R.id.btnPreviewTestRun).setOnClickListener {
            val route = currentRoute ?: return@setOnClickListener
            startTestRun(route)
        }

        val routeId = intent.getLongExtra(EXTRA_ROUTE_ID, -1L)
        if (routeId <= 0) {
            finish()
            return
        }
        lifecycleScope.launch {
            val route = runCatching { RunRepository(applicationContext).getRoute(routeId) }.getOrNull()
            if (isFinishing || isDestroyed) return@launch
            if (route == null) {
                com.zcshou.gogogo.run.ui.glass.GlassFeedback.toast(
                    this@RoutePreviewActivity,
                    "路线不存在或已被删除"
                )
                finish()
                return@launch
            }
            bind(route)
        }
    }

    override fun onResume() {
        super.onResume()
        runCatching { mapView.onResume() }
        // 前台恢复：重新开放地图面板的光学采样
        GlassRenderGate.setActive(true)
    }

    override fun onPause() {
        // 切后台 / 锁屏：立即停掉地图面板的捕获与着色器刷新（严格 0fps）
        GlassRenderGate.setActive(false)
        // 试跑演示同样必须停：省电/后台不允许任何动画继续跑
        stopTestRun()
        runCatching { mapView.onPause() }
        super.onPause()
    }

    override fun onDestroy() {
        stopTestRun()
        trail?.remove()
        trail = null
        runCatching { mapView.onDestroy() }
        MapBackdropCapture.release(mapView)
        super.onDestroy()
    }

    /**
     * 试跑一圈：4 秒内用一颗光纤光点沿路线演示跑法与顺/逆方向。
     * 纯地图叠加层动画，由协程驱动；退出、切后台、省电档立即释放。
     */
    private fun startTestRun(route: RunRouteEntity) {
        if (testRunJob?.isActive == true) {
            stopTestRun()
            return
        }
        val points = runCatching { TrackJsonCodec.decode(route.denseWaypointsJson) }
            .getOrDefault(emptyList())
        if (points.size < 3) return
        if (DeepRunGlassManager.tier(this) == DeepRunGlassManager.Tier.BALANCED_MATTE) {
            com.zcshou.gogogo.run.ui.glass.GlassFeedback.toast(this, "省电平衡档下不播放试跑动画")
            return
        }
        val cumulative = TrackMathUtils.cumulativeDistances(points)
        val total = cumulative.last()
        if (total <= 0.0) return
        val map = mapView.map
        val icon = FiberPositionDot.icon(this)
        testRunMarker?.remove()
        testRunMarker = runCatching {
            map.addOverlay(
                MarkerOptions()
                    .position(toBd(points.first()))
                    .icon(icon)
                    .anchor(0.5f, 0.5f)
                    .zIndex(40)
                    .title("试跑")
            ) as? com.baidu.mapapi.map.Marker
        }.getOrNull()
        val marker = testRunMarker ?: return

        testRunJob = lifecycleScope.launch {
            val startAt = SystemClock.elapsedRealtime()
            while (isActive && !isFinishing && !isDestroyed) {
                val elapsed = SystemClock.elapsedRealtime() - startAt
                val fraction = (elapsed / TEST_RUN_DURATION_MS.toFloat()).coerceIn(0f, 1f)
                val position = TrackMathUtils.interpolate(points, cumulative, total * fraction)
                runCatching { marker.position = toBd(position) }
                if (fraction >= 1f) break
                delay(TEST_RUN_FRAME_MS)
            }
            runCatching { marker.remove() }
            if (testRunMarker === marker) testRunMarker = null
        }
    }

    private fun stopTestRun() {
        testRunJob?.cancel()
        testRunJob = null
        testRunMarker?.let { runCatching { it.remove() } }
        testRunMarker = null
    }

    private fun toBd(point: TrackMathUtils.LatLng): LatLng {
        val bd = CoordTransformUtil.wgs84ToBd09(point.lat, point.lng)
        return LatLng(bd.lat, bd.lng)
    }

    /**
     * 顶部信息条 / 底部数据条升级为**地图光学玻璃**：
     * 与建圈器、自由漫游共用同一套捕获（同帧只截一次地图）与刷新闸门。
     */
    private fun bindGlassPanels() {
        listOf(R.id.previewTopBar, R.id.previewStats).forEach { id ->
            (findViewById<View>(id) as? LiquidGlassView)?.apply {
                MapBackdropCapture.bind(this, mapView)
                GlassRenderGate.registerMapPanel(this)
                OpticalGlassTuning.applyMapPanel(this)
            }
        }
        // 预览页也能拖动/缩放地图：只重绘这两块面板，相机静止 180ms 后归零
        runCatching {
            mapView.map.setOnMapStatusChangeListener(object : BaiduMap.OnMapStatusChangeListener {
                override fun onMapStatusChangeStart(status: MapStatus) = Unit
                override fun onMapStatusChangeStart(status: MapStatus, reason: Int) = Unit
                override fun onMapStatusChange(status: MapStatus) {
                    GlassRenderGate.requestMapRefresh(260L)
                }
                override fun onMapStatusChangeFinish(status: MapStatus) {
                    GlassRenderGate.requestMapRefresh(160L)
                }
            })
        }
    }

    private fun bind(route: RunRouteEntity) {
        currentRoute = route
        val points = runCatching { TrackJsonCodec.decode(route.denseWaypointsJson) }
            .getOrDefault(emptyList())

        findViewById<TextView>(R.id.previewName).text = displayName(route)
        findViewById<TextView>(R.id.previewMeta).text = String.format(
            Locale.US,
            "%s · %s",
            if (route.isClockwise) "顺时针" else "逆时针",
            if (route.isClosedLoop) "闭环路线" else "未闭环"
        )
        findViewById<TextView>(R.id.statLap).text =
            if (route.singleLapMeters >= 1000) {
                String.format(Locale.US, "%.2f km", route.singleLapMeters / 1000.0)
            } else {
                String.format(Locale.US, "%.0f m", route.singleLapMeters)
            }
        findViewById<TextView>(R.id.statPoints).text = "${points.size} 点"
        findViewById<TextView>(R.id.statMode).text = buildModeLabel(route.buildMode)

        // 海拔剖面：真实 <ele> 优先，否则用曲率 + 微地形估算
        val profile = RouteSources.parseElevationProfileJson(route.elevationProfileJson)
        findViewById<RouteElevationProfileView>(R.id.previewElevation).bind(points, profile)

        if (points.size < 2) {
            com.zcshou.gogogo.run.ui.glass.GlassFeedback.toast(
                this,
                "该路线轨迹点不足，无法预览"
            )
            return
        }

        // WGS-84 → BD-09，与建圈器同一套坐标与线型
        val bdPoints = points.map {
            val bd = CoordTransformUtil.wgs84ToBd09(it.lat, it.lng)
            LatLng(bd.lat, bd.lng)
        }
        val map = mapView.map
        map.clear()
        trail = FiberTrail.create(map, bdPoints, density, ThemeHelper.isDark(this))
        runCatching {
            map.addOverlay(
                MarkerOptions()
                    .position(bdPoints.first())
                    .icon(FiberPositionDot.icon(this))
                    .anchor(0.5f, 0.5f)
                    .zIndex(30)
                    .title("起点")
            )
        }

        // 等地图 View 完成测量后再按边界缩放，留出上下玻璃信息条的安全区
        mapView.post {
            if (isFinishing || isDestroyed) return@post
            runCatching {
                val bounds = LatLngBounds.Builder().include(bdPoints).build()
                map.setMapStatus(
                    MapStatusUpdateFactory.newLatLngBounds(
                        bounds,
                        dp(44), dp(190), dp(44), dp(170)
                    )
                )
            }
        }
    }

    private fun applyInsets() {
        val root = findViewById<android.view.View>(R.id.previewRoot)
        val topBar = findViewById<android.view.View>(R.id.previewTopBar)
        val stats = findViewById<android.view.View>(R.id.previewStats)
        val scrim = findViewById<android.view.View>(R.id.previewTopScrim)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (topBar.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.topMargin = dp(10) + bars.top
                topBar.layoutParams = it
            }
            (stats.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.bottomMargin = dp(24) + bars.bottom
                stats.layoutParams = it
            }
            scrim.layoutParams?.let {
                it.height = bars.top + dp(12)
                scrim.layoutParams = it
            }
            // Logo / 比例尺放在底部数据条上方
            mapView.setLogoPosition(LogoPosition.logoPostionleftBottom)
            mapView.setPadding(0, 0, 0, dp(118) + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun displayName(route: RunRouteEntity): String =
        if (route.buildMode == "DEMO") {
            route.name.replace("（示范）", " · 示范").replace("(示范)", " · 示范")
        } else {
            route.name
        }

    private fun buildModeLabel(mode: String): String = when (mode) {
        "ROAD_SNAP" -> "路网贴合"
        "GPX_IMPORT" -> "GPX 导入"
        "ROAM_RECORD" -> "漫游录制"
        "PRESET_TRACK" -> "操场套模"
        "FREE_DRAW" -> "自由手绘"
        "DEMO" -> "示范跑道"
        "SHARE_CODE" -> "口令导入"
        else -> "逐点连线"
    }
}
