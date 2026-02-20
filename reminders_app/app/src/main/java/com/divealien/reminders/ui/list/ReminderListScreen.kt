package com.divealien.reminders.ui.list

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.divealien.reminders.domain.model.Reminder
import com.divealien.reminders.util.DateTimeUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderListScreen(
    onAddReminder: () -> Unit,
    onEditReminder: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ReminderListViewModel = viewModel()
) {
    val reminders by viewModel.reminders.collectAsState()
    var reminderToDelete by remember { mutableStateOf<Reminder?>(null) }

    // Sort: enabled+future first (by time), then disabled/past at bottom
    val sortedReminders = remember(reminders) {
        reminders.sortedWith(
            compareByDescending<Reminder> { it.isEnabled }
                .thenBy { if (it.isEnabled) it.nextTriggerTime else Long.MAX_VALUE }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dave's Reminders") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddReminder) {
                Icon(Icons.Default.Add, contentDescription = "Add Reminder")
            }
        }
    ) { padding ->
        if (sortedReminders.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No reminders yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 8.dp
                )
            ) {
                items(sortedReminders, key = { it.id }) { reminder ->
                    SwipeToDismissItem(
                        reminder = reminder,
                        onEdit = { onEditReminder(reminder.id) },
                        onDelete = { reminderToDelete = reminder },
                        onToggle = { viewModel.toggleEnabled(reminder) }
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    reminderToDelete?.let { reminder ->
        AlertDialog(
            onDismissRequest = { reminderToDelete = null },
            title = { Text("Delete this reminder?") },
            text = { Text("\"${reminder.title}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteReminder(reminder)
                    reminderToDelete = null
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { reminderToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDismissItem(
    reminder: Reminder,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                false // Don't dismiss, let the dialog handle it
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val color by animateColorAsState(
                when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                    else -> Color.Transparent
                },
                label = "swipe_bg"
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        },
        enableDismissFromStartToEnd = false
    ) {
        ReminderCard(
            reminder = reminder,
            onEdit = onEdit,
            onToggle = onToggle
        )
    }
}

@Composable
private fun ReminderCard(
    reminder: Reminder,
    onEdit: () -> Unit,
    onToggle: () -> Unit
) {
    val alpha = if (reminder.isEnabled) 1f else 0.5f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(
            containerColor = if (reminder.isPast && reminder.isEnabled)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(Modifier.let { if (alpha < 1f) it else it })
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = reminder.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
                    )
                    if (reminder.isRecurring) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Repeat,
                            contentDescription = "Recurring",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha)
                        )
                    }
                    if (reminder.isSnoozed) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Snooze,
                            contentDescription = "Snoozed",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.tertiary.copy(alpha = alpha)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (reminder.isSnoozed && reminder.snoozeUntil != null)
                        "Snoozed until ${DateTimeUtils.formatDateTime(reminder.snoozeUntil)}"
                    else
                        DateTimeUtils.formatDateTime(reminder.nextTriggerTime),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                )
                if (reminder.notes.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = reminder.notes,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha * 0.7f)
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            Switch(
                checked = reminder.isEnabled,
                onCheckedChange = { onToggle() },
                thumbContent = {
                    Icon(
                        if (reminder.isEnabled) Icons.Default.Notifications
                        else Icons.Default.NotificationsOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }
    }
}
