package com.zcshou.gogogo.run.ui.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.zcshou.gogogo.R
import com.zcshou.gogogo.mascot.MascotEgg
import com.zcshou.gogogo.mascot.MascotEggCardView
import com.zcshou.gogogo.mascot.MascotMode
import com.zcshou.gogogo.mascot.MascotPrefs
import com.zcshou.gogogo.run.ui.editor.RouteEditorActivity
import com.zcshou.gogogo.run.ui.theme.ThemeHelper

/**
 * 功能使用指南：权限配好之后，告诉新人第一条路线怎么跑起来。
 *
 * 和权限教程分开：权限是「必须做完才能开跑」，功能是「建路线 → 设目标 → 发车 → 看战报」。
 * 页面只做说明和入口，不持有任何状态，也不依赖地图/权限。
 */
class UsageGuideActivity : AppCompatActivity() {

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, UsageGuideActivity::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.apply(this)
        super.onCreate(savedInstanceState)
        ThemeHelper.enableEdgeToEdge(this)
        setContentView(R.layout.activity_usage_guide)
        ThemeHelper.padForSystemBars(findViewById(R.id.usageGuideRoot))

        findViewById<View>(R.id.btnUsageBack).setOnClickListener { finish() }

        val egg = findViewById<MascotEggCardView>(R.id.usageGuideEgg)
        if (MascotPrefs.mode(this) != MascotMode.OFF) {
            // 首次使用引导页即解锁「初次见面」拍立得
            com.zcshou.gogogo.mascot.MascotEggStore.unlock(this, MascotEgg.WELCOME_ARMS)
            egg.bind(MascotEgg.WELCOME_ARMS)
        }

        val createRoute = View.OnClickListener { openRouteEditor() }
        findViewById<View>(R.id.btnUsageCreate).setOnClickListener(createRoute)
        findViewById<View>(R.id.btnUsageCreate2).setOnClickListener(createRoute)

        findViewById<View>(R.id.btnUsageDemo).setOnClickListener {
            runCatching { startActivity(Intent(this, DemoPreviewActivity::class.java)) }
        }
        findViewById<View>(R.id.btnUsageHome).setOnClickListener { finish() }
    }

    /**
     * 直接进「操场套模」：第一次用的人不需要先理解三种建圈方式。
     * 建完返回时回到首页，新路线卡就在眼前。
     */
    private fun openRouteEditor() {
        runCatching {
            startActivity(
                Intent(this, RouteEditorActivity::class.java)
                    .putExtra(
                        RouteEditorActivity.EXTRA_INITIAL_MODE,
                        RouteEditorActivity.MODE_PRESET
                    )
            )
            finish()
        }
    }
}
