package com.example.personalovertimerecord.data.db

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.personalovertimerecord.utils.DatabaseKeyManager
import net.sqlcipher.database.SupportFactory

@Database(
    entities = [AttendanceEntity::class, CalendarLinkEntity::class, ChangeLogEntity::class],
    version = 10,
    // 导出 schema 供迁移测试与人工审阅；schema JSON 需随代码提交到 app/schemas/
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun attendanceDao(): AttendanceDao

    abstract fun calendarLinkDao(): CalendarLinkDao

    abstract fun changeLogDao(): ChangeLogDao

    companion object {
        private const val TAG = "AppDatabase"
        
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * 加密库初始化失败的原因（null 表示加密库正常工作）。
         * 注意：应用不再自动降级到明文库——加密初始化失败会直接抛出，
         * 由 UI 层引导用户处理，避免考勤/工资数据以明文落盘。
         */
        @Volatile
        var encryptionFallbackReason: String? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: createEncryptedDatabase(context).also { INSTANCE = it }
            }
        }

        /**
         * 只允许加密库（fail-closed）：
         * 历史上这里在加密初始化失败时会回退到明文库 overtime_database_unencrypted，
         * 一旦触发，全部考勤/工资/请假数据会以明文 SQLite 落盘。
         * 现改为记录原因并抛出，由界面引导用户处理，任何情况下都不再写明文数据库。
         */
        private fun createEncryptedDatabase(context: Context): AppDatabase {
            return try {
                encryptionFallbackReason = null
                val passphrase = DatabaseKeyManager.getDatabaseKey(context)
                val factory = SupportFactory(passphrase)

                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "overtime_database"
                )
                    .openHelperFactory(factory)
                    .addMigrations(*MIGRATIONS)
                    // 升级缺迁移时不再静默整库删除：仅对降级（通常不会发生）做破坏性处理。
                    // schema 漂移/缺迁移会让 Room 直接报错，宁可让用户升级失败并反馈，
                    // 也不能无声清空数年记录。
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create encrypted database (no plaintext fallback)", e)
                encryptionFallbackReason = e.message ?: e.javaClass.simpleName
                throw IllegalStateException(
                    "数据库加密初始化失败（${encryptionFallbackReason}）。" +
                        "为避免数据以明文保存，应用已停止启动。请重试；若持续失败，请备份后重新安装。",
                    e
                )
            }
        }
        
        // 从版本1迁移到版本2，添加 modifiedAt 字段
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 添加 modifiedAt 列，默认值为 NULL
                database.execSQL(
                    "ALTER TABLE attendance_records ADD COLUMN modifiedAt INTEGER"
                )
            }
        }

        // 从版本2迁移到版本3，添加请假相关字段（isLeave / leaveType / leaveHours）
        // 此前缺失该迁移，配合 fallbackToDestructiveMigration 会导致老用户升级时整库被删
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE attendance_records ADD COLUMN isLeave INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE attendance_records ADD COLUMN leaveType TEXT"
                )
                database.execSQL(
                    "ALTER TABLE attendance_records ADD COLUMN leaveHours REAL NOT NULL DEFAULT 0"
                )
            }
        }

        // 从版本3迁移到版本4，添加软删除标记字段
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 添加 isDeleted 列，默认值为 0（未删除）
                database.execSQL(
                    "ALTER TABLE attendance_records ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // 从版本4迁移到版本5，为 date 列添加唯一索引（按日期查询/排序更高效，
        // 同时在数据库层面保证同一天只有一条记录，防止重复打卡/重复登记）。
        // 索引名与 Room 对 @Index(value=["date"], unique=true) 自动生成的名称保持一致。
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 先清理历史重复数据：同一日期只保留一条
                // （优先保留未删除记录中 id 最大的；若该日期全部已删除则保留 id 最大的墓碑记录）
                database.execSQL(
                    """
                    DELETE FROM attendance_records
                    WHERE id NOT IN (
                        SELECT keep_id FROM (
                            SELECT COALESCE(
                                (SELECT MAX(id) FROM attendance_records a2
                                 WHERE a2.date = a1.date AND a2.isDeleted = 0),
                                (SELECT MAX(id) FROM attendance_records a3
                                 WHERE a3.date = a1.date)
                            ) AS keep_id
                            FROM (SELECT DISTINCT date FROM attendance_records) a1
                        )
                    )
                    """.trimIndent()
                )
                // 若此前已创建过非唯一索引则先移除，再创建唯一索引
                database.execSQL("DROP INDEX IF EXISTS index_attendance_records_date")
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_attendance_records_date ON attendance_records(date)"
                )
            }
        }

        // 从版本5迁移到版本6（仅用于修复历史 schema 漂移，不新增/删除任何列）：
        //
        // 背景：v5 时期 date 列的索引存在两种历史形态 —— 曾有一个构建分支以 version = 5
        // 声明的是「非唯一索引」(Index(value=["date"]))，而合并后的版本声明的是「唯一索引」
        // (Index(value=["date"], unique=true))，但版本号没有随之递增。于是旧设备上
        // identity hash 为 7f6c3140... 的 v5 数据库在升级到新构建时触发
        // “Room cannot verify the data integrity … Expected identity hash b8bd267b…, found 7f6c3140…”。
        //
        // 这里不做破坏性重建：先清理同日期重复记录（与 4→5 相同的保留策略），
        // 再统一重建为实体声明的唯一索引。对已经是唯一索引的健康 v5 库而言该迁移是幂等的空操作，
        // 数据完整保留；对历史非唯一索引库则就地修复，避免 fallbackToDestructiveMigration 清库。
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 先清理历史重复数据：同一日期只保留一条
                // （优先保留未删除记录中 id 最大的；若该日期全部已删除则保留 id 最大的墓碑记录）
                database.execSQL(
                    """
                    DELETE FROM attendance_records
                    WHERE id NOT IN (
                        SELECT keep_id FROM (
                            SELECT COALESCE(
                                (SELECT MAX(id) FROM attendance_records a2
                                 WHERE a2.date = a1.date AND a2.isDeleted = 0),
                                (SELECT MAX(id) FROM attendance_records a3
                                 WHERE a3.date = a1.date)
                            ) AS keep_id
                            FROM (SELECT DISTINCT date FROM attendance_records) a1
                        )
                    )
                    """.trimIndent()
                )
                // 若此前创建的是非唯一索引则先移除，再创建唯一索引（与实体声明保持一致）
                database.execSQL("DROP INDEX IF EXISTS index_attendance_records_date")
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_attendance_records_date ON attendance_records(date)"
                )
            }
        }

        // 从版本6迁移到版本7（新增每条记录的自定义工资倍率）：
        // customRate < 0 表示未自定义（按设置默认倍率计算），与实体 -1 哨兵默认值一致；
        // 旧行统一填 -1，历史记录的计算口径完全不变。
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE attendance_records ADD COLUMN customRate REAL NOT NULL DEFAULT -1.0"
                )
            }
        }

        // 从版本7迁移到版本8（新增每条记录的班次标记）：
        // shiftType "DAY"=白班（默认）/ "NIGHT"=夜班；仅作记录与统计展示，不影响工资计算；
        // 旧行统一填 DAY，历史记录语义不变。
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE attendance_records ADD COLUMN shiftType TEXT NOT NULL DEFAULT 'DAY'"
                )
            }
        }

        // 从版本8迁移到版本9（新增系统日历映射表）：
        // 仅保存本机记录与日历事件的对应关系，不参与备份/云端同步；空表即可，无需迁移历史数据。
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS calendar_links (" +
                        "eventId INTEGER NOT NULL, " +
                        "calendarId INTEGER NOT NULL, " +
                        "recordDate TEXT NOT NULL, " +
                        "syncedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(eventId))"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_calendar_links_recordDate " +
                        "ON calendar_links(recordDate)"
                )
            }
        }

        // 从版本9迁移到版本10（新增修改日志表）：
        // 记录本机用户对考勤记录的手动增/改/删/清空历史；空表即可，无历史数据可迁。
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS change_logs (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "timestamp INTEGER NOT NULL, " +
                        "recordDate TEXT NOT NULL, " +
                        "changeType TEXT NOT NULL, " +
                        "summary TEXT NOT NULL)"
                )
            }
        }

        /**
         * 全部迁移（1→2 … 9→10），构建器与迁移测试共用同一份。
         * 注意：需声明在所有 MIGRATION_x_y 之后。
         */
        @JvmField
        val MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10
        )
    }
}
