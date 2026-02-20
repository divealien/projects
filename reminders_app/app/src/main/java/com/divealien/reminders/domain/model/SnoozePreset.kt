package com.divealien.reminders.domain.model

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

sealed class SnoozePreset {

    abstract fun computeTargetTime(): Long
    abstract fun displayLabel(): String
    open fun settingsLabel(): String = displayLabel()

    data class RelativeMinutes(val minutes: Int) : SnoozePreset() {
        override fun computeTargetTime(): Long =
            System.currentTimeMillis() + minutes * 60_000L

        override fun displayLabel(): String = when {
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0 -> "${minutes / 60} hr"
            else -> "${minutes / 60}h ${minutes % 60}m"
        }
    }

    data class RelativeDays(val days: Int) : SnoozePreset() {
        override fun computeTargetTime(): Long {
            val now = LocalDateTime.now()
            val target = now.plusDays(days.toLong())
                .withHour(now.hour).withMinute(now.minute).withSecond(0).withNano(0)
            return target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }

        override fun displayLabel(): String = when (days) {
            1 -> "1 day"
            else -> "$days days"
        }
    }

    data class TomorrowAt(val hour: Int, val minute: Int) : SnoozePreset() {
        private fun targetDateTime(): LocalDateTime {
            val todayAt = LocalDateTime.of(LocalDate.now(), LocalTime.of(hour, minute))
            return if (todayAt.isAfter(LocalDateTime.now())) todayAt
            else todayAt.plusDays(1)
        }

        override fun computeTargetTime(): Long =
            targetDateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        override fun displayLabel(): String {
            val isToday = targetDateTime().toLocalDate() == LocalDate.now()
            val prefix = if (isToday) "Today" else "Tomorrow"
            return "$prefix ${timeLabel()}"
        }

        override fun settingsLabel(): String = timeLabel()

        private fun timeLabel(): String {
            val period = if (hour < 12) "AM" else "PM"
            val displayHour = when {
                hour == 0 -> 12
                hour > 12 -> hour - 12
                else -> hour
            }
            return if (minute == 0) "$displayHour $period"
            else "$displayHour:%02d $period".format(minute)
        }
    }

    companion object {
        val defaults: List<SnoozePreset> = listOf(
            RelativeMinutes(15),
            RelativeMinutes(60),
            RelativeDays(2),
            RelativeDays(7),
            TomorrowAt(9, 0),
            TomorrowAt(12, 0),
            TomorrowAt(18, 0)
        )

        fun toJson(presets: List<SnoozePreset>): String {
            val array = JSONArray()
            for (preset in presets) {
                val obj = JSONObject()
                when (preset) {
                    is RelativeMinutes -> {
                        obj.put("type", "relative_minutes")
                        obj.put("minutes", preset.minutes)
                    }
                    is RelativeDays -> {
                        obj.put("type", "relative_days")
                        obj.put("days", preset.days)
                    }
                    is TomorrowAt -> {
                        obj.put("type", "tomorrow_at")
                        obj.put("hour", preset.hour)
                        obj.put("minute", preset.minute)
                    }
                }
                array.put(obj)
            }
            return array.toString()
        }

        fun fromJson(json: String): List<SnoozePreset> {
            if (json.isBlank()) return defaults
            return try {
                val array = JSONArray(json)
                (0 until array.length()).mapNotNull { i ->
                    val obj = array.getJSONObject(i)
                    when (obj.getString("type")) {
                        "relative_minutes" -> RelativeMinutes(obj.getInt("minutes"))
                        "relative_days" -> RelativeDays(obj.getInt("days"))
                        "tomorrow_at" -> TomorrowAt(obj.getInt("hour"), obj.getInt("minute"))
                        else -> null
                    }
                }
            } catch (e: Exception) {
                defaults
            }
        }
    }
}
