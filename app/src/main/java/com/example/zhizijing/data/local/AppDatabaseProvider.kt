package com.example.zhizijing.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object AppDatabaseProvider {
    @Volatile
    private var instance: AppDatabase? = null

    // Room 单例数据库入口
    fun get(context: Context): AppDatabase =
        instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "zhizijing.db",
            )
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }

    // 版本迁移补齐姿态字段
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE action_results ADD COLUMN postureLevel TEXT")
            db.execSQL("UPDATE action_results SET postureLevel = depthLevel WHERE postureLevel IS NULL")
        }
    }
}
