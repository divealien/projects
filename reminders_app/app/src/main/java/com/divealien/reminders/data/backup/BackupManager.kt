package com.divealien.reminders.data.backup

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.documentfile.provider.DocumentFile
import com.divealien.reminders.data.local.ReminderDao
import com.divealien.reminders.data.local.entity.ReminderEntity
import com.divealien.reminders.domain.model.RecurrenceType
import com.divealien.reminders.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class BackupManager(private val context: Context, private val dao: ReminderDao) {

    private val backupFolderKey = stringPreferencesKey("backup_folder_uri")
    private var debounceJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    suspend fun getBackupFolderUri(): String? {
        return context.settingsDataStore.data
            .map { it[backupFolderKey] }
            .first()
    }

    suspend fun setBackupFolderUri(uri: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[backupFolderKey] = uri
        }
    }

    fun requestBackup() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(Constants.BACKUP_DEBOUNCE_MS)
            performBackup()
        }
    }

    /**
     * Returns null on success, or an error message string on failure.
     */
    suspend fun performBackup(fileName: String = Constants.BACKUP_FILE_NAME): String? {
        val uriString = getBackupFolderUri()
            ?: return "No backup folder configured"
        val folderUri = Uri.parse(uriString)

        return try {
            val reminders = dao.getAllRemindersList()

            val folder = DocumentFile.fromTreeUri(context, folderUri)
                ?: return "Cannot access folder"

            if (!folder.exists()) return "Folder does not exist"
            if (!folder.canWrite()) return "Folder is not writable"

            // Delete existing file with same name
            folder.findFile(fileName)?.delete()

            // Create new backup file
            val backupDoc = folder.createFile("text/csv", fileName)
                ?: return "Cannot create file in folder"

            context.contentResolver.openOutputStream(backupDoc.uri)?.use { output ->
                val writer = output.bufferedWriter()
                writer.write(CSV_HEADER)
                writer.newLine()
                for (reminder in reminders) {
                    writer.write(reminderToCsvRow(reminder))
                    writer.newLine()
                }
                writer.flush()
            }

            null // success
        } catch (e: Exception) {
            e.message ?: "Unknown error"
        }
    }

    suspend fun restoreBackup(): Boolean {
        val uriString = getBackupFolderUri() ?: return false
        val folderUri = Uri.parse(uriString)

        return try {
            val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return false
            val backupDoc = folder.findFile(Constants.BACKUP_FILE_NAME) ?: return false

            val reminders = mutableListOf<ReminderEntity>()

            context.contentResolver.openInputStream(backupDoc.uri)?.use { input ->
                val reader = BufferedReader(InputStreamReader(input))
                val headerLine = reader.readLine() ?: return false
                val columns = headerLine.split(DELIMITER)

                reader.forEachLine { line ->
                    if (line.isNotBlank()) {
                        val entity = csvRowToReminder(line, columns)
                        if (entity != null) {
                            reminders.add(entity)
                        }
                    }
                }
            }

            dao.deleteAll()
            for (reminder in reminders) {
                dao.insert(reminder)
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Imports reminders from a simple CSV file with human-friendly format.
     * Format: title~datetime~recurrence
     * datetime: yyyy-MM-dd HH:mm
     * recurrence: DAILY, WEEKLY, MONTHLY, YEARLY, EVERY_N_DAYS:N, or blank for one-shot
     * Returns (imported count, error message or null).
     */
    suspend fun importReminders(uri: Uri): Pair<Int, String?> {
        return try {
            val reminders = mutableListOf<ReminderEntity>()

            context.contentResolver.openInputStream(uri)?.use { input ->
                val reader = BufferedReader(InputStreamReader(input))
                val headerLine = reader.readLine() ?: return Pair(0, "Empty file")
                val columns = headerLine.split(DELIMITER).map { it.trim().lowercase() }

                val titleIdx = columns.indexOf("title")
                val dateTimeIdx = columns.indexOf("datetime")
                val recurrenceIdx = columns.indexOf("recurrence")

                if (titleIdx == -1 || dateTimeIdx == -1) {
                    return Pair(0, "Missing required columns: title, datetime")
                }

                var lineNum = 1
                reader.forEachLine { line ->
                    lineNum++
                    if (line.isNotBlank()) {
                        val fields = line.split(DELIMITER)
                        val title = fields.getOrNull(titleIdx)?.trim()?.let { unescapeField(it) } ?: return@forEachLine
                        val dateTimeStr = fields.getOrNull(dateTimeIdx)?.trim() ?: return@forEachLine
                        val recurrenceStr = fields.getOrNull(recurrenceIdx)?.trim() ?: ""

                        if (title.isEmpty() || dateTimeStr.isEmpty()) return@forEachLine

                        val localDt = try {
                            LocalDateTime.parse(dateTimeStr, IMPORT_DATE_FORMAT)
                        } catch (e: Exception) {
                            return@forEachLine
                        }

                        val millis = localDt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

                        val (recType, recInterval) = parseRecurrence(recurrenceStr)

                        val now = System.currentTimeMillis()
                        reminders.add(
                            ReminderEntity(
                                id = 0,
                                title = title,
                                nextTriggerTime = millis,
                                originalDateTime = millis,
                                recurrenceType = recType,
                                recurrenceInterval = recInterval,
                                isEnabled = true,
                                createdAt = now,
                                updatedAt = now
                            )
                        )
                    }
                }
            } ?: return Pair(0, "Cannot read file")

            val insertedIds = mutableListOf<Long>()
            for (reminder in reminders) {
                val id = dao.insert(reminder)
                insertedIds.add(id)
            }

            Pair(insertedIds.size, null)
        } catch (e: Exception) {
            Pair(0, e.message ?: "Unknown error")
        }
    }

    private fun parseRecurrence(value: String): Pair<RecurrenceType, Int?> {
        if (value.isBlank() || value.equals("NONE", ignoreCase = true)) {
            return Pair(RecurrenceType.NONE, null)
        }
        if (value.startsWith("EVERY_N_DAYS:", ignoreCase = true)) {
            val n = value.substringAfter(":").trim().toIntOrNull() ?: 1
            return Pair(RecurrenceType.EVERY_N_DAYS, n)
        }
        return try {
            Pair(RecurrenceType.valueOf(value.uppercase()), null)
        } catch (e: Exception) {
            Pair(RecurrenceType.NONE, null)
        }
    }

    private fun reminderToCsvRow(r: ReminderEntity): String {
        val fields = listOf(
            r.id.toString(),
            escapeField(r.title),
            escapeField(r.notes),
            r.nextTriggerTime.toString(),
            r.originalDateTime.toString(),
            r.recurrenceType.name,
            r.recurrenceInterval?.toString() ?: "",
            if (r.recurrenceDaysOfWeek.isEmpty()) "" else r.recurrenceDaysOfWeek.joinToString(",") { it.value.toString() },
            r.isEnabled.toString(),
            r.isSnoozed.toString(),
            r.snoozeUntil?.toString() ?: "",
            r.createdAt.toString(),
            r.updatedAt.toString(),
            r.completedAt?.toString() ?: ""
        )
        return fields.joinToString(DELIMITER)
    }

    private fun csvRowToReminder(line: String, columns: List<String>): ReminderEntity? {
        return try {
            val fields = line.split(DELIMITER)
            val map = columns.zip(fields).toMap()

            ReminderEntity(
                id = map["id"]?.toLongOrNull() ?: return null,
                title = unescapeField(map["title"] ?: ""),
                notes = unescapeField(map["notes"] ?: ""),
                nextTriggerTime = map["nextTriggerTime"]?.toLongOrNull() ?: return null,
                originalDateTime = map["originalDateTime"]?.toLongOrNull() ?: return null,
                recurrenceType = map["recurrenceType"]?.let { RecurrenceType.valueOf(it) } ?: RecurrenceType.NONE,
                recurrenceInterval = map["recurrenceInterval"]?.takeIf { it.isNotEmpty() }?.toIntOrNull(),
                recurrenceDaysOfWeek = parseDaysOfWeek(map["recurrenceDaysOfWeek"] ?: ""),
                isEnabled = map["isEnabled"]?.toBooleanStrictOrNull() ?: true,
                isSnoozed = map["isSnoozed"]?.toBooleanStrictOrNull() ?: false,
                snoozeUntil = map["snoozeUntil"]?.takeIf { it.isNotEmpty() }?.toLongOrNull(),
                createdAt = map["createdAt"]?.toLongOrNull() ?: System.currentTimeMillis(),
                updatedAt = map["updatedAt"]?.toLongOrNull() ?: System.currentTimeMillis(),
                completedAt = map["completedAt"]?.takeIf { it.isNotEmpty() }?.toLongOrNull()
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseDaysOfWeek(value: String): List<DayOfWeek> {
        if (value.isBlank()) return emptyList()
        return value.split(",").mapNotNull { it.trim().toIntOrNull()?.let { v -> DayOfWeek.of(v) } }
    }

    companion object {
        private const val DELIMITER = "~"
        private const val CSV_HEADER =
            "id~title~notes~nextTriggerTime~originalDateTime~recurrenceType~recurrenceInterval~recurrenceDaysOfWeek~isEnabled~isSnoozed~snoozeUntil~createdAt~updatedAt~completedAt"
        private val IMPORT_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        private fun escapeField(value: String): String {
            return value
                .replace("\\", "\\\\")
                .replace("~", "\\~")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
        }

        private fun unescapeField(value: String): String {
            val sb = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                if (value[i] == '\\' && i + 1 < value.length) {
                    when (value[i + 1]) {
                        '\\' -> { sb.append('\\'); i += 2 }
                        '~' -> { sb.append('~'); i += 2 }
                        'n' -> { sb.append('\n'); i += 2 }
                        'r' -> { sb.append('\r'); i += 2 }
                        else -> { sb.append(value[i]); i++ }
                    }
                } else {
                    sb.append(value[i])
                    i++
                }
            }
            return sb.toString()
        }
    }
}
