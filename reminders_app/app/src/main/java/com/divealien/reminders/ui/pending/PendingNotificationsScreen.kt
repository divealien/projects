package com.divealien.reminders.ui.pending

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.divealien.reminders.domain.model.Reminder
import com.divealien.reminders.domain.model.SnoozePreset
import com.divealien.reminders.util.DateTimeUtils
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun PendingNotificationsScreen(
    onDone: () -> Unit,
    onEditReminder: (Long) -> Unit,
    viewModel: PendingNotificationsViewModel = viewModel()
) {
    val reminders by viewModel.reminders.collectAsState()
    val snoozePresets by viewModel.snoozePresets.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadPendingReminders()
    }

    // Auto-close when all reminders have been actioned
    LaunchedEffect(reminders) {
        if (reminders.isEmpty() && viewModel.reminders.value.isEmpty()) {
            // Don't close on initial empty state — wait for load
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pending Reminders") }
            )
        }
    ) { padding ->
        if (reminders.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No pending reminders",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                items(reminders, key = { it.id }) { reminder ->
                    var showSnoozeOptions by remember { mutableStateOf(false) }

                    PendingReminderCard(
                        reminder = reminder,
                        showSnoozeOptions = showSnoozeOptions,
                        snoozePresets = snoozePresets,
                        onComplete = { viewModel.complete(reminder) },
                        onSnoozeClick = { showSnoozeOptions = !showSnoozeOptions },
                        onSnooze = { preset ->
                            viewModel.snooze(reminder, preset)
                            showSnoozeOptions = false
                        },
                        onCustomSnooze = { millis ->
                            viewModel.snoozeUntil(reminder, millis)
                            showSnoozeOptions = false
                        },
                        onLongClick = { onEditReminder(reminder.id) }
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PendingReminderCard(
    reminder: Reminder,
    showSnoozeOptions: Boolean,
    snoozePresets: List<SnoozePreset>,
    onComplete: () -> Unit,
    onSnoozeClick: () -> Unit,
    onSnooze: (SnoozePreset) -> Unit,
    onCustomSnooze: (Long) -> Unit,
    onLongClick: () -> Unit
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var customDateTime by remember { mutableStateOf(LocalDateTime.now().plusHours(1)) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { },
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    DateTimeUtils.formatTime(reminder.nextTriggerTime),
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    reminder.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onComplete,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("COMPLETE")
                }
                Button(
                    onClick = onSnoozeClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("SNOOZE")
                }
            }

            if (showSnoozeOptions) {
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    snoozePresets.forEach { preset ->
                        OutlinedButton(onClick = { onSnooze(preset) }) {
                            Text(preset.displayLabel())
                        }
                    }
                    OutlinedButton(onClick = {
                        customDateTime = LocalDateTime.now().plusHours(1)
                        showDatePicker = true
                    }) {
                        Text("Other...")
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        val initialMillis = customDateTime.toLocalDate()
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)

        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val selectedDate = Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate()
                        customDateTime = LocalDateTime.of(selectedDate, customDateTime.toLocalTime())
                    }
                    showDatePicker = false
                    showTimePicker = true
                }) {
                    Text("Next")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = customDateTime.hour,
            initialMinute = customDateTime.minute,
            is24Hour = true
        )

        Dialog(onDismissRequest = { showTimePicker = false }) {
            OutlinedCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Snooze until",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    TimePicker(state = timePickerState)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showTimePicker = false }) {
                            Text("Cancel")
                        }
                        TextButton(onClick = {
                            val newTime = LocalTime.of(timePickerState.hour, timePickerState.minute)
                            val finalDateTime = LocalDateTime.of(customDateTime.toLocalDate(), newTime)
                            val millis = finalDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            onCustomSnooze(millis)
                            showTimePicker = false
                        }) {
                            Text("Snooze")
                        }
                    }
                }
            }
        }
    }
}
