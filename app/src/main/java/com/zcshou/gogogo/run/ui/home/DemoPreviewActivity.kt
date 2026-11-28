package com.zcshou.gogogo.run.ui.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.baidu.mapapi.map.BitmapDescriptor
import com.baidu.mapapi.map.BitmapDescriptorFactory
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MapView
import com.baidu.mapapi.map.Marker
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.map.PolylineOptions
import com.baidu.mapapi.model.LatLng as BdLatLng
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.engine.CoordTransformUtil
import com.zcshou.gogogo.run.engine.TrackMathUtils
import com.zcshou.gogogo.run.engine.TrackTemplates
import com.zcshou.gogogo.run.ui.theme.MapStyleHelper
import com.zcshou.gogogo.run.ui.theme.ThemeHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 无需任何权限的 10 秒模拟跑圈预览：虚拟光点沿 400m 跑道跑一圈。
 */
class DemoPreviewActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var countdown: TextView
    private var marker: Marker? = null
    private var dotIcon: BitmapDescriptor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.apply(this)
        super.onCreate(savedInstanceState)
        ThemeHelper.enableEdgeToEdge(this)
        setContentView(R.layout.activity_demo_preview)
        ThemeHelper.padForSystemBars(findViewById(R.id.demoRoot))

        mapView = findViewById(R.id.demoMap)
        countdown = findViewById(R.id.demoCountdown)
        MapStyleHelper.applyForTheme(mapView)
        mapView.map.setOnMapLoadedCallback { MapStyleHelper.applyForTheme(mapView) }
        findViewById<View>(R.id.btnDemoClose).setOnClickListener { finish() }

        val map = mapView.map
        mapView.showZoomControls(false)
        map.uiSettings.setScrollGesturesEnabled(false)
        map.uiSettings.setZoomGesturesEnabled(false)

        val center = TrackMathUtils.LatLng(39.9042, 116.4074)
        val points = TrackTemplates.transform(
            TrackTemplates.template("400").localPoints,
            center.lat, center.lng, 1.0, 0.0
        )
        val cumulative = TrackMathUtils.cumulativeDistances(points)
        val total = cumulative.last()

        val bdPoints = points.map { toBd(it) }
        map.addOverlay(
            PolylineOptions()
                .points(bdPoints)
                .width((1.5f * resources.displayMetrics.density).toInt().coerceAtLeast(1))
                .color(getColor(R.color.pf_volt))
        )
        map.setMapStatus(
            MapStatusUpdateFactory.newLatLngZoom(toBd(center), 17.5f)
        )

        val icon = buildDotIcon()
        dotIcon = icon
        marker = map.addOverlay(
            MarkerOptions().position(bdPoints.first()).icon(icon).anchor(0.5f, 0.5f)
        ) as? Marker

        lifecycleScope.launch {
            var activeElapsed = 0L
            var lastSec = -1
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var lastTick = android.os.SystemClock.elapsedRealtime()
                while (activeElapsed < 10_000L) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    activeElapsed += (now - lastTick).coerceIn(0L, 250L)
                    lastTick = now
                    val t = (activeElapsed / 10_000.0).coerceIn(0.0, 1.0)
                    marker?.position = toBd(TrackMathUtils.interpolate(points, cumulative, total * t))
                    val sec = (10 - activeElapsed / 1000).coerceAtLeast(0).toInt()
                    if (sec != lastSec) {
                        lastSec = sec
                        countdown.text = if (sec > 0) {
                            "虚拟光点将沿 400m 跑道演示 10 秒 · $sec"
                        } else {
                            "演示结束 · 完成 3 项配置后即可真实开跑"
                        }
                    }
                    if (t >= 1.0) break
                    delay(100)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        runCatching { mapView.onResume() }
    }

    override fun onPause() {
        runCatching { mapView.onPause() }
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { mapView.map.setOnMapLoadedCallback(null) }
        runCatching { dotIcon?.recycle() }
        dotIcon = null
        runCatching { mapView.onDestroy() }
        super.onDestroy()
    }

    private fun toBd(p: TrackMathUtils.LatLng): BdLatLng {
        val b = CoordTransformUtil.wgs84ToBd09(p.lat, p.lng)
        return BdLatLng(b.lat, b.lng)
    }

    private fun buildDotIcon(): BitmapDescriptor {
        val size = (18 * resources.displayMetrics.density).toInt().coerceAtLeast(28)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = getColor(R.color.pf_volt)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = getColor(R.color.pf_canvas)
        canvas.drawCircle(size / 2f, size / 2f, size * 0.28f, paint)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }
}
