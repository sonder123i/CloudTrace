package com.zcshou.gogogo.run.ui.home

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.zcshou.gogogo.run.engine.RunSplit
import com.zcshou.gogogo.run.engine.TrackMathUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 「深海光学战报海报」离屏渲染 + 分享/保存。
 *
 * 隐私红线：海报只呈现**相对几何轨迹**、脱敏标题与聚合数据，
 * 绝不写入任何经纬度数字、街道名或路线名（可能包含地点信息）。
 */
object ShareCardRenderer {

    const val WIDTH = 1080
    const val HEIGHT = 1600
    const val SHARE_DIR = "share-cards"

    // 海报自带一套光学配色（分享图不随昼夜主题变化，故不引用 UI 颜色令牌）
    private const val BG_TOP = 0xFF080E18.toInt()
    private const val BG_MID = 0xFF0B1524.toInt()
    private const val BG_BOTTOM = 0xFF05090F.toInt()
    private const val AURORA_CYAN = 0x225CE1E6
    private const val AURORA_VIOLET = 0x22B388FF
    private const val GRID_LINE = 0x0CFFFFFF
    private const val TEXT_PRIMARY = 0xFFF5F7FA.toInt()
    private const val TEXT_SECONDARY = 0xFF9AA4B2.toInt()
    private const val TEXT_TERTIARY = 0xFF67707C.toInt()
    private const val ACCENT_ICE = 0xFF5CE1E6.toInt()
    private const val ACCENT_TEAL = 0xFF34D399.toInt()
    private const val ACCENT_INDIGO = 0xFF8B9CF6.toInt()

    /**
     * 下面所有坐标与字号已按 1080×1600 画布的**像素**直接设计
     * （页脚基线 1512 < HEIGHT，轨迹区 700–1120，分段区 1210–1330），
     * 因此缩放系数必须是 1f；任何非 1 的系数都会把下半张海报挤出画布。
     */
    private const val U = 1f

    data class Payload(
        val startedAt: Long,
        val distanceKm: Double,
        val durationSec: Int,
        val averagePaceSec: Int,
        val elevationGainMeters: Double,
        val points: List<TrackMathUtils.LatLng>,
        val laps: Int,
        /** true=绕圈（轨迹首尾闭合可连回起点），false=原路折返（绝不能伪造闭合边） */
        val isClosedLoop: Boolean,
        val splits: List<RunSplit> = emptyList(),
        val strategyLabel: String? = null,
        /** 伴侣模式命中的彩蛋金句（脱敏，仅一句文案） */
        val mascotCaption: String? = null
    )

