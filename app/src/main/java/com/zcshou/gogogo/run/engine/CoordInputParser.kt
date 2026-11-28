package com.zcshou.gogogo.run.engine

/**
 * 搜索框坐标直输解析（纯 Kotlin，可 JVM 单测）。
 *
 * 支持：
 * - 十进制：`39.9042,116.4074` / `39.9042 116.4074` / `39.9042;116.4074`
 * - 度分秒：`39°54'15"N 116°24'27"E` / `39度54分15秒 116度24分27秒`
 * - 方位前缀：`北纬39.9042 东经116.4074` / `N39.9042 E116.4074`
 *
 * 中国大陆常见「经度填在前」会被自动纠正并置 [Parsed.swapped]（UI 据此提示），
 * 越界坐标一律 [OutOfRange] 拒收，绝不把 300 这种值当地图坐标用。
 */
object CoordInputParser {

    sealed class Result {
        /** [swapped] = true 表示输入疑似经纬度反写，已按纬度在前纠正 */
        data class Parsed(val lat: Double, val lng: Double, val swapped: Boolean = false) : Result()

        /** 看起来不是坐标：交给地图搜索继续处理 */
        object NotCoordinate : Result()

        /** 是坐标形状但越界 */
        object OutOfRange : Result()
    }

    private val DMS_TOKEN = Regex(
        """([0-9]+(?:\.[0-9]+)?)\s*(?:°|度)\s*""" +
            """(?:([0-9]+(?:\.[0-9]+)?)\s*(?:'|′|分)\s*)?""" +
            """(?:([0-9]+(?:\.[0-9]+)?)\s*(?:"|″|秒)\s*)?""" +
            """([NSEWnsew北南东西]?)"""
    )

    private val PREFIX_TOKEN = Regex(
        """(北纬|南纬|东经|西经|[NnSsEeWw])\s*([0-9]+(?:\.[0-9]+)?)"""
    )

    private val DECIMAL_SEPARATORS = Regex("""[\s,;/|]+""")
    private val NUMBER_PATTERN = Regex("""^[+-]?[0-9]+(?:\.[0-9]+)?$""")

    fun parse(raw: String): Result {
        val text = normalize(raw)
        if (text.length < 3) return Result.NotCoordinate
        parseDms(text)?.let { return it }
        parsePrefixed(text)?.let { return it }
        return parseDecimal(text)
    }

    /** 全角逗号/分号/空格统一成半角，避免中文输入法下解析失败 */
    private fun normalize(raw: String): String = buildString(raw.length) {
        raw.trim().forEach { ch ->
            append(
                when (ch) {
                    '，', '、' -> ','
                    '；' -> ';'
                    '　' -> ' '
                    '＋' -> '+'
                    '－', '−' -> '-'
                    else -> ch
                }
            )
        }
    }

    private fun parseDms(text: String): Result? {
        val matches = DMS_TOKEN.findAll(text).toList()
        if (matches.size < 2) return null
        val tokens = matches.take(2).map { match ->
            val degrees = match.groupValues[1].toDoubleOrNull() ?: return null
            val minutes = match.groupValues[2].toDoubleOrNull() ?: 0.0
            val seconds = match.groupValues[3].toDoubleOrNull() ?: 0.0
            val dir = match.groupValues[4]
            val magnitude = degrees + minutes / 60.0 + seconds / 3600.0
            val axis = axisOf(dir)
            NumberToken(
                value = if (axis?.negative == true) -magnitude else magnitude,
                axis = axis?.axis
            )
        }
        // 两个同轴方向（如「39°54'N 45°30'N」）不是有效坐标：
        // 不加守卫会把第二个纬度静默当经度用（与 parsePrefixed 同一守卫口径）
        if (tokens[0].axis != null && tokens[0].axis == tokens[1].axis) return null
        return assemble(tokens[0], tokens[1])
    }

