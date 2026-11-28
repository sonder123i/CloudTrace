package com.zcshou.gogogo.run.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 1. 跑圈路线模板表（一次创建，永久复用；内部全部存储 WGS-84 坐标）
 */
@Entity(
    tableName = "run_routes",
    indices = [Index(value = ["createdAt"])]
)
data class RunRouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,                      // 路线名称，如 "南操场 400m 标准圈"
    val buildMode: String,                 // "TRACK_SPLINE" | "ROAD_SNAP" | "GPX_IMPORT"
    val singleLapMeters: Double,           // 单圈基准总长度（米）
    val isClosedLoop: Boolean = true,      // true=首尾闭环绕圈，false=原路折返跑
    val isClockwise: Boolean = true,       // 跑圈方向（编辑器可一键反转）
    val denseWaypointsJson: String,        // 插值/吸附后的高密度 WGS-84 轨迹序列 JSON
    val anchorNodesJson: String,           // 用户手动点击的原始锚点序列 JSON（支持单点停留秒数）
    val baseAltitudeMeters: Double = 50.0, // 当地真实基准海拔（保存时采样，引擎据此叠加微地形）
    // --- 记忆上次开跑参数（支撑首页 1 步直达开跑） ---
    val lastTargetDistanceKm: Float = 3.0f,
    val lastTargetPaceSecPerKm: Int = 340,
    val lastSmartJitterEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    // --- v5：工作台组织字段 ---
    /** 置顶：首页路线切换条与列表把置顶路线永远排在最前 */
    val isPinned: Boolean = false,
    /** 分类标签（如「操场」「公园」「校园」「测试」），空串表示未分类 */
    val tag: String = "",
    /**
     * 可选海拔剖面：GPX `<ele>` 或按微地形估算的真实海拔序列 JSON，
     * 如 `[50.2, 51.0, ...]`；旧路线为 null 时预览页回退到曲率/微地形估算
     */
    val elevationProfileJson: String? = null
)

/**
 * 2. 跑步任务预设与定时调度表
 */
@Entity(
    tableName = "run_schedules",
    indices = [Index(value = ["routeId"]), Index(value = ["isScheduleEnabled"])]
)
data class RunScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routeId: Long,                     // 关联的路线 ID
    val title: String,                     // 任务名称，如 "晚间 3.2 公里有氧"
    val targetDistanceKm: Float,           // 目标公里数，如 3.2f
    val distanceJitterMeters: Int = 150,   // 智能拟真里程正偏余量上限（0~150米，防每次卡整点结束）
    val targetPaceSecPerKm: Int = 340,     // 基准配速（秒/公里，340即 5'40"）
    val enableLaneDrift: Boolean = true,   // 是否开启“每圈曲率自适应变道防重叠”
    val enableCooldownWalk: Boolean = true,// 达标后是否开启 25 秒慢走缓冲收尾
    // --- 定时调度配置 ---
    val isScheduleEnabled: Boolean = false,
    val windowStartMinutes: Int = 1110,    // 启动窗口起点（当日分钟数，如 18:30 = 1110）
    val windowJitterMinutes: Int = 8,      // 随机延迟窗口（分钟，防每天分秒不差）
    val repeatDaysMask: Int = 0b0011111,   // 周一至周日位掩码（bit0=周一 … bit4=周五）
    val autoIsolateWifi: Boolean = true,   // 开跑前是否触发 Wi-Fi 隔离（Shizuku 静音断网或弹窗提醒）
    val linkedTargetAppPkg: String? = null, // 关联目标跑步 App 包名（通知点击一键拉起）
    // --- v5：跳过单日与配速策略 ---
    /** 跳过该日一次触发（ISO 本地日期串，如 `2026-11-28`）；触发后由 Receiver 自动清空 */
    val skipDateIso: String? = null,
    /** 定时自动开跑使用的配速策略（PaceStrategy.name） */
    val paceStrategy: String = "STEADY"
)

/**
 * 3. 跑步执行历史表
 */
@Entity(
    tableName = "run_records",
    indices = [Index(value = ["status", "startedAt"]), Index(value = ["startedAt"])]
)
data class RunRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routeId: Long = 0,
    val routeName: String,
    val actualDistanceMeters: Double,
    val durationSeconds: Int,
    val averagePaceSec: Int,
    val startedAt: Long,
    val status: String,                    // "COMPLETED" | "ABORTED"
    // --- v5：真实分段 / 策略 / 爬升 ---
    /**
     * 真实分段 JSON：`[{"idx":1,"distM":1000,"durSec":338,"paceSec":338,"phase":"STEADY"}]`，
     * 旧记录为 null（战报复盘回退到历史估算并显式标注）
     */
    val splitsJson: String? = null,
    /** 本次使用的配速策略（PaceStrategy.name） */
    val paceStrategy: String = "STEADY",
    /** 累计正海拔爬升（米） */
    val elevationGainMeters: Double = 0.0
)

/**
 * 已完成跑次的使用总览。
 *
 * 只用于「支持云迹」邀请的触发判定：必须真的跑过多次、跑够里程，
 * 才认为用户确实体验到了功能价值，而不是按安装时长硬推。
 */
data class RunUsageStats(
    val completedCount: Int,
    val totalMeters: Double,
    val firstCompletedAt: Long?,
    val lastCompletedAt: Long?
)