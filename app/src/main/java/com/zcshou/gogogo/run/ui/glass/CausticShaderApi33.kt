package com.zcshou.gogogo.run.ui.glass

import android.graphics.Canvas
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi

/** API 无关的焦散绘制接口，避免 API < 33 加载 RuntimeShader。 */
internal interface CausticShader {
    val available: Boolean
    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        corner: Float,
        touchX: Float,
        touchY: Float,
        intensity: Float,
        radius: Float,
        /**
         * 重力/倾角感应到的光源方向（归一化，[-1,1]）。
         * 由 `LightSourceController` 提供；省电档与冻结时调用方传 0。
         */
        lightX: Float,
        lightY: Float
    )
}

internal object CausticShaderFactory {
    fun create(): CausticShader? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return CausticShaderApi33().takeIf { it.available }
    }
}

/**
 * API 33+ 生成式焦散着色器。
 *
 * AGSL 只产出光，不采样背景；背景折射仍由 LiquidGlassView 的透镜管线负责。
 * 这样两层职责分离：SDF 折射 + 这层触摸焦散可以独立启停。
 *
 * ## V6.4 传感器联动
 *
 * 手机微倾时，指尖光斑与边缘光束会朝重力方向轻微偏移（[uLight]），
 * 让"光真的照在玻璃上"这件事在静态截图之外也可被感知。
 * 幅度刻意压得很小（半径的 18%），否则会读成故障闪烁。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class CausticShaderApi33 : CausticShader {

    private val runtimeShader: RuntimeShader? = runCatching {
        RuntimeShader(
            """
            uniform float2 uSize;
            uniform float2 uTouch;
            uniform float2 uLight;
            uniform float uIntensity;
            uniform float uRadius;
            uniform float uCorner;

            float sdRoundRect(float2 p, float2 halfSize, float r) {
                float2 q = abs(p - halfSize) - (halfSize - r);
                return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
            }

            half4 main(float2 fragCoord) {
                // 光源把整个焦散场朝倾斜方向轻推，静态时 uLight=(0,0) 即原配方
                float2 center = uTouch + uLight * uRadius * 0.18;
                float d = distance(fragCoord, center);
                float g = exp(-pow(d / max(uRadius, 1.0), 2.0) * 2.1);
                float ring = smoothstep(uRadius * 0.26, 0.0, abs(d - uRadius * 0.55));
                float sd = sdRoundRect(fragCoord, uSize * 0.5, uCorner);
                float edge = smoothstep(8.0, 0.0, abs(sd));
                float near = smoothstep(uRadius * 2.6, 0.0, d);

                // 倾斜方向那一侧的边缘光束更亮
                float2 n = fragCoord - uSize * 0.5;
                n = n / max(length(n), 0.0001);
                float2 l = uLight;
                l = l / max(length(l), 0.0001);
                float lean = 0.72 + 0.56 * smoothstep(-0.25, 1.0, dot(n, l));
                float rim = edge * near * lean;

                float light = (g * 0.32 + ring * 0.16 + rim * 0.55) * uIntensity;
                float a = clamp(light * 1.15, 0.0, 0.55);
                float3 col = float3(0.88, 0.95, 1.0);
                return half4(half3(col * a), half(a));
            }
            """.trimIndent()
        )
    }.getOrNull()

    override val available: Boolean = runtimeShader != null

    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        shader = runtimeShader
        // API 36 且 ROM 真的实现了 RuntimeColorFilter 时，用平台鲜艳度滤镜强化光束，
        // 省掉自己再叠一层饱和计算；不可用就保持默认合成。
        GlassApi36Support.vibrancyFilter()?.let { colorFilter = it }
    }

    override fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        corner: Float,
        touchX: Float,
        touchY: Float,
        intensity: Float,
        radius: Float,
        lightX: Float,
        lightY: Float
    ) {
        val shader = runtimeShader ?: return
        shader.setFloatUniform("uSize", width.toFloat(), height.toFloat())
        shader.setFloatUniform("uTouch", touchX, touchY)
        shader.setFloatUniform("uLight", lightX, lightY)
        shader.setFloatUniform("uIntensity", intensity)
        shader.setFloatUniform("uRadius", radius)
        shader.setFloatUniform("uCorner", corner)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}
