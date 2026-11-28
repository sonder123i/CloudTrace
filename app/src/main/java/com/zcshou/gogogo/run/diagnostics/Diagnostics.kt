package com.zcshou.gogogo.run.diagnostics

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.zcshou.gogogo.BuildConfig
import com.zcshou.gogogo.GoApplication
import com.zcshou.gogogo.run.data.repository.RunRepository
import com.zcshou.gogogo.run.guard.SetupGuard
import com.zcshou.gogogo.run.ui.home.StarPrompt
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale

/**
 * 本地诊断：把排查所需的环境信息、权限状态、使用计数和日志尾部整理成文本。
 *
 * 只包含计数与系统环境，不包含坐标、路线名称、应用列表；日志本身也不记录坐标。
 * 诊断包只落内部存储；分享通过 FileProvider 交给系统面板，不新增权限、不联网。
 */
object Diagnostics {

    private const val MAX_LOG_TAIL_BYTES = 300 * 1024
    private const val BUNDLE_PREFIX = "CloudTrace_diagnostics_"
    private const val KEEP_BUNDLES = 2
    private const val MAX_CRASH_CHARS = 4000

    const val LOG_DIR = "logs"
    private const val BUNDLE_DIR = "diagnostics"

    /** 生成完整诊断文本（含最近一次崩溃记录，若有）。 */
    suspend fun build(context: Context, repository: RunRepository): String {
        val setup = SetupGuard.check(context)
        val routes = runCatching { repository.routeCount() }.getOrDefault(0)
        val stats = runCatching { repository.usageStats() }.getOrNull()
        val log = logFile(context)

        return buildString {
            appendLine("云迹（CloudTrace）诊断信息")
            appendLine("生成时间: ${CrashJournal.formatTime(System.currentTimeMillis())}")
            appendLine("版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("包名: ${context.packageName}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} / ${Build.BRAND}")
            appendLine("ROM: ${Build.DISPLAY}")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString(",")}")
            appendLine("安装来源: ${installerPackage(context)}")
            appendLine("侧载安装: ${yesNo(SetupGuard.isSideloaded(context))}")
            appendLine("开发者选项: ${state(setup.devOptions)}")
            appendLine("模拟位置: ${state(setup.mockApp)}")
            appendLine("悬浮窗: ${state(setup.overlay)}")
            appendLine("路线数: $routes")
            if (stats != null) {
                appendLine("已完成跑次: ${stats.completedCount}")
                appendLine(
                    "累计里程: ${
                        String.format(Locale.CHINA, "%.1f", stats.totalMeters / 1000.0)
                    } km"
                )
            }
            appendLine("更新源: ${BuildConfig.UPDATE_MANIFEST_URL}")
            appendLine("签名 SHA-256: ${signerSha256(context)}")
            appendLine("日志文件: ${log?.absolutePath ?: "无"} (${log?.length() ?: 0L} B)")
            CrashJournal.read(context)?.let { crash ->
                appendLine()
                appendLine("== 上次崩溃 ==")
                append(crash.take(MAX_CRASH_CHARS))
            }
        }
    }

    fun logFile(context: Context): File? {
        // 与 GoApplication.initXlog 的存储策略对齐：日志在内部 filesDir/logs
        val dir = File(context.filesDir, LOG_DIR)
        val file = File(dir, GoApplication.LOG_FILE_NAME)
        return file.takeIf { it.isFile }
    }

    /** 诊断文本 + 日志尾部写入一个 txt，供系统分享；返回文件，失败返回 null。 */
    fun exportBundle(context: Context, diagnostics: String): File? {
        // 诊断包落内部存储：外部 getExternalFilesDir 在 Android 8/9 上可被其他应用读取。
        val dir = File(context.filesDir, BUNDLE_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        cleanupBundles(dir)
        cleanupLegacyBundles(context)
        val file = File(
            dir,
            "$BUNDLE_PREFIX${System.currentTimeMillis()}.txt"
        )
        val content = buildString {
            append(diagnostics)
            appendLine()
            appendLine()
            appendLine("== 最近日志 ==")
            append(readLogTail(logFile(context)))
        }
        return runCatching {
            file.writeText(content, Charsets.UTF_8)
            file
        }.getOrNull()
    }

    fun shareBundle(activity: Activity, file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileProvider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "云迹诊断信息")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, "分享诊断信息"))
        }
    }

    fun copy(context: Context, text: String): Boolean = runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("云迹诊断信息", text))
    }.isSuccess

    /** 打开 GitHub 新建 issue：诊断信息复制到剪贴板，正文只放模板，避免 URL 过长。 */
    fun openIssue(activity: Activity, diagnostics: String): Boolean {
        val title = "问题反馈：${BuildConfig.VERSION_NAME} / " +
            "${Build.MANUFACTURER} ${Build.MODEL}"
        // 中文经 URL 编码后体积膨胀，完整诊断放剪贴板，正文只留可读模板。
        copy(activity, diagnostics)
        val body = buildString {
            appendLine("## 问题描述")
            appendLine()
            appendLine("（请描述遇到的问题、复现步骤和预期结果）")
            appendLine()
            appendLine("## 诊断信息")
            appendLine()
            appendLine("（完整诊断信息已复制到剪贴板，请在此处粘贴；日志可随后作为附件上传）")
        }
        val url = "${StarPrompt.REPO_URL}/issues/new" +
            "?title=${Uri.encode(title)}&body=${Uri.encode(body)}"
        return runCatching {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
    }

    // ---------------- 内部 ----------------

    private fun readLogTail(file: File?): String {
        if (file == null || file.length() <= 0L) return "（暂无日志文件）"
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                val start = (length - MAX_LOG_TAIL_BYTES).coerceAtLeast(0L)
                val bytes = ByteArray((length - start).toInt())
                raf.seek(start)
                raf.readFully(bytes)
                String(bytes, Charsets.UTF_8)
            }
        }.getOrDefault("（读取日志失败）")
    }

    private fun cleanupBundles(dir: File) {
        val old = dir.listFiles { file ->
            file.isFile && file.name.startsWith(BUNDLE_PREFIX)
        }?.sortedByDescending { it.lastModified() } ?: return
        old.drop(KEEP_BUNDLES).forEach { runCatching { it.delete() } }
    }

    /** 旧版本曾把诊断包写在外部 Logs/ 下，迁移后顺手清掉遗留（含旧日志本体）。 */
    private fun cleanupLegacyBundles(context: Context) {
        val legacy = context.getExternalFilesDir("Logs") ?: return
        legacy.listFiles { file -> file.isFile }
            ?.forEach { runCatching { it.delete() } }
    }

    fun installerPackage(context: Context): String = runCatching {
        val pm = context.packageManager
        val packageName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(context.packageName)
        }
        packageName ?: "未知（可能是侧载或系统直接安装）"
    }.getOrDefault("未知")

    fun signerSha256(context: Context): String = runCatching {
        val pm = context.packageManager
        val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures?.firstOrNull()
        } ?: error("no signer")
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            .joinToString(":") { "%02X".format(it) }
    }.getOrDefault("未知")

    private fun state(value: Boolean): String = if (value) "已开启" else "未开启"

    private fun yesNo(value: Boolean): String = if (value) "是" else "否"
}