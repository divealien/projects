package com.divealien.reminders.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.divealien.reminders.RemindersApp
import com.divealien.reminders.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SnoozeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra(Constants.EXTRA_REMINDER_ID, -1)
        val snoozeDuration = intent.getLongExtra(Constants.EXTRA_SNOOZE_DURATION, Constants.SNOOZE_10_MIN)
        if (reminderId == -1L) return

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as RemindersApp
                val dao = app.database.reminderDao()
                val entity = dao.getReminderById(reminderId) ?: return@launch

                // Dismiss the notification
                app.notificationHelper.cancelNotification(reminderId)

                val snoozeUntil = System.currentTimeMillis() + snoozeDuration
                val updated = entity.copy(
                    isSnoozed = true,
                    snoozeUntil = snoozeUntil,
                    updatedAt = System.currentTimeMillis()
                )
                dao.update(updated)

                // Schedule snooze alarm
                app.alarmScheduler.schedule(updated.toDomain())

                app.backupManager.requestBackup()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
