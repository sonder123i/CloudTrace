package com.zcshou.gogogo.run.engine

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * 参数化标准跑道模板（局部米制坐标，中心在原点），支持平移/旋转/缩放后映射为 WGS-84。
 */
object TrackTemplates {

    data class Template(val id: String, val label: String, val localPoints: List<Pair<Double, Double>>)

    val ids = listOf("400", "300", "200", "rect500", "eight")

    fun labelOf(id: String): String = when (id) {
        "400" -> "400m 田径场"
        "300" -> "300m 操场"
        "200" -> "200m 迷你场"
        "rect500" -> "圆角矩形 150×80"
        else -> "8 字跑道"
    }

    fun template(id: String): Template = Template(id, labelOf(id), localPointsOf(id))

    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<Pair<Double, Double>>>()

    private fun localPointsOf(id: String): List<Pair<Double, Double>> = cache.getOrPut(id) {
        val raw = when (id) {
            "400" -> oval(84.39, 36.5)
            "300" -> oval(68.0, 26.1)
            "200" -> oval(42.0, 18.5)
            "rect500" -> roundedRect(150.0, 80.0, 15.0)
            else -> figureEight(100.0)
        }
        raw
    }

    /** 变换到 WGS-84：x 东向、y 北向；scale∈[0.5,2.5]，rotation 顺时针角度 */
    fun transform(
        local: List<Pair<Double, Double>>,
        lat0: Double,
        lng0: Double,
        scale: Double,
        rotationDeg: Double
    ): List<TrackMathUtils.LatLng> {
        val rad = Math.toRadians(rotationDeg)
        val cosR = cos(rad)
        val sinR = sin(rad)
        val metersPerLng = 111320.0 * cos(Math.toRadians(lat0))
        return local.map { (x0, y0) ->
            val x = x0 * scale
            val y = y0 * scale
            val xr = x * cosR + y * sinR
            val yr = -x * sinR + y * cosR
            TrackMathUtils.LatLng(
                lat0 + yr / 110574.0,
                lng0 + xr / metersPerLng
            )
        }
    }

    private fun oval(straight: Double, radius: Double): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        val half = straight / 2.0
        val topLen = straight
        line(out, -half, radius, half, radius, topLen)
        arc(out, half, 0.0, radius, 90.0, -90.0, Math.PI * radius)
        line(out, half, -radius, -half, -radius, topLen)
        arc(out, -half, 0.0, radius, 270.0, 90.0, Math.PI * radius)
        return out
    }

    private fun roundedRect(w: Double, h: Double, r: Double): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        val hw = w / 2.0
        val hh = h / 2.0
        line(out, -(hw - r), -hh, hw - r, -hh, w - 2 * r)
        arc(out, hw - r, -hh + r, r, -90.0, 0.0, Math.PI * r / 2)
        line(out, hw, -(hh - r), hw, hh - r, h - 2 * r)
        arc(out, hw - r, hh - r, r, 0.0, 90.0, Math.PI * r / 2)
        line(out, hw - r, hh, -(hw - r), hh, w - 2 * r)
        arc(out, -(hw - r), hh - r, r, 90.0, 180.0, Math.PI * r / 2)
        line(out, -hw, hh - r, -hw, -(hh - r), h - 2 * r)
        arc(out, -(hw - r), -(hh - r), r, 180.0, 270.0, Math.PI * r / 2)
        return out
    }

    private fun figureEight(a: Double): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        val steps = 480
        for (i in 0..steps) {
            val t = 2.0 * Math.PI * i / steps
            val denom = 1.0 + sin(t) * sin(t)
            out.add((a * cos(t) / denom) to (a * sin(t) * cos(t) / denom))
        }
        return out
    }

    private fun line(
        out: MutableList<Pair<Double, Double>>,
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        length: Double
    ) {
        val steps = ceil(length / 1.5).toInt().coerceAtLeast(2)
        for (i in 0 until steps) {
            val f = i.toDouble() / steps
            out.add((x1 + (x2 - x1) * f) to (y1 + (y2 - y1) * f))
        }
    }

    private fun arc(
        out: MutableList<Pair<Double, Double>>,
        cx: Double,
        cy: Double,
        r: Double,
        startDeg: Double,
        endDeg: Double,
        arcLen: Double
    ) {
        val steps = ceil(arcLen / 1.5).toInt().coerceAtLeast(2)
        for (i in 0 until steps) {
            val f = i.toDouble() / steps
            val deg = startDeg + (endDeg - startDeg) * f
            val rad = Math.toRadians(deg)
            out.add((cx + r * cos(rad)) to (cy + r * sin(rad)))
        }
    }
}
