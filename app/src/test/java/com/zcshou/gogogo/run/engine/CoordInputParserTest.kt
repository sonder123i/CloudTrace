package com.zcshou.gogogo.run.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordInputParserTest {

    private fun parsed(raw: String): CoordInputParser.Result.Parsed {
        val result = CoordInputParser.parse(raw)
        assertTrue("应解析为坐标，实际=$result raw=$raw", result is CoordInputParser.Result.Parsed)
        return result as CoordInputParser.Result.Parsed
    }

    @Test
    fun `decimal with comma space and semicolon`() {
        listOf(
            "39.9042,116.4074",
            "39.9042 116.4074",
            "39.9042;116.4074",
            "  39.9042 , 116.4074  "
        ).forEach { raw ->
            val p = parsed(raw)
            assertEquals("lat @$raw", 39.9042, p.lat, 1e-6)
            assertEquals("lng @$raw", 116.4074, p.lng, 1e-6)
            assertEquals(false, p.swapped)
        }
    }

    @Test
    fun `full width separators are normalized`() {
        val p = parsed("39.9042，116.4074")
        assertEquals(39.9042, p.lat, 1e-6)
        assertEquals(116.4074, p.lng, 1e-6)
    }

    @Test
    fun `dms with degree minute second symbols`() {
        val p = parsed("""39°54'15"N 116°24'27"E""")
        assertEquals(39.9042, p.lat, 1e-4)
        assertEquals(116.4075, p.lng, 1e-4)
    }

    @Test
    fun `dms with chinese units`() {
        val p = parsed("39度54分15秒 116度24分27秒")
        assertEquals(39.9042, p.lat, 1e-4)
        assertEquals(116.4075, p.lng, 1e-4)
    }

    @Test
    fun `degrees only is still a coordinate`() {
        val p = parsed("39.9042° 116.4074°")
        assertEquals(39.9042, p.lat, 1e-6)
        assertEquals(116.4074, p.lng, 1e-6)
    }

    @Test
    fun `prefix directions place the axes explicitly`() {
        // 纬度在前的常规顺序：不算纠正
        val normal = parsed("北纬39.9042 东经116.4074")
        assertEquals(39.9042, normal.lat, 1e-6)
        assertEquals(116.4074, normal.lng, 1e-6)
        assertEquals(false, normal.swapped)
        // 经度在前：轴标注明确、无需猜测，但顺序仍被重排 —— 统一置 swapped，
        // UI 的「已自动纠正顺序」提示对显式标注与隐式纠正口径一致
        val lngFirst = parsed("东经116.4074 北纬39.9042")
        assertEquals(39.9042, lngFirst.lat, 1e-6)
        assertEquals(116.4074, lngFirst.lng, 1e-6)
        assertTrue("经度在前应标记顺序纠正", lngFirst.swapped)
    }

    @Test
    fun `same axis dms pair is rejected instead of reinterpreted`() {
        // 两个都标注纬度：不加守卫会把第二个纬度静默当经度用
        assertEquals(
            CoordInputParser.Result.NotCoordinate,
            CoordInputParser.parse("""39°54'N 45°30'N""")
        )
    }

    @Test
    fun `single letter prefix works`() {
        val p = parsed("N39.9042 E116.4074")
        assertEquals(39.9042, p.lat, 1e-6)
        assertEquals(116.4074, p.lng, 1e-6)
    }

    @Test
    fun `southern and western hemisphere keep the sign`() {
        val p = parsed("""33°52'00"S 151°12'00"W""")
        assertTrue("lat=${p.lat}", p.lat < 0)
        assertTrue("lng=${p.lng}", p.lng < 0)
    }

    @Test
    fun `swapped china coordinates are corrected with a hint`() {
        // 经度 116 填在前：落在中国经度 73~136 / 纬度 3~54 区间
        val p = parsed("116.4074,39.9042")
        assertEquals(39.9042, p.lat, 1e-6)
        assertEquals(116.4074, p.lng, 1e-6)
        assertTrue("应标记为反写纠正", p.swapped)
    }

    @Test
    fun `plain non-china coordinates keep the normal order`() {
        val p = parsed("-33.8688,151.2093")
        assertEquals(-33.8688, p.lat, 1e-6)
        assertEquals(151.2093, p.lng, 1e-6)
        assertEquals(false, p.swapped)
    }

    @Test
    fun `out of range coordinates are rejected instead of clamped`() {
        assertEquals(CoordInputParser.Result.OutOfRange, CoordInputParser.parse("200,300"))
        assertEquals(CoordInputParser.Result.OutOfRange, CoordInputParser.parse("39.9,200"))
        assertEquals(CoordInputParser.Result.OutOfRange, CoordInputParser.parse("0,0"))
    }

    @Test
    fun `plain text is not a coordinate`() {
        listOf("奥林匹克公园", "39.9", "39.9 116.4 17", "", "abc,def").forEach {
            assertEquals("raw=$it", CoordInputParser.Result.NotCoordinate, CoordInputParser.parse(it))
        }
    }

    @Test
    fun `partial dms with minutes only`() {
        val p = parsed("""39°54'N 116°24'E""")
        assertEquals(39.9, p.lat, 1e-6)
        assertEquals(116.4, p.lng, 1e-6)
    }
}
