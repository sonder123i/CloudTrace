package com.zcshou.gogogo.run.ui.home

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.data.db.RunUsageStats
import com.zcshou.gogogo.run.ui.glass.GlassDialogs
import java.util.Locale

/**
 * 「支持云迹」邀请。
 *
 * 和 Star 邀请不同，这里不做按时间的软提醒：只有用户真的完成过多次跑步、
 * 跑够里程，并且最近还在使用时才出现。理由是赞赏必须建立在「确实用到了、
 * 感受到了价值」之上，否则就是打扰。
 *
 * 打扰上限：
 * - 最多出现 [MAX_PROMPTS] 次，两次之间至少间隔 [COOLDOWN_DAYS] 天；
 * - 点「去支持」或「不再提示」都永久静默（无法确认是否真的付款，按用户意愿处理）；
 * - 关于页保留常驻入口，不弹窗也能主动去支持。
 */
object SupportPrompt {

    private const val PREFS = "pf_support"
    private const val KEY_OPTED_OUT = "opted_out"
    private const val KEY_PROMPT_COUNT = "prompt_count"
    private const val KEY_LAST_PROMPT_AT = "last_prompt_at"

    /** 最多打扰 2 次；赞赏比 Star 更敏感，次数更少。 */
    private const val MAX_PROMPTS = 2

    /** 至少完成 3 次、累计 5 公里，才算「真的用起来了」。 */
    private const val MIN_COMPLETED_RUNS = 3
    private const val MIN_TOTAL_METERS = 5000.0

    /** 首次完成满 3 天再问，避免装完当天就被要钱。 */
    private const val MIN_USAGE_DAYS = 3L

    /** 超过 14 天没跑，说明当前没有真实使用场景，不打扰。 */
    private const val RECENT_ACTIVITY_DAYS = 14L

    /** 两次邀请之间至少隔 14 天。 */
    private const val COOLDOWN_DAYS = 14L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun days(value: Long): Long = value * 24L * 60L * 60L * 1000L

    /** 不发生数据库查询的廉价前置判断，先挡掉已退出 / 已达上限的用户。 */
    fun canPrompt(context: Context): Boolean = with(prefs(context)) {
        if (getBoolean(KEY_OPTED_OUT, false)) return false
        if (getInt(KEY_PROMPT_COUNT, 0) >= MAX_PROMPTS) return false
        val last = getLong(KEY_LAST_PROMPT_AT, 0L)
        last <= 0L || System.currentTimeMillis() - last >= days(COOLDOWN_DAYS)
    }

    /** 只有真实完成过多次、跑够里程、近期仍在使用时才邀请。 */
    fun isEligible(context: Context, stats: RunUsageStats): Boolean {
        if (!canPrompt(context)) return false
        if (stats.completedCount < MIN_COMPLETED_RUNS) return false
        if (stats.totalMeters < MIN_TOTAL_METERS) return false
        val first = stats.firstCompletedAt ?: return false
        val last = stats.lastCompletedAt ?: return false
        val now = System.currentTimeMillis()
        if (now - first < days(MIN_USAGE_DAYS)) return false
        if (now - last > days(RECENT_ACTIVITY_DAYS)) return false
        return true
    }

    /** 记录一次展示，用于上限与冷却判断。 */
    private fun markShown(context: Context) {
        val count = prefs(context).getInt(KEY_PROMPT_COUNT, 0)
        prefs(context).edit()
            .putInt(KEY_PROMPT_COUNT, count + 1)
            .putLong(KEY_LAST_PROMPT_AT, System.currentTimeMillis())
            .apply()
    }

    /** 永久不再邀请：点过「去支持」或「不再提示」。 */
    fun optOut(context: Context) {
        prefs(context).edit().putBoolean(KEY_OPTED_OUT, true).apply()
    }

    fun show(activity: Activity, stats: RunUsageStats, onDismiss: () -> Unit = {}): AlertDialog {
        markShown(activity)
        val km = String.format(Locale.CHINA, "%.1f", stats.totalMeters / 1000.0)
        val dialog = GlassDialogs.builder(activity)
            .setTitle(activity.getString(R.string.support_prompt_title, km))
            .setMessage(
                activity.getString(R.string.support_prompt_body, stats.completedCount)
            )
            .setPositiveButton(R.string.support_prompt_go) { _, _ ->
                // 打开支持页即视为已表达意愿；无法确认是否真的付款，按用户意愿静默。
                optOut(activity)
                runCatching { activity.startActivity(SupportActivity.intent(activity)) }
            }
            .setNegativeButton(R.string.support_prompt_later, null)
            .setNeutralButton(R.string.support_prompt_never) { _, _ -> optOut(activity) }
            .create()
        dialog.setOnShowListener {
            for (button in intArrayOf(
                AlertDialog.BUTTON_POSITIVE,
                AlertDialog.BUTTON_NEGATIVE,
                AlertDialog.BUTTON_NEUTRAL
            )) {
                dialog.getButton(button)?.transformationMethod = null
            }
        }
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
        return dialog
    }
}