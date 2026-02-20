package com.divealien.reminders.domain.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

data class Reminder(
    val id: Long = 0,
    val title: String,
    val notes: String = "",
    val nextTriggerTime: Long, // epoch millis
    val originalDateTime: Long, // epoch millis — the originally chosen date/time
    val recurrenceType: RecurrenceType = RecurrenceType.NONE,
    val recurrenceInterval: Int? = null, // for EVERY_N_DAYS
    val recurrenceDaysOfWeek: List<DayOfWeek> = emptyList(), // for WEEKLY
    val isEnabled: Boolean = true,
    val isSnoozed: Boolean = false,
    val snoozeUntil: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
) {
    val nextTriggerDateTime: LocalDateTime
        get() = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(nextTriggerTime),
            ZoneId.systemDefault()
        )

    val isRecurring: Boolean
        get() = recurrenceType != RecurrenceType.NONE

    val isPast: Boolean
        get() = nextTriggerTime < System.currentTimeMillis() && !isSnoozed

    val isCompleted: Boolean
        get() = completedAt != null
}
