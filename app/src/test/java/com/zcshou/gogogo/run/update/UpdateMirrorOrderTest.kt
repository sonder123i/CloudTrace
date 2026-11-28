package com.zcshou.gogogo.run.update

import org.junit.Assert.assertEquals
import org.junit.Test

/** 线路排序：清单顺序即优先级（Gitee 优先），用户手动指定才提到最前。 */
class UpdateMirrorOrderTest {

    private fun mirrors(vararg ids: String): List<UpdateMirror> = ids.map {
        UpdateMirror(id = it, name = it.uppercase(), url = "https://example.com/$it.apk")
    }

    private fun ids(list: List<UpdateMirror>) = list.map { it.id }

    @Test
    fun keepsManifestOrderWhenNothingRequested() {
        // Gitee 在清单里排第一就必须第一个试，不能被历史偏好顶掉
        assertEquals(
            listOf("gitee", "gh"),
            ids(UpdateMirrorOrder.arrange(mirrors("gitee", "gh"), null))
        )
        assertEquals(
            listOf("gitee", "gh", "cdn"),
            ids(UpdateMirrorOrder.arrange(mirrors("gitee", "gh", "cdn"), null))
        )
    }

    @Test
    fun requestedMirrorMovesToFrontButOthersKeepOrder() {
        assertEquals(
            listOf("gh", "gitee"),
            ids(UpdateMirrorOrder.arrange(mirrors("gitee", "gh"), "gh"))
        )
        assertEquals(
            listOf("cdn", "gitee", "gh"),
            ids(UpdateMirrorOrder.arrange(mirrors("gitee", "gh", "cdn"), "cdn"))
        )
    }

    @Test
    fun requestingTheFirstMirrorChangesNothing() {
        assertEquals(
            listOf("gitee", "gh"),
            ids(UpdateMirrorOrder.arrange(mirrors("gitee", "gh"), "gitee"))
        )
    }

    @Test
    fun unknownRequestedMirrorFallsBackToManifestOrder() {
        assertEquals(
            listOf("gitee", "gh"),
            ids(UpdateMirrorOrder.arrange(mirrors("gitee", "gh"), "old-mirror"))
        )
        assertEquals(emptyList<String>(), ids(UpdateMirrorOrder.arrange(emptyList(), "gh")))
    }
}