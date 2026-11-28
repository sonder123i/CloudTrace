package com.zcshou.gogogo.run.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.AtomicFile
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.PublicKey
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 应用更新状态机：检查、下载、校验、安装。
 *
 * 与 UI 解耦：UI 只订阅 [state]，网络/文件/安装全部在这里完成。
 * 检查与下载都做了并发合并；同一时刻只允许一个检查、一个下载任务。
 */
class UpdateManager internal constructor(
    private val context: Context,
    private val testKeys: Map<String, PublicKey>? = null,
    private val testSources: ((String) -> List<String>)? = null,
    private val allowedUrl: (String) -> Boolean = { UpdateUrls.isAllowed(it) },
    private val checkIntervalMs: Long = SIX_HOURS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    enum class Check { IDLE, CHECKING, AVAILABLE, UP_TO_DATE, FAILED, CACHED }

    enum class Phase {
        IDLE, PREPARING, CONNECTING, DOWNLOADING, SWITCHING, WAITING_NETWORK,
        PAUSED, VERIFYING, READY, FAILED, CANCELLED
    }

    data class State(
        val manifest: UpdateManifest? = null,
        val check: Check = Check.IDLE,
        val phase: Phase = Phase.IDLE,
        val bytes: Long = 0,
        val mirror: UpdateMirror? = null,
        val message: String = "",
        val errorCode: String = "",
        val showDialog: Boolean = false,
        val manualRequest: Boolean = false
    ) {
        val active: Boolean
            get() = phase in setOf(
                Phase.PREPARING, Phase.CONNECTING, Phase.DOWNLOADING,
                Phase.SWITCHING, Phase.WAITING_NETWORK, Phase.VERIFYING
            )

        val progress: Int
            get() = manifest?.let {
                // 校验器目前会 floor size ≥ 1，这里再加一道除数防御：
                // 未来清单 schema 放宽也不至于除零 ArithmeticException
                (bytes * 100 / it.size.coerceAtLeast(1)).coerceIn(0, 100).toInt()
            } ?: 0
    }

    companion object {
        const val EXTRA_SHOW_UPDATE = "com.zcshou.gogogo.extra.SHOW_UPDATE"

        @Volatile
        private var instance: UpdateManager? = null

        fun getInstance(context: Context): UpdateManager = instance ?: synchronized(this) {
            instance ?: UpdateManager(context.applicationContext).also { instance = it }
        }

        private const val SIX_HOURS = 6 * 60 * 60 * 1000L
        private const val DAY = 24 * 60 * 60 * 1000L
        private const val MAX_MANIFEST_BYTES = 128 * 1024
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("app_update_v1", Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "app-updates").apply { mkdirs() }
    private val partial = File(directory, "download.part")
    private val completed = File(directory, "verified.apk")
    private val taskRecord = AtomicFile(File(directory, "task.json"))
    private val channel = UpdateConfig.CHANNEL

    private val keys by lazy {
        testKeys ?: run {
            val json = context.assets.open("app-update-keys.json")
                .bufferedReader().use { it.readText() }
            UpdateManifestVerifier.parseKeys(json)
        }
    }

    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()

    private val transfer = UpdateTransfer(allowUrl = allowedUrl)
    private val http = UpdateTransfer.client().newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val checkMutex = Mutex()
    private val commands = Mutex()
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var task: UpdateManifest? = null
    private var taskEtag: String? = null
    private var taskMirror: String? = null
    private var requestedMirror: String? = null
    private var lastSave = 0L
    private val restore = scope.launch { restoreTask() }

    // 版本号在进程生命周期内不变：缓存一次，避免下载进度每帧刷新时反复 binder 查询
    private val currentPackageInfo: PackageInfo by lazy { packageInfo() }

    fun getCurrentVersionName(): String = currentPackageInfo.versionName.orEmpty()

    fun getCurrentVersionCode(): Int = versionCode(currentPackageInfo).toInt()

    @Suppress("DEPRECATION")
    private fun packageInfo(): PackageInfo =
        context.packageManager.getPackageInfo(context.packageName, 0)

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    fun checkForUpdate(manual: Boolean = false) {
        scope.launch {
            restore.join()
            checkMutex.withLock {
                if (checkJob?.isActive == true) {
                    if (manual) {
                        mutable.update { it.copy(showDialog = true, manualRequest = true) }
                    }
                    return@withLock
                }
                if (!UpdateConfig.enabled && testSources == null) {
                    mutable.update {
                        it.copy(
                            check = Check.FAILED,
                            showDialog = it.showDialog || manual,
                            manualRequest = it.manualRequest || manual,
                            message = "当前构建未配置更新源",
                            errorCode = "UPDATE_DISABLED"
                        )
                    }
                    return@withLock
                }
                val now = clock()
                if (!manual && now - prefs.getLong("last-check:$channel", 0L) < checkIntervalMs) {
                    return@withLock
                }
                mutable.update {
                    it.copy(
                        check = Check.CHECKING,
                        showDialog = it.showDialog || manual,
                        manualRequest = it.manualRequest || manual,
                        message = "正在检查更新…",
                        errorCode = ""
                    )
                }
                checkJob = scope.launch {
                    try {
                        val previous = cached()
                        val sourceList = sources()
                        val results = Collections.synchronizedList(mutableListOf<UpdateManifest>())
                        val outcomes = ConcurrentHashMap<String, String>()
                        withTimeoutOrNull(12_000) {
                            val semaphore = Semaphore(2)
                            coroutineScope {
                                sourceList.map { url ->
                                    launch {
                                        semaphore.withPermit {
                                            try {
                                                results.add(fetch(url))
                                                outcomes[url] = ""
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                outcomes[url] =
                                                    (e as? UpdateFailure)?.code ?: "CHECK_FAILED"
                                            }
                                        }
                                    }
                                }.joinAll()
                            }
                        }
                        currentCoroutineContext().ensureActive()

                        val chosen = UpdateManifestVerifier.choose(results.toList())
                        val previousManifest = previous
                        val value = chosen ?: previousManifest
                        val current = getCurrentVersionCode()
                        // 离线时缓存仍在：区分「缓存里确实有更新」和「上次检查本来就没更新」，
                        // 两种情况都不该用红色报错吓人，提示里说清楚是旧结果即可。
                        val cachedAvailable = chosen == null && previousManifest != null &&
                            previousManifest.isInstallCandidate(current, Build.VERSION.SDK_INT)
                        if (chosen != null) write(cacheFile(), chosen.envelope)
                        val available = value != null &&
                            value.isInstallCandidate(current, Build.VERSION.SDK_INT)
                        val canRemind = value?.let {
                            it.forceUpdate ||
                                now - prefs.getLong("later:${it.versionCode}", 0L) >= DAY
                        } ?: false
                        val neverPublished = results.isEmpty() && previousManifest == null &&
                            sourceList.firstOrNull()?.let { outcomes[it] } == "MANIFEST_ABSENT"
                        // 仅当真正收到服务端响应（含 304 / 清单未发布）时才记录自动检查时间戳，离线失败保留立即重试机会
                        if (chosen != null || results.isNotEmpty() || neverPublished) {
                            prefs.edit().putLong("last-check:$channel", now).apply()
                        }
                        val firstFailure = sourceList.asSequence()
                            .mapNotNull { outcomes[it]?.takeIf(String::isNotEmpty) }
                            .firstOrNull()

                        mutable.update { s ->
                            val activeTask = s.active || s.phase in setOf(
                                Phase.PAUSED, Phase.READY
                            )
                            s.copy(
                                manifest = if (activeTask) s.manifest else value,
                                check = when {
                                    chosen == null && previousManifest != null -> Check.CACHED
                                    chosen == null && neverPublished -> Check.UP_TO_DATE
                                    chosen == null -> Check.FAILED
                                    available -> Check.AVAILABLE
                                    else -> Check.UP_TO_DATE
                                },
                                showDialog = s.showDialog ||
                                    (chosen != null && available && canRemind),
                                message = if (activeTask) s.message else when {
                                    chosen == null && cachedAvailable ->
                                        "本次检查失败 · 上次检查发现 v${previousManifest!!.versionName}，可直接下载"
                                    chosen == null && previousManifest != null ->
                                        "本次检查失败 · 上次检查未发现新版本"
                                    chosen == null && neverPublished -> "已是最新版本"
                                    chosen == null -> "检查更新失败，请重试"
                                    value != null && value.minSdk > Build.VERSION.SDK_INT ->
                                        "新版本不支持当前 Android 版本"
                                    available -> "发现新版本 v${value!!.versionName}"
                                    else -> "已是最新版本"
                                },
                                errorCode = if (activeTask) s.errorCode
                                else if (chosen == null && previousManifest == null &&
                                    !neverPublished
                                ) {
                                    firstFailure ?: "CHECK_FAILED"
                                } else {
                                    ""
                                }
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        mutable.update {
                            it.copy(
                                check = Check.FAILED,
                                message = (e as? UpdateFailure)?.message
                                    ?: "检查更新失败，请重试",
                                errorCode = (e as? UpdateFailure)?.code ?: "CHECK_FAILED"
                            )
                        }
                    }
                }
            }
        }
    }

    fun dismiss() {
        mutable.value.manifest?.let {
            prefs.edit().putLong("later:${it.versionCode}", clock()).apply()
        }
        mutable.update { it.copy(showDialog = false, manualRequest = false) }
    }

    fun hideDialog() {
        mutable.update { it.copy(showDialog = false, manualRequest = false) }
    }

    /** 通知栏/底部入口重新打开下载面板。 */
    fun showDownload() {
        mutable.update { it.copy(showDialog = true, manualRequest = true) }
    }

    fun startDownload(mirrorId: String? = null) {
        scope.launch {
            commands.withLock {
                restore.join()
                if (mutable.value.active) return@withLock
                downloadJob?.cancelAndJoin()
                val manifest = mutable.value.manifest ?: return@withLock
                if (context.packageName != manifest.packageName ||
                    !manifest.isInstallCandidate(
                        getCurrentVersionCode(), Build.VERSION.SDK_INT
                    )
                ) {
                    return@withLock
                }
                if (task?.identity != manifest.identity) {
                    partial.delete()
                    completed.delete()
                    taskEtag = null
                    taskMirror = null
                }
                task = manifest
                requestedMirror = mirrorId
                mutable.update {
                    it.copy(
                        phase = Phase.PREPARING,
                        bytes = partial.length(),
                        message = "正在准备下载",
                        errorCode = "",
                        showDialog = true,
                        manualRequest = true
                    )
                }
                try {
                    persistTask()
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, UpdateDownloadService::class.java)
                    )
                } catch (_: Exception) {
                    mutable.update {
                        it.copy(
                            phase = Phase.FAILED,
                            message = "无法启动下载，请返回前台重试",
                            errorCode = "SERVICE_START"
                        )
                    }
                }
            }
        }
    }

    fun pauseDownload(cancel: Boolean = false) {
        transfer.cancel()
        scope.launch {
            commands.withLock {
                downloadJob?.cancelAndJoin()
                if (cancel) {
                    partial.delete()
                    completed.delete()
                    taskRecord.delete()
                    task = null
                    taskEtag = null
                    taskMirror = null
                }
                mutable.update {
                    it.copy(
                        phase = if (cancel) Phase.CANCELLED else Phase.PAUSED,
                        bytes = if (cancel) 0 else partial.length(),
                        message = if (cancel) "下载已取消" else "已暂停，可继续下载"
                    )
                }
                if (!cancel) persistTask()
                context.stopService(Intent(context, UpdateDownloadService::class.java))
            }
        }
    }

    internal fun serviceStopped() {
        if (mutable.value.active) {
            transfer.cancel()
            downloadJob?.cancel()
            mutable.update {
                it.copy(phase = Phase.PAUSED, message = "下载已暂停，可继续下载")
            }
        }
    }

    internal fun runDownload() {
        if (downloadJob?.isActive == true || mutable.value.phase != Phase.PREPARING) return
        downloadJob = scope.launch {
            val manifest = task ?: return@launch
            val deadline = android.os.SystemClock.elapsedRealtime() + 20 * 60 * 1000
            val order = listOfNotNull(
                requestedMirror,
                prefs.getString("successful-mirror", null)
            ) + manifest.mirrors.map { it.id }
            val mirrors = manifest.mirrors.sortedBy {
                order.indexOf(it.id).let { index -> if (index < 0) Int.MAX_VALUE else index }
            }
            var finalMessage = "下载线路暂时不可用，已保留断点"
            var finalCode = "DOWNLOAD_FAILED"
            try {
                var index = 0
                while (index < mirrors.size) {
                    currentCoroutineContext().ensureActive()
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                        updateError("DOWNLOAD_BUDGET", "下载时间较长，已暂停并保留断点")
                    }
                    if (!online()) {
                        mutable.update {
                            it.copy(
                                phase = Phase.WAITING_NETWORK,
                                message = "网络已断开，等待连接…"
                            )
                        }
                        delay(1000)
                        continue
                    }
                    val mirror = mirrors[index]
                    mutable.update {
                        it.copy(
                            phase = if (index == 0) Phase.CONNECTING else Phase.SWITCHING,
                            mirror = mirror,
                            message = if (index == 0) "正在连接 ${mirror.name}"
                            else "正在切换到 ${mirror.name}",
                            errorCode = ""
                        )
                    }
                    try {
                        if (directory.usableSpace <
                            manifest.size - partial.length() + 2 * 1024 * 1024
                        ) {
                            updateError("DISK_FULL", "存储空间不足，请清理后重试")
                        }
                        val oldEtag = taskEtag.takeIf { taskMirror == mirror.id }
                        taskMirror = mirror.id
                        taskEtag = oldEtag
                        transfer.download(
                            manifest = manifest,
                            mirror = mirror,
                            file = partial,
                            etag = oldEtag,
                            budgetMillis = deadline - android.os.SystemClock.elapsedRealtime()
                        ) { bytes, etag ->
                            taskEtag = etag
                            mutable.update {
                                it.copy(
                                    phase = Phase.DOWNLOADING,
                                    bytes = bytes,
                                    mirror = mirror,
                                    message = "正在下载"
                                )
                            }
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastSave > 1000) {
                                persistTask()
                                lastSave = now
                            }
                        }
                        mutable.update {
                            it.copy(phase = Phase.VERIFYING, message = "下载完成，正在校验…")
                        }
                        verifyApk(manifest, partial)
                        if (!partial.renameTo(completed)) {
                            updateError("FILE_SAVE", "无法保存安装包，请重试")
                        }
                        prefs.edit().putString("successful-mirror", mirror.id).apply()
                        mutable.update {
                            it.copy(
                                phase = Phase.READY,
                                bytes = manifest.size,
                                message = "校验通过，点击安装"
                            )
                        }
                        persistTask()
                        return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        currentCoroutineContext().ensureActive()
                        if (!online()) continue
                        finalMessage = (e as? UpdateFailure)?.message
                            ?: "下载超时或连接中断，已保留断点"
                        finalCode = (e as? UpdateFailure)?.code ?: "DOWNLOAD_NETWORK"
                        if (finalCode in setOf(
                                "APK_HASH", "APK_IDENTITY", "APK_SIGNATURE",
                                "APK_ABI", "DOWNLOAD_SIZE"
                            )
                        ) {
                            partial.delete()
                            taskEtag = null
                        }
                        if (finalCode == "DISK_FULL" || finalCode == "DOWNLOAD_BUDGET") break
                        index++
                    }
                }
                mutable.update {
                    it.copy(
                        phase = Phase.PAUSED,
                        bytes = partial.length(),
                        message = finalMessage,
                        errorCode = finalCode
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update {
                    it.copy(
                        phase = Phase.PAUSED,
                        message = (e as? UpdateFailure)?.message ?: "下载已暂停，请重试",
                        errorCode = (e as? UpdateFailure)?.code ?: "DOWNLOAD_FAILED"
                    )
                }
            } finally {
                if (mutable.value.active) {
                    mutable.update {
                        it.copy(phase = Phase.PAUSED, message = "下载已暂停，可继续下载")
                    }
                }
                runCatching { persistTask() }
            }
        }
    }

    @Suppress("DEPRECATION")
    internal fun verifyApk(manifest: UpdateManifest, file: File) {
        if (file.length() != manifest.size ||
            UpdateTransfer.digest(file) != manifest.sha256
        ) {
            updateError("APK_HASH", "安装包完整性校验失败，已停止安装")
        }
        UpdateApkAbi.verify(file, manifest.abi)
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val apk = context.packageManager.getPackageArchiveInfo(file.path, flags)
            ?: updateError("APK_IDENTITY", "无法识别安装包")
        if (apk.packageName != manifest.packageName ||
            versionCode(apk) != manifest.versionCode.toLong() ||
            apk.versionName != manifest.versionName
        ) {
            updateError("APK_IDENTITY", "安装包身份与更新清单不一致")
        }
        val installed = context.packageManager
            .getPackageInfo(context.packageName, flags)
        if (signatures(installed).isEmpty() ||
            signatures(apk) != signatures(installed)
        ) {
            updateError("APK_SIGNATURE", "安装包不是当前应用的官方签名")
        }
    }

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> {
        val signers = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        return signers?.map { it.toCharsString() }?.toSet().orEmpty()
    }

    fun installReady() {
        scope.launch {
            commands.withLock {
                if (mutable.value.phase != Phase.READY) return@withLock
                val manifest = task ?: return@withLock
                try {
                    verifyApk(manifest, completed)
                    val current = getCurrentVersionCode()
                    if (manifest.versionCode < current ||
                        manifest.versionCode == current
                    ) {
                        updateError("APK_DOWNGRADE", "不能安装相同或较旧版本")
                    }
                    withContext(Dispatchers.Main) {
                        if (Build.VERSION.SDK_INT >= 26 &&
                            !context.packageManager.canRequestPackageInstalls()
                        ) {
                            mutable.update {
                                it.copy(message = "请允许安装此来源，返回后点击安装即可")
                            }
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:${context.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } else {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileProvider",
                                completed
                            )
                            val installIntent = Intent(Intent.ACTION_VIEW)
                                .setDataAndType(
                                    uri,
                                    "application/vnd.android.package-archive"
                                )
                                .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            // 只把读取授权交给系统安装器，避免第三方“安装器”应用从选择器抢走 APK
                            val installer = context.packageManager.resolveActivity(
                                installIntent,
                                PackageManager.MATCH_SYSTEM_ONLY or PackageManager.MATCH_DEFAULT_ONLY
                            )?.activityInfo?.packageName
                                ?: updateError("INSTALL_FAILED", "未找到系统安装器")
                            installIntent.setPackage(installer)
                            context.startActivity(installIntent)
                        }
                    }
                } catch (e: Exception) {
                    mutable.update {
                        it.copy(
                            message = (e as? UpdateFailure)?.message
                                ?: "无法打开安装器，请重试",
                            errorCode = (e as? UpdateFailure)?.code ?: "INSTALL_FAILED"
                        )
                    }
                }
            }
        }
    }

    private fun sources(): List<String> =
        testSources?.invoke(channel) ?: UpdateConfig.sources

    private fun cacheFile(): AtomicFile =
        AtomicFile(File(directory, "$channel-manifest.json"))

    private fun cached(): UpdateManifest? = runCatching {
        UpdateManifestVerifier.verify(
            cacheFile().readFully().toString(Charsets.UTF_8),
            keys,
            channel
        )
    }.getOrNull()

    private fun write(file: AtomicFile, text: String) {
        val output = file.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }

    private suspend fun fetch(url: String): UpdateManifest {
        var target = url
        val etagKey = "etag:$url"
        val stored = cached()
        repeat(6) {
            if (!allowedUrl(target)) updateError("MANIFEST_URL", "更新地址不受支持")
            val builder = Request.Builder().url(target).header("Cache-Control", "no-cache")
            // 仅当当前缓存信封正是该 URL 上次 ETag 对应的信封时才附带 If-None-Match，避免主备双源互相踩出 304 失败
            if (stored != null && prefs.getString("etag-payload:$url", null) == stored.envelope) {
                prefs.getString(etagKey, null)?.let { builder.header("If-None-Match", it) }
            }
            val call = http.newCall(builder.build())
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        if (cont.isActive) cont.resumeWith(Result.failure(e))
                    }

                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        if (cont.isActive) cont.resumeWith(Result.success(response))
                        else response.close()
                    }
                })
            }.use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    target = response.header("Location")
                        ?.let { response.request.url.resolve(it)?.toString() }
                        ?: updateError("MANIFEST_REDIRECT", "更新地址跳转失败")
                    return@use
                }
                if (response.code == 304 && stored != null &&
                    prefs.getString("etag-payload:$url", null) == stored.envelope
                ) {
                    return stored
                }
                if (response.code == 404 || response.code == 410) {
                    updateError("MANIFEST_ABSENT", "更新清单尚未发布")
                }
                if (!response.isSuccessful) {
                    updateError(
                        "MANIFEST_HTTP",
                        "更新来源暂时不可用（HTTP ${response.code}）"
                    )
                }
                val contentLength = response.body?.contentLength() ?: -1L
                if (contentLength > MAX_MANIFEST_BYTES) {
                    updateError("MANIFEST_SIZE", "更新清单过大")
                }
                val bytes = response.body?.byteStream()?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (output.size() + n > MAX_MANIFEST_BYTES) {
                            updateError("MANIFEST_SIZE", "更新清单过大")
                        }
                        output.write(buffer, 0, n)
                    }
                    output.toByteArray()
                } ?: updateError("MANIFEST_EMPTY", "更新来源没有返回清单")

                val result = UpdateManifestVerifier.verify(
                    bytes.toString(Charsets.UTF_8), keys, channel
                )
                response.header("ETag")?.let {
                    prefs.edit()
                        .putString(etagKey, it)
                        .putString("etag-payload:$url", result.envelope)
                        .apply()
                }
                return result
            }
        }
        updateError("MANIFEST_REDIRECT", "更新地址跳转过多")
    }

    private fun online(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.getNetworkCapabilities(manager.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun persistTask() {
        val manifest = task ?: return
        val json = JSONObject()
            .put("envelope", manifest.envelope)
            .put("channel", manifest.channel)
            .put("identity", manifest.identity)
            .put("mirror", taskMirror ?: "")
            .put("etag", taskEtag ?: "")
            .put("ready", mutable.value.phase == Phase.READY)
        write(taskRecord, json.toString())
    }

    private fun restoreTask() {
        runCatching {
            val json = JSONObject(taskRecord.readFully().toString(Charsets.UTF_8))
            val original = UpdateManifestVerifier.verify(
                json.getString("envelope"), keys, channel
            )
            if (json.optString("identity") != original.identity) {
                updateError("APK_IDENTITY", "下载记录与签名安装包不一致")
            }
            if (original.versionCode <= getCurrentVersionCode()) {
                partial.delete()
                completed.delete()
                taskRecord.delete()
                return
            }
            task = original
            taskMirror = json.optString("mirror").ifBlank { null }
            taskEtag = json.optString("etag").ifBlank { null }
            val ready = completed.exists() &&
                runCatching { verifyApk(original, completed) }.isSuccess
            mutable.update {
                it.copy(
                    manifest = original,
                    phase = if (ready) Phase.READY else Phase.PAUSED,
                    bytes = if (ready) original.size else partial.length(),
                    message = if (ready) "安装包已就绪" else "上次下载已暂停，可继续下载"
                )
            }
        }
    }

    internal fun close() {
        transfer.cancel()
        scope.cancel()
    }
}