    private fun parsePrefixed(text: String): Result? {
        val matches = PREFIX_TOKEN.findAll(text).toList()
        if (matches.size < 2) return null
        val tokens = matches.take(2).map { match ->
            val magnitude = match.groupValues[2].toDoubleOrNull() ?: return null
            val axis = axisOf(match.groupValues[1])
            NumberToken(
                value = if (axis?.negative == true) -magnitude else magnitude,
                axis = axis?.axis
            )
        }
        // 两个同轴前缀（如「北纬…北纬…」）不是有效坐标
        if (tokens[0].axis != null && tokens[0].axis == tokens[1].axis) return null
        return assemble(tokens[0], tokens[1])
    }

    private fun parseDecimal(text: String): Result {
        val parts = text.split(DECIMAL_SEPARATORS)
            .filter { it.isNotEmpty() }
        if (parts.size != 2) return Result.NotCoordinate
        if (!parts.all { it.matches(NUMBER_PATTERN) }) return Result.NotCoordinate
        val first = parts[0].toDoubleOrNull() ?: return Result.NotCoordinate
        val second = parts[1].toDoubleOrNull() ?: return Result.NotCoordinate
        return assemble(NumberToken(first, null), NumberToken(second, null))
    }

    /**
     * 组装经纬度：
     * - 明确轴（N/S/E/W 或北纬/东经）优先；
     * - 无轴时按「纬度在前」的通用写法，命中中国经纬度区间且反向也成立才纠正。
     *
     * 只要输入顺序与「纬度在前」相反（包括显式标注轴的 LNG-first 写法），
     * 都置 [Result.Parsed.swapped]，UI 的「已自动纠正顺序」提示才口径一致。
     */
    private fun assemble(first: NumberToken, second: NumberToken): Result {
        val firstAxis = first.axis
        val secondAxis = second.axis
        if (firstAxis != null && secondAxis != null && firstAxis != secondAxis) {
            val lat = if (firstAxis == Axis.LAT) first.value else second.value
            val lng = if (firstAxis == Axis.LNG) first.value else second.value
            return validate(lat, lng, swapped = firstAxis == Axis.LNG)
        }
        if (firstAxis != null || secondAxis != null) {
            val lat = if (firstAxis == Axis.LAT) first.value else second.value
            val lng = if (firstAxis == Axis.LNG) first.value else second.value
            if (firstAxis == Axis.LNG) {
                // 「东经 116.4 39.9」：第二个数没写轴，补成纬度（LNG 在前 = 已纠正）
                return validate(second.value, first.value, swapped = true)
            }
            if (secondAxis == Axis.LAT) {
                // 「39.9 北纬…」这类错序输入：按纬度在前重排
                return validate(second.value, first.value, swapped = true)
            }
            return validate(lat, lng, swapped = false)
        }
        return assembleByConvention(first.value, second.value)
    }

    private fun assembleByConvention(first: Double, second: Double): Result {
        val normalOk = first in -90.0..90.0 && second in -180.0..180.0
        val reversedOk = second in -90.0..90.0 && first in -180.0..180.0
        // 中国大陆经度区间 73~136、纬度区间 3~54：两者同时命中几乎只可能是反写
        val chinaHint = first in 73.0..136.0 && second in 3.0..54.0
        return when {
            chinaHint && reversedOk -> validate(second, first, swapped = true)
            normalOk -> validate(first, second, swapped = false)
            reversedOk -> validate(second, first, swapped = true)
            else -> Result.OutOfRange
        }
    }

    private fun validate(lat: Double, lng: Double, swapped: Boolean): Result = when {
        lat !in -90.0..90.0 || lng !in -180.0..180.0 -> Result.OutOfRange
        lat == 0.0 && lng == 0.0 -> Result.OutOfRange
        else -> Result.Parsed(lat, lng, swapped)
    }

    private enum class Axis { LAT, LNG }

    private data class Direction(val axis: Axis, val negative: Boolean)

    private data class NumberToken(val value: Double, val axis: Axis?)

    private fun axisOf(raw: String): Direction? = when (raw) {
        "N", "n", "北纬" -> Direction(Axis.LAT, false)
        "S", "s", "南纬" -> Direction(Axis.LAT, true)
        "E", "e", "东经", "东" -> Direction(Axis.LNG, false)
        "W", "w", "西经", "西" -> Direction(Axis.LNG, true)
        "北" -> Direction(Axis.LAT, false)
        "南" -> Direction(Axis.LAT, true)
        else -> null
    }
}
