package com.zcshou.gogogo.run.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 路线口令 ↔ 二维码。
 *
 * 生成侧把 [RouteShareCodec] 的整段口令直接编进二维码（不再套一层压缩，
 * 口令本身就是已压缩的 Base64），解码侧用 zxing 纯 Java 读取器扫描
 * 用户从系统图片选择器挑来的截图 —— 全程零相机权限、零联网。
 */
object RouteQrCodec {

    /** 单个二维码能塞下的口令长度上限：超过就只走文本口令，不硬挤成一张糊图 */
    const val MAX_PAYLOAD_CHARS = 2_800

    private const val QUIET_ZONE_MODULES = 2

    /** 深色模块用近黑，浅色底用纯白：保证在任意背景上的对比度 */
    private const val MODULE_DARK = 0xFF0B1220.toInt()
    private const val MODULE_LIGHT = 0xFFFFFFFF.toInt()

    /**
     * 生成二维码位图。
     *
     * @param sizePx 目标边长（正方形），会被对齐到模块整数倍以避免摩尔纹
     * @param rounded 是否把每个模块画成圆角小方块（深海光学风格的柔和感）
     * @return 口令过长或编码失败时返回 null，调用方回退到纯文本口令
     */
    fun encode(
        payload: String,
        sizePx: Int = 720,
        rounded: Boolean = true,
        darkColor: Int = MODULE_DARK,
        lightColor: Int = MODULE_LIGHT
    ): Bitmap? {
        if (payload.isBlank() || payload.length > MAX_PAYLOAD_CHARS) return null
        val matrix = runCatching {
            MultiFormatWriter().encode(
                payload,
                BarcodeFormat.QR_CODE,
                0,
                0,
                mapOf(
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
                    EncodeHintType.CHARACTER_SET to "UTF-8"
                )
            )
        }.getOrNull() ?: return null

        val modules = matrix.width
        if (modules <= 0) return null
        val target = sizePx.coerceIn(160, 2048)
        // 每个模块取整数像素：避免非整数缩放导致模块边缘糊成灰
        val modulePx = (target / modules).coerceAtLeast(1)
        val actual = modulePx * modules

        val bitmap = Bitmap.createBitmap(actual, actual, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(lightColor)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = darkColor }
        if (rounded) {
            val radius = modulePx * 0.32f
            val rect = RectF()
            for (y in 0 until modules) {
                for (x in 0 until modules) {
                    if (!matrix.get(x, y)) continue
                    val left = (x * modulePx).toFloat()
                    val top = (y * modulePx).toFloat()
                    rect.set(left, top, left + modulePx, top + modulePx)
                    canvas.drawRoundRect(rect, radius, radius, paint)
                }
            }
        } else {
            val path = Path()
            for (y in 0 until modules) {
                for (x in 0 until modules) {
                    if (!matrix.get(x, y)) continue
                    path.addRect(
                        (x * modulePx).toFloat(),
                        (y * modulePx).toFloat(),
                        ((x + 1) * modulePx).toFloat(),
                        ((y + 1) * modulePx).toFloat(),
                        Path.Direction.CW
                    )
                }
            }
            canvas.drawPath(path, paint)
        }
        return bitmap
    }

    /**
     * 从相册截图解码口令。
     *
     * 先按原尺寸尝试一次；失败再降采样一次（截图里二维码常常只占屏幕一小块，
     * 直接缩小反而会丢模块，所以降采样只在原尺寸失败后进行）。
     */
    fun decode(bitmap: Bitmap): String? {
        decodeAt(bitmap)?.let { return it }
        val scaled = runCatching {
            val maxSide = maxOf(bitmap.width, bitmap.height)
            if (maxSide <= 1_200) return null
            val ratio = 1_200f / maxSide
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        }.getOrNull() ?: return null
        return decodeAt(scaled)
    }

    /** 从口令文本里直接解出路线（二维码解出的内容可能还带着分享文案） */
    fun decodeToRoute(payload: String): RouteShareCodec.SharedRoute? {
        val code = RouteShareCodec.extractCode(payload) ?: payload.trim()
        return RouteShareCodec.decode(code)
    }

    private fun decodeAt(bitmap: Bitmap): String? = runCatching {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        // zxing 的 RGBLuminanceSource 期望 ARGB 打包成 Int
        val source = RGBLuminanceSource(width, height, pixels)
        val binary = BinaryBitmap(HybridBinarizer(source))
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                    DecodeHintType.CHARACTER_SET to "UTF-8"
                )
            )
        }
        reader.decodeWithState(binary)?.text
    }.getOrNull()
}
