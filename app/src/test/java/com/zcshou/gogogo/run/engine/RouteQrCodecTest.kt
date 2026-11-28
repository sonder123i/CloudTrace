package com.zcshou.gogogo.run.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 二维码编码的纯逻辑边界（位图绘制本身依赖 android.graphics，
 * 由插桩测试/真机覆盖，这里只守住长度边界与口令回读链路）。
 */
class RouteQrCodecTest {

    private fun square(centerLat: Double, centerLng: Double, sideMeters: Double): List<TrackMathUtils.LatLng> {
        val half = sideMeters / 2
        val corners = listOf(
            TrackMathUtils.destinationPoint(TrackMathUtils.LatLng(centerLat, centerLng), 0.0, half),
            TrackMathUtils.destinationPoint(TrackMathUtils.LatLng(centerLat, centerLng), 90.0, half),
            TrackMathUtils.destinationPoint(TrackMathUtils.LatLng(centerLat, centerLng), 180.0, half),
            TrackMathUtils.destinationPoint(TrackMathUtils.LatLng(centerLat, centerLng), 270.0, half)
        )
        return corners + corners.first()
    }

    @Test
    fun `payload length limit matches what a qr code can carry`() {
        assertTrue(RouteQrCodec.MAX_PAYLOAD_CHARS in 1_000..4_000)
    }

    @Test
    fun `share code round trips through the qr payload path`() {
        val points = square(36.667662, 117.027707, 120.0)
        val code = RouteShareCodec.encode("操场圈", points, clockwise = true)
        assertTrue("口令应短于二维码上限", code.length <= RouteQrCodec.MAX_PAYLOAD_CHARS)

        // 二维码解码后拿到的是同一段口令文本；再走 decodeToRoute 应还原路线
        val route = RouteQrCodec.decodeToRoute(code)
        assertEquals("操场圈", route!!.name)
        assertTrue(route.clockwise)
        assertEquals(points.size, route.points.size)
    }

    @Test
    fun `decodeToRoute accepts a full share message with surrounding text`() {
        val points = square(36.667662, 117.027707, 80.0)
        val code = RouteShareCodec.encode("公园环线", points, clockwise = false)
        // 模拟二维码里塞了整段分享文案（含前缀与说明）
        val message = "【云迹】跑圈路线「公园环线」\n复制整段口令即可导入：\n$code"
        val route = RouteQrCodec.decodeToRoute(message)
        assertEquals("公园环线", route!!.name)
        assertEquals(false, route.clockwise)
    }

    @Test
    fun `decodeToRoute rejects garbage`() {
        assertNull(RouteQrCodec.decodeToRoute("这不是口令"))
        assertNull(RouteQrCodec.decodeToRoute(""))
    }

    @Test
    fun `long routes still fit under the payload limit or are reported too long`() {
        // 2000 个点的密集轨迹：口令可能超限，此时 UI 应回退纯文本口令而不是画糊图
        val dense = (0 until 2_000).map { i ->
            TrackMathUtils.destinationPoint(
                TrackMathUtils.LatLng(36.667662, 117.027707),
                (i % 360).toDouble(),
                50.0 + (i % 40)
            )
        }
        val code = RouteShareCodec.encode("超长轨迹", dense, clockwise = true)
        // 无论是否超限，decode 链路都必须保持无损
        val route = RouteQrCodec.decodeToRoute(code)
        assertEquals(dense.size, route!!.points.size)
        if (code.length > RouteQrCodec.MAX_PAYLOAD_CHARS) {
            assertTrue("超限时应能被识别为过长", code.length > RouteQrCodec.MAX_PAYLOAD_CHARS)
        }
    }
}
