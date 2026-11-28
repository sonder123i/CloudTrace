package com.zcshou.gogogo.run.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

class TrackMathUtilsTest {

    @Test
    fun haversine_oneDegreeLatitude() {
        val d = TrackMathUtils.distanceMeters(
            TrackMathUtils.LatLng(0.0, 0.0),
            TrackMathUtils.LatLng(1.0, 0.0)
        )
        assertEquals(111194.9, d, 5.0)
    }

    @Test
    fun bearing_cardinal() {
        val origin = TrackMathUtils.LatLng(36.0, 117.0)
        assertEquals(0.0, TrackMathUtils.bearingDegrees(origin, TrackMathUtils.LatLng(36.001, 117.0)), 0.01)
        assertEquals(90.0, TrackMathUtils.bearingDegrees(origin, TrackMathUtils.LatLng(36.0, 117.001)), 0.05)
    }

    @Test
    fun offsetPoint_perpendicular() {
        val p = TrackMathUtils.LatLng(36.667662, 117.027707)
        val shifted = TrackMathUtils.offsetPoint(p, 0.0, 10.0)
        assertEquals(10.0, TrackMathUtils.distanceMeters(p, shifted), 0.05)
        assertEquals(90.0, TrackMathUtils.bearingDegrees(p, shifted), 0.5)
    }

    @Test
    fun spline_oval_is_closed_dense_smooth() {
        val anchors = TrackFactory.ovalAnchors(TrackMathUtils.LatLng(36.667662, 117.027707))
        assertEquals(6, anchors.size)
        val dense = TrackMathUtils.catmullRomClosed(anchors, 2.0)
        assertTrue("密度过低: ${dense.size}", dense.size >= 150)
        val cum = TrackMathUtils.cumulativeDistances(dense)
        val total = cum.last()
        assertTrue("周长异常: $total", total in 380.0..430.0)
        val gap = TrackMathUtils.distanceMeters(dense.first(), dense.last())
        assertTrue("闭合缺口: $gap", gap <= 3.0)
        var maxDelta = 0.0
        for (i in 1 until dense.size - 1) {
            val a = TrackMathUtils.bearingDegrees(dense[i - 1], dense[i])
            val b = TrackMathUtils.bearingDegrees(dense[i], dense[i + 1])
            maxDelta = max(maxDelta, TrackMathUtils.headingDelta(a, b))
        }
        assertTrue("平滑度不足: $maxDelta", maxDelta < 35.0)
    }

    @Test
    fun interpolate_midpoint() {
        val origin = TrackMathUtils.LatLng(36.0, 117.0)
        val pts = listOf(
            origin,
            TrackMathUtils.destinationPoint(origin, 0.0, 10.0),
            TrackMathUtils.destinationPoint(origin, 0.0, 20.0)
        )
        val cum = TrackMathUtils.cumulativeDistances(pts)
        val mid = TrackMathUtils.interpolate(pts, cum, 5.0)
        assertEquals(5.0, TrackMathUtils.distanceMeters(pts[0], mid), 0.05)
    }

    @Test
    fun bearingAt_straightNorth() {
        val origin = TrackMathUtils.LatLng(36.0, 117.0)
        val pts = listOf(
            origin,
            TrackMathUtils.destinationPoint(origin, 0.0, 2.0),
            TrackMathUtils.destinationPoint(origin, 0.0, 4.0)
        )
        val cum = TrackMathUtils.cumulativeDistances(pts)
        assertEquals(0.0, TrackMathUtils.bearingAt(pts, cum, 3.0), 0.5)
    }

    @Test
    fun catmullRomOpen_keepsEndpointsWithoutClosingSegment() {
        val origin = TrackMathUtils.LatLng(36.0, 117.0)
        val anchors = listOf(
            origin,
            TrackMathUtils.destinationPoint(origin, 90.0, 100.0),
            TrackMathUtils.destinationPoint(origin, 90.0, 100.0).let {
                TrackMathUtils.destinationPoint(it, 0.0, 100.0)
            }
        )
        val dense = TrackMathUtils.catmullRomOpen(anchors, 2.0)
        assertTrue("点密度过低: ${dense.size}", dense.size >= 50)
        assertEquals(anchors.first().lat, dense.first().lat, 1e-9)
        assertEquals(anchors.first().lng, dense.first().lng, 1e-9)
        assertEquals(anchors.last().lat, dense.last().lat, 1e-9)
        assertEquals(anchors.last().lng, dense.last().lng, 1e-9)
        val total = TrackMathUtils.cumulativeDistances(dense).last()
        assertTrue("开放样条不应闭合: $total", total in 190.0..210.0)
    }

    @Test
    fun distanceMeters_antipodalPointsDoesNotReturnNaN() {
        val d = TrackMathUtils.distanceMeters(
            TrackMathUtils.LatLng(0.0, 0.0),
            TrackMathUtils.LatLng(0.0, 180.0)
        )
        assertTrue(!d.isNaN() && d > 20_000_000.0)
    }

    // ---------------- 闭环重设起点 / 反转方向 ----------------

