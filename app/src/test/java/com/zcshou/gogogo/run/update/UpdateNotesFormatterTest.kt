package com.zcshou.gogogo.run.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateNotesFormatterTest {

    @Test
    fun dropsVersionTitleAndVerifySection() {
        val markdown = """
            # CloudTrace v1.14.4

            ## 新增

            - 首页新增「支持云迹」入口。

            ## 修复

            - 修复定时任务偶尔重复触发。

            ## 校验

            - 版本：1.14.4 (1144)
            - 安装包：CloudTrace_1.14.4_arm64-v8a_release.apk
        """.trimIndent()

        val sections = UpdateNotesFormatter.sections(markdown)

        assertEquals(listOf("新增", "修复"), sections.map { it.title })
        assertEquals(listOf("· 首页新增「支持云迹」入口。"), sections[0].lines)
        assertEquals(listOf("· 修复定时任务偶尔重复触发。"), sections[1].lines)
        val text = UpdateNotesFormatter.format(markdown)
        assertTrue(text.contains("首页新增"))
        assertTrue(!text.contains("CloudTrace v1.14.4"))
        assertTrue(!text.contains("校验"))
        assertTrue(!text.contains("安装包"))
    }

    @Test
    fun stripsInlineMarkdownAndKeepsPlainParagraphs() {
        val markdown = """
            # v1.14.4

            这次主要修稳定性和手感。

            ## 优化

            - 屏幕卡片换成**真玻璃**折射，`BALANCED` 档更省电。
            - 详情见 [更新日志](https://example.com/log)。
        """.trimIndent()

        val text = UpdateNotesFormatter.format(markdown)

        assertTrue(!text.contains("**"))
        assertTrue(!text.contains("`"))
        assertTrue(!text.contains("]("))
        assertTrue(text.contains("屏幕卡片换成真玻璃折射"))
        assertTrue(text.contains("详情见 更新日志"))
        assertTrue(text.contains("这次主要修稳定性和手感。"))
        assertEquals("优化", UpdateNotesFormatter.sections(markdown)[1].title)
    }

    @Test
    fun notesWithoutSectionsStillReadable() {
        val text = UpdateNotesFormatter.format("只修了一个崩溃，无需设置。")
        assertEquals("只修了一个崩溃，无需设置。", text)
        assertEquals(listOf<String?>(null), UpdateNotesFormatter.sections("只修了一个崩溃。").map { it.title })
    }

    @Test
    fun verifyOnlyNotesRenderEmpty() {
        val markdown = """
            # CloudTrace v1.14.4

            ## 校验

            - 版本：1.14.4 (1144)
        """.trimIndent()

        assertTrue(UpdateNotesFormatter.sections(markdown).isEmpty())
        assertEquals("", UpdateNotesFormatter.format(markdown))
    }
}
