package com.zcshou.gogogo.run.ui.splash

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * 全屏扩散折射环（API 33+ AGSL 版本）。
 *
 * 破壳阶段需要一圈「极克制的环形折射光环 + 轻微 RGB 棱镜色散边缘」。
 * 用 [RuntimeShader] 一次编译、逐帧只改 uniform，比每帧重建 `RadialGradient`
 * 便宜得多，也比多层描边更像真实薄膜：
 *
 * - 环带用两条 `smoothstep` 夹出软边，外缘再叠一层更高频的正弦细纹，
 *   模拟波长干涉——但幅度只有 ±14%，避免变成艳俗荧光；
 * - 色散方向固定为「内暖外冷」，与 [com.zcshou.gogogo.run.ui.theme.FiberStyle]
 *   的靛蓝→冰青走向一致，扩散时不会出现色相打架；
 * - 输出颜色**预乘 alpha**（`rgb * a`），交给 Skia 直接合成，
 *   避免非预乘着色器在深色底上出现的灰边。
 *
 * ## 低版本降级
 *
 * 任何异常（AGSL 编译失败、OEM 阉割 RuntimeShader）都被 [SplashRing.create]
 * 吞掉并返回 null，调用方自动回落到预分配 `RadialGradient` 描边光环。
 * 类加载发生在 `SDK_INT >= 33` 判断之后，API 27–32 永远不会解析本类符号。
 */
interface SplashRingRenderer {

    /**
     * 画一圈扩散环。所有参数都是标量，调用方逐帧传值，本接口内部零分配。
     *
     * @param paint 调用方预分配的画笔（本方法只临时借用 `shader` 槽位，返回前清空）
     */
    fun draw(
        canvas: Canvas,
        paint: Paint,
        width: Float,
        height: Float,
        centerX: Float,
        centerY: Float,
        radius: Float,
        thickness: Float,
        alpha: Float,
        tintIn: Int,
        tintOut: Int
    )
}

/** 工厂：唯一入口，负责 SDK 判定与安全降级。 */
object SplashRing {

    /** 编译失败后永久关闭，避免每帧都去撞一次异常。 */
    @Volatile
    private var failed = false

    fun create(): SplashRingRenderer? {
        if (failed) return null
        // 显式 SDK 判断放在最外层：既保证低版本不解析平台符号，也让 lint 的
        // NewApi 数据流能证明这里安全。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return try {
            SplashRingShaderApi33()
        } catch (t: Throwable) {
            failed = true
            null
        }
    }
}

/**
 * AGSL 实现。
 *
 * 只由 [SplashRing.create] 在 API 33+ 构造；构造失败（编译错误）会让工厂降级。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class SplashRingShaderApi33 : SplashRingRenderer {

    private val shader: RuntimeShader = RuntimeShader(AGSL)

    override fun draw(
        canvas: Canvas,
        paint: Paint,
        width: Float,
        height: Float,
        centerX: Float,
        centerY: Float,
        radius: Float,
        thickness: Float,
        alpha: Float,
        tintIn: Int,
        tintOut: Int
    ) {
        if (alpha <= 0.002f || radius <= 0f) return
        val inR = Color.red(tintIn) / 255f
        val inG = Color.green(tintIn) / 255f
        val inB = Color.blue(tintIn) / 255f
        val inA = Color.alpha(tintIn) / 255f
        val outR = Color.red(tintOut) / 255f
        val outG = Color.green(tintOut) / 255f
        val outB = Color.blue(tintOut) / 255f
        val outA = Color.alpha(tintOut) / 255f

        shader.setFloatUniform("uCenter", centerX, centerY)
        shader.setFloatUniform("uRadius", radius)
        shader.setFloatUniform("uThickness", thickness)
        shader.setFloatUniform("uAlpha", alpha)
        shader.setFloatUniform("uTintIn", inR, inG, inB, inA)
        shader.setFloatUniform("uTintOut", outR, outG, outB, outA)

        paint.shader = shader
        paint.alpha = 255
        canvas.drawRect(0f, 0f, width, height, paint)
        paint.shader = null
    }

    private companion object {
        /**
         * `fragCoord` 是设备像素坐标。环带两条软边 + 外缘正弦细纹，
         * 幅度克制到「只在动的时候察觉」，静止截图几乎看不出扫描线。
         */
        const val AGSL = """
            uniform float2 uCenter;
            uniform float uRadius;
            uniform float uThickness;
            uniform float uAlpha;
            uniform float4 uTintIn;
            uniform float4 uTintOut;

            half4 main(float2 fragCoord) {
                float2 d = fragCoord - uCenter;
                float dist = length(d);
                float inner = uRadius - uThickness;
                float outer = uRadius + uThickness;
                if (dist < inner || dist > outer) {
                    return half4(0.0);
                }
                float band = smoothstep(inner, inner + uThickness * 0.45, dist)
                           * (1.0 - smoothstep(outer - uThickness * 0.45, outer, dist));
                float span = max(outer - inner, 0.001);
                float t = clamp((dist - inner) / span, 0.0, 1.0);
                float3 rgb = mix(uTintIn.rgb, uTintOut.rgb, t);
                float ripple = 0.88 + 0.12 * sin(dist * 0.22);
                float a = band * uAlpha * ripple;
                return half4(rgb * a, a);
            }
        """
    }
}
