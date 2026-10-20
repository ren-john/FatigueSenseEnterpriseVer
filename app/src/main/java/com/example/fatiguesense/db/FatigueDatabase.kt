package com.example.fatiguesense.enterprise.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [FatigueEventEntity::class],
    version  = 2,
    exportSchema = false,
)
abstract class FatigueDatabase : RoomDatabase() {

    abstract fun fatigueEventDao(): FatigueEventDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE fatigue_events ADD COLUMN triggerType TEXT NOT NULL DEFAULT 'EAR'")
            }
        }
        @Volatile private var INSTANCE: FatigueDatabase? = null

        fun getInstance(context: Context): FatigueDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FatigueDatabase::class.java,
                    "fatiguesense.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
