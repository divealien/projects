package com.divealien.reminders.ui.snooze

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

data class SnoozeUiState(
    val reminder: Reminder? = null,
    val presets: List<SnoozePreset> = emptyList(),
    val isDone: Boolean = false
)

class SnoozeViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as RemindersApp
    private val repository = app.repository
    private val alarmScheduler = app.alarmScheduler
    private val notificationHelper = app.notificationHelper

    private val snoozePresetsKey = stringPreferencesKey("snooze_presets")

    private val _uiState = MutableStateFlow(SnoozeUiState())
    val uiState: StateFlow<SnoozeUiState> = _uiState

    fun load(reminderId: Long) {
        viewModelScope.launch {
            val reminder = repository.getReminderById(reminderId)
            val json = app.settingsDataStore.data
                .map { it[snoozePresetsKey] }
                .first()
            val presets = if (json != null) SnoozePreset.fromJson(json) else SnoozePreset.defaults
            _uiState.value = SnoozeUiState(reminder = reminder, presets = presets)
        }
    }

    fun applySnooze(preset: SnoozePreset) {
        val reminder = _uiState.value.reminder ?: return
        viewModelScope.launch {
            val snoozeUntil = preset.computeTargetTime()
            val updated = reminder.copy(
                isSnoozed = true,
                snoozeUntil = snoozeUntil,
                isEnabled = true,
                completedAt = null,
                updatedAt = System.currentTimeMillis()
            )
            repository.save(updated)
            alarmScheduler.schedule(updated)
            notificationHelper.cancelNotification(reminder.id)
            _uiState.value = _uiState.value.copy(isDone = true)
        }
    }

    fun applyCustomSnooze(targetTimeMillis: Long) {
        val reminder = _uiState.value.reminder ?: return
        viewModelScope.launch {
            val updated = reminder.copy(
                isSnoozed = true,
                snoozeUntil = targetTimeMillis,
                isEnabled = true,
                completedAt = null,
                updatedAt = System.currentTimeMillis()
            )
            repository.save(updated)
            alarmScheduler.schedule(updated)
            notificationHelper.cancelNotification(reminder.id)
            _uiState.value = _uiState.value.copy(isDone = true)
        }
    }
}
