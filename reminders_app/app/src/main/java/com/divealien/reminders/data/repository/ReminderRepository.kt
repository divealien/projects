package com.divealien.reminders.data.repository

import com.divealien.reminders.data.local.ReminderDao
import com.divealien.reminders.data.local.entity.ReminderEntity
import com.divealien.reminders.domain.model.Reminder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ReminderRepository(
    private val dao: ReminderDao,
    private val onMutation: (suspend () -> Unit)? = null
) {

    fun getAllReminders(): Flow<List<Reminder>> {
        return dao.getAllReminders().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    fun getActiveReminders(): Flow<List<Reminder>> {
        return dao.getActiveReminders().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    suspend fun getActiveRemindersList(): List<Reminder> {
        return dao.getActiveRemindersList().map { it.toDomain() }
    }

    suspend fun getReminderById(id: Long): Reminder? {
        return dao.getReminderById(id)?.toDomain()
    }

    suspend fun save(reminder: Reminder): Long {
        val entity = ReminderEntity.fromDomain(
            reminder.copy(updatedAt = System.currentTimeMillis())
        )
        val id = if (reminder.id == 0L) {
            dao.insert(entity)
        } else {
            dao.update(entity)
            reminder.id
        }
        onMutation?.invoke()
        return id
    }

    suspend fun delete(reminder: Reminder) {
        dao.deleteById(reminder.id)
        onMutation?.invoke()
    }

    suspend fun deleteById(id: Long) {
        dao.deleteById(id)
        onMutation?.invoke()
    }

    fun getRemindersExcludeCompleted(): Flow<List<Reminder>> {
        return dao.getActiveRemindersExcludeCompleted().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    fun getCompletedReminders(): Flow<List<Reminder>> {
        return dao.getCompletedReminders().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    suspend fun deleteOldCompleted(retentionDays: Int) {
        val cutoff = System.currentTimeMillis() - retentionDays * 86400000L
        dao.deleteCompletedOlderThan(cutoff)
    }
}
