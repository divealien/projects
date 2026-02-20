package com.divealien.reminders.util

object Constants {
    const val NOTIFICATION_CHANNEL_ID = "reminders_channel"
    const val EXTRA_REMINDER_ID = "extra_reminder_id"
    const val EXTRA_SNOOZE_FLAG = "extra_snooze_flag"
    const val ACTION_COMPLETE = "com.divealien.reminders.ACTION_COMPLETE"

    const val DATABASE_NAME = "reminders.db"
    const val BACKUP_FILE_NAME = "reminders_backup.csv"

    const val BACKUP_DEBOUNCE_MS = 2000L

    const val EXTRA_SNOOZE_DURATION = "extra_snooze_duration"
    const val SNOOZE_10_MIN = 10 * 60 * 1000L
}
