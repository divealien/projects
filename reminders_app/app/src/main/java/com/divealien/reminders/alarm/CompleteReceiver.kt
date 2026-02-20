package com.divealien.reminders.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.divealien.reminders.RemindersApp
import com.divealien.reminders.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra(Constants.EXTRA_REMINDER_ID, -1)
        if (reminderId == -1L) return

        val app = context.applicationContext as RemindersApp
        app.notificationHelper.cancelNotification(reminderId)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = app.database.reminderDao()
                val entity = dao.getReminderById(reminderId) ?: return@launch
                if (!entity.toDomain().isRecurring && entity.completedAt == null) {
                    dao.update(
                        entity.copy(
                            completedAt = System.currentTimeMillis(),
                            isEnabled = false,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
