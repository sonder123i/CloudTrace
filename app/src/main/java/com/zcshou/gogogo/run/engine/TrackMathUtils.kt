package com.zcshou.gogogo.run.engine

import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS-84 球面几何工具：距离、航向、法线偏移、闭合向心 Catmull-Rom 样条。
 * 纯 Kotlin 无 Android 依赖，可直接 JVM 单测。
 */
object TrackMathUtils {

    private const val EARTH_RADIUS_M = 6371000.0
    private const val METERS_PER_DEG_LAT = 110574.0
    private const val METERS_PER_DEG_LNG_EQUATOR = 111320.0
    /** 首尾距小于该值视为「带显式闭合点」 */
    private const val EXPLICIT_CLOSE_EPSILON_M = 0.5

    data class LatLng(val lat: Double, val lng: Double)

    fun distanceMeters(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = (sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLng / 2).pow(2)).coerceIn(0.0, 1.0)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1.0 - h))
    }

    /** 航向角：正北 0°，顺时针 0~360 */
    fun bearingDegrees(a: LatLng, b: LatLng): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun destinationPoint(from: LatLng, bearingDeg: Double, distanceM: Double): LatLng {
        val brg = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(from.lat)
        val lng1 = Math.toRadians(from.lng)
        val ang = distanceM / EARTH_RADIUS_M
        val lat2 = kotlin.math.asin(sin(lat1) * cos(ang) + cos(lat1) * sin(ang) * cos(brg))
        val lng2 = lng1 + atan2(
            sin(brg) * sin(ang) * cos(lat1),
            cos(ang) - sin(lat1) * sin(lat2)
        )
        return LatLng(Math.toDegrees(lat2), normalizeLng(Math.toDegrees(lng2)))
    }

    /**
     * 沿切线方向的垂直法线平移：lateralMeters 正为右侧，负为左侧。
     */
    fun offsetPoint(point: LatLng, tangentBearingDeg: Double, lateralMeters: Double): LatLng =
        destinationPoint(point, (tangentBearingDeg + 90.0) % 360.0, lateralMeters)

    /**
     * 闭合向心 Catmull-Rom 样条（alpha = 0.5），输入 4~8 个锚点，
     * 输出每隔约 spacingMeters 一个点的平滑闭环 WGS-84 轨迹。
     */
    fun catmullRomClosed(anchors: List<LatLng>, spacingMeters: Double = 2.0): List<LatLng> {
        if (anchors.size < 3) return anchors.toList()
        val lat0 = anchors.map { it.lat }.average()
        val lng0 = anchors.map { it.lng }.average()
        val local = anchors.map { toLocal(it, lat0, lng0) }
        val n = local.size
        val out = ArrayList<LatLng>()
        for (i in 0 until n) {
            val p0 = local[(i - 1 + n) % n]
            val p1 = local[i]
            val p2 = local[(i + 1) % n]
            val p3 = local[(i + 2) % n]
            val segLen = hypot(p2.first - p1.first, p2.second - p1.second)
            if (segLen < 1e-6) continue
            val steps = max(2, ceil(segLen / spacingMeters).toInt())
            val t0 = 0.0
            val t1 = t0 + max(hypot(p1.first - p0.first, p1.second - p0.second), 1e-6).pow(0.5)
            val t2 = t1 + max(segLen, 1e-6).pow(0.5)
            val t3 = t2 + max(hypot(p3.first - p2.first, p3.second - p2.second), 1e-6).pow(0.5)
            for (s in 0 until steps) {
                val t = t1 + (t2 - t1) * s / steps
                val pt = barryGoldman(p0, p1, p2, p3, t0, t1, t2, t3, t)
                out.add(fromLocal(pt.first, pt.second, lat0, lng0))
            }
        }
        return out
    }

    /** 开放向心 Catmull-Rom 样条（首尾不闭合，端点用重复点夹紧） */
    fun catmullRomOpen(anchors: List<LatLng>, spacingMeters: Double = 2.0): List<LatLng> {
        if (anchors.size < 2) return anchors.toList()
        if (anchors.size == 2) {
            val a = anchors[0]
            val b = anchors[1]
            val total = distanceMeters(a, b)
            val steps = max(2, ceil(total / spacingMeters).toInt())
            return (0..steps).map { i ->
                val f = i.toDouble() / steps
                LatLng(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f)
            }
        }
        val lat0 = anchors.map { it.lat }.average()
        val lng0 = anchors.map { it.lng }.average()
        val local = anchors.map { toLocal(it, lat0, lng0) }
        val n = local.size
        val out = ArrayList<LatLng>()
        for (i in 0 until n - 1) {
            val p0 = local[(i - 1).coerceAtLeast(0)]
            val p1 = local[i]
            val p2 = local[i + 1]
            val p3 = local[(i + 2).coerceAtMost(n - 1)]
            val segLen = hypot(p2.first - p1.first, p2.second - p1.second)
            if (segLen < 1e-6) continue
            val steps = max(2, ceil(segLen / spacingMeters).toInt())
            val t0 = 0.0
            val t1 = t0 + max(hypot(p1.first - p0.first, p1.second - p0.second), 1e-6).pow(0.5)
            val t2 = t1 + max(segLen, 1e-6).pow(0.5)
            val t3 = t2 + max(hypot(p3.first - p2.first, p3.second - p2.second), 1e-6).pow(0.5)
            for (s in 0 until steps) {
                val t = t1 + (t2 - t1) * s / steps
                val pt = barryGoldman(p0, p1, p2, p3, t0, t1, t2, t3, t)
                out.add(fromLocal(pt.first, pt.second, lat0, lng0))
            }
        }
        out.add(anchors.last())
        return out
    }

    /** 折线累计里程（含起点 0） */
    fun cumulativeDistances(points: List<LatLng>): DoubleArray {        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cum[i] = cum[i - 1] + distanceMeters(points[i - 1], points[i])
        }
        return cum
    }

    /** 按沿线里程插值取点（dist 超界时按模取整圈，适配闭环） */
    fun interpolate(points: List<LatLng>, cumulative: DoubleArray, dist: Double): LatLng {
        require(points.isNotEmpty()) { "empty polyline" }
        if (points.size == 1) return points[0]
        val total = cumulative.last()
        val d = if (total > 0) ((dist % total) + total) % total else 0.0
        var lo = 0
        var hi = cumulative.size - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) ushr 1
            if (cumulative[mid] <= d) lo = mid else hi = mid
        }
        val segLen = cumulative[lo + 1] - cumulative[lo]
        if (segLen <= 1e-9) return points[lo]
        val f = (d - cumulative[lo]) / segLen
        val a = points[lo]
        val b = points[lo + 1]
        return LatLng(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f)
    }

    /** 里程处的切线航向 */
    fun bearingAt(points: List<LatLng>, cumulative: DoubleArray, dist: Double): Double {
        if (points.size < 2) return 0.0
        val total = cumulative.last()
        val d = if (total > 0) ((dist % total) + total) % total else 0.0
        var lo = 0
        var hi = cumulative.size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cumulative[mid] <= d) lo = mid else hi = mid - 1
        }
        return bearingDegrees(points[lo], points[lo + 1])
    }

    /** 两航向角之差（0~180） */
    fun headingDelta(a: Double, b: Double): Double {
        val d = Math.abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    /** 指定里程处的前后切线夹角（度），用于曲率近似 */
    fun curvatureAngleAt(
        points: List<LatLng>,
        cumulative: DoubleArray,
        dist: Double,
        lookaroundMeters: Double = 8.0
    ): Double {
        if (points.size < 3) return 0.0
        val before = bearingAt(points, cumulative, dist - lookaroundMeters)
        val after = bearingAt(points, cumulative, dist + lookaroundMeters)
        return headingDelta(before, after)
    }

    /** 曲率自适应横向偏移：急弯按比例衰减，最窄收到 minLateralMeters，防内侧自交打结 */
    fun curvatureAdaptiveLateral(
        desiredMeters: Double,
        curvatureAngleDeg: Double,
        fullLateralUntilDeg: Double = 15.0,
        collapseFromDeg: Double = 30.0,
        minLateralMeters: Double = 0.8
    ): Double {
        if (curvatureAngleDeg <= fullLateralUntilDeg) return desiredMeters
        val sign = if (desiredMeters < 0) -1.0 else 1.0
        if (curvatureAngleDeg >= collapseFromDeg) return sign * minLateralMeters
        val t = (curvatureAngleDeg - fullLateralUntilDeg) / (collapseFromDeg - fullLateralUntilDeg)
        return sign * (kotlin.math.abs(desiredMeters) * (1 - t) + minLateralMeters * t)
    }

    /** 三点滑动平均平滑折线（首尾保持不动） */
    fun smooth3(points: List<LatLng>): List<LatLng> {
        if (points.size < 3) return points
        val out = ArrayList<LatLng>(points.size)
        out.add(points.first())
        for (i in 1 until points.size - 1) {
            val a = points[i - 1]
            val b = points[i]
            val c = points[i + 1]
            out.add(LatLng((a.lat + b.lat + c.lat) / 3.0, (a.lng + b.lng + c.lng) / 3.0))
        }
        out.add(points.last())
        return out
    }

    /**
     * 空间位置绑定的微地形海拔：按路径归一化进度生成固定正弦起伏，
     * 同一物理位置每次经过高度一致，仅叠加微小噪点。
     */
    fun microTerrainAltitude(
        baseAltitudeM: Double,
        progressFraction: Double,
        amplitudeM: Double = 1.2,
        noiseM: Double = 0.15,
        random: kotlin.random.Random = kotlin.random.Random.Default
    ): Double {
        val terrain = amplitudeM * sin(2 * Math.PI * progressFraction)
        val noise = (random.nextDouble() * 2 - 1) * noiseM
        return baseAltitudeM + terrain + noise
    }

    /**
     * 轨迹是否构成闭环。
     *
     * 判定用「首尾间距相对平均点距」而不是绝对米数：`catmullRomClosed` 的输出
     * 末点落在首点前一个采样步长（约 1 个 spacing），并没有显式重复点；
     * 用固定 0.5m 会把这类标准闭环误判成开放路线。
     */
    fun isClosedLoop(points: List<LatLng>): Boolean {
        if (points.size < 3) return false
        val gap = distanceMeters(points.first(), points.last())
        if (gap <= EXPLICIT_CLOSE_EPSILON_M) return true
        val total = cumulativeDistances(points).last()
        if (total <= 0.0) return false
        val averageSpacing = total / (points.size - 1)
        // 间距不超过 1.5 个采样步长：既覆盖「无显式闭合点」的环形轨迹，
        // 又不会把首尾恰好靠近的开放折线误当成闭环
        return gap <= averageSpacing * 1.5
    }

    /** 是否带显式闭合点（末点与首点几乎重合） */
    fun hasExplicitClosePoint(points: List<LatLng>): Boolean =
        points.size >= 2 && distanceMeters(points.first(), points.last()) <= EXPLICIT_CLOSE_EPSILON_M

    /**
     * 闭环轨迹重设起点：把 [newStartIndex] 转成新的第 0 点，其余点按原顺序环形接续，
     * 并以「新首点的副本」收尾（始终产出显式闭合点）。
     *
     * 为什么必须显式补闭合点：环形重排会丢掉原来的「末点→首点」接缝段。
     * 只做旋转的话，`cumulativeDistances().last()` 会比真实周长少一段
     * （`catmullRomClosed` 输出正好少约一个采样步长），引擎的 lapLength
     * 随之偏小、圈数统计偏多。补上闭合点后，输出总长等于环形周长。
     *
     * 非闭环轨迹原样返回：对开放折线做环形旋转会凭空造出一条「末点→首点」跳跃段，
     * 调用方应改用 [reversePath]。
     */
    fun rotateLoopStart(points: List<LatLng>, newStartIndex: Int): List<LatLng> {
        if (points.size < 2) return points
        if (!isClosedLoop(points)) return points
        // 已有显式闭合点时先摘掉末点，避免旋转后出现两个相邻重复点
        val alreadyClosed = hasExplicitClosePoint(points)
        val body = if (alreadyClosed) points.subList(0, points.size - 1) else points
        if (body.size < 2) return points
        val pivot = newStartIndex.coerceIn(0, body.size - 1)
        // 起点不变且本来就有闭合点：无需改动
        if (pivot == 0 && alreadyClosed) return points
        val rotated = ArrayList<LatLng>(body.size + 1)
        for (i in 0 until body.size) {
            rotated.add(body[(pivot + i) % body.size])
        }
        rotated.add(rotated.first())
        return rotated
    }

    /**
     * 反转行进方向：纯倒序。
     *
     * 闭环倒序后首尾关系不变；开放折返路线倒序后从原终点出发，
     * 由引擎的 ping-pong 逻辑负责折返，不需要在这里补点。
     */
    fun reversePath(points: List<LatLng>): List<LatLng> =
        if (points.size < 2) points else points.reversed()

    private fun normalizeLng(lng: Double): Double = when {
        lng > 180.0 -> lng - 360.0
        lng < -180.0 -> lng + 360.0
        else -> lng
    }

    private fun toLocal(p: LatLng, lat0: Double, lng0: Double): Pair<Double, Double> {
        val east = (p.lng - lng0) * METERS_PER_DEG_LNG_EQUATOR * cos(Math.toRadians(lat0))
        val north = (p.lat - lat0) * METERS_PER_DEG_LAT
        return east to north
    }

    private fun fromLocal(east: Double, north: Double, lat0: Double, lng0: Double): LatLng {
        val lat = lat0 + north / METERS_PER_DEG_LAT
        val lng = lng0 + east / (METERS_PER_DEG_LNG_EQUATOR * cos(Math.toRadians(lat0)))
        return LatLng(lat, lng)
    }

    private fun barryGoldman(
        p0: Pair<Double, Double>, p1: Pair<Double, Double>,
        p2: Pair<Double, Double>, p3: Pair<Double, Double>,
        t0: Double, t1: Double, t2: Double, t3: Double, t: Double
    ): Pair<Double, Double> {
        val w10 = (t1 - t) / (t1 - t0); val w01 = (t - t0) / (t1 - t0)
        val a1x = p0.first * w10 + p1.first * w01
        val a1y = p0.second * w10 + p1.second * w01

        val w21 = (t2 - t) / (t2 - t1); val w12 = (t - t1) / (t2 - t1)
        val a2x = p1.first * w21 + p2.first * w12
        val a2y = p1.second * w21 + p2.second * w12

        val w32 = (t3 - t) / (t3 - t2); val w23 = (t - t2) / (t3 - t2)
        val a3x = p2.first * w32 + p3.first * w23
        val a3y = p2.second * w32 + p3.second * w23

        val w20 = (t2 - t) / (t2 - t0); val w02 = (t - t0) / (t2 - t0)
        val b1x = a1x * w20 + a2x * w02
        val b1y = a1y * w20 + a2y * w02

        val w31 = (t3 - t) / (t3 - t1); val w13 = (t - t1) / (t3 - t1)
        val b2x = a2x * w31 + a3x * w13
        val b2y = a2y * w31 + a3y * w13

        return (b1x * w21 + b2x * w12) to (b1y * w21 + b2y * w12)
    }
}