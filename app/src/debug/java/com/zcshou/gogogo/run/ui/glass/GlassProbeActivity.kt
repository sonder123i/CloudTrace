package com.zcshou.gogogo.run.ui.glass

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.zcshou.gogogo.run.engine.TrackMathUtils
import com.zcshou.gogogo.run.ui.overlay.RouteThumbnailView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 仅存在于 debug 变体的玻璃渲染探针页。
 * 用高对比色块铺底，验证 AGSL 折射 / RenderEffect 模糊 / 磨砂兜底三种路径是否真的出画面。
 *
 * adb shell am start -n com.zcshou.gogogo/.run.ui.glass.GlassProbeActivity
 */
class GlassProbeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0B0E11"))
        }

        // 高对比色块 + 细密条纹：折射与模糊都会在这里露出破绽
        val palette = intArrayOf(
            Color.parseColor("#00E5FF"),
            Color.parseColor("#FF3D71"),
            Color.parseColor("#C8FF00"),
            Color.parseColor("#7C4DFF")
        )
        val bars = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        palette.forEachIndexed { index, color ->
            bars.addView(
                TextView(this).apply {
                    text = if (index % 2 == 0) "CLOUDTRACE  ·  0123456789" else "深海光学  ·  ABYSSAL OPTICS"
                    setTextColor(Color.parseColor("#0B0E11"))
                    textSize = 26f
                    gravity = Gravity.CENTER
                    setBackgroundColor(color)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }
        root.addView(
            bars,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val glass = LiquidGlassContainer(this).apply {
            setGlassTint(0x66FFFFFF)
            setGlassCornerRadius(28f * density)
            background = GradientDrawable().apply {
                cornerRadius = 28f * density
                setColor(Color.TRANSPARENT)
            }
            addView(
                TextView(this@GlassProbeActivity).apply {
                    text = "Liquid Glass\nTier 1 · AGSL"
                    setTextColor(Color.WHITE)
                    textSize = 20f
                    gravity = Gravity.CENTER
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        root.addView(
            glass,
            FrameLayout.LayoutParams(dp(300), dp(180), Gravity.CENTER)
        )

        setContentView(root)

        // 探针页也要登记静态画布宿主：否则 LiquidGlassContainer 找不到快照，
        // 只能回退静态磨砂 —— 那样就验证不到 AGSL 共享折射这条路径。
        // 登记色块条本身（不是含玻璃的 root），避免把玻璃自己画进自己的背景。
        StaticBackdropProvider.attach(bars)

        // 首帧布局完成后再采样一次，确保背景色块已进入快照
        root.postDelayed({
            glass.refreshBackdrop()
            StaticBackdropProvider.invalidate(bars)
            glass.invalidate()
            Log.i(
                "LiquidGlass",
                "probe tier=${DeepRunGlassManager.tier(this)} " +
                    "suppressed=${DeepRunGlassManager.isRenderSuppressed} " +
                    "w=${glass.width} h=${glass.height}"
            )
        }, 600)

        // 声呐预热动效演示：4s 点亮 + 1.5s 全亮，循环播放
        val thumb = RouteThumbnailView(this).apply {
            setRoute(demoTrack())
        }
        root.addView(
            thumb,
            FrameLayout.LayoutParams(dp(120), dp(120), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        )
        val handler = Handler(Looper.getMainLooper())
        val sonarLoop = object : Runnable {
            override fun run() {
                thumb.startSonar(4000)
                handler.postDelayed({
                    thumb.stopSonar()
                    handler.postDelayed(this, 1500)
                }, 4200)
            }
        }
        handler.postDelayed(sonarLoop, 1000)
    }

    /** 合成一条 WGS-84 田径场环线（直道 + 两段半圆弯道）。 */
    private fun demoTrack(): List<TrackMathUtils.LatLng> {
        val pts = mutableListOf<TrackMathUtils.LatLng>()
        val straightLen = 60.0
        val radius = 18.0
        val step = 18
        for (i in 0..step) {
            val x = -straightLen / 2 + straightLen * i / step
            pts += TrackMathUtils.LatLng(radius / 111320.0, x / 111320.0)
        }
        for (i in 0..step) {
            val a = PI * i / step
            val x = straightLen / 2 + radius * sin(a)
            val y = radius * cos(a)
            pts += TrackMathUtils.LatLng(y / 111320.0, x / 111320.0)
        }
        for (i in 0..step) {
            val x = straightLen / 2 - straightLen * i / step
            pts += TrackMathUtils.LatLng(-radius / 111320.0, x / 111320.0)
        }
        for (i in 0..step) {
            val a = PI * i / step
            val x = -straightLen / 2 - radius * sin(a)
            val y = radius * cos(a)
            pts += TrackMathUtils.LatLng(y / 111320.0, x / 111320.0)
        }
        return pts
    }
}
