package com.zcshou.gogogo.run.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RunRouteEntity::class, RunScheduleEntity::class, RunRecordEntity::class],
    version = 5,
    // schema JSON 随构建导出到 app/schemas/ 并入库：迁移正确性可被
    // MigrationTestHelper 逐版本校验，避免升级用户数据库打不开
    exportSchema = true
)
abstract class GoRunDatabase : RoomDatabase() {
    abstract fun routeDao(): RunRouteDao
    abstract fun scheduleDao(): RunScheduleDao
    abstract fun recordDao(): RunRecordDao

    companion object {
        @Volatile
        private var instance: GoRunDatabase? = null

        /** v1 -> v2：云迹规格新增 方向/记忆参数/关联 App/记录 routeId 字段（androidTest 迁移测试用） */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE run_routes ADD COLUMN isClockwise INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE run_routes ADD COLUMN lastTargetDistanceKm REAL NOT NULL DEFAULT 3.0")
                db.execSQL("ALTER TABLE run_routes ADD COLUMN lastTargetPaceSecPerKm INTEGER NOT NULL DEFAULT 340")
                db.execSQL("ALTER TABLE run_routes ADD COLUMN lastSmartJitterEnabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE run_schedules ADD COLUMN linkedTargetAppPkg TEXT")
                db.execSQL("ALTER TABLE run_records ADD COLUMN routeId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE run_records SET routeId = 0")
            }
        }

        /** v2 -> v3：新增当地真实基准海拔（androidTest 迁移测试用） */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE run_routes ADD COLUMN baseAltitudeMeters REAL NOT NULL DEFAULT 50.0")
            }
        }

        /** v3 -> v4：为高频排序与过滤列补充索引 */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_run_routes_createdAt` ON `run_routes` (`createdAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_run_schedules_routeId` ON `run_schedules` (`routeId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_run_schedules_isScheduleEnabled` ON `run_schedules` (`isScheduleEnabled`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_run_records_status_startedAt` ON `run_records` (`status`, `startedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_run_records_startedAt` ON `run_records` (`startedAt`)")
            }
        }

        /**
         * v4 -> v5：一次性收口后续各期所需的字段，后续 Phase 不再零碎升库。
         * 全部带默认值 / 可空，保证旧路线、旧定时、旧跑次 100% 可读。
         */
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 路线：置顶 / 分类标签 / 可选海拔剖面
                db.execSQL("ALTER TABLE run_routes ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE run_routes ADD COLUMN tag TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE run_routes ADD COLUMN elevationProfileJson TEXT")
                // 定时：跳过单日 / 配速策略
                db.execSQL("ALTER TABLE run_schedules ADD COLUMN skipDateIso TEXT")
                db.execSQL("ALTER TABLE run_schedules ADD COLUMN paceStrategy TEXT NOT NULL DEFAULT 'STEADY'")
                // 跑次：真实分段 / 配速策略 / 累计爬升
                db.execSQL("ALTER TABLE run_records ADD COLUMN splitsJson TEXT")
                db.execSQL("ALTER TABLE run_records ADD COLUMN paceStrategy TEXT NOT NULL DEFAULT 'STEADY'")
                db.execSQL("ALTER TABLE run_records ADD COLUMN elevationGainMeters REAL NOT NULL DEFAULT 0.0")
            }
        }

        fun get(context: Context): GoRunDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    GoRunDatabase::class.java,
                    "go_run.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build().also { instance = it }
            }
    }
}