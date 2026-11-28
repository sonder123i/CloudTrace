package com.zcshou.gogogo.run.ui.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import com.zcshou.gogogo.R
import kotlin.math.sqrt

/**
 * 边缘流光（Border Beam）：一段彗星状高光沿圆角矩形周长绕行，外加一圈缓慢流转的色散描边。
 *
 * 对应 Google AI Mode 加载态那种「边缘在流动」的效果——彩色只出现在 1~2dp 的描边上，
 * 填充保持中性磨砂，所以白玻璃依然干净。
 *
 * ## 为什么用弧长而不是 SweepGradient 转
 *
 * 扫描渐变按**角度**等分。一个 288×40dp 的胶囊，长直边只占很小角度却要走很长距离，
 * 圆头占大角度但周长极短——转起来就是「直边慢、圆头瞬间冲过去」，一眼假。
 * 跑动的光核因此用 [PathMeasure] 按**弧长**取位置，速度在视觉上恒定。
 *
 * ## 为什么用软光斑铺贴而不是分段描边
 *
 * 早期版本把拖尾切成十几段直线逐段 stroke，段与段之间 alpha / 线宽突变，
 * 放大看有明显的阶梯。这里改成沿路径等距铺贴**软边光斑**（预渲染的径向渐变 bitmap）：
 * 相邻光斑自然重叠，形成连续衰减的彗尾，且每帧只是 drawBitmap，比 stroke 更便宜。
 *
 * ## 呼吸
 *
 * 三条不同周期的正弦叠加驱动 [breath]，调制拖尾长度、头部大小、整体亮度与色散环浓度。
 * 三个周期（3.1s / 4.7s / 2.3s）互质且都不等于绕行周期，避免「呼吸和绕行绑在一起」
 * 那种一眼就能看出规律的机械感。
 *
 * ## 性能
 *
 * 静止时不绘制；帧节拍由宿主驱动（见 FloatingCapsuleView 的 beam 帧循环），
 * 折叠态降帧、展开态全速、锁屏完全停止。
 */
