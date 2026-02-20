package com.divealien.reminders.ui.completed

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.divealien.reminders.RemindersApp
import com.divealien.reminders.domain.model.Reminder
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CompletedRemindersViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as RemindersApp
    private val repository = app.repository

    val completedReminders: StateFlow<List<Reminder>> = repository.getCompletedReminders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun permanentlyDelete(reminder: Reminder) {
        viewModelScope.launch {
            repository.delete(reminder)
        }
    }
}
