package com.divealien.reminders.ui.edit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.divealien.reminders.RemindersApp
import com.divealien.reminders.domain.model.RecurrenceType
import com.divealien.reminders.domain.model.Reminder
import com.divealien.reminders.util.DateTimeUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDateTime

data class EditUiState(
    val title: String = "",
    val dateTime: LocalDateTime = LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0),
    val recurrenceType: RecurrenceType = RecurrenceType.NONE,
    val recurrenceInterval: Int = 1,
    val recurrenceDaysOfWeek: List<DayOfWeek> = emptyList(),
    val isNew: Boolean = true,
    val isLoading: Boolean = false,
    val isSaved: Boolean = false
)

class ReminderEditViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as RemindersApp
    private val repository = app.repository
    private val alarmScheduler = app.alarmScheduler

    private val _uiState = MutableStateFlow(EditUiState())
    val uiState: StateFlow<EditUiState> = _uiState

    private var existingReminder: Reminder? = null

    fun loadReminder(id: Long) {
        if (id == 0L) return // New reminder
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val reminder = repository.getReminderById(id)
            if (reminder != null) {
                existingReminder = reminder
                _uiState.value = EditUiState(
                    title = reminder.title,
                    dateTime = DateTimeUtils.fromEpochMillis(reminder.nextTriggerTime),
                    recurrenceType = reminder.recurrenceType,
                    recurrenceInterval = reminder.recurrenceInterval ?: 1,
                    recurrenceDaysOfWeek = reminder.recurrenceDaysOfWeek,
                    isNew = false
                )
            }
        }
    }

    fun updateTitle(title: String) {
        _uiState.value = _uiState.value.copy(title = title)
    }

    fun updateNotes(notes: String) {
        _uiState.value = _uiState.value.copy(notes = notes)
    }

    fun updateDateTime(dateTime: LocalDateTime) {
        _uiState.value = _uiState.value.copy(dateTime = dateTime)
    }

    fun updateRecurrenceType(type: RecurrenceType) {
        _uiState.value = _uiState.value.copy(recurrenceType = type)
    }

    fun updateRecurrenceInterval(interval: Int) {
        _uiState.value = _uiState.value.copy(recurrenceInterval = interval.coerceAtLeast(1))
    }

    fun toggleDayOfWeek(day: DayOfWeek) {
        val current = _uiState.value.recurrenceDaysOfWeek
        val updated = if (day in current) current - day else current + day
        _uiState.value = _uiState.value.copy(recurrenceDaysOfWeek = updated)
    }

    fun save() {
        val state = _uiState.value
        if (state.title.isBlank()) return

        viewModelScope.launch {
            val epochMillis = DateTimeUtils.toEpochMillis(state.dateTime)
            val existing = existingReminder

            val reminder = Reminder(
                id = existing?.id ?: 0L,
                title = state.title.trim(),
                notes = state.notes.trim(),
                nextTriggerTime = epochMillis,
                originalDateTime = if (existing != null) existing.originalDateTime else epochMillis,
                recurrenceType = state.recurrenceType,
                recurrenceInterval = if (state.recurrenceType == RecurrenceType.EVERY_N_DAYS)
                    state.recurrenceInterval else null,
                recurrenceDaysOfWeek = if (state.recurrenceType == RecurrenceType.WEEKLY)
                    state.recurrenceDaysOfWeek else emptyList(),
                isEnabled = true,
                isSnoozed = false,
                snoozeUntil = null,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

            val id = repository.save(reminder)
            val savedReminder = reminder.copy(id = id)
            alarmScheduler.schedule(savedReminder)
            _uiState.value = _uiState.value.copy(isSaved = true)
        }
    }

    fun deleteReminder() {
        val existing = existingReminder ?: return
        viewModelScope.launch {
            alarmScheduler.cancel(existing.id)
            repository.delete(existing)
            _uiState.value = _uiState.value.copy(isSaved = true)
        }
    }
}