    /** 一圈 400m 标准操场：旋转起点后总长不变、首尾仍闭合、点集只是环形重排 */
    @Test
    fun rotateLoopStart_keepsLengthAndClosure() {
        val dense = TrackMathUtils.catmullRomClosed(
            TrackFactory.ovalAnchors(TrackMathUtils.LatLng(36.667662, 117.027707)),
            2.0
        )
        val originalLength = TrackMathUtils.cumulativeDistances(dense).last()
        assertTrue("样例应为闭环", TrackMathUtils.isClosedLoop(dense))

        val pivot = dense.size / 3
        val rotated = TrackMathUtils.rotateLoopStart(dense, pivot)

        // 新首点 = 原 pivot 点
        assertEquals(dense[pivot].lat, rotated.first().lat, 1e-12)
        assertEquals(dense[pivot].lng, rotated.first().lng, 1e-12)
        // 首尾仍闭合
        assertTrue("旋转后应仍闭合", TrackMathUtils.isClosedLoop(rotated))
        assertTrue("旋转后应有显式闭合点", TrackMathUtils.hasExplicitClosePoint(rotated))
        // 补上接缝段后，周长等于原环形周长（原输出末点到首点还差约一个采样步长）
        val rotatedLength = TrackMathUtils.cumulativeDistances(rotated).last()
        val seamGap = TrackMathUtils.distanceMeters(dense.last(), dense.first())
        assertEquals(originalLength + seamGap, rotatedLength, 1e-6)
        assertTrue("周长不应变小: $rotatedLength vs $originalLength", rotatedLength >= originalLength - 1e-6)
        // 不丢点：除新增的闭合点外，点集与原轨迹一致
        assertEquals(dense.size + 1, rotated.size)
        assertEquals(dense.toSet(), rotated.dropLast(1).toSet())
    }

    @Test
    fun rotateLoopStart_zeroIndexOnlyAddsSeamSegment() {
        val dense = TrackMathUtils.catmullRomClosed(
            TrackFactory.ovalAnchors(TrackMathUtils.LatLng(36.667662, 117.027707)),
            2.0
        )
        val rotated = TrackMathUtils.rotateLoopStart(dense, 0)
        // 起点不变：仅补齐接缝闭合点，采样点顺序完全一致
        assertEquals(dense.first().lat, rotated.first().lat, 1e-12)
        assertEquals(dense, rotated.dropLast(1))
        assertTrue(TrackMathUtils.hasExplicitClosePoint(rotated))

        // 已有显式闭合点时 pivot=0 是严格恒等
        val withClose = dense + dense.first()
        assertEquals(withClose, TrackMathUtils.rotateLoopStart(withClose, 0))
    }

    @Test
    fun rotateLoopStart_clampsOutOfRangeIndex() {
        val dense = TrackMathUtils.catmullRomClosed(
            TrackFactory.ovalAnchors(TrackMathUtils.LatLng(36.667662, 117.027707)),
            2.0
        )
        // 越界索引收敛到末点，不应抛异常
        val rotated = TrackMathUtils.rotateLoopStart(dense, dense.size * 10)
        assertTrue(rotated.size >= 2)
        assertTrue(TrackMathUtils.isClosedLoop(rotated))
    }

    @Test
    fun rotateLoopStart_explicitClosePointStaysClosed() {
        // RouteSources.closeLoopIfNeeded 产出的形态：末点是首点的显式副本
        val dense = TrackMathUtils.catmullRomClosed(
            TrackFactory.ovalAnchors(TrackMathUtils.LatLng(36.667662, 117.027707)),
            2.0
        )
        val withClose = dense + dense.first()
        val rotated = TrackMathUtils.rotateLoopStart(withClose, 10)
        assertEquals(dense[10].lat, rotated.first().lat, 1e-12)
        assertEquals(rotated.first().lat, rotated.last().lat, 1e-12)
        assertEquals(withClose.size, rotated.size)
    }

    /** 开放折线不做环形旋转：否则会凭空生成「末点→首点」的跳跃段 */
    @Test
    fun rotateLoopStart_openPathIsReturnedUnchanged() {
        val origin = TrackMathUtils.LatLng(36.0, 117.0)
        val open = listOf(
            origin,
            TrackMathUtils.destinationPoint(origin, 90.0, 100.0),
            TrackMathUtils.destinationPoint(origin, 90.0, 200.0)
        )
        assertEquals(open, TrackMathUtils.rotateLoopStart(open, 1))
    }

    @Test
    fun reversePath_closedLoopStaysClosed() {
        val dense = TrackMathUtils.catmullRomClosed(
            TrackFactory.ovalAnchors(TrackMathUtils.LatLng(36.667662, 117.027707)),
            2.0
        )
        val reversed = TrackMathUtils.reversePath(dense)
        assertEquals(dense.size, reversed.size)
        assertTrue(TrackMathUtils.isClosedLoop(reversed))
        assertEquals(dense.first().lat, reversed.last().lat, 1e-12)
        // 总长不变
        assertEquals(
            TrackMathUtils.cumulativeDistances(dense).last(),
            TrackMathUtils.cumulativeDistances(reversed).last(),
            1e-6
        )
    }

    @Test
    fun reversePath_openPathStartsAtOriginalEnd() {
        val origin = TrackMathUtils.LatLng(36.0, 117.0)
        val open = listOf(
            origin,
            TrackMathUtils.destinationPoint(origin, 90.0, 100.0),
            TrackMathUtils.destinationPoint(origin, 90.0, 200.0)
        )
        val reversed = TrackMathUtils.reversePath(open)
        // 纯倒序：从原终点出发，折返由引擎 ping-pong 处理，不额外补点
        assertEquals(open.last().lat, reversed.first().lat, 1e-12)
        assertEquals(open.first().lat, reversed.last().lat, 1e-12)
        assertEquals(open.size, reversed.size)
    }

    @Test
    fun reversePath_tinyInputIsReturnedAsIs() {
        val single = listOf(TrackMathUtils.LatLng(36.0, 117.0))
        assertEquals(single, TrackMathUtils.reversePath(single))
        assertEquals(emptyList<TrackMathUtils.LatLng>(), TrackMathUtils.reversePath(emptyList()))
    }
}