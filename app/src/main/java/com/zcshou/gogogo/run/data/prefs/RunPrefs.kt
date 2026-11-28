package com.zcshou.gogogo.run.data.prefs

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 首页/漫游通用偏好：目标跑步 App、平滑走过去、常去地标、周跑量目标。 */
object RunPrefs {

    private const val NAME = "pf_run_prefs"
    private const val KEY_TARGET_APP = "target_run_app"
    private const val KEY_SMOOTH_WALK = "smooth_walk"
    private const val KEY_FAVORITES = "roam_favorites"
    private const val KEY_WEEKLY_GOAL_KM = "weekly_goal_km"
    /** 常用地标上限：24 个足够覆盖「公司 / 家 / 各常去公园」，超出按最近使用淘汰 */
    private const val MAX_FAVORITES = 24

    /** 周跑量目标默认值（公里） */
    const val DEFAULT_WEEKLY_GOAL_KM = 15.0f

    data class Favorite(
        val name: String,
        val lat: Double,
        val lng: Double,
        val tag: String = "常用",
        val pinned: Boolean = false
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun targetApp(context: Context): String? =
        prefs(context).getString(KEY_TARGET_APP, null)?.takeIf { it.isNotBlank() }

    fun setTargetApp(context: Context, pkg: String?) {
        prefs(context).edit().apply {
            if (pkg.isNullOrBlank()) remove(KEY_TARGET_APP) else putString(KEY_TARGET_APP, pkg)
        }.apply()
    }

    fun isSmoothWalkEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SMOOTH_WALK, true)

    fun setSmoothWalkEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SMOOTH_WALK, enabled).apply()
    }

    /** 本周跑量目标（公里），非法值回落到默认 15km */
    fun weeklyGoalKm(context: Context): Float =
        prefs(context).getFloat(KEY_WEEKLY_GOAL_KM, DEFAULT_WEEKLY_GOAL_KM)
            .takeIf { it in 1f..200f } ?: DEFAULT_WEEKLY_GOAL_KM

    fun setWeeklyGoalKm(context: Context, km: Float) {
        prefs(context).edit()
            .putFloat(KEY_WEEKLY_GOAL_KM, km.coerceIn(1f, 200f))
            .apply()
    }

    fun favorites(context: Context): List<Favorite> = runCatching {
        val raw = prefs(context).getString(KEY_FAVORITES, null) ?: return emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val lat = obj.optDouble("lat", Double.NaN)
            val lng = obj.optDouble("lng", Double.NaN)
            if (lat.isNaN() || lng.isNaN()) {
                null
            } else {
                // 旧版本 JSON 没有 tag/pinned：opt 缺省补齐，反序列化不失败
                Favorite(
                    name = name,
                    lat = lat,
                    lng = lng,
                    tag = obj.optString("tag").takeIf { it.isNotBlank() } ?: "常用",
                    pinned = obj.optBoolean("pinned", false)
                )
            }
        }
    }.getOrDefault(emptyList())

    /** 置顶优先、其余保持最近使用顺序 */
    fun favoritesForQuickJump(context: Context): List<Favorite> =
        favorites(context).sortedByDescending { it.pinned }

    fun addFavorite(context: Context, favorite: Favorite) {
        val list = favorites(context).toMutableList()
        list.removeAll { it.name == favorite.name }
        list.add(0, favorite)
        while (list.size > MAX_FAVORITES) {
            // 淘汰时优先丢未置顶且最早加入的（列表尾部）；
            // 只剩置顶项时退化为丢最末一项，保证硬上限不被击穿
            // （此前 break 会让全置顶列表无限增长）
            val victim = list.indexOfLast { !it.pinned }
            if (victim <= 0) {
                list.removeAt(list.size - 1)
            } else {
                list.removeAt(victim)
            }
        }
        persistFavorites(context, list)
    }

    fun removeFavorite(context: Context, name: String) {
        persistFavorites(context, favorites(context).filterNot { it.name == name })
    }

    fun setFavoritePinned(context: Context, name: String, pinned: Boolean) {
        persistFavorites(
            context,
            favorites(context).map { if (it.name == name) it.copy(pinned = pinned) else it }
        )
    }

    fun setFavoriteTag(context: Context, name: String, tag: String) {
        val clean = tag.trim().ifEmpty { "常用" }.take(6)
        persistFavorites(
            context,
            favorites(context).map { if (it.name == name) it.copy(tag = clean) else it }
        )
    }

    private fun persistFavorites(context: Context, list: List<Favorite>) {
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("name", it.name)
                    .put("lat", it.lat)
                    .put("lng", it.lng)
                    .put("tag", it.tag)
                    .put("pinned", it.pinned)
            )
        }
        prefs(context).edit().putString(KEY_FAVORITES, array.toString()).apply()
    }
}
