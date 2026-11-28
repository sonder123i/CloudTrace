package com.zcshou.gogogo.mascot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图鉴元数据完整性：每个彩蛋都必须有标题与解锁提示，
 * 否则图鉴格子会出现「空白条件」这种半成品观感。
 */
class MascotEggMetadataTest {

    @Test
    fun `every egg has a title and unlock hint`() {
        MascotEgg.entries.forEach { egg ->
            assertTrue("${egg.name} 缺少标题", egg.title.isNotBlank())
            assertTrue("${egg.name} 缺少解锁提示", egg.unlockHint.isNotBlank())
            assertTrue("${egg.name} 资源名非法", egg.resName.startsWith("egg_"))
        }
    }

    @Test
    fun `egg count matches the ten planned polaroids`() {
        assertEquals(10, MascotEgg.entries.size)
    }

    @Test
    fun `titles and resource names are unique`() {
        assertEquals(
            MascotEgg.entries.size,
            MascotEgg.entries.map { it.title }.distinct().size
        )
        assertEquals(
            MascotEgg.entries.size,
            MascotEgg.entries.map { it.resName }.distinct().size
        )
    }

    @Test
    fun `report resolver always returns a known egg`() {
        // 快配速 → DRIVE
        assertEquals(
            MascotEgg.DRIVE,
            MascotEggResolver.forReport(averagePaceSecPerKm = 280, durationSeconds = 900, endHour = 15)
        )
        // 深夜 → FLASHLIGHT
        assertEquals(
            MascotEgg.FLASHLIGHT,
            MascotEggResolver.forReport(averagePaceSecPerKm = 340, durationSeconds = 900, endHour = 23)
        )
        // 清晨 → TOAST
        assertEquals(
            MascotEgg.TOAST,
            MascotEggResolver.forReport(averagePaceSecPerKm = 340, durationSeconds = 900, endHour = 7)
        )
        // 长时托管 → DESK_TAIL
        assertEquals(
            MascotEgg.DESK_TAIL,
            MascotEggResolver.forReport(averagePaceSecPerKm = 340, durationSeconds = 1_500, endHour = 15)
        )
        // 其余白天 → SLACKING_WHALE
        assertEquals(
            MascotEgg.SLACKING_WHALE,
            MascotEggResolver.forReport(averagePaceSecPerKm = 340, durationSeconds = 900, endHour = 15)
        )
    }

    @Test
    fun `every egg has a non-blank caption`() {
        MascotEgg.entries.forEach { egg ->
            assertTrue("${egg.name} 缺少金句", egg.caption.isNotBlank())
        }
    }
}
