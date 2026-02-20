package com.divealien.reminders.ui.list

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.divealien.reminders.domain.model.Reminder
import com.divealien.reminders.util.DateTimeUtils
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReminderListScreen(
    onAddReminder: () -> Unit,
    onEditReminder: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ReminderListViewModel = viewModel()
) {
    val reminders by viewModel.reminders.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    var reminderToDelete by remember { mutableStateOf<Reminder?>(null) }
    var isSearchActive by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val dayFmt = remember { DateTimeFormatter.ofPattern("EEE d MMM") }

    val grouped: List<Pair<String, List<Reminder>>> = remember(reminders, searchQuery) {
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)

        val filtered = if (searchQuery.isBlank()) reminders
        else reminders.filter { it.title.contains(searchQuery, ignoreCase = true) }

        val sorted = filtered.sortedWith(
            compareByDescending<Reminder> { it.isEnabled }
                .thenBy { if (it.isEnabled) it.nextTriggerTime else Long.MAX_VALUE }
        )

        val (enabled, disabled) = sorted.partition { it.isEnabled }

        val enabledGroups = enabled
            .groupBy { reminder ->
                DateTimeUtils.fromEpochMillis(
                    if (reminder.isSnoozed && reminder.snoozeUntil != null) reminder.snoozeUntil
                    else reminder.nextTriggerTime
                ).toLocalDate()
            }
            .map { (date, group) ->
                val dayStr = date.format(dayFmt)
                val label = when (date) {
                    today -> "Today  $dayStr"
                    tomorrow -> "Tomorrow  $dayStr"
                    else -> dayStr
                }
                label to group
            }

        val disabledGroup = if (disabled.isNotEmpty()) listOf("Disabled" to disabled) else emptyList()

        enabledGroups + disabledGroup
    }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) focusRequester.requestFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = { Text("Search reminders...") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            )
                        )
                    } else {
                        Text("Dave's Reminders")
                    }
                },
                actions = {
                    if (isSearchActive) {
                        IconButton(onClick = {
                            viewModel.setSearchQuery("")
                            isSearchActive = false
                        }) {
                            Icon(Icons.Default.Clear, contentDescription = "Close search")
                        }
                    } else {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                    }
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
        if (grouped.isEmpty()) {
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
                verticalArrangement = Arrangement.spacedBy(5.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                grouped.forEach { (label, groupReminders) ->
                    stickyHeader(key = "header_$label") {
                        DateGroupHeader(label)
                    }
                    items(groupReminders, key = { it.id }) { reminder ->
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
    }

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

@Composable
private fun DateGroupHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
    )
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
                false
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = Modifier.padding(horizontal = 16.dp),
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
    val timeText = if (reminder.isSnoozed && reminder.snoozeUntil != null)
        DateTimeUtils.formatTime(reminder.snoozeUntil)
    else
        DateTimeUtils.formatTime(reminder.nextTriggerTime)

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
                .padding(horizontal = 12.dp, vertical = 0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = timeText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                modifier = Modifier.width(44.dp)
            )
            Text(
                text = reminder.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (reminder.isRecurring) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Default.Repeat,
                    contentDescription = "Recurring",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha)
                )
            }
            if (reminder.isSnoozed) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Default.Snooze,
                    contentDescription = "Snoozed",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.tertiary.copy(alpha = alpha)
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier.height(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Switch(
                    checked = reminder.isEnabled,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier.scale(0.75f),
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
}
