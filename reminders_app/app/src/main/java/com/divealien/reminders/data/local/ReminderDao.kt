package com.divealien.reminders.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.divealien.reminders.data.local.entity.ReminderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {

    @Query("SELECT * FROM reminders ORDER BY nextTriggerTime ASC")
    fun getAllReminders(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE isEnabled = 1 ORDER BY nextTriggerTime ASC")
    fun getActiveReminders(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders")
    suspend fun getAllRemindersList(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE isEnabled = 1")
    suspend fun getActiveRemindersList(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getReminderById(id: Long): ReminderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reminder: ReminderEntity): Long

    @Update
    suspend fun update(reminder: ReminderEntity)

    @Delete
    suspend fun delete(reminder: ReminderEntity)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM reminders")
    suspend fun deleteAll()

    @Query("SELECT * FROM reminders WHERE completedAt IS NULL ORDER BY nextTriggerTime ASC")
    fun getActiveRemindersExcludeCompleted(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE completedAt IS NOT NULL ORDER BY completedAt DESC")
    fun getCompletedReminders(): Flow<List<ReminderEntity>>

    @Query("DELETE FROM reminders WHERE completedAt IS NOT NULL AND completedAt < :cutoff")
    suspend fun deleteCompletedOlderThan(cutoff: Long)
}
