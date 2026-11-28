package com.zcshou.gogogo.run.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 海报版式自检。
 *
 * [ShareCardRenderer] 里所有坐标/字号都直接以 1080×1600 画布的像素书写
 * （页脚基线 1512、分段基线 1330、轨迹区 700–1120），因此缩放系数必须是 1f。
 * 一旦有人把它改回「dp→px」的换算值（例如 3f），`3.00 km` 这类大字号会撑满
 * 上半张图，把轨迹、分段与页脚整段挤出画布 —— 线上表现是「海报只剩标题和里程」。
 */
class ShareCardRendererLayoutTest {

    @Test
    fun `unit scale stays at pixel identity`() {
        val field = ShareCardRenderer::class.java.getDeclaredField("U")
        field.isAccessible = true
        assertEquals(
            "海报坐标已按像素设计，U 必须为 1f，否则下半张会被裁掉",
            1f,
            field.getFloat(null),
            0f
        )
    }

    @Test
    fun `canvas size matches the documented poster`() {
        assertEquals(1080, ShareCardRenderer.WIDTH)
        assertEquals(1600, ShareCardRenderer.HEIGHT)
    }
}
