package com.zcshou.gogogo.mascot

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 彩蛋图鉴持久化：记录每张拍立得的首次解锁时间与解锁次数。
 *
 * 只写 SharedPreferences，不含任何网络与统计；读坏数据时按「未解锁」处理，
 * 绝不因为图鉴损坏让首页/设置页崩掉。
 */
object MascotEggStore {

    private const val NAME = "pf_mascot_eggs"
    private const val KEY_PREFIX = "egg_"
    private const val KEY_UNLOCKED_AT = "_unlocked_at"
    private const val KEY_COUNT = "_count"

    data class Entry(
        val egg: MascotEgg,
        val unlockedAt: Long,
        val count: Int
    ) {
        val unlocked: Boolean get() = unlockedAt > 0L
        val unlockedAtText: String
            get() = if (unlockedAt <= 0L) "" else {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(unlockedAt))
            }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /**
     * 解锁一张彩蛋。
     *
     * @return true 表示这是**首次**解锁（宿主据此弹一次拍立得浮卡）；
     *         重复解锁只累加次数，返回 false，避免反复打扰。
     */
    fun unlock(context: Context, egg: MascotEgg): Boolean {
        val prefs = prefs(context)
        val atKey = KEY_PREFIX + egg.name + KEY_UNLOCKED_AT
        val countKey = KEY_PREFIX + egg.name + KEY_COUNT
        val first = prefs.getLong(atKey, 0L) <= 0L
        val count = prefs.getInt(countKey, 0)
        prefs.edit().apply {
            if (first) putLong(atKey, System.currentTimeMillis())
            putInt(countKey, count + 1)
        }.apply()
        return first
    }

    fun isUnlocked(context: Context, egg: MascotEgg): Boolean =
        prefs(context).getLong(KEY_PREFIX + egg.name + KEY_UNLOCKED_AT, 0L) > 0L

    fun entry(context: Context, egg: MascotEgg): Entry {
        val prefs = prefs(context)
        return Entry(
            egg = egg,
            unlockedAt = prefs.getLong(KEY_PREFIX + egg.name + KEY_UNLOCKED_AT, 0L),
            count = prefs.getInt(KEY_PREFIX + egg.name + KEY_COUNT, 0)
        )
    }

    fun entries(context: Context): List<Entry> = MascotEgg.entries.map { entry(context, it) }

    fun unlockedCount(context: Context): Int = entries(context).count { it.unlocked }

    fun totalCount(): Int = MascotEgg.entries.size

    /** 供设置页显示「已收集 X/Y」 */
    fun progressText(context: Context): String =
        "已收集 ${unlockedCount(context)}/${totalCount()}"
}
