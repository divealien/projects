package com.divealien.reminders.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.divealien.reminders.RemindersApp
import com.divealien.reminders.util.Constants
import com.divealien.reminders.util.DateTimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra(Constants.EXTRA_REMINDER_ID, -1)
        Log.d(TAG, "AlarmReceiver.onReceive — reminderId=$reminderId")

        if (reminderId == -1L) return

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as RemindersApp
                val dao = app.database.reminderDao()
                val entity = dao.getReminderById(reminderId)

                if (entity == null) {
                    Log.w(TAG, "Reminder $reminderId not found in database")
                    return@launch
                }

                val reminder = entity.toDomain()
                Log.d(TAG, "Showing notification for reminder ${reminder.id}: '${reminder.title}'")

                // Show notification
                app.notificationHelper.showNotification(reminder)

                // If this alarm fired from a snooze, just clear the snooze state —
                // the reminder was already advanced when it originally fired.
                if (reminder.isSnoozed) {
                    val updated = entity.copy(
                        isSnoozed = false,
                        snoozeUntil = null,
                        updatedAt = System.currentTimeMillis()
                    )
                    dao.update(updated)
                    Log.d(TAG, "Snoozed reminder ${reminder.id} — cleared snooze state (no advance)")
                } else if (reminder.isRecurring) {
                    // Advance recurring reminders
                    val nextTime = DateTimeUtils.computeNextOccurrence(
                        afterTime = reminder.nextTriggerTime,
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
                        Log.d(TAG, "Recurring reminder ${reminder.id} — next trigger at $nextTime")
                    }
                } else {
                    // One-shot: disable after firing and mark completed
                    val updated = entity.copy(
                        isEnabled = false,
                        isSnoozed = false,
                        snoozeUntil = null,
                        completedAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                    dao.update(updated)
                    Log.d(TAG, "One-shot reminder ${reminder.id} — disabled and completed after firing")
                }

                app.backupManager.requestBackup()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling alarm for reminder $reminderId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AlarmReceiver"
    }
}
