package com.divealien.reminders.ui.snooze

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SnoozeScreen(
    reminderId: Long,
    onNavigateBack: () -> Unit,
    viewModel: SnoozeViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(reminderId) {
        viewModel.load(reminderId)
    }

    LaunchedEffect(uiState.isDone) {
        if (uiState.isDone) onNavigateBack()
    }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var customDateTime by remember {
        mutableStateOf(LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Snooze") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            val reminder = uiState.reminder
            if (reminder != null) {
                Text(
                    reminder.title,
                    style = MaterialTheme.typography.headlineSmall
                )
                if (reminder.notes.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        reminder.notes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                "Snooze until",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(12.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (preset in uiState.presets) {
                    FilledTonalButton(onClick = { viewModel.applySnooze(preset) }) {
                        Text(preset.displayLabel())
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                "Other",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(8.dp))

            val dateFormatter = remember { DateTimeFormatter.ofPattern("EEE, MMM d, yyyy") }
            val timeFormatter = remember { DateTimeFormatter.ofPattern("h:mm a") }

            OutlinedButton(
                onClick = { showDatePicker = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(customDateTime.format(dateFormatter))
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = { showTimePicker = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(customDateTime.format(timeFormatter))
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    val millis = customDateTime.atZone(ZoneId.systemDefault())
                        .toInstant().toEpochMilli()
                    viewModel.applyCustomSnooze(millis)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = customDateTime.atZone(ZoneId.systemDefault())
                    .toInstant().toEpochMilli() > System.currentTimeMillis()
            ) {
                Text("Snooze to selected time")
            }

            Spacer(Modifier.height(32.dp))
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
                }) {
                    Text("OK")
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
            initialMinute = customDateTime.minute
        )

        Dialog(onDismissRequest = { showTimePicker = false }) {
            OutlinedCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Select time",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    TimePicker(state = timePickerState)
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showTimePicker = false }) {
                            Text("Cancel")
                        }
                        TextButton(onClick = {
                            val newTime = LocalTime.of(timePickerState.hour, timePickerState.minute)
                            customDateTime = LocalDateTime.of(customDateTime.toLocalDate(), newTime)
                            showTimePicker = false
                        }) {
                            Text("OK")
                        }
                    }
                }
            }
        }
    }
}
