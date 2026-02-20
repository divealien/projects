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
        val snoozeMinutes = intent.getIntExtra(EXTRA_SNOOZE_MINUTES, 15)
        if (reminderId == -1L) return

        val app = context.applicationContext as RemindersApp
        app.notificationHelper.cancelNotification(reminderId)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = app.database.reminderDao()
                val entity = dao.getReminderById(reminderId) ?: return@launch
                val snoozeUntil = System.currentTimeMillis() + snoozeMinutes * 60_000L
                val updated = entity.copy(
                    isSnoozed = true,
                    snoozeUntil = snoozeUntil,
                    isEnabled = true,
                    completedAt = null,
                    updatedAt = System.currentTimeMillis()
                )
                dao.update(updated)
                app.alarmScheduler.schedule(updated.toDomain())
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_SNOOZE_MINUTES = "extra_snooze_minutes"
        const val ACTION_SNOOZE = "com.divealien.reminders.ACTION_SNOOZE"
    }
}