class BorderBeamDrawable @JvmOverloads constructor(
    private val context: Context,
    private val cornerRadiusPx: Float,
    private val borderWidthPx: Float
) : Drawable() {

    private val density = context.resources.displayMetrics.density

    /** 0..1 的周长相位：0 = 起点，1 = 绕完一圈。 */
    var phase = 0f
        set(value) {
            field = value % 1f
            if (field < 0f) field += 1f
            invalidateSelf()
        }

    /**
     * 0..1 的色散环相位，周期与 [phase] **不同**（宿主用更慢的时长驱动）。
     *
     * 必须和光核解耦：若两者同周期，整圈色带会跟着彗尾一起转，看起来像一条被绑住的光带，
     * 少了「薄膜在底下缓慢流动」的层次感。旋转角按完整 360° 走，回绕时视觉连续。
     */
    var ringPhase = 0f
        set(value) {
            field = value % 1f
            if (field < 0f) field += 1f
            invalidateSelf()
        }

    /**
     * 呼吸量 0..1：0 = 收（最暗最小），1 = 张（最亮最大）。
     * 由宿主每帧写入，本类只负责把它映射到各个视觉参数上。
     */
    var breath = 0.5f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (kotlin.math.abs(field - clamped) < 0.002f) return
            field = clamped
            invalidateSelf()
        }

    /** 整体强度 0..1：告警/暂停等状态可整体压暗或熄灯。 */
    var intensity = 1f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field == clamped) return
            field = clamped
            invalidateSelf()
        }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidthPx
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val rect = RectF()
    private val path = Path()
    private val outerClipPath = Path()
    private val measure = PathMeasure()
    private val sweepMatrix = Matrix()
    private val headMatrix = Matrix()
    private val bloomMatrix = Matrix()
    private val stampRect = RectF()
    private val posTan = FloatArray(2)
    private var sweepShader: SweepGradient? = null
    private var lastSweepIntensity = -1f
    private var headShader: RadialGradient? = null
    private var lastHeadIntensity = -1f
    private var bloomShader: RadialGradient? = null
    private var lastBloomKey = -1f

    private var pathLength = 0f
    private var lastLeft = Int.MIN_VALUE
    private var lastTop = Int.MIN_VALUE
    private var lastRight = Int.MIN_VALUE
    private var lastBottom = Int.MIN_VALUE
    private var burstStartMs = -1L

    /** 触发一次 480ms 的交互光晕脉冲（展开控制岛/点击控制按钮时调用）。 */
    fun triggerBurst() {
        burstStartMs = android.os.SystemClock.uptimeMillis()
        invalidateSelf()
    }

    private fun currentBurstFactor(): Float {
        if (burstStartMs <= 0L) return 0f
        val elapsed = android.os.SystemClock.uptimeMillis() - burstStartMs
        if (elapsed !in 0L..480L) {
            burstStartMs = -1L
            return 0f
        }
        val t = elapsed / 480f
        val inv = 1f - t
        return inv * inv
    }

    /**
     * 谷歌 AI Mode 五色光谱流光：取**不透明**纯正色相，透明度统一在绘制时按强度/拖尾曲线乘。
     * 蓝 → 靛紫 → 珊瑚红 → 琥珀金 → 青碧绿，呈现流动玻璃棱镜质感。
     */
    private val beamColors = intArrayOf(
        opaqueOf(R.color.dr_beam_blue, 0xFF4285F4.toInt()),
        opaqueOf(R.color.dr_beam_indigo, 0xFF8B5CF6.toInt()),
        opaqueOf(R.color.dr_beam_coral, 0xFFEA4335.toInt()),
        opaqueOf(R.color.dr_beam_amber, 0xFFFBBC04.toInt()),
        opaqueOf(R.color.dr_beam_teal, 0xFF00BFA5.toInt())
    )

    /**
     * 预渲染的软光斑：白核 → 透明边缘的径向渐变。
     * 上色靠 [PorterDuffColorFilter]（SRC_IN），所以一张 bitmap 可以铺任意色相的彗尾。
     */
    private val dotBitmap: Bitmap by lazy { createSoftDot() }

    /**
     * 彗尾色阶：预建 ColorFilter 避免每帧 new 对象。
     * 步数够多（28）才能让 5 色过渡看不出分段，又不至于让 GC 有压力。
     */
    private val tintSteps = 28
    private val tintFilters: Array<ColorFilter?> by lazy {
        Array(tintSteps) { i ->
            val t = if (tintSteps <= 1) 1f else i / (tintSteps - 1).toFloat()
            PorterDuffColorFilter(colorAt(t), PorterDuff.Mode.SRC_IN)
        }
    }

    /** 拖尾占周长的比例（呼吸与交互脉冲会适度拉长）。 */
    private val tailRatio = 0.34f

    /** 每帧铺贴的光斑数：32 段让丝滑彗尾在大小面板上都浑然一体。 */
    private val stamps = 32

    override fun draw(canvas: Canvas) {
        if (intensity <= 0.01f) return
        val b = bounds
        if (b.isEmpty) return
        if (!ensurePath(b)) return

        val burst = currentBurstFactor()
        drawInnerBloom(canvas, b, burst)
        drawDispersionRing(canvas, b, burst)
        drawBeam(canvas, burst)
    }

    /** 圆角矩形路径只在尺寸变化时重建——PathMeasure 的 setPath 不便宜。 */
    private fun ensurePath(b: android.graphics.Rect): Boolean {
        if (b.left == lastLeft && b.top == lastTop &&
            b.right == lastRight && b.bottom == lastBottom && pathLength > 0f
        ) {
            return true
        }
        lastLeft = b.left
        lastTop = b.top
        lastRight = b.right
        lastBottom = b.bottom

        rect.set(b)
        val outerR = cornerRadiusPx.coerceAtMost(minOf(rect.width(), rect.height()) / 2f)
        outerClipPath.reset()
        outerClipPath.addRoundRect(rect, outerR, outerR, Path.Direction.CW)

        val inset = borderWidthPx / 2f
        val radius = (cornerRadiusPx - inset).coerceAtLeast(0f)
        path.reset()
        path.addRoundRect(
            rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset,
            radius, radius, Path.Direction.CW
        )
        measure.setPath(path, false)
        pathLength = measure.length
        return pathLength > 1f
    }

    /**
     * 局部内溢漫射光（Google AI Mode 核心质感）：只在流光头部内侧 18~28dp 范围内
     * 透出一小团柔和彩色漫射光，中心面板保持干净中性磨砂，绝不把整块玻璃染脏。
     */
    private fun drawInnerBloom(canvas: Canvas, b: android.graphics.Rect, burst: Float) {
        val headDist = normalize(phase * pathLength)
        if (!measure.getPosTan(headDist, posTan, null)) return

        val minDim = minOf(b.width(), b.height()).toFloat()
        val bloomR = (minDim * 0.55f).coerceIn(18f * density, 38f * density) * (1f + 0.35f * burst)
        val bloomStrength = (intensity * (0.14f + 0.14f * burst)).coerceIn(0f, 0.32f)
        if (bloomStrength <= 0.01f) return

        var bShader = bloomShader
        if (bShader == null || kotlin.math.abs(lastBloomKey - bloomStrength) > 0.015f) {
            bShader = RadialGradient(
                0f, 0f, 1f,
                intArrayOf(
                    withAlpha(beamColors[0], bloomStrength),
                    withAlpha(beamColors[1], bloomStrength * 0.45f),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP
            )
            bloomShader = bShader
            lastBloomKey = bloomStrength
        }

        bloomMatrix.setScale(bloomR, bloomR)
        bloomMatrix.postTranslate(posTan[0], posTan[1])
        bShader.setLocalMatrix(bloomMatrix)
        bloomPaint.shader = bShader

        val save = canvas.save()
        canvas.clipPath(outerClipPath)
        canvas.drawCircle(posTan[0], posTan[1], bloomR, bloomPaint)
        canvas.restoreToCount(save)
        bloomPaint.shader = null
    }

    /**
     * 整圈色散：SweepGradient 按相位旋转。
     *
     * 它只是「颜色在缓慢流动」，旋转角按完整 360° 走以保证回绕连续。
     */
    private fun drawDispersionRing(canvas: Canvas, b: android.graphics.Rect, burst: Float) {
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        var shader = sweepShader
        if (shader == null || lastSweepIntensity != intensity) {
            shader = SweepGradient(
                cx, cy,
                intArrayOf(
                    beamColors[0], beamColors[1], beamColors[2],
                    beamColors[3], beamColors[4], beamColors[0]
                ),
                null
            )
            sweepShader = shader
            lastSweepIntensity = intensity
        }
        sweepMatrix.setRotate(ringPhase * 360f, cx, cy)
        shader.setLocalMatrix(sweepMatrix)
        ringPaint.shader = shader

        val breathe = 0.72f + 0.28f * breath + 0.25f * burst
        // 两层：先宽一圈做柔光散射，再窄一圈做清晰光谱细带。
        ringPaint.strokeWidth = borderWidthPx * 3.4f
        ringPaint.alpha = (95 * intensity * breathe).toInt().coerceIn(0, 255)
        canvas.drawPath(path, ringPaint)

        ringPaint.strokeWidth = borderWidthPx * (1f + 0.2f * burst)
        ringPaint.alpha = (235 * intensity * breathe).toInt().coerceIn(0, 255)
        canvas.drawPath(path, ringPaint)

        ringPaint.shader = null
        ringPaint.alpha = 255
    }

    /**
     * 彗尾：沿路径从尾到头等距铺贴软光斑。
     */
    private fun drawBeam(canvas: Canvas, burst: Float) {
        val tailLen = pathLength * tailRatio * (0.86f + 0.28f * breath + 0.25f * burst)
        val head = phase * pathLength
        val baseSize = borderWidthPx * 4.0f * (0.92f + 0.16f * breath + 0.22f * burst)

        for (i in 0..stamps) {
            val t = i / stamps.toFloat()
            val dist = normalize(head - tailLen * (1f - t))
            if (!measure.getPosTan(dist, posTan, null)) continue

            val energy = t * t * sqrt(t)
            val alpha = (255f * energy * intensity * (1f + 0.2f * burst)).toInt().coerceIn(0, 255)
            if (alpha <= 2) continue

            val size = baseSize * (0.28f + 0.72f * t)
            val half = size / 2f
            stampRect.set(posTan[0] - half, posTan[1] - half, posTan[0] + half, posTan[1] + half)

            dotPaint.alpha = alpha
            dotPaint.colorFilter = tintFilters[(t * (tintSteps - 1)).toInt().coerceIn(0, tintSteps - 1)]
            canvas.drawBitmap(dotBitmap, null, stampRect, dotPaint)
        }

        // 头部光核：单位半径径向渐变复用 LocalMatrix 缩放平移，零逐帧分配
        val headDist = normalize(head)
        if (measure.getPosTan(headDist, posTan, null)) {
            val glowR = borderWidthPx * 3.8f * (0.88f + 0.24f * breath + 0.35f * burst)
            var hShader = headShader
            if (hShader == null || lastHeadIntensity != intensity) {
                hShader = RadialGradient(
                    0f, 0f, 1f,
                    intArrayOf(
                        withAlpha(Color.WHITE, 0.96f * intensity),
                        withAlpha(beamColors[0], 0.55f * intensity),
                        withAlpha(beamColors[1], 0.28f * intensity),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.34f, 0.65f, 1f),
                    Shader.TileMode.CLAMP
                )
                headShader = hShader
                lastHeadIntensity = intensity
            }
            headMatrix.setScale(glowR, glowR)
            headMatrix.postTranslate(posTan[0], posTan[1])
            hShader.setLocalMatrix(headMatrix)
            headPaint.shader = hShader
            canvas.drawCircle(posTan[0], posTan[1], glowR, headPaint)
            headPaint.shader = null
        }

        dotPaint.alpha = 255
        dotPaint.colorFilter = null
    }

    /** 沿 t 在四色之间插值，得到彗尾的色相过渡。 */
    private fun colorAt(t: Float): Int {
        val f = t.coerceIn(0f, 1f) * (beamColors.size - 1)
        val i = f.toInt().coerceIn(0, beamColors.size - 2)
        return ColorUtils.blendARGB(beamColors[i], beamColors[i + 1], f - i)
    }

    /**
     * 生成软光斑：中心实心白、外缘平滑归零。
     *
     * 用 radial 渐变而不是简单的实心圆：实心圆的边缘会在铺贴时出现一圈圈硬边，
     * 只有羽化到透明的光斑重叠起来才是连续的。
     */
    private fun createSoftDot(): Bitmap {
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                r, r, r,
                intArrayOf(
                    Color.WHITE,
                    Color.argb(220, 255, 255, 255),
                    Color.argb(90, 255, 255, 255),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.28f, 0.62f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawCircle(r, r, r, p)
        return bmp
    }

    private fun opaqueOf(colorRes: Int, fallback: Int): Int {
        val c = runCatching { context.getColor(colorRes) }.getOrDefault(fallback)
        return Color.rgb(Color.red(c), Color.green(c), Color.blue(c))
    }

    private fun normalize(distance: Float): Float {
        var d = distance % pathLength
        if (d < 0f) d += pathLength
        return d
    }

    private fun withAlpha(color: Int, factor: Float): Int =
        ColorUtils.setAlphaComponent(
            color,
            (Color.alpha(color) * factor.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        )

    override fun setAlpha(alpha: Int) {
        intensity = (alpha / 255f).coerceIn(0f, 1f)
    }

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
