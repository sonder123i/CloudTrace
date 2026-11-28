package com.zcshou.gogogo.run.update

/**
 * 更新说明的展示清洗。
 *
 * 清单里放的是完整的发版记录：一级标题是版本号，「校验」段是维护者核对渠道包用的
 * 版本号与文件名。这些内容不该出现在「发现新版本」弹窗里 —— 用户只想知道
 * 「这次改了什么」。所以这里统一裁掉标题和校验段，把 Markdown 的强调、
 * 行内代码、链接和列表符号整理成适合中文短文阅读的纯文本。
 *
 * 纯 Kotlin，不依赖 Android 框架，方便单测。
 */
object UpdateNotesFormatter {

    /** 维护者用的段落，命中后连同后面内容一起丢掉。 */
    private const val INTERNAL_SECTION = "校验"

    /** 一个展示小节：`title` 为空表示没有小节标题的引导段落。 */
    data class Section(val title: String?, val lines: List<String>)

    private val LINK = Regex("""\[([^\]]*)]\(([^)]*)\)""")
    private val BULLET = Regex("""^[-*·+]\s+""")

    /** 拆成小节，保留原有顺序。 */
    fun sections(markdown: String): List<Section> {
        val result = mutableListOf<Section>()
        var currentTitle: String? = null
        var current = mutableListOf<String>()
        var internal = false

        fun flush() {
            if (current.isNotEmpty() || currentTitle != null) {
                result += Section(currentTitle, current.toList())
            }
            current = mutableListOf()
            currentTitle = null
        }

        for (raw in markdown.lineSequence()) {
            val line = raw.trim()
            when {
                internal -> Unit
                line.startsWith("## ") -> {
                    val title = inline(line.removePrefix("## "))
                    if (title.contains(INTERNAL_SECTION)) {
                        internal = true
                        flush()
                    } else {
                        flush()
                        currentTitle = title
                    }
                }
                // 版本号大标题由弹窗自己的版本行展示，不再重复
                line.startsWith("# ") -> Unit
                line.isEmpty() -> Unit
                else -> current += if (BULLET.containsMatchIn(line)) {
                    "· " + inline(BULLET.replace(line, ""))
                } else {
                    inline(line)
                }
            }
        }
        if (!internal) flush()
        // 只剩小标题、没有内容的段落没有信息量，直接丢掉，避免弹窗里出现孤立标题
        return result.filter { it.lines.isNotEmpty() }
    }

    /** 供纯文本场景（测试、日志）使用的整段文案。 */
    fun format(markdown: String): String = sections(markdown)
        .joinToString("\n\n") { section ->
            buildString {
                if (!section.title.isNullOrBlank()) append(section.title).append('\n')
                append(section.lines.joinToString("\n"))
            }
        }
        .trim()

    /** 去掉 Markdown 行内标记，保留可读文本。 */
    private fun inline(text: String): String = LINK.replace(text) { it.groupValues[1] }
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .trim()
}
