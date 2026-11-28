package com.zcshou.gogogo.run.ui.glass

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi

/**
 * API 无关的「静态画布单 Pass 透镜」接口，避免 API < 33 加载 RuntimeShader。
 */
internal interface ContainerOptics {
    val available: Boolean

    /**
     * 在 [view] 的局部坐标系里画一层圆角矩形玻璃：
     * 中心采样模糊纹理 + 染色，边缘采样清晰纹理做 SDF 折射与色散。
     *
     * @param originX / [originY] 本 View 左上角在静态画布宿主视图坐标系中的偏移（px）
     */
    fun draw(
        canvas: Canvas,
        view: View,
        snapshot: StaticBackdropProvider.Snapshot,
        originX: Float,
        originY: Float,
        radius: Float,
        tint: Int,
        bevel: Float,
        dispersion: Float,
        night: Boolean
    ): Boolean
}

internal object ContainerOpticsFactory {

    fun create(): ContainerOptics? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return ContainerOpticsApi33().takeIf { it.available }
    }
}

/**
 * API 33+ AGSL 容器透镜（`Kyant0/AndroidLiquidGlass` 配方移植）。
 *
 * 单 Pass 完成：
 * 1. Rounded-Rect SDF 求距离场，只让圆角与边缘厚度区发生折射；
 * 2. 中心区：0.25x 模糊纹理 + Vibrancy 提活性 + 极薄染色（保持文字可读对比度）；
 * 3. 边缘区：0.5x 清晰纹理按法线方向偏移采样（可读 72dp 网格的真实弯曲）
 *    + RGB 微色散 + Schlick 掠射高光 + 底部厚度暗边。
 *
 * 每帧只更新 Uniform，不截屏、不分配 Bitmap：滚动时仅传 (offsetX, offsetY)。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class ContainerOpticsApi33 : ContainerOptics {

    private val runtimeShader: RuntimeShader? = runCatching {
        RuntimeShader(SHADER)
    }.getOrNull()

    override val available: Boolean get() = runtimeShader != null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = runtimeShader }

    /**
     * 中心鲜艳度。API 36 且色彩管线快路径可用时由平台侧承接一部分，
     * 这里就少提一点，避免叠成过饱和的"塑料绿"。
     */
    private val vibrancy: Float =
        if (GlassApi36Support.probe()) 1.16f else 1.24f

    override fun draw(
        canvas: Canvas,
        view: View,
        snapshot: StaticBackdropProvider.Snapshot,
        originX: Float,
        originY: Float,
        radius: Float,
        tint: Int,
        bevel: Float,
        dispersion: Float,
        night: Boolean
    ): Boolean {
        val shader = runtimeShader ?: return false
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        if (w <= 0f || h <= 0f) return false

        shader.setInputShader("uCrisp", snapshot.crispShader)
        shader.setInputShader("uBlurred", snapshot.blurredShader)
        shader.setFloatUniform("uSize", w, h)
        shader.setFloatUniform("uRadius", radius)
        shader.setFloatUniform("uOrigin", originX, originY)
        shader.setFloatUniform("uCrispScale", snapshot.crispScale)
        shader.setFloatUniform("uBlurScale", snapshot.blurredScale)
        shader.setFloatUniform("uBevel", bevel.coerceAtLeast(1f))
        shader.setFloatUniform("uDispersion", dispersion.coerceIn(0f, 1f))
        shader.setFloatUniform("uVibrancy", if (night) vibrancy * 0.94f else vibrancy)
        shader.setFloatUniform("uNight", if (night) 1f else 0f)
        val tintAlpha = GlassPipelineRules.clampTintAlpha(android.graphics.Color.alpha(tint) / 255f)
        shader.setFloatUniform(
            "uTint",
            android.graphics.Color.red(tint) / 255f,
            android.graphics.Color.green(tint) / 255f,
            android.graphics.Color.blue(tint) / 255f,
            tintAlpha
        )
        shader.setFloatUniform("uSheen", 1f, 1f, 1f, 1f)
        canvas.drawRect(0f, 0f, w, h, paint)
        return true
    }

    private companion object {
        /** 与 [ContainerOpticsApi33] 一体维护；改任何 Uniform 都要同步这里。 */
        const val SHADER = """
            uniform float2 uSize;
            uniform float uRadius;
            uniform float2 uOrigin;
            uniform float uCrispScale;
            uniform float uBlurScale;
            uniform float uBevel;
            uniform float uDispersion;
            uniform float uVibrancy;
            uniform float uNight;
            uniform float4 uTint;
            uniform float4 uSheen;
            uniform shader uCrisp;
            uniform shader uBlurred;

            float sdRoundRect(float2 p, float2 halfSize, float r) {
                float2 q = abs(p - halfSize) - (halfSize - r);
                return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
            }

            half4 main(float2 fragCoord) {
                float2 halfSize = uSize * 0.5;
                float d = sdRoundRect(fragCoord, halfSize, uRadius);
                if (d > 1.5) {
                    return half4(0.0, 0.0, 0.0, 0.0);
                }

                float2 vp = uOrigin + fragCoord;

                // 中心：模糊纹理 + Vibrancy + 极薄冷雾染色
                half4 base = uBlurred.eval(vp * uBlurScale);
                half lum = dot(base.rgb, half3(0.2126, 0.7152, 0.0722));
                half3 vivid = mix(half3(lum), base.rgb, half3(half(uVibrancy)));
                vivid = mix(vivid, vivid * half3(half(0.86)), half3(half(uNight * 0.42)));
                float3 tinted = mix(
                    float3(float(vivid.r), float(vivid.g), float(vivid.b)),
                    float3(uTint.r, uTint.g, uTint.b),
                    uTint.a
                );

                // 边缘：SDF 法线方向的清晰纹理折射 + RGB 棱镜微色散
                float2 n = fragCoord - halfSize;
                n = n / max(length(n), 0.0001);
                float rim = 1.0 - smoothstep(0.0, uBevel, -d);
                float push = rim * rim * uBevel * 0.9;
                float2 cuv = (vp + n * push) * uCrispScale;

                float3 refr = float3(0.0, 0.0, 0.0);
                if (uDispersion > 0.001) {
                    float2 cuvR = (vp + n * push * (1.0 + uDispersion * 0.45)) * uCrispScale;
                    float2 cuvB = (vp + n * push * (1.0 - uDispersion * 0.45)) * uCrispScale;
                    refr = float3(
                        float(uCrisp.eval(cuvR).r),
                        float(uCrisp.eval(cuv).g),
                        float(uCrisp.eval(cuvB).b)
                    );
                } else {
                    half4 c0 = uCrisp.eval(cuv);
                    refr = float3(float(c0.r), float(c0.g), float(c0.b));
                }
                float3 col = mix(tinted, refr, rim * 0.82);

                // Schlick 掠射高光：上沿最强，侧沿收敛，下沿几乎为零
                float graze = max(0.0, dot(n, float2(0.0, -1.0)));
                float spec = pow(graze, 3.5) * rim * mix(1.0, 0.55, uNight);
                col = col + float3(uSheen.r, uSheen.g, uSheen.b) * spec * 0.55;

                // 底部极薄暗边，塑造玻璃厚度
                float bottomShade = max(0.0, dot(n, float2(0.0, 1.0))) * rim;
                col = col * (1.0 - bottomShade * 0.055);

                half aa = half(1.0 - smoothstep(-0.9, 0.9, d));
                return half4(half3(col) * aa, aa);
            }
        """
    }
}
