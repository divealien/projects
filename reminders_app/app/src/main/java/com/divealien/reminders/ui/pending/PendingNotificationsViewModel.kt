package com.divealien.reminders.ui.pending

import android.app.Application
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.divealien.reminders.RemindersApp
import com.divealien.reminders.data.backup.settingsDataStore
import com.divealien.reminders.domain.model.Reminder
import com.divealien.reminders.domain.model.SnoozePreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class PendingNotificationsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as RemindersApp
    private val dao = app.database.reminderDao()
    private val alarmScheduler = app.alarmScheduler
    private val notificationHelper = app.notificationHelper

    private val snoozePresetsKey = stringPreferencesKey("snooze_presets")

    private val _reminders = MutableStateFlow<List<Reminder>>(emptyList())
    val reminders: StateFlow<List<Reminder>> = _reminders

    private val _snoozePresets = MutableStateFlow<List<SnoozePreset>>(emptyList())
    val snoozePresets: StateFlow<List<SnoozePreset>> = _snoozePresets

    fun loadPendingReminders() {
        viewModelScope.launch {
            val pendingIds = notificationHelper.getPendingIds()

            val reminders = pendingIds.mapNotNull { id ->
                dao.getReminderById(id)?.toDomain()
            }
            _reminders.value = reminders

            val json = app.settingsDataStore.data
                .map { it[snoozePresetsKey] }
                .first()
            _snoozePresets.value = if (json != null) SnoozePreset.fromJson(json) else SnoozePreset.defaults
        }
    }

    fun complete(reminder: Reminder) {
        viewModelScope.launch {
            val entity = dao.getReminderById(reminder.id) ?: return@launch
            if (!entity.toDomain().isRecurring && entity.completedAt == null) {
                dao.update(
                    entity.copy(
                        completedAt = System.currentTimeMillis(),
                        isEnabled = false,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            notificationHelper.cancelNotification(reminder.id)
            removeFromList(reminder.id)
        }
    }

    fun snooze(reminder: Reminder, preset: SnoozePreset) {
        snoozeUntil(reminder, preset.computeTargetTime())
    }

    fun snoozeUntil(reminder: Reminder, targetTimeMillis: Long) {
        viewModelScope.launch {
            val entity = dao.getReminderById(reminder.id) ?: return@launch
            val updated = entity.copy(
                isSnoozed = true,
                snoozeUntil = targetTimeMillis,
                isEnabled = true,
                completedAt = null,
                updatedAt = System.currentTimeMillis()
            )
            dao.update(updated)
            alarmScheduler.schedule(updated.toDomain())
            notificationHelper.cancelNotification(reminder.id)
            removeFromList(reminder.id)
        }
    }

    private fun removeFromList(id: Long) {
        _reminders.value = _reminders.value.filter { it.id != id }
    }
}
