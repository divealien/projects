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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class SettingsUiState(
    val backupFolderUri: String? = null,
    val backupStatus: String = "",
    val isRestoring: Boolean = false,
    val importStatus: String = "",
    val snoozePresets: List<SnoozePreset> = SnoozePreset.defaults,
    val retentionDays: Int = 30,
    val dismissSnoozeMinutes: Int = 30
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as RemindersApp
    private val backupManager = app.backupManager
    private val alarmScheduler = app.alarmScheduler

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    private val snoozePresetsKey = stringPreferencesKey("snooze_presets")
    private val retentionDaysKey = intPreferencesKey("retention_days")
    private val dismissSnoozeKey = intPreferencesKey("dismiss_snooze_minutes")

    init {
        viewModelScope.launch {
            val uri = backupManager.getBackupFolderUri()
            val presets = loadSnoozePresets()
            val retention = loadRetentionDays()
            val dismissMinutes = loadDismissSnoozeMinutes()
            _uiState.value = _uiState.value.copy(
                backupFolderUri = uri,
                backupStatus = if (uri != null) "Backup folder set" else "No backup folder selected",
                snoozePresets = presets,
                retentionDays = retention,
                dismissSnoozeMinutes = dismissMinutes
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

    private suspend fun loadDismissSnoozeMinutes(): Int {
        return app.settingsDataStore.data
            .map { it[dismissSnoozeKey] ?: 30 }
            .first()
    }

    fun setDismissSnoozeMinutes(minutes: Int) {
        viewModelScope.launch {
            app.settingsDataStore.edit { prefs ->
                prefs[dismissSnoozeKey] = minutes
            }
            _uiState.value = _uiState.value.copy(dismissSnoozeMinutes = minutes)
        }
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

    fun restoreBackup(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRestoring = true, backupStatus = "Restoring...")
            val success = backupManager.restoreFromUri(uri)
            if (success) {
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
                    backupStatus = "Restore failed — check the selected file"
                )
            }
        }
    }

    fun importReminders(uri: Uri) {
        viewModelScope.launch {
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val preImportFile = "reminders_pre_import_$timestamp.csv"
            _uiState.value = _uiState.value.copy(importStatus = "Backing up before import...")
            backupManager.performBackup(preImportFile)

            _uiState.value = _uiState.value.copy(importStatus = "Importing...")
            val (count, error) = backupManager.importReminders(uri)
            if (error != null) {
                _uiState.value = _uiState.value.copy(importStatus = "Import failed: $error")
            } else {
                // Schedule alarms for imported reminders
                val reminders = app.repository.getActiveRemindersList()
                for (reminder in reminders) {
                    alarmScheduler.schedule(reminder)
                }
                _uiState.value = _uiState.value.copy(
                    importStatus = "$count reminder${if (count != 1) "s" else ""} imported"
                )
                backupManager.requestBackup()
            }
        }
    }

    fun triggerManualBackup() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(backupStatus = "Backing up...")
            val (fileName, error) = backupManager.performManualBackup()
            _uiState.value = _uiState.value.copy(
                backupStatus = if (fileName != null) "Saved: $fileName" else "Backup failed: $error"
            )
        }
    }
}
