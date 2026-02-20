package com.divealien.reminders.util

import com.divealien.reminders.domain.model.RecurrenceType
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

object DateTimeUtils {

    private val dateFormatter = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy  HH:mm")

    fun formatDate(epochMillis: Long): String {
        val ldt = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(epochMillis),
            ZoneId.systemDefault()
        )
        return ldt.format(dateFormatter)
    }

    fun formatTime(epochMillis: Long): String {
        val ldt = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(epochMillis),
            ZoneId.systemDefault()
        )
        return ldt.format(timeFormatter)
    }

    fun formatDateTime(epochMillis: Long): String {
        val ldt = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(epochMillis),
            ZoneId.systemDefault()
        )
        return ldt.format(dateTimeFormatter)
    }

    fun toEpochMillis(dateTime: LocalDateTime): Long {
        return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    fun fromEpochMillis(epochMillis: Long): LocalDateTime {
        return LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(epochMillis),
            ZoneId.systemDefault()
        )
    }

    /**
     * Computes the next occurrence after [afterTime] based on recurrence settings.
     * Returns epoch millis of the next trigger time.
     */
    fun computeNextOccurrence(
        afterTime: Long,
        originalDateTime: Long,
        recurrenceType: RecurrenceType,
        recurrenceInterval: Int?,
        recurrenceDaysOfWeek: List<DayOfWeek>
    ): Long? {
        val after = fromEpochMillis(afterTime)
        val original = fromEpochMillis(originalDateTime)

        val next = when (recurrenceType) {
            RecurrenceType.NONE -> return null

            RecurrenceType.DAILY -> after.plusDays(1)
                .withHour(original.hour).withMinute(original.minute).withSecond(0)

            RecurrenceType.EVERY_N_DAYS -> {
                val interval = recurrenceInterval ?: 1
                after.plusDays(interval.toLong())
                    .withHour(original.hour).withMinute(original.minute).withSecond(0)
            }

            RecurrenceType.WEEKLY -> {
                if (recurrenceDaysOfWeek.isEmpty()) return null
                computeNextWeeklyOccurrence(after, original, recurrenceDaysOfWeek)
            }

            RecurrenceType.MONTHLY -> {
                var candidate = after.plusMonths(1)
                    .withHour(original.hour).withMinute(original.minute).withSecond(0)
                // Handle day overflow (e.g. Jan 31 → Feb 28)
                val targetDay = original.dayOfMonth
                val maxDay = candidate.toLocalDate().lengthOfMonth()
                candidate = candidate.withDayOfMonth(minOf(targetDay, maxDay))
                candidate
            }

            RecurrenceType.YEARLY -> {
                var candidate = after.plusYears(1)
                    .withHour(original.hour).withMinute(original.minute).withSecond(0)
                // Handle Feb 29 → Feb 28 in non-leap years
                val targetDay = original.dayOfMonth
                val maxDay = candidate.toLocalDate().lengthOfMonth()
                candidate = candidate.withDayOfMonth(minOf(targetDay, maxDay))
                candidate
            }
        }

        return toEpochMillis(next)
    }

    private fun computeNextWeeklyOccurrence(
        after: LocalDateTime,
        original: LocalDateTime,
        daysOfWeek: List<DayOfWeek>
    ): LocalDateTime {
        val sortedDays = daysOfWeek.sorted()
        // Find the next day of week after 'after'
        var candidate = after.plusDays(1)
            .withHour(original.hour).withMinute(original.minute).withSecond(0)

        for (i in 0 until 7) {
            val check = candidate.plusDays(i.toLong())
            if (check.dayOfWeek in sortedDays) {
                return check
            }
        }

        // Fallback: next occurrence of first day in list
        return candidate.with(TemporalAdjusters.next(sortedDays.first()))
    }

    fun recurrenceLabel(
        recurrenceType: RecurrenceType,
        recurrenceInterval: Int?,
        recurrenceDaysOfWeek: List<DayOfWeek>
    ): String {
        return when (recurrenceType) {
            RecurrenceType.NONE -> "Does not repeat"
            RecurrenceType.DAILY -> "Daily"
            RecurrenceType.EVERY_N_DAYS -> "Every ${recurrenceInterval ?: 1} days"
            RecurrenceType.WEEKLY -> {
                val days = recurrenceDaysOfWeek.sorted().joinToString(", ") {
                    it.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() }
                }
                "Weekly on $days"
            }
            RecurrenceType.MONTHLY -> "Monthly"
            RecurrenceType.YEARLY -> "Yearly"
        }
    }
}
