package com.divealien.reminders.data.local.converter

import androidx.room.TypeConverter
import com.divealien.reminders.domain.model.RecurrenceType
import java.time.DayOfWeek

class Converters {

    @TypeConverter
    fun fromRecurrenceType(value: RecurrenceType): String = value.name

    @TypeConverter
    fun toRecurrenceType(value: String): RecurrenceType = RecurrenceType.valueOf(value)

    @TypeConverter
    fun fromDayOfWeekList(days: List<DayOfWeek>): String {
        return days.joinToString(",") { it.value.toString() }
    }

    @TypeConverter
    fun toDayOfWeekList(value: String): List<DayOfWeek> {
        if (value.isBlank()) return emptyList()
        return value.split(",").map { DayOfWeek.of(it.trim().toInt()) }
    }
}