    /** 离屏绘制；请在 Dispatchers.Default 调用（不做任何 UI / 资源反射） */
    fun render(payload: Payload): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, WIDTH.toFloat() * 0.4f, HEIGHT.toFloat(),
            intArrayOf(BG_TOP, BG_MID, BG_BOTTOM),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        paint.shader = null
        drawBackdrop(canvas, paint)
        drawHeader(canvas, paint, payload)
        drawHero(canvas, paint, payload)
        drawTrack(canvas, paint, payload)
        drawSplits(canvas, paint, payload)
        drawFooter(canvas, paint, payload)
        return bitmap
    }

    /** 极光光斑 + 72dp 参照网格暗纹（给海报一层「深海光学」纵深） */
    private fun drawBackdrop(canvas: Canvas, paint: Paint) {
        paint.style = Paint.Style.FILL
        paint.color = AURORA_CYAN
        paint.maskFilter = BlurMaskFilter(190f * U, BlurMaskFilter.Blur.NORMAL)
        canvas.drawCircle(WIDTH * 0.16f, HEIGHT * 0.10f, 300f * U, paint)
        paint.color = AURORA_VIOLET
        canvas.drawCircle(WIDTH * 0.92f, HEIGHT * 0.30f, 260f * U, paint)
        paint.maskFilter = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * U
        paint.color = GRID_LINE
        val step = 72f * U
        var x = step
        while (x < WIDTH) {
            canvas.drawLine(x, 0f, x, HEIGHT.toFloat(), paint)
            x += step
        }
        var y = step
        while (y < HEIGHT) {
            canvas.drawLine(0f, y, WIDTH.toFloat(), y, paint)
            y += step
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawHeader(canvas: Canvas, paint: Paint, payload: Payload) {
        paint.color = ACCENT_ICE
        paint.textSize = 26f * U
        paint.typeface = android.graphics.Typeface.create(
            "sans-serif-medium", android.graphics.Typeface.NORMAL
        )
        canvas.drawText("云迹 · CLOUDTRACE", 90f * U, 130f * U, paint)

        paint.color = TEXT_PRIMARY
        paint.textSize = 62f * U
        paint.typeface = android.graphics.Typeface.create(
            "sans-serif", android.graphics.Typeface.BOLD
        )
        canvas.drawText("跑步战报", 90f * U, 210f * U, paint)

        paint.color = TEXT_SECONDARY
        paint.textSize = 26f * U
        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.fontFeatureSettings = "tnum"
        val date = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.CHINA).format(Date(payload.startedAt))
        canvas.drawText(date, 90f * U, 254f * U, paint)
        payload.strategyLabel?.let { label ->
            paint.color = ACCENT_TEAL
            canvas.drawText("· $label 配速策略", 90f * U + 300f * U, 254f * U, paint)
        }
    }

    private fun drawHero(canvas: Canvas, paint: Paint, payload: Payload) {
        paint.color = TEXT_PRIMARY
        paint.fontFeatureSettings = "tnum"
        paint.typeface = android.graphics.Typeface.create(
            "sans-serif", android.graphics.Typeface.BOLD
        )
        paint.textSize = 170f * U
        val distance = String.format(Locale.US, "%.2f", payload.distanceKm)
        canvas.drawText(distance, 90f * U, 470f * U, paint)
        val width = paint.measureText(distance)
        paint.textSize = 56f * U
        paint.color = TEXT_SECONDARY
        canvas.drawText("km", 90f * U + width + 14f * U, 470f * U, paint)

        // 三项聚合数据
        val stats = arrayOf(
            "用时" to formatDuration(payload.durationSec),
            "平均配速" to formatPace(payload.averagePaceSec),
            "累计爬升" to String.format(Locale.US, "+%.1f m", payload.elevationGainMeters)
        )
        var x = 90f * U
        stats.forEach { (label, value) ->
            paint.textSize = 24f * U
            paint.color = TEXT_TERTIARY
            paint.typeface = android.graphics.Typeface.DEFAULT
            canvas.drawText(label, x, 560f * U, paint)
            paint.textSize = 44f * U
            paint.color = TEXT_PRIMARY
            paint.typeface = android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL
            )
            canvas.drawText(value, x, 616f * U, paint)
            x += 300f * U
        }
    }

    /**
     * 双层光纤多圈轨迹：只做相对几何归一化，绝不标注方位/经纬度。
     */
    private fun drawTrack(canvas: Canvas, paint: Paint, payload: Payload) {
        if (payload.points.size < 3) return
        val areaLeft = 90f * U
        val areaTop = 700f * U
        val areaWidth = WIDTH - 180f * U
        val areaHeight = 420f * U
        val lat0 = payload.points.sumOf { it.lat } / payload.points.size
        val lng0 = payload.points.sumOf { it.lng } / payload.points.size
        val mx = 111320.0 * cos(Math.toRadians(lat0))
        val my = 110574.0
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        val xs = FloatArray(payload.points.size)
        val ys = FloatArray(payload.points.size)
        payload.points.forEachIndexed { index, point ->
            val px = ((point.lng - lng0) * mx).toFloat()
            val py = ((point.lat - lat0) * my).toFloat()
            xs[index] = px
            ys[index] = py
            if (px < minX) minX = px
            if (px > maxX) maxX = px
            if (py < minY) minY = py
            if (py > maxY) maxY = py
        }
        val spanX = (maxX - minX).coerceAtLeast(1f)
        val spanY = (maxY - minY).coerceAtLeast(1f)
        val scale = min(areaWidth / spanX, areaHeight / spanY)
        val offsetX = areaLeft + (areaWidth - spanX * scale) / 2f
        val offsetY = areaTop + (areaHeight - spanY * scale) / 2f

        val lapCount = payload.laps.coerceIn(1, 6)
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        val glow = BlurMaskFilter(9f * U, BlurMaskFilter.Blur.NORMAL)
        for (lap in 0 until lapCount) {
            val lane = if (lapCount <= 1) 0f else ((lap - (lapCount - 1) / 2f) * 3.2f * U)
            val path = Path()
            val count = xs.size
            for (i in 0 until count) {
                // 开放折返路线没有「下一段」绕回起点：末点方向沿用 incoming 段，
                // 避免车道偏移突跳；闭环保持模运算取 next
                val next = if (payload.isClosedLoop) (i + 1) % count else (i + 1).coerceAtMost(count - 1)
                val dx: Float
                val dy: Float
                if (!payload.isClosedLoop && i == count - 1 && count >= 2) {
                    dx = xs[i] - xs[i - 1]
                    dy = ys[i] - ys[i - 1]
                } else {
                    dx = xs[next] - xs[i]
                    dy = ys[next] - ys[i]
                }
                val len = kotlin.math.hypot(dx, dy).coerceAtLeast(0.01f)
                val px = offsetX + (xs[i] - dy / len * lane - minX) * scale
                val py = offsetY + (maxY - (ys[i] + dx / len * lane)) * scale
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            // 仅闭环才连回首尾：开放路线画闭合边会把折返跑伪装成环线
            if (payload.isClosedLoop) path.close()
            val fraction = if (lapCount <= 1) 0f else lap.toFloat() / (lapCount - 1)
            paint.color = blend(ACCENT_ICE, ACCENT_TEAL, fraction, 70)
            paint.strokeWidth = 12f * U
            paint.maskFilter = glow
            canvas.drawPath(path, paint)
            paint.color = blend(ACCENT_ICE, ACCENT_TEAL, fraction, 230)
            paint.strokeWidth = 3f * U
            paint.maskFilter = null
            canvas.drawPath(path, paint)
        }
        paint.style = Paint.Style.FILL
    }

    /** 分段配速迷你条：快段偏冰青，慢段偏靛蓝 */
    private fun drawSplits(canvas: Canvas, paint: Paint, payload: Payload) {
        val splits = payload.splits
        if (splits.isEmpty()) return
        val shown = min(splits.size, 10)
        val left = 90f * U
        val right = WIDTH - 90f * U
        val baseY = 1330f * U
        val maxHeight = 120f * U
        val gap = 10f * U
        val barWidth = ((right - left) - gap * (shown - 1)) / shown
        var fastest = Int.MAX_VALUE
        var slowest = 0
        splits.forEach {
            if (it.paceSec < fastest) fastest = it.paceSec
            if (it.paceSec > slowest) slowest = it.paceSec
        }
        val span = (slowest - fastest).coerceAtLeast(1)
        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.textSize = 22f * U
        paint.color = TEXT_TERTIARY
        paint.fontFeatureSettings = "tnum"
        canvas.drawText("分段配速", left, baseY - maxHeight - 24f * U, paint)
        for (i in 0 until shown) {
            val split = splits[i]
            val level = 0.45f + 0.55f * (1f - (split.paceSec - fastest).toFloat() / span)
            val height = maxHeight * level
            val x = left + i * (barWidth + gap)
            paint.color = blend(ACCENT_TEAL, ACCENT_INDIGO, (split.paceSec - fastest).toFloat() / span, 235)
            canvas.drawRoundRect(x, baseY - height, x + barWidth, baseY, 6f * U, 6f * U, paint)
        }
    }

    private fun drawFooter(canvas: Canvas, paint: Paint, payload: Payload) {
        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.textSize = 22f * U
        paint.color = TEXT_TERTIARY
        paint.fontFeatureSettings = "tnum"
        canvas.drawText(
            "仅含相对几何轨迹 · 不含经纬度与街道名",
            90f * U, 1470f * U, paint
        )
        paint.textSize = 24f * U
        paint.color = TEXT_SECONDARY
        canvas.drawText("云迹 · 离线拟真跑圈", 90f * U, 1512f * U, paint)

        payload.mascotCaption?.let { caption ->
            // 拍立得小印章：右下角圆角卡片 + 一句伴侣金句
            val stampLeft = WIDTH - 90f * U - 380f * U
            val stampTop = 1400f * U
            paint.color = 0x1AFFFFFF
            canvas.drawRoundRect(
                stampLeft, stampTop, WIDTH - 90f * U, stampTop + 118f * U,
                18f * U, 18f * U, paint
            )
            paint.color = ACCENT_ICE
            paint.textSize = 22f * U
            canvas.drawText("鲸鲸的战报印章", stampLeft + 22f * U, stampTop + 38f * U, paint)
            paint.color = TEXT_PRIMARY
            paint.textSize = 24f * U
            canvas.drawText(caption.take(14), stampLeft + 22f * U, stampTop + 80f * U, paint)
        }
    }

    // ---------------------------------------------------------------- 导出

    /** 写入 cacheDir/share-cards/：只经 provider_paths 放行的专用子目录对外暴露 */
    fun writeToCache(context: Context, bitmap: Bitmap): File? = runCatching {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        val name = "cloudtrace-report-${System.currentTimeMillis()}.png"
        val file = File(dir, name)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        file
    }.getOrNull()

    /** 系统分享面板：FileProvider content:// + 临时读权限 */
    fun share(context: Context, bitmap: Bitmap): Boolean {
        val file = writeToCache(context, bitmap) ?: return false
        val uri: Uri = runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileProvider",
                file
            )
        }.getOrNull() ?: return false
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.startActivity(
                Intent.createChooser(intent, "分享战报海报")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
    }

    /**
     * 保存到相册：API 29+ 走 MediaStore（零权限）；API 27–28 退到应用专属
     * 图片目录并触发媒体扫描（不申请 WRITE_EXTERNAL_STORAGE，守住隐私底线）。
     *
     * @return 成功时返回给用户看的路径/相册说明，失败返回 null
     */
    suspend fun saveToGallery(context: Context, bitmap: Bitmap): String? =
        withContext(Dispatchers.IO) {
            val name = "cloudtrace-report-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.png"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    val resolver = context.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/CloudTrace"
                        )
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                    ) ?: return@runCatching null
                    resolver.openOutputStream(uri)?.use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    "已保存到相册 · Pictures/CloudTrace"
                }.getOrNull()
            } else {
                runCatching {
                    val dir = File(
                        context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                        "CloudTrace"
                    ).apply { mkdirs() }
                    val file = File(dir, name)
                    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    MediaScannerConnection.scanFile(
                        context, arrayOf(file.absolutePath), arrayOf("image/png"), null
                    )
                    "已保存到 ${file.parent}"
                }.getOrNull()
            }
        }

    private fun formatDuration(seconds: Int): String {
        val safe = seconds.coerceAtLeast(0)
        val hour = safe / 3600
        val minute = (safe % 3600) / 60
        val second = safe % 60
        return if (hour > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hour, minute, second)
        } else {
            String.format(Locale.US, "%02d:%02d", minute, second)
        }
    }

    private fun formatPace(secPerKm: Int): String =
        if (secPerKm > 0) String.format(Locale.US, "%d'%02d\"", secPerKm / 60, secPerKm % 60) else "--"

    private fun blend(from: Int, to: Int, fraction: Float, alpha: Int): Int {
        val f = fraction.coerceIn(0f, 1f)
        return Color.argb(
            alpha,
            (Color.red(from) + (Color.red(to) - Color.red(from)) * f).roundToInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * f).roundToInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f).roundToInt()
        )
    }
}
