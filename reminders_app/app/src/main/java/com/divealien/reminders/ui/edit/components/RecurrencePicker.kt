package com.divealien.reminders.ui.edit.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.divealien.reminders.domain.model.RecurrenceType
import java.time.DayOfWeek

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecurrencePicker(
    recurrenceType: RecurrenceType,
    recurrenceInterval: Int,
    recurrenceDaysOfWeek: List<DayOfWeek>,
    onRecurrenceTypeChanged: (RecurrenceType) -> Unit,
    onIntervalChanged: (Int) -> Unit,
    onDayToggled: (DayOfWeek) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "Repeat",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = recurrenceTypeLabel(recurrenceType),
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                RecurrenceType.entries.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(recurrenceTypeLabel(type)) },
                        onClick = {
                            onRecurrenceTypeChanged(type)
                            expanded = false
                        }
                    )
                }
            }
        }

        // Show interval input for EVERY_N_DAYS
        AnimatedVisibility(visible = recurrenceType == RecurrenceType.EVERY_N_DAYS) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Every", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = recurrenceInterval.toString(),
                    onValueChange = { text ->
                        text.toIntOrNull()?.let { onIntervalChanged(it) }
                    },
                    modifier = Modifier.width(80.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                Text("days", style = MaterialTheme.typography.bodyLarge)
            }
        }

        // Show day-of-week chips for WEEKLY
        AnimatedVisibility(visible = recurrenceType == RecurrenceType.WEEKLY) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    "On days:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    DayOfWeek.entries.forEach { day ->
                        FilterChip(
                            selected = day in recurrenceDaysOfWeek,
                            onClick = { onDayToggled(day) },
                            label = {
                                Text(day.name.take(3).lowercase()
                                    .replaceFirstChar { it.uppercase() })
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun recurrenceTypeLabel(type: RecurrenceType): String {
    return when (type) {
        RecurrenceType.NONE -> "Does not repeat"
        RecurrenceType.DAILY -> "Daily"
        RecurrenceType.EVERY_N_DAYS -> "Every N days"
        RecurrenceType.WEEKLY -> "Weekly"
        RecurrenceType.MONTHLY -> "Monthly"
        RecurrenceType.YEARLY -> "Yearly"
    }
}
