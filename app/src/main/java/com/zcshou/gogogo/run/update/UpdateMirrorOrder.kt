package com.zcshou.gogogo.run.update

/**
 * 下载线路排序（纯 Kotlin，可 JVM 单测）。
 *
 * 规则：**清单声明顺序即优先级**（Gitee 在前，国内直连更快，GitHub 兜底）；
 * 用户手动指定某条线路时把它提到最前，其余仍按清单顺序。
 *
 * 刻意不参考「上次成功的线路」：一旦某次因 Gitee 缺文件而用 GitHub 下成功，
 * 把 GitHub 记住并顶到最前，之后每次都会先试慢线，国内用户永远拿不到快线。
 * 失败回落已经由下载循环按顺序重试覆盖，不需要额外的历史偏好。
 */
internal object UpdateMirrorOrder {

    fun arrange(mirrors: List<UpdateMirror>, requestedId: String?): List<UpdateMirror> {
        if (requestedId == null) return mirrors
        val picked = mirrors.firstOrNull { it.id == requestedId } ?: return mirrors
        return listOf(picked) + mirrors.filterNot { it.id == requestedId }
    }
}