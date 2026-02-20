package com.divealien.reminders.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.divealien.reminders.data.local.converter.Converters
import com.divealien.reminders.data.local.entity.ReminderEntity
import com.divealien.reminders.util.Constants

@Database(entities = [ReminderEntity::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class ReminderDatabase : RoomDatabase() {

    abstract fun reminderDao(): ReminderDao

    companion object {
        @Volatile
        private var INSTANCE: ReminderDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reminders ADD COLUMN completedAt INTEGER DEFAULT NULL")
            }
        }

        fun getInstance(context: Context): ReminderDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ReminderDatabase::class.java,
                    Constants.DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        fun closeAndReset() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}
