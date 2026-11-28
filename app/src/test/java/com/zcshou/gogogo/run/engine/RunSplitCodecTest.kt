package com.zcshou.gogogo.run.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunSplitCodecTest {

    @Test
    fun `encode decode round trip keeps every field`() {
        val splits = listOf(
            RunSplit(1, 1000, 338, 338, RunPhase.RUNNING.name),
            RunSplit(2, 1000, 350, 350, RunPhase.DETOUR_POINT.name),
            RunSplit(3, 240, 120, 500, RunPhase.COOLDOWN_WALK.name)
        )
        val json = RunSplitCodec.encode(splits)
        val decoded = RunSplitCodec.decode(json)
        assertEquals(splits, decoded)
    }

    @Test
    fun `encode empty list returns null so legacy fallback stays distinguishable`() {
        assertNull(RunSplitCodec.encode(emptyList()))
        assertTrue(RunSplitCodec.decode(null).isEmpty())
        assertTrue(RunSplitCodec.decode("").isEmpty())
    }

    @Test
    fun `decode tolerates broken json and invalid segments`() {
        assertTrue(RunSplitCodec.decode("{not json").isEmpty())
        assertTrue(RunSplitCodec.decode("[{\"distM\":0,\"durSec\":10}]").isEmpty())
        // 缺 paceSec 时按 dist/dur 回算
        val decoded = RunSplitCodec.decode("[{\"idx\":1,\"distM\":500,\"durSec\":200}]")
        assertEquals(1, decoded.size)
        assertEquals(400, decoded[0].paceSec)
        assertEquals(RunPhase.RUNNING.name, decoded[0].phase)
    }
}
