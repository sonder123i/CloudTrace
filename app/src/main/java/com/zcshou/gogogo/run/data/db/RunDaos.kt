package com.zcshou.gogogo.run.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RunRouteDao {
    /** 置顶路线永远靠前，其后按创建时间倒序 */
    @Query("SELECT * FROM run_routes ORDER BY isPinned DESC, createdAt DESC")
    fun observeRoutes(): Flow<List<RunRouteEntity>>

    @Query("SELECT * FROM run_routes WHERE id = :id")
    suspend fun getById(id: Long): RunRouteEntity?

    @Query("SELECT COUNT(*) FROM run_routes")
    suspend fun count(): Int

    @Query("UPDATE run_routes SET isPinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("UPDATE run_routes SET tag = :tag WHERE id = :id")
    suspend fun setTag(id: Long, tag: String)

    @Insert
    suspend fun insert(route: RunRouteEntity): Long

    @Update
    suspend fun update(route: RunRouteEntity)

    @Delete
    suspend fun delete(route: RunRouteEntity)
}

@Dao
interface RunScheduleDao {
    @Query("SELECT * FROM run_schedules ORDER BY id DESC")
    fun observeSchedules(): Flow<List<RunScheduleEntity>>

    @Query("SELECT * FROM run_schedules WHERE routeId = :routeId ORDER BY id DESC")
    fun observeByRoute(routeId: Long): Flow<List<RunScheduleEntity>>

    @Query("SELECT * FROM run_schedules WHERE isScheduleEnabled = 1")
    suspend fun getEnabled(): List<RunScheduleEntity>

    @Query("SELECT * FROM run_schedules WHERE id = :id")
    suspend fun getById(id: Long): RunScheduleEntity?

    @Insert
    suspend fun insert(schedule: RunScheduleEntity): Long

    @Update
    suspend fun update(schedule: RunScheduleEntity)

    @Delete
    suspend fun delete(schedule: RunScheduleEntity)
}

@Dao
interface RunRecordDao {
    @Query("SELECT * FROM run_records ORDER BY startedAt DESC")
    fun observeRecords(): Flow<List<RunRecordEntity>>

    /** 指定时间戳之后的全量记录（月历热力图 / 连续打卡统计用，含未完成跑次） */
    @Query("SELECT * FROM run_records WHERE startedAt >= :sinceMs ORDER BY startedAt DESC")
    fun observeRecordsSince(sinceMs: Long): Flow<List<RunRecordEntity>>

    /** 全部已完成跑次的开始时间（倒序）：连续打卡天数与月度热力在内存里聚合，避免多次查库 */
    @Query("SELECT startedAt FROM run_records WHERE status = 'COMPLETED' ORDER BY startedAt DESC")
    fun observeCompletedTimestamps(): Flow<List<Long>>

    /** 指定时间戳之后的累计里程（本周概览用） */
    @Query("SELECT COALESCE(SUM(actualDistanceMeters), 0) FROM run_records WHERE startedAt >= :sinceMs AND status = 'COMPLETED'")
    fun observeCompletedMetersSince(sinceMs: Long): Flow<Double>

    @Query("SELECT COUNT(*) FROM run_records WHERE startedAt >= :sinceMs AND status = 'COMPLETED'")
    fun observeCompletedCountSince(sinceMs: Long): Flow<Int>

    /** 最近 N 条已完成记录（周目标环 / 首页微件摘要用） */
    @Query("SELECT * FROM run_records WHERE status = 'COMPLETED' ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recentCompleted(limit: Int): List<RunRecordEntity>

    /** 支持邀请的触发依据：只统计真正完成过的跑次与里程。 */
    @Query(
        """
        SELECT COUNT(*) AS completedCount,
               COALESCE(SUM(actualDistanceMeters), 0) AS totalMeters,
               MIN(startedAt) AS firstCompletedAt,
               MAX(startedAt) AS lastCompletedAt
        FROM run_records
        WHERE status = 'COMPLETED'
        """
    )
    suspend fun completedStats(): RunUsageStats

    @Insert
    suspend fun insert(record: RunRecordEntity): Long
}