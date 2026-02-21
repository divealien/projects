package com.divealien.reminders.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.divealien.reminders.domain.model.SnoozePreset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onViewCompleted: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showAddPresetDialog by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            // Persist permission so it survives app restarts
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, flags)
            viewModel.setBackupFolder(it)
        }
    }

    val csvPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importReminders(it) }
    }

    val restorePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { pendingRestoreUri = it }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
            Spacer(Modifier.height(8.dp))

            // Backup section
            Text(
                "Backup",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        uiState.backupStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (uiState.backupFolderUri != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Folder: ${Uri.parse(uiState.backupFolderUri).lastPathSegment ?: "Selected"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { folderPickerLauncher.launch(null) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (uiState.backupFolderUri == null) "Pick Backup Folder"
                    else "Change Backup Folder"
                )
            }

            if (uiState.backupFolderUri != null) {
                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { viewModel.triggerManualBackup() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Backup Now")
                }

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "text/*"
                            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv", "text/plain", "text/comma-separated-values"))
                            uiState.backupFolderUri?.let { uriStr ->
                                val treeUri = Uri.parse(uriStr)
                                val docUri = DocumentsContract.buildDocumentUriUsingTree(
                                    treeUri,
                                    DocumentsContract.getTreeDocumentId(treeUri)
                                )
                                putExtra(DocumentsContract.EXTRA_INITIAL_URI, docUri)
                            }
                        }
                        restorePickerLauncher.launch(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isRestoring
                ) {
                    Text("Restore from Backup…")
                }

                Spacer(Modifier.height(12.dp))

                var keepExpanded by remember { mutableStateOf(false) }
                val keepOptions = listOf(3, 7, 14, 30)

                ExposedDropdownMenuBox(
                    expanded = keepExpanded,
                    onExpandedChange = { keepExpanded = it }
                ) {
                    OutlinedTextField(
                        value = "${uiState.dailyBackupKeep} daily backups",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Rolling daily backups to keep") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = keepExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = keepExpanded,
                        onDismissRequest = { keepExpanded = false }
                    ) {
                        keepOptions.forEach { n ->
                            DropdownMenuItem(
                                text = { Text("$n days") },
                                onClick = {
                                    viewModel.setDailyBackupKeep(n)
                                    keepExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Import section
            Text(
                "Import",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = { csvPickerLauncher.launch(arrayOf("text/csv", "text/plain", "text/comma-separated-values")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Import Reminders from CSV")
            }

            if (uiState.importStatus.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    uiState.importStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(24.dp))

            // Completed Reminders section
            Text(
                "Completed Reminders",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onViewCompleted,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("View Completed Reminders")
            }

            Spacer(Modifier.height(12.dp))

            var retentionExpanded by remember { mutableStateOf(false) }
            val retentionOptions = listOf(7, 14, 30, 60, 90)

            ExposedDropdownMenuBox(
                expanded = retentionExpanded,
                onExpandedChange = { retentionExpanded = it }
            ) {
                OutlinedTextField(
                    value = "${uiState.retentionDays} days",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Auto-delete after") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = retentionExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = retentionExpanded,
                    onDismissRequest = { retentionExpanded = false }
                ) {
                    retentionOptions.forEach { days ->
                        DropdownMenuItem(
                            text = { Text("$days days") },
                            onClick = {
                                viewModel.setRetentionDays(days)
                                retentionExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Battery optimization section
            Text(
                "Battery",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "For reliable notifications, disable battery optimization for this app.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Battery Settings")
            }

            // Notification settings
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "Notifications",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Notification Settings")
                }
            }

            // Snooze Presets section
            Spacer(Modifier.height(24.dp))
            Text(
                "Snooze Presets",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (uiState.snoozePresets.isEmpty()) {
                        Text(
                            "No presets configured",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        uiState.snoozePresets.forEachIndexed { index, preset ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    preset.settingsLabel(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { viewModel.removeSnoozePreset(index) }) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showAddPresetDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add Preset")
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showAddPresetDialog) {
        AddPresetDialog(
            onDismiss = { showAddPresetDialog = false },
            onAdd = { preset ->
                viewModel.addSnoozePreset(preset)
                showAddPresetDialog = false
            }
        )
    }

    pendingRestoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestoreUri = null },
            title = { Text("Restore from Backup?") },
            text = {
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: uri.lastPathSegment ?: "selected file"
                Text("This will replace all current reminders with the contents of \"$name\". This cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.restoreBackup(uri)
                    pendingRestoreUri = null
                }) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreUri = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPresetDialog(
    onDismiss: () -> Unit,
    onAdd: (SnoozePreset) -> Unit
) {
    val typeOptions = listOf("Minutes", "Days", "Time of day")
    var selectedType by remember { mutableStateOf(typeOptions[0]) }
    var typeExpanded by remember { mutableStateOf(false) }
    var value by remember { mutableIntStateOf(15) }
    var hour by remember { mutableIntStateOf(9) }
    var minute by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Snooze Preset") },
        text = {
            Column {
                ExposedDropdownMenuBox(
                    expanded = typeExpanded,
                    onExpandedChange = { typeExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedType,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = typeExpanded,
                        onDismissRequest = { typeExpanded = false }
                    ) {
                        typeOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    selectedType = option
                                    typeExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                when (selectedType) {
                    "Minutes" -> {
                        OutlinedTextField(
                            value = value.toString(),
                            onValueChange = { text -> text.toIntOrNull()?.let { value = it.coerceAtLeast(1) } },
                            label = { Text("Minutes") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    "Days" -> {
                        OutlinedTextField(
                            value = value.toString(),
                            onValueChange = { text -> text.toIntOrNull()?.let { value = it.coerceAtLeast(1) } },
                            label = { Text("Days") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    "Time of day" -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = hour.toString(),
                                onValueChange = { text -> text.toIntOrNull()?.let { hour = it.coerceIn(0, 23) } },
                                label = { Text("Hour") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(":", style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                value = minute.toString().padStart(2, '0'),
                                onValueChange = { text -> text.toIntOrNull()?.let { minute = it.coerceIn(0, 59) } },
                                label = { Text("Min") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val preset = when (selectedType) {
                    "Minutes" -> SnoozePreset.RelativeMinutes(value)
                    "Days" -> SnoozePreset.RelativeDays(value)
                    "Time of day" -> SnoozePreset.TomorrowAt(hour, minute)
                    else -> return@TextButton
                }
                onAdd(preset)
            }) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
