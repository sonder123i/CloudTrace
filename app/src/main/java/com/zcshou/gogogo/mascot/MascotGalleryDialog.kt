package com.zcshou.gogogo.mascot

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.ui.glass.GlassDialogs

/**
 * 「鲸鲸图鉴 · 拍立得」弹窗。
 *
 * 两列网格展示全部 [MascotEgg]：
 * - 已解锁：显示原图 + 标题 + 解锁日期；
 * - 未解锁：显示深海轮廓占位卡 + 解锁条件（**不隐藏格子**，否则图鉴会越玩越空）。
 */
object MascotGalleryDialog {

    fun show(context: Context) {
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val content = android.view.LayoutInflater.from(context)
            .inflate(R.layout.dialog_mascot_gallery, view, false)
        view.addView(content)
        // 弹窗高度：给网格一个明确上限，超出后内部滚动
        val grid = content.findViewById<LinearLayout>(R.id.galleryGrid)
        val density = context.resources.displayMetrics.density
        grid.layoutParams = grid.layoutParams.apply {
            height = (context.resources.displayMetrics.heightPixels * 0.62f).toInt()
        }

        content.findViewById<TextView>(R.id.galleryProgress).text =
            "鲸鲸图鉴 · ${MascotEggStore.progressText(context)}"

        val entries = MascotEggStore.entries(context)
        var row: LinearLayout? = null
        entries.forEachIndexed { index, entry ->
            if (index % COLUMNS == 0) {
                row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                grid.addView(
                    row,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(context, 6) }
                )
            }
            row?.addView(
                buildCell(context, entry),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(context, 6)
                }
            )
        }
        // 最后一行不足两列时补一个空占位，保持左右等宽
        if (entries.size % COLUMNS != 0) {
            row?.addView(
                View(context),
                LinearLayout.LayoutParams(0, 1, 1f).apply { marginEnd = dp(context, 6) }
            )
        }

        GlassDialogs.builder(context)
            .setTitle("鲸鲸图鉴")
            .setView(view)
            .setPositiveButton("收好", null)
            .show()
    }

    private fun buildCell(context: Context, entry: MascotEggStore.Entry): View {
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
            background = ContextCompat.getDrawable(context, R.drawable.bg_capsule)
        }
        val frame = FrameLayout(context)
        val image = ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = entry.egg.title
        }
        val frameLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(context, 96)
        )
        frame.addView(image, frameLp)

        val resId = context.resources.getIdentifier(
            entry.egg.resName, "drawable", context.packageName
        )
        if (entry.unlocked && resId != 0) {
            image.setImageResource(resId)
            image.alpha = 1f
        } else {
            // 未解锁或缺图：保留格子，用轮廓占位 + 锁标记表达「还没拿到」
            if (resId != 0) {
                image.setImageResource(resId)
                image.alpha = 0.22f
            } else {
                image.setImageResource(R.drawable.ic_fish_track)
                image.alpha = 0.18f
            }
        }
        cell.addView(frame)

        cell.addView(
            TextView(context).apply {
                text = entry.egg.title.ifEmpty { entry.egg.name }
                setTextColor(ContextCompat.getColor(context, R.color.pf_text_primary))
                textSize = 11f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 5) }
        )

        cell.addView(
            TextView(context).apply {
                text = if (entry.unlocked) {
                    if (entry.count > 1) "${entry.unlockedAtText} · ×${entry.count}" else entry.unlockedAtText
                } else {
                    entry.egg.unlockHint
                }
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (entry.unlocked) R.color.pf_text_secondary else R.color.pf_text_tertiary
                    )
                )
                textSize = 9f
                gravity = Gravity.CENTER
                maxLines = 2
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 2) }
        )
        return cell
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private const val COLUMNS = 2
}
