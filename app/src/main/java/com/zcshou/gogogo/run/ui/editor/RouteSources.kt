package com.zcshou.gogogo.run.ui.editor

import com.zcshou.gogogo.run.engine.TrackMathUtils
import com.zcshou.gogogo.run.engine.TrackMathUtils.LatLng
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * 无地图建圈的路线来源：GPX 解析 / 重采样 / 闭环判定 / 关键点文本解析。
 * 纯 Kotlin 无 Android 依赖，可直接 JVM 单测。全部 WGS-84。
 */
object RouteSources {

    /**
     * GPX 解析结果。
     *
     * [elevations] 与 [points] 等长，元素为 `null` 表示该点没有 `<ele>`
     * （老 GPX 只有经纬度），调用方据此决定是否使用真实海拔剖面。
     */
    data class GpxResult(
        val trackName: String?,
        val points: List<LatLng>,
        val elevations: List<Double?> = emptyList(),
        val waypoints: List<LatLng> = emptyList()
    )

    private val POINT_TAG = Regex("""<(?:trkpt|rtept)\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val LAT_ATTR = Regex("""\blat\s*=\s*["']([-\d.]+)["']""")
    private val LON_ATTR = Regex("""\blon\s*=\s*["']([-\d.]+)["']""")
    private val NAME_TAG = Regex("""<name>\s*([^<]{1,128}?)\s*</name>""", RegexOption.IGNORE_CASE)
    private val ELE_TAG = Regex("""<ele>\s*([-\d.]+)\s*</ele>""", RegexOption.IGNORE_CASE)
    private val WPT_BLOCK = Regex(
        """<wpt\b([^>]*)>(.*?)</wpt>|<wpt\b([^>]*)/>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /**
     * 解析 GPX：轨迹点 + 每点 `<ele>` + `<wpt>` 停靠点。
     *
     * 轨迹点用「标签起点 + 元素体」成对扫描，才能拿到该点内部的 `<ele>`；
     * 自闭合 `<trkpt ... />` 没有元素体，海拔记 null。
     */
    fun parseGpx(xml: String): GpxResult {
        val points = ArrayList<LatLng>()
        val elevations = ArrayList<Double?>()
        // 逐个 <trkpt>/<rtept> 起点扫描，取到下一个标签起点之前的文本作为该点元素体
        val starts = POINT_TAG.findAll(xml).toList()
        starts.forEachIndexed { index, match ->
            val attrs = match.groupValues[1]
            val lat = LAT_ATTR.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull()
            val lon = LON_ATTR.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull()
            if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                return@forEachIndexed
            }
            points.add(LatLng(lat, lon))
            // 元素体 = 当前标签结束 → 下一个轨迹点标签开始（或文末）
            val bodyStart = match.range.last + 1
            val bodyEnd = starts.getOrNull(index + 1)?.range?.first ?: xml.length
            val body = if (bodyEnd > bodyStart) xml.substring(bodyStart, bodyEnd) else ""
            elevations.add(ELE_TAG.find(body)?.groupValues?.get(1)?.toDoubleOrNull())
        }
        val name = NAME_TAG.find(xml)?.groupValues?.get(1)?.trim()
        return GpxResult(
            trackName = name,
            points = points,
            elevations = elevations,
            waypoints = parseWaypoints(xml)
        )
    }

    /** `<wpt lat="" lon="">` 停靠点：只取坐标，用于导入后提示沿途打卡点数量 */
    private fun parseWaypoints(xml: String): List<LatLng> =
        WPT_BLOCK.findAll(xml).mapNotNull { match ->
            val attrs = match.groupValues[1].ifBlank { match.groupValues[3] }
            val lat = LAT_ATTR.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull()
            val lon = LON_ATTR.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull()
            if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                LatLng(lat, lon)
            } else {
                null
            }
        }.toList()

    /**
     * 把逐点海拔压成存储用的剖面：优先真实 `<ele>`；缺失点用前向填充
     * （缺失点沿用最近一个已知值，开头缺失段用第一个有效值回填），
     * 整段都没有 `<ele>` 时返回 null（预览页回退到曲率/微地形估算）。
     */
    fun elevationProfile(elevations: List<Double?>): List<Double>? {
        if (elevations.isEmpty()) return null
        val valid = elevations.count { it != null }
        if (valid == 0) return null
        if (valid == elevations.size) return elevations.map { it!! }
        val out = ArrayList<Double>(elevations.size)
        var lastKnown: Double? = null
        // 前向填充
        for (value in elevations) {
            if (value != null) lastKnown = value
            out.add(lastKnown ?: 0.0)
        }
        // 首段没有有效值：用第一个已知值回填
        val firstKnown = elevations.firstOrNull { it != null } ?: return null
        for (i in out.indices) {
            if (elevations[i] == null && out[i] == 0.0) out[i] = firstKnown
        }
        return out
    }

    /** 剖面 JSON（`[50.2, 51.0, ...]`，保留 1 位小数以控制体积） */
    fun elevationProfileJson(profile: List<Double>?): String? {
        if (profile.isNullOrEmpty()) return null
        val sb = StringBuilder(profile.size * 7 + 2)
        sb.append('[')
        profile.forEachIndexed { index, value ->
            if (index > 0) sb.append(',')
            sb.append(String.format(java.util.Locale.US, "%.1f", value))
        }
        sb.append(']')
        return sb.toString()
    }

    /** 剖面 JSON 解析（损坏/空数据返回 null） */
    fun parseElevationProfileJson(json: String?): List<Double>? {
        if (json.isNullOrBlank()) return null
        val body = json.trim().removePrefix("[").removeSuffix("]")
        if (body.isBlank()) return null
        val values = body.split(',')
            .mapNotNull { it.trim().toDoubleOrNull() }
        return values.takeIf { it.size >= 2 }
    }

    /** 单条路线重采样输出点数上限：2m 步长下约 400km，正常路线不会触达 */
    private const val MAX_RESAMPLED_POINTS = 200_000

    /**
     * 按约 spacingMeters 重采样折线（含首尾）。
     *
     * 输入有 10MB 文件上限但**总里程无界**：异常 GPX（如对跖点反复跳变）
     * 会把总里程撑到天文数字，按固定步长展开直接打爆内存。因此当估算
     * 输出超过 [MAX_RESAMPLED_POINTS] 时自适应加大步长，把点数压回上限内 ——
     * 路线照常导入，只是采样更粗，调用方无需特殊处理。
     */
    fun resample(points: List<LatLng>, spacingMeters: Double = 2.0): List<LatLng> {
        if (points.size < 2) return points
        val cumulative = TrackMathUtils.cumulativeDistances(points)
        val total = cumulative.last()
        if (total <= 0.0) return points.take(1)
        val step = maxOf(spacingMeters, total / MAX_RESAMPLED_POINTS)
        val out = ArrayList<LatLng>((total / step).toInt() + 2)
        var d = 0.0
        while (d < total) {
            out.add(TrackMathUtils.interpolate(points, cumulative, d))
            d += step
        }
        out.add(points.last())
        return out
    }

    /** 首尾距不超过 maxGapMeters 视为闭环并补闭合点 */
    fun closeLoopIfNeeded(points: List<LatLng>, maxGapMeters: Double = 40.0): Pair<List<LatLng>, Boolean> {
        if (points.size < 3) return points to false
        val gap = TrackMathUtils.distanceMeters(points.first(), points.last())
        return when {
            gap <= 0.5 -> points to true
            gap <= maxGapMeters -> (points + points.first()) to true
            else -> points to false
        }
    }

    /** Douglas-Peucker 抽稀（米制容差），用于手绘/漫游录制轨迹平滑 */
    fun simplifyRdp(points: List<LatLng>, epsilonMeters: Double = 1.5): List<LatLng> {
        if (points.size < 3) return points
        val lat0 = points.first().lat
        val lng0 = points.first().lng
        val mx = 111320.0 * cos(Math.toRadians(lat0))
        val my = 110574.0
        val local = points.map {
            (it.lng - lng0) * mx to (it.lat - lat0) * my
        }
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            if (b <= a + 1) continue
            val (ax, ay) = local[a]
            val (bx, by) = local[b]
            val segLen = hypot(bx - ax, by - ay)
            var maxDist = 0.0
            var index = -1
            for (i in a + 1 until b) {
                val (px, py) = local[i]
                val d = if (segLen < 1e-9) {
                    hypot(px - ax, py - ay)
                } else {
                    abs((bx - ax) * (ay - py) - (ax - px) * (by - ay)) / segLen
                }
                if (d > maxDist) {
                    maxDist = d
                    index = i
                }
            }
            if (maxDist > epsilonMeters && index > 0) {
                keep[index] = true
                stack.addLast(a to index)
                stack.addLast(index to b)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** 经纬度多边形有向面积判断：true = 顺时针 */
    fun isClockwise(points: List<LatLng>): Boolean {
        if (points.size < 3) return true
        val lat0 = points.first().lat
        val mx = 111320.0 * cos(Math.toRadians(lat0))
        var area = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            val ax = a.lng * mx
            val ay = a.lat * 110574.0
            val bx = b.lng * mx
            val by = b.lat * 110574.0
            area += ax * by - bx * ay
        }
        return area < 0
    }

    /**
     * 关键点文本：lat,lng 数值流（逗号/空格/分号分隔均可，支持多行与 # 注释）。
     * 按出现顺序两两成对解析，越界或非数值的对直接跳过。
     */
    fun parsePointsText(text: String): List<LatLng> {
        val tokens = text.lineSequence()
            .map { it.substringBefore('#') }
            .flatMap { it.split(Regex("[,\\s;]+")).asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val points = ArrayList<LatLng>(tokens.size / 2)
        var index = 0
        while (index + 1 < tokens.size) {
            val lat = tokens[index].toDoubleOrNull()
            val lon = tokens[index + 1].toDoubleOrNull()
            if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                points.add(LatLng(lat, lon))
            }
            index += 2
        }
        return points
    }

    /**
     * 导出标准 GPX 1.1（WGS-84）备份文本。
     *
     * @param elevations 可选海拔序列，与 [points] 等长时写入 `<ele>`；
     *                   长度不符或为 null 时退化为纯坐标（向后兼容老行为）
     */
    fun toGpx(
        name: String,
        points: List<LatLng>,
        elevations: List<Double?>? = null
    ): String {
        val escaped = name
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
        val useElevation = elevations != null && elevations.size == points.size
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<gpx version=\"1.1\" creator=\"${com.zcshou.gogogo.run.BrandConfig.SHARE_SCHEME}\" ")
            append("xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            append("  <trk><name>").append(escaped).append("</name><trkseg>\n")
            points.forEachIndexed { index, point ->
                val elevation = if (useElevation) elevations!![index] else null
                if (elevation == null) {
                    append(
                        String.format(
                            java.util.Locale.US,
                            "    <trkpt lat=\"%.7f\" lon=\"%.7f\"/>\n",
                            point.lat, point.lng
                        )
                    )
                } else {
                    append(
                        String.format(
                            java.util.Locale.US,
                            "    <trkpt lat=\"%.7f\" lon=\"%.7f\"><ele>%.1f</ele></trkpt>\n",
                            point.lat, point.lng, elevation
                        )
                    )
                }
            }
            append("  </trkseg></trk>\n</gpx>\n")
        }
    }
}