package com.zcshou.gogogo.run.update

import com.zcshou.gogogo.BuildConfig

/**
 * 应用更新配置：只放公开信息（清单地址、允许的 CDN 域）。
 *
 * 更新签名私钥由发版机本地持有，绝不进入 APK / 仓库。
 */
object UpdateConfig {

    const val CHANNEL = "stable"
    const val KEY_ID = "gogogo-update-2026-11"

    /** 当前构建是否配置了可用更新源。 */
    val enabled: Boolean
        get() = BuildConfig.UPDATE_ENABLED &&
            sources.isNotEmpty() &&
            allowedHosts.isNotEmpty()

    /** 主源 + 备源，空值自动忽略。 */
    val sources: List<String> by lazy {
        listOf(
            BuildConfig.UPDATE_MANIFEST_URL,
            BuildConfig.UPDATE_BACKUP_URL
        ).map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /**
     * 更新相关网络只允许自建/官方 Release 域。
     *
     * 构建脚本里的默认值已经包含 Pages、Gitee raw 与 GitHub/Gitee Release 资产域；
     * 如果换成自定义 CDN，需要通过 `GOGOGO_UPDATE_ALLOWED_HOSTS` 显式追加。
     */
    val allowedHosts: Set<String> by lazy {
        val configured = BuildConfig.UPDATE_ALLOWED_HOSTS
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        configured.ifEmpty { FALLBACK_HOSTS }
    }

    private val FALLBACK_HOSTS = setOf(
        "github.com",
        "raw.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "objects.githubusercontent.com",
        "gitee.com",
        "foruda.gitee.com",
        "raw.giteeusercontent.com",
        "attachments.gitee.com"
    )
}
