package com.zcshou.gogogo.run.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 迁移回归：用入库的 schema JSON 逐版本建库、灌数、跑迁移，由 Room 校验表结构。
 * 迁移写错（列类型/约束不符）会让升级用户数据库直接打不开、路线数据不可恢复，
 * 因此必须用 MigrationTestHelper 对照导出的 schema 验证，而不是只确认 ALTER 能执行。
 */
@RunWith(AndroidJUnit4::class)
class GoRunDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        GoRunDatabase::class.java
    )

    @Test
    fun migrate1To2_keepsRowsAndFillsDefaults() {
        helper.createDatabase(DB_NAME, 1).use { db ->
            db.execSQL(
                "INSERT INTO run_routes (name, buildMode, singleLapMeters, isClosedLoop, denseWaypointsJson, anchorNodesJson, createdAt) " +
                    "VALUES ('操场圈', 'TRACK_SPLINE', 400.0, 1, '[]', '[]', 0)"
            )
            db.execSQL(
                "INSERT INTO run_records (routeName, actualDistanceMeters, durationSeconds, averagePaceSec, startedAt, status) " +
                    "VALUES ('操场圈', 1000.0, 300, 300, 0, 'COMPLETED')"
            )
        }
        val db = helper.runMigrationsAndValidate(DB_NAME, 2, true, GoRunDatabase.MIGRATION_1_2)
        db.query(
            "SELECT isClockwise, lastTargetDistanceKm, lastTargetPaceSecPerKm, lastSmartJitterEnabled FROM run_routes"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
            assertEquals(3.0f, c.getFloat(1), 1e-6f)
            assertEquals(340, c.getInt(2))
            assertEquals(1, c.getInt(3))
        }
        db.query("SELECT routeId FROM run_records").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0L, c.getLong(0))
        }
        db.close()
    }

    @Test
    fun migrate2To3_addsBaseAltitudeDefault() {
        helper.createDatabase(DB_NAME, 2).use { db ->
            db.execSQL(
                "INSERT INTO run_routes (name, buildMode, singleLapMeters, isClosedLoop, isClockwise, denseWaypointsJson, anchorNodesJson, lastTargetDistanceKm, lastTargetPaceSecPerKm, lastSmartJitterEnabled, createdAt) " +
                    "VALUES ('操场圈', 'TRACK_SPLINE', 400.0, 1, 1, '[]', '[]', 3.0, 340, 1, 0)"
            )
        }
        val db = helper.runMigrationsAndValidate(DB_NAME, 3, true, GoRunDatabase.MIGRATION_2_3)
        db.query("SELECT baseAltitudeMeters FROM run_routes").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(50.0, c.getDouble(0), 1e-9)
        }
        db.close()
    }

    @Test
    fun migrate3To4_createsIndices() {
        helper.createDatabase(DB_NAME, 3).close()
        val db = helper.runMigrationsAndValidate(DB_NAME, 4, true, GoRunDatabase.MIGRATION_3_4)
        db.close()
    }

    @Test
    fun migrate4To5_keepsRowsAndFillsDefaults() {
        helper.createDatabase(DB_NAME, 4).use { db ->
            db.execSQL(
                "INSERT INTO run_routes (name, buildMode, singleLapMeters, isClosedLoop, isClockwise, " +
                    "denseWaypointsJson, anchorNodesJson, baseAltitudeMeters, lastTargetDistanceKm, " +
                    "lastTargetPaceSecPerKm, lastSmartJitterEnabled, createdAt) " +
                    "VALUES ('南操场', 'TRACK_SPLINE', 400.0, 1, 1, '[]', '[]', 48.5, 3.2, 335, 1, 1000)"
            )
            db.execSQL(
                "INSERT INTO run_schedules (routeId, title, targetDistanceKm, distanceJitterMeters, " +
                    "targetPaceSecPerKm, enableLaneDrift, enableCooldownWalk, isScheduleEnabled, " +
                    "windowStartMinutes, windowJitterMinutes, repeatDaysMask, autoIsolateWifi, linkedTargetAppPkg) " +
                    "VALUES (1, '晚间有氧', 3.0, 150, 340, 1, 1, 1, 1110, 8, 31, 1, NULL)"
            )
            db.execSQL(
                "INSERT INTO run_records (routeId, routeName, actualDistanceMeters, durationSeconds, " +
                    "averagePaceSec, startedAt, status) " +
                    "VALUES (1, '南操场', 3040.0, 1030, 339, 2000, 'COMPLETED')"
            )
        }
        val db = helper.runMigrationsAndValidate(DB_NAME, 5, true, GoRunDatabase.MIGRATION_4_5)

        // 旧路线：一行都不能丢，新列取安全默认值
        db.query(
            "SELECT name, singleLapMeters, baseAltitudeMeters, lastTargetDistanceKm, isPinned, tag, elevationProfileJson FROM run_routes"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("南操场", c.getString(0))
            assertEquals(400.0, c.getDouble(1), 1e-9)
            assertEquals(48.5, c.getDouble(2), 1e-9)
            assertEquals(3.2f, c.getFloat(3), 1e-6f)
            assertEquals(0, c.getInt(4))
            assertEquals("", c.getString(5))
            assertTrue(c.isNull(6))
            assertEquals(1, c.count)
        }
        // 旧定时：跳过日为空、策略回落到匀速
        db.query("SELECT title, repeatDaysMask, isScheduleEnabled, skipDateIso, paceStrategy FROM run_schedules").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("晚间有氧", c.getString(0))
            assertEquals(31, c.getInt(1))
            assertEquals(1, c.getInt(2))
            assertTrue(c.isNull(3))
            assertEquals("STEADY", c.getString(4))
        }
        // 旧跑次：分段为空（复盘回退估算）、爬升 0、策略匀速
        db.query(
            "SELECT routeName, actualDistanceMeters, durationSeconds, splitsJson, paceStrategy, elevationGainMeters FROM run_records"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("南操场", c.getString(0))
            assertEquals(3040.0, c.getDouble(1), 1e-9)
            assertEquals(1030, c.getInt(2))
            assertTrue(c.isNull(3))
            assertEquals("STEADY", c.getString(4))
            assertEquals(0.0, c.getDouble(5), 1e-9)
        }
        db.close()
    }

    private companion object {
        const val DB_NAME = "go-run-migration-test.db"
    }
}
