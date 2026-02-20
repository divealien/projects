package com.divealien.reminders.ui.list

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.divealien.reminders.RemindersApp
import com.divealien.reminders.domain.model.Reminder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReminderListViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as RemindersApp
    private val repository = app.repository
    private val alarmScheduler = app.alarmScheduler

    val reminders: StateFlow<List<Reminder>> = repository.getRemindersExcludeCompleted()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun deleteReminder(reminder: Reminder) {
        viewModelScope.launch {
            alarmScheduler.cancel(reminder.id)
            repository.delete(reminder)
        }
    }

    fun toggleEnabled(reminder: Reminder) {
        viewModelScope.launch {
            val updated = reminder.copy(
                isEnabled = !reminder.isEnabled,
                updatedAt = System.currentTimeMillis()
            )
            repository.save(updated)
            if (updated.isEnabled) {
                alarmScheduler.schedule(updated)
            } else {
                alarmScheduler.cancel(updated.id)
            }
        }
    }
}
