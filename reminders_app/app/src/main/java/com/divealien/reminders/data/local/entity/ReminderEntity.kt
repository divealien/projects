package com.divealien.reminders.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.divealien.reminders.domain.model.RecurrenceType
import com.divealien.reminders.domain.model.Reminder
import java.time.DayOfWeek

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val notes: String = "",
    val nextTriggerTime: Long,
    val originalDateTime: Long,
    val recurrenceType: RecurrenceType = RecurrenceType.NONE,
    val recurrenceInterval: Int? = null,
    val recurrenceDaysOfWeek: List<DayOfWeek> = emptyList(),
    val isEnabled: Boolean = true,
    val isSnoozed: Boolean = false,
    val snoozeUntil: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
) {
    fun toDomain(): Reminder = Reminder(
        id = id,
        title = title,
        notes = notes,
        nextTriggerTime = nextTriggerTime,
        originalDateTime = originalDateTime,
        recurrenceType = recurrenceType,
        recurrenceInterval = recurrenceInterval,
        recurrenceDaysOfWeek = recurrenceDaysOfWeek,
        isEnabled = isEnabled,
        isSnoozed = isSnoozed,
        snoozeUntil = snoozeUntil,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt
    )

    companion object {
        fun fromDomain(reminder: Reminder): ReminderEntity = ReminderEntity(
            id = reminder.id,
            title = reminder.title,
            notes = reminder.notes,
            nextTriggerTime = reminder.nextTriggerTime,
            originalDateTime = reminder.originalDateTime,
            recurrenceType = reminder.recurrenceType,
            recurrenceInterval = reminder.recurrenceInterval,
            recurrenceDaysOfWeek = reminder.recurrenceDaysOfWeek,
            isEnabled = reminder.isEnabled,
            isSnoozed = reminder.isSnoozed,
            snoozeUntil = reminder.snoozeUntil,
            createdAt = reminder.createdAt,
            updatedAt = reminder.updatedAt,
            completedAt = reminder.completedAt
        )
    }
}
