package com.divealien.reminders.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.divealien.reminders.RemindersApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as RemindersApp
                val dao = app.database.reminderDao()
                val activeReminders = dao.getActiveRemindersList()
                val now = System.currentTimeMillis()

                for (entity in activeReminders) {
                    val reminder = entity.toDomain()
                    if (reminder.nextTriggerTime <= now) {
                        // Missed alarm — show notification immediately
                        app.notificationHelper.showNotification(reminder)

                        // Advance recurring reminders
                        if (reminder.isRecurring) {
                            val nextTime = com.divealien.reminders.util.DateTimeUtils.computeNextOccurrence(
                                afterTime = now,
                                originalDateTime = reminder.originalDateTime,
                                recurrenceType = reminder.recurrenceType,
                                recurrenceInterval = reminder.recurrenceInterval,
                                recurrenceDaysOfWeek = reminder.recurrenceDaysOfWeek
                            )
                            if (nextTime != null) {
                                val updated = entity.copy(
                                    nextTriggerTime = nextTime,
                                    isSnoozed = false,
                                    snoozeUntil = null,
                                    updatedAt = System.currentTimeMillis()
                                )
                                dao.update(updated)
                                app.alarmScheduler.schedule(updated.toDomain())
                            }
                        } else {
                            val updated = entity.copy(
                                isEnabled = false,
                                updatedAt = System.currentTimeMillis()
                            )
                            dao.update(updated)
                        }
                    } else {
                        // Future alarm — reschedule
                        app.alarmScheduler.schedule(reminder)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
