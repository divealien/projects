package com.divealien.reminders.ui.settings

import android.app.Application
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.divealien.reminders.RemindersApp
import com.divealien.reminders.data.backup.settingsDataStore
import com.divealien.reminders.domain.model.SnoozePreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class SettingsUiState(
    val backupFolderUri: String? = null,
    val backupStatus: String = "",
    val isRestoring: Boolean = false,
    val snoozePresets: List<SnoozePreset> = SnoozePreset.defaults,
    val retentionDays: Int = 30
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as RemindersApp
    private val backupManager = app.backupManager
    private val alarmScheduler = app.alarmScheduler

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    private val snoozePresetsKey = stringPreferencesKey("snooze_presets")
    private val retentionDaysKey = intPreferencesKey("retention_days")

    init {
        viewModelScope.launch {
            val uri = backupManager.getBackupFolderUri()
            val presets = loadSnoozePresets()
            val retention = loadRetentionDays()
            _uiState.value = _uiState.value.copy(
                backupFolderUri = uri,
                backupStatus = if (uri != null) "Backup folder set" else "No backup folder selected",
                snoozePresets = presets,
                retentionDays = retention
            )
        }
    }

    private suspend fun loadSnoozePresets(): List<SnoozePreset> {
        val json = app.settingsDataStore.data
            .map { it[snoozePresetsKey] }
            .first()
        return if (json != null) SnoozePreset.fromJson(json) else SnoozePreset.defaults
    }

    private suspend fun saveSnoozePresets(presets: List<SnoozePreset>) {
        app.settingsDataStore.edit { prefs ->
            prefs[snoozePresetsKey] = SnoozePreset.toJson(presets)
        }
        _uiState.value = _uiState.value.copy(snoozePresets = presets)
    }

    private suspend fun loadRetentionDays(): Int {
        return app.settingsDataStore.data
            .map { it[retentionDaysKey] ?: 30 }
            .first()
    }

    fun setRetentionDays(days: Int) {
        viewModelScope.launch {
            app.settingsDataStore.edit { prefs ->
                prefs[retentionDaysKey] = days
            }
            _uiState.value = _uiState.value.copy(retentionDays = days)
        }
    }

    fun addSnoozePreset(preset: SnoozePreset) {
        viewModelScope.launch {
            val current = _uiState.value.snoozePresets
            saveSnoozePresets(current + preset)
        }
    }

    fun removeSnoozePreset(index: Int) {
        viewModelScope.launch {
            val current = _uiState.value.snoozePresets.toMutableList()
            if (index in current.indices) {
                current.removeAt(index)
                saveSnoozePresets(current)
            }
        }
    }

    fun setBackupFolder(uri: Uri) {
        viewModelScope.launch {
            backupManager.setBackupFolderUri(uri.toString())
            _uiState.value = _uiState.value.copy(
                backupFolderUri = uri.toString(),
                backupStatus = "Backup folder set"
            )
            // Trigger an immediate backup
            val error = backupManager.performBackup()
            _uiState.value = _uiState.value.copy(
                backupStatus = if (error == null) "Backup completed" else "Backup failed: $error"
            )
        }
    }

    fun restoreBackup() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRestoring = true, backupStatus = "Restoring...")
            val success = backupManager.restoreBackup()
            if (success) {
                // Reschedule all alarms
                val reminders = app.repository.getActiveRemindersList()
                for (reminder in reminders) {
                    alarmScheduler.schedule(reminder)
                }
                _uiState.value = _uiState.value.copy(
                    isRestoring = false,
                    backupStatus = "Restore completed — ${reminders.size} reminders restored"
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isRestoring = false,
                    backupStatus = "Restore failed — check backup folder"
                )
            }
        }
    }

    fun triggerManualBackup() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(backupStatus = "Backing up...")
            val error = backupManager.performBackup()
            _uiState.value = _uiState.value.copy(
                backupStatus = if (error == null) "Backup completed" else "Backup failed: $error"
            )
        }
    }
}
