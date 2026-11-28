package com.zcshou.gogogo.run.engine

import com.zcshou.gogogo.run.BrandConfig
import com.zcshou.gogogo.run.engine.TrackMathUtils.LatLng
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 纯本地路线口令：压缩 + Base64 塞进 `品牌协议://` 链接，零服务器分享给室友。
 * 格式：v1|nameBase64|顺时针0/1|lat,lng;lat,lng;...
 * 纯 Kotlin（java.util.zip/Base64），可直接 JVM 单测。
 */
object RouteShareCodec {

    private val PREFIXES = listOf(BrandConfig.SHARE_PREFIX, BrandConfig.LEGACY_PREFIX)
    private const val MAX_DECODE_BYTES = 4 * 1024 * 1024
    /** 粘贴整段聊天记录的防御上限：正常口令远小于此，超过即视为误粘贴直接拒绝。 */
    private const val MAX_INPUT_CHARS = 256 * 1024
    /** 路线名入库与 UI 展示的长度上限。 */
    private const val MAX_NAME_CHARS = 64

    private val TRAILING_PUNCTUATION = charArrayOf(
        '。', '，', ',', '.', ';', '；', '!', '！', '?', '？',
        ')', '）', ']', '】', '}', '》', '>', '"', '\'', '、'
    )

    data class SharedRoute(
        val name: String,
        val points: List<LatLng>,
        val clockwise: Boolean
    )

    /**
     * 从聊天消息 / 剪贴板整段文本中拎出第一条口令。
     * 兼容更名前后的两种协议前缀；口令本身只含 URL-safe Base64 字符，
     * 遇到空白或中文标点即视为结束。
     */
    fun extractCode(text: String): String? =
        PREFIXES.firstNotNullOfOrNull { prefix -> extractWith(text, prefix) }

    private fun extractWith(text: String, prefix: String): String? {
        val trimmed = text.trim()
        var fromIndex = 0
        while (fromIndex < trimmed.length) {
            val start = trimmed.indexOf(prefix, startIndex = fromIndex, ignoreCase = true)
            if (start < 0) return null
            val token = trimmed.substring(start + prefix.length)
                .takeWhile { !it.isWhitespace() }
                .trimEnd(*TRAILING_PUNCTUATION)
            if (token.isNotEmpty()) return prefix + token
            fromIndex = start + prefix.length
        }
        return null
    }

    fun encode(name: String, points: List<LatLng>, clockwise: Boolean): String {
        require(points.size >= 2) { "至少 2 个点" }
        val name64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(name.toByteArray(Charsets.UTF_8))
        val coords = points.joinToString(";") {
            String.format(Locale.US, "%.6f,%.6f", it.lat, it.lng)
        }
        val payload = "v1|$name64|${if (clockwise) 1 else 0}|$coords"
        val compressed = ByteArrayOutputStream().use { bos ->
            GZIPOutputStream(bos).use { gzip ->
                gzip.write(payload.toByteArray(Charsets.UTF_8))
            }
            bos.toByteArray()
        }
        return PREFIXES.first() + Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
    }

    fun decode(text: String): SharedRoute? = runCatching {
        if (text.length > MAX_INPUT_CHARS) return null
        // 分享时会把口令夹在说明文字里发出；导入侧要允许直接粘贴整段聊天内容。
        val code = extractCode(text)
        val raw = if (code != null) {
            val prefix = PREFIXES.firstOrNull { code.startsWith(it, ignoreCase = true) } ?: return null
            code.substring(prefix.length)
        } else {
            text.trim().filterNot { it.isWhitespace() }.takeIf { it.isNotEmpty() } ?: return null
        }
        val compressed = decodeBase64(raw) ?: return null
        val payload = gunzip(compressed) ?: return null
        val parts = payload.split("|", limit = 4)
        if (parts.size != 4 || parts[0] != "v1") return null
        val name = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
        val clockwise = parts[2] == "1"
        val points = parts[3].split(";").mapNotNull { pair ->
            val xy = pair.split(",")
            if (xy.size != 2) return@mapNotNull null
            val lat = xy[0].toDoubleOrNull() ?: return@mapNotNull null
            val lng = xy[1].toDoubleOrNull() ?: return@mapNotNull null
            if (lat in -90.0..90.0 && lng in -180.0..180.0) LatLng(lat, lng) else null
        }
        if (points.size < 2) return null
        SharedRoute(
            name = name.take(MAX_NAME_CHARS).ifBlank { "分享路线" },
            points = points,
            clockwise = clockwise
        )
    }.getOrNull()

    private fun decodeBase64(raw: String): ByteArray? {
        val decoders = listOf(
            Base64.getUrlDecoder(),
            Base64.getMimeDecoder(),
            Base64.getDecoder()
        )
        decoders.forEach { decoder ->
            runCatching { decoder.decode(raw) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun gunzip(compressed: ByteArray): String? = runCatching {
        ByteArrayInputStream(compressed).use { input ->
            GZIPInputStream(input).use { gzip ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = gzip.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_DECODE_BYTES) return null
                    out.write(buffer, 0, read)
                }
                out.toString(Charsets.UTF_8.name())
            }
        }
    }.getOrNull()
}
