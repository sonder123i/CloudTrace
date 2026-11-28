package com.zcshou.gogogo.run.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * 真实分段：一段结束时的里程 / 耗时 / 平均配速，以及该段所处引擎相位。
 *
 * [phase] 取 [RunPhase] 的名字，用于复盘时区分正常跑、绕路打卡与慢走收尾。
 */
data class RunSplit(
    val idx: Int,
    val distM: Int,
    val durSec: Int,
    val paceSec: Int,
    val phase: String = RunPhase.RUNNING.name
)

/**
 * `RunRecordEntity.splitsJson` 的编解码（纯逻辑，可 JVM 单测）。
 *
 * 格式与规格书一致：`[{"idx":1,"distM":1000,"durSec":338,"paceSec":338,"phase":"STEADY"}]`。
 * 解码对旧数据/损坏数据一律返回空列表，战报复盘据此回退到历史估算。
 */
object RunSplitCodec {

    fun encode(splits: List<RunSplit>): String? {
        if (splits.isEmpty()) return null
        val array = JSONArray()
        splits.forEach { split ->
            array.put(
                JSONObject()
                    .put("idx", split.idx)
                    .put("distM", split.distM)
                    .put("durSec", split.durSec)
                    .put("paceSec", split.paceSec)
                    .put("phase", split.phase)
            )
        }
        return array.toString()
    }

    fun decode(json: String?): List<RunSplit> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val distM = obj.optInt("distM", 0)
                val durSec = obj.optInt("durSec", 0)
                if (distM <= 0 || durSec <= 0) return@mapNotNull null
                RunSplit(
                    idx = obj.optInt("idx", index + 1),
                    distM = distM,
                    durSec = durSec,
                    paceSec = obj.optInt("paceSec", durSec * 1000 / distM),
                    phase = obj.optString("phase").takeIf { it.isNotBlank() }
                        ?: RunPhase.RUNNING.name
                )
            }
        }.getOrDefault(emptyList())
    }
}
