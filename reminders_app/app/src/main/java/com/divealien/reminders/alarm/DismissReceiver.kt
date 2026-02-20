package com.divealien.reminders.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.intPreferencesKey
import com.divealien.reminders.RemindersApp
import com.divealien.reminders.data.backup.settingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class DismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as RemindersApp
        val pendingIds = app.notificationHelper.getPendingIds()
        if (pendingIds.isEmpty()) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dismissMinutes = context.settingsDataStore.data
                    .map { it[DISMISS_SNOOZE_KEY] ?: DEFAULT_DISMISS_MINUTES }
                    .first()

                val dao = app.database.reminderDao()
                val snoozeUntil = System.currentTimeMillis() + dismissMinutes * 60_000L

                for (id in pendingIds) {
                    val entity = dao.getReminderById(id) ?: continue
                    val updated = entity.copy(
                        isSnoozed = true,
                        snoozeUntil = snoozeUntil,
                        isEnabled = true,
                        completedAt = null,
                        updatedAt = System.currentTimeMillis()
                    )
                    dao.update(updated)
                    app.alarmScheduler.schedule(updated.toDomain())
                }

                app.notificationHelper.clearAllPendingIds()
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_DISMISS = "com.divealien.reminders.ACTION_DISMISS"
        private val DISMISS_SNOOZE_KEY = intPreferencesKey("dismiss_snooze_minutes")
        const val DEFAULT_DISMISS_MINUTES = 30
    }
}
