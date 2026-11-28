package com.zcshou.gogogo.run.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSourcesTest {

    private val sampleGpx = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="test">
          <trk>
            <name>南操场测试圈</name>
            <trkseg>
              <trkpt lat="36.667662" lon="117.027707"><ele>55</ele></trkpt>
              <trkpt lat="36.667762" lon="117.027807"/>
              <trkpt lat="36.667862" lon="117.027907"/>
            </trkseg>
          </trk>
        </gpx>
    """.trimIndent()

    @Test
    fun parseGpx_readsNameAndPoints() {
        val result = RouteSources.parseGpx(sampleGpx)
        assertEquals(3, result.points.size)
        assertEquals("南操场测试圈", result.trackName)
        assertEquals(36.667662, result.points[0].lat, 1e-9)
        assertEquals(117.027907, result.points[2].lng, 1e-9)
    }

    @Test
    fun resample_hitsSpacing() {
        val start = com.zcshou.gogogo.run.engine.TrackMathUtils.LatLng(36.0, 117.0)
        val end = com.zcshou.gogogo.run.engine.TrackMathUtils.destinationPoint(start, 90.0, 20.0)
        val resampled = RouteSources.resample(listOf(start, end), 2.0)
        assertTrue("点数=${resampled.size}", resampled.size in 10..12)
        assertEquals(end.lat, resampled.last().lat, 1e-9)
        assertEquals(end.lng, resampled.last().lng, 1e-9)
    }

    @Test
    fun closeLoop_appendsStartWithinGap() {
        val start = com.zcshou.gogogo.run.engine.TrackMathUtils.LatLng(36.0, 117.0)
        val second = com.zcshou.gogogo.run.engine.TrackMathUtils.destinationPoint(start, 90.0, 30.0)
        val third = com.zcshou.gogogo.run.engine.TrackMathUtils.destinationPoint(start, 0.0, 10.0)
        val (points, closed) = RouteSources.closeLoopIfNeeded(listOf(start, second, third))
        assertTrue(closed)
        assertEquals(points.first(), points.last())
    }

    @Test
    fun parsePointsText_handlesSeparatorsAndComments() {
        val text = """
            # 操场关键点
            36.6676,117.0277
            36.6678 117.0280
            36.6680;117.0275
            bad line
            95.0,117.0
        """.trimIndent()
        val points = RouteSources.parsePointsText(text)
        assertEquals(3, points.size)
        assertEquals(36.6678, points[1].lat, 1e-9)
    }

    @Test
    fun parsePointsText_singleLineSemicolonList() {
        val text = "36.6676,117.0277;36.6678,117.0280;36.6679,117.0275;36.6675,117.0273"
        val points = RouteSources.parsePointsText(text)
        assertEquals(4, points.size)
        assertEquals(117.0273, points[3].lng, 1e-9)
    }

    @Test
    fun simplifyRdp_collapsesStraightLine() {
        val start = com.zcshou.gogogo.run.engine.TrackMathUtils.LatLng(36.0, 117.0)
        val line = (0..100).map { i ->
            com.zcshou.gogogo.run.engine.TrackMathUtils.destinationPoint(start, 90.0, i.toDouble())
        }
        val simplified = RouteSources.simplifyRdp(line, 1.5)
        assertEquals(2, simplified.size)
    }

    @Test
    fun simplifyRdp_keepsSignificantCorner() {
        val a = com.zcshou.gogogo.run.engine.TrackMathUtils.LatLng(36.0, 117.0)
        val b = com.zcshou.gogogo.run.engine.TrackMathUtils.destinationPoint(a, 90.0, 100.0)
        val c = com.zcshou.gogogo.run.engine.TrackMathUtils.destinationPoint(b, 0.0, 100.0)
        val simplified = RouteSources.simplifyRdp(listOf(a, b, c), 1.5)
        assertEquals(3, simplified.size)
    }

    @Test
    fun isClockwise_detectsDirection() {
        val cw = listOf(
            com.zcshou.gogogo.run.engine.TrackMathUtils.LatLng(36.0000, 117.0000),
            com.zcshou.gogogo.run.engine.TrackMathUtils.LatLng(36.0000, 117.0009),
            com.zcshou.gogogo.run.engine.TrackMathUtils.LatLng(35.9992, 117.0009),
            com.zcshou.gogogo.run.engine.TrackMathUtils.LatLng(35.9992, 117.0000)
        )
        assertTrue(RouteSources.isClockwise(cw))
        assertTrue(!RouteSources.isClockwise(cw.reversed()))
    }

    @Test
    fun toGpx_roundTripsPointsAndEscapesName() {
        val points = listOf(
            com.zcshou.gogogo.run.engine.TrackMathUtils.LatLng(36.1234567, 117.7654321),
            com.zcshou.gogogo.run.engine.TrackMathUtils.LatLng(36.1235000, 117.7655000)
        )
        val gpx = RouteSources.toGpx("东区 & <田径场>", points)
        assertTrue(gpx.contains("&amp;"))
        assertTrue(gpx.contains("&lt;田径场&gt;"))
        val parsed = RouteSources.parseGpx(gpx)
        assertEquals(2, parsed.points.size)
        assertEquals(points[0].lat, parsed.points[0].lat, 1e-7)
        assertEquals(points[0].lng, parsed.points[0].lng, 1e-7)
        assertEquals(points[1].lat, parsed.points[1].lat, 1e-7)
    }

    // ---------------- 海拔 / 路点 ----------------

    private val gpxWithEleAndWpt = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="test">
          <wpt lat="36.667500" lon="117.027500"><name>补给点</name></wpt>
          <wpt lat="36.667900" lon="117.027900"/>
          <trk>
            <name>带海拔的圈</name>
            <trkseg>
              <trkpt lat="36.667662" lon="117.027707"><ele>48.5</ele></trkpt>
              <trkpt lat="36.667762" lon="117.027807"><ele>49.2</ele></trkpt>
              <trkpt lat="36.667862" lon="117.027907"><ele>51.0</ele></trkpt>
            </trkseg>
          </trk>
        </gpx>
    """.trimIndent()

    @Test
    fun parseGpx_readsElevationForEveryPoint() {
        val result = RouteSources.parseGpx(gpxWithEleAndWpt)
        assertEquals(3, result.points.size)
        assertEquals(3, result.elevations.size)
        assertEquals(48.5, result.elevations[0]!!, 1e-9)
        assertEquals(49.2, result.elevations[1]!!, 1e-9)
        assertEquals(51.0, result.elevations[2]!!, 1e-9)
    }

    @Test
    fun parseGpx_readsWaypointsIncludingSelfClosing() {
        val result = RouteSources.parseGpx(gpxWithEleAndWpt)
        assertEquals(2, result.waypoints.size)
        assertEquals(36.667500, result.waypoints[0].lat, 1e-9)
        assertEquals(36.667900, result.waypoints[1].lat, 1e-9)
    }

    @Test
    fun parseGpx_partialElevationKeepsNullSlots() {
        // 样例只有首点带 <ele>：其余点必须保留 null，长度与点数严格对齐
        val result = RouteSources.parseGpx(sampleGpx)
        assertEquals(3, result.elevations.size)
        assertEquals(55.0, result.elevations[0]!!, 1e-9)
        assertTrue(result.elevations[1] == null)
        assertTrue(result.elevations[2] == null)
        assertTrue(result.waypoints.isEmpty())
    }

    @Test
    fun parseGpx_selfClosingTrackPointHasNoElevation() {
        val xml = """
            <gpx><trk><trkseg>
              <trkpt lat="36.0" lon="117.0"><ele>10</ele></trkpt>
              <trkpt lat="36.001" lon="117.001"/>
              <trkpt lat="36.002" lon="117.002"><ele>12</ele></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent()
        val result = RouteSources.parseGpx(xml)
        assertEquals(3, result.points.size)
        assertEquals(10.0, result.elevations[0]!!, 1e-9)
        assertTrue(result.elevations[1] == null)
        assertEquals(12.0, result.elevations[2]!!, 1e-9)
    }

    @Test
    fun parseGpx_legacyGpxWithoutEleStaysCompatible() {
        val xml = """
            <gpx><trk><trkseg>
              <trkpt lat="36.0" lon="117.0"/>
              <trkpt lat="36.001" lon="117.001"/>
            </trkseg></trk></gpx>
        """.trimIndent()
        val result = RouteSources.parseGpx(xml)
        assertEquals(2, result.points.size)
        assertEquals(2, result.elevations.size)
        assertTrue(result.elevations.all { it == null })
    }

    @Test
    fun elevationProfile_returnsNullWhenNoElevationAtAll() {
        assertTrue(RouteSources.elevationProfile(listOf(null, null, null)) == null)
        assertTrue(RouteSources.elevationProfile(emptyList()) == null)
    }

    @Test
    fun elevationProfile_keepsRealValuesAndFillsGaps() {
        val profile = RouteSources.elevationProfile(listOf(50.0, null, 52.0, null, 54.0))
        assertEquals(5, profile!!.size)
        assertEquals(50.0, profile[0], 1e-9)
        assertEquals(50.0, profile[1], 1e-9) // 前向填充
        assertEquals(52.0, profile[2], 1e-9)
        assertEquals(52.0, profile[3], 1e-9)
        assertEquals(54.0, profile[4], 1e-9)
    }

    @Test
    fun elevationProfile_backfillsLeadingGaps() {
        val profile = RouteSources.elevationProfile(listOf(null, null, 60.0))
        assertEquals(60.0, profile!![0], 1e-9)
        assertEquals(60.0, profile[1], 1e-9)
        assertEquals(60.0, profile[2], 1e-9)
    }

    @Test
    fun elevationProfileJson_roundTrips() {
        val profile = listOf(48.5, 49.25, 51.0)
        val json = RouteSources.elevationProfileJson(profile)
        // 保留 1 位小数，49.25 按 HALF_UP 进位到 49.3
        assertEquals("[48.5,49.3,51.0]", json)
        val parsed = RouteSources.parseElevationProfileJson(json)
        assertEquals(48.5, parsed!![0], 1e-9)
        assertEquals(49.3, parsed[1], 1e-9)
        assertEquals(51.0, parsed[2], 1e-9)
    }

    @Test
    fun parseElevationProfileJson_rejectsBrokenInput() {
        assertTrue(RouteSources.parseElevationProfileJson(null) == null)
        assertTrue(RouteSources.parseElevationProfileJson("") == null)
        assertTrue(RouteSources.parseElevationProfileJson("[]") == null)
        assertTrue(RouteSources.parseElevationProfileJson("[1]") == null)
        assertTrue(RouteSources.parseElevationProfileJson("not json") == null)
    }

    @Test
    fun toGpx_writesEleWhenProfileMatchesPoints() {
        val start = com.zcshou.gogogo.run.engine.TrackMathUtils.LatLng(36.0, 117.0)
        val end = com.zcshou.gogogo.run.engine.TrackMathUtils.LatLng(36.001, 117.0)
        val gpx = RouteSources.toGpx("测试", listOf(start, end), listOf(50.0, 51.5))
        assertTrue(gpx.contains("<ele>50.0</ele>"))
        assertTrue(gpx.contains("<ele>51.5</ele>"))
        // 回读一致
        val parsed = RouteSources.parseGpx(gpx)
        assertEquals(2, parsed.points.size)
        assertEquals(50.0, parsed.elevations[0]!!, 1e-9)
        assertEquals(51.5, parsed.elevations[1]!!, 1e-9)
    }

    @Test
    fun toGpx_withoutElevationsKeepsLegacyShape() {
        val start = com.zcshou.gogogo.run.engine.TrackMathUtils.LatLng(36.0, 117.0)
        val gpx = RouteSources.toGpx("测试", listOf(start, start))
        assertTrue(!gpx.contains("<ele>"))
        assertTrue(gpx.contains("lat=\"36.0000000\""))
    }

    @Test
    fun toGpx_ignoresMismatchedElevationLength() {
        val start = com.zcshou.gogogo.run.engine.TrackMathUtils.LatLng(36.0, 117.0)
        // 长度不匹配时不得写出错位的 <ele>
        val gpx = RouteSources.toGpx("测试", listOf(start, start), listOf(50.0))
        assertTrue(!gpx.contains("<ele>"))
    }
}