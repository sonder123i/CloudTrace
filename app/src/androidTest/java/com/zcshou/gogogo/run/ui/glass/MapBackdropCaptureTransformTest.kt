package com.zcshou.gogogo.run.ui.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 地图玻璃底衬采样几何回归：capture 必须精确截取面板 rect 对应的地图区域。
 *
 * 历史 bug：`canvas.translate` 误用 TINY_SCALE（应为 CAPTURE_SCALE）。Canvas 变换
 * 预拼接下等效映射 `device = 3·tiny − 0.1·rect`，正确应为 `− 0.3·rect` ——
 * 采样窗口整体偏移 0.2×rect，玻璃越靠地图下方，透出的内容错位越明显。
 *
 * 验证方式：构造一张「上半红 / 下半蓝」的假地图，截取下半区域 ——
 * 修复后面板必须全蓝；旧实现会因窗口上偏把红色采样进来。
 */
@RunWith(AndroidJUnit4::class)
class MapBackdropCaptureTransformTest {

    /** 1000×2000 假地图：y < 1000 红，y ≥ 1000 蓝。 */
    private fun twoBandMap(): View {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return object : View(context) {
            private val paint = Paint()
            override fun onDraw(canvas: Canvas) {
                paint.color = Color.RED
                canvas.drawRect(0f, 0f, 1000f, 1000f, paint)
                paint.color = Color.BLUE
                canvas.drawRect(0f, 1000f, 1000f, 2000f, paint)
            }
        }.apply { layout(0, 0, 1000, 2000) }
    }

    /** 反射调 private capture(source, rect)：入口与 LiquidGlassView 回调一致。 */
    private fun captureRect(source: View, rect: RectF): Bitmap? {
        val method = MapBackdropCapture::class.java.getDeclaredMethod(
            "capture", View::class.java, RectF::class.java
        )
        method.isAccessible = true
        val instance = MapBackdropCapture::class.java.getDeclaredField("INSTANCE").get(null)
        return method.invoke(instance, source, rect) as Bitmap?
    }

    @Test
    fun capture_bottomHalfPanelShowsOnlyBlue() {
        val source = twoBandMap()
        try {
            // 面板位于地图下半区（1000~2000）：修复前窗口上偏 0.2×rect，
            // 面板上 2/3 会误采到红色半区。注意管线会做去蓝校色（颜色矩阵）
            // 且双线性滤波在面板首行有边界混合，故只检查内部行、用通道比较。
            val panel = captureRect(source, RectF(0f, 1000f, 1000f, 2000f))
            assertNotNull("capture 应产出面板位图", panel)
            panel!!
            assertEquals("宽=rect宽×0.3", 300, panel.width)
            assertEquals("高=rect高×0.3", 300, panel.height)
            for (y in 2 until panel.height) {
                for (x in 0 until panel.width) {
                    val p = panel.getPixel(x, y)
                    val r = Color.red(p)
                    val b = Color.blue(p)
                    assertTrue(
                        "pixel($x,$y) 蓝分量 $b 必须远大于红分量 $r（不得采到上半区红色）",
                        b > 150 && r < 100
                    )
                }
            }
        } finally {
            MapBackdropCapture.release(source)
        }
    }

    @Test
    fun capture_panelCrossingTheBandBoundaryKeepsTheSplit() {
        val source = twoBandMap()
        try {
            // 面板横跨红蓝边界（500~1500）：上红下蓝，边界应落在面板正中
            // （view y=1000 → panel y = (1000-500)×0.3 = 150）
            val panel = captureRect(source, RectF(0f, 500f, 1000f, 1500f))
            assertNotNull("capture 应产出面板位图", panel)
            panel!!
            assertEquals(300, panel.height)
            val topCenter = panel.getPixel(panel.width / 2, 10)
            val bottomCenter = panel.getPixel(panel.width / 2, panel.height - 10)
            assertTrue("上沿应采到红色半区 r=${Color.red(topCenter)} b=${Color.blue(topCenter)}", Color.red(topCenter) > 200 && Color.blue(topCenter) < 120)
            assertTrue("下沿应采到蓝色半区 r=${Color.red(bottomCenter)} b=${Color.blue(bottomCenter)}", Color.blue(bottomCenter) > 150 && Color.red(bottomCenter) < 100)
            // 边界 ±10px 容差内必须换色
            val aboveBoundary = panel.getPixel(panel.width / 2, 138)
            val belowBoundary = panel.getPixel(panel.width / 2, 162)
            assertTrue("边界上方应为红色", Color.red(aboveBoundary) > 200)
            assertTrue("边界下方应为蓝色", Color.blue(belowBoundary) > 150)
        } finally {
            MapBackdropCapture.release(source)
        }
    }
}
