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
import java.time.Instant
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

    /** Debounced auto-backup triggered on every data mutation. Writes to fixed rolling filename. */
    fun requestBackup() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(Constants.BACKUP_DEBOUNCE_MS)
            performBackup(Constants.BACKUP_FILE_NAME)
        }
    }

    /**
     * Manual backup — writes a timestamped file so auto-backups never overwrite it.
     * Returns the filename on success, or null + error string on failure.
     */
    suspend fun performManualBackup(): Pair<String?, String?> {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val fileName = "reminders_$timestamp.csv"
        val error = performBackup(fileName)
        return if (error == null) Pair(fileName, null) else Pair(null, error)
    }

    /**
     * Writes all reminders to [fileName] in the configured backup folder.
     * Returns null on success, or an error message on failure.
     */
    suspend fun performBackup(fileName: String = Constants.BACKUP_FILE_NAME): String? {
        val uriString = getBackupFolderUri() ?: return "No backup folder configured"
        val folderUri = Uri.parse(uriString)

        return try {
            val reminders = dao.getAllRemindersList()
            val folder = DocumentFile.fromTreeUri(context, folderUri)
                ?: return "Cannot access folder"
            if (!folder.exists()) return "Folder does not exist"
            if (!folder.canWrite()) return "Folder is not writable"

            folder.findFile(fileName)?.delete()
            val backupDoc = folder.createFile("text/csv", fileName)
                ?: return "Cannot create file in folder"

            context.contentResolver.openOutputStream(backupDoc.uri)?.use { output ->
                val writer = output.bufferedWriter()
                writer.write(BACKUP_HEADER)
                writer.newLine()
                for (reminder in reminders) {
                    writer.write(formatBackupRow(reminder))
                    writer.newLine()
                }
                writer.flush()
            }
            null
        } catch (e: Exception) {
            e.message ?: "Unknown error"
        }
    }

    /**
     * Restores from a user-picked URI. Replaces all current reminders.
     * Returns true on success.
     */
    suspend fun restoreFromUri(uri: Uri): Boolean {
        return try {
            val reminders = mutableListOf<ReminderEntity>()

            context.contentResolver.openInputStream(uri)?.use { input ->
                val reader = BufferedReader(InputStreamReader(input))
                val headerLine = reader.readLine() ?: return false
                val columns = headerLine.split(DELIMITER).map { it.trim().lowercase() }

                reader.forEachLine { line ->
                    if (line.isNotBlank()) {
                        parseBackupRow(line.split(DELIMITER), columns)?.let { reminders.add(it) }
                    }
                }
            } ?: return false

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
     * Imports reminders from a simple CSV file (adds to existing, does not replace).
     * Format: title~datetime~recurrence
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

                reader.forEachLine { line ->
                    if (line.isNotBlank()) {
                        val fields = line.split(DELIMITER)
                        val title = fields.getOrNull(titleIdx)?.trim()?.let { unescapeField(it) } ?: return@forEachLine
                        val dateTimeStr = fields.getOrNull(dateTimeIdx)?.trim() ?: return@forEachLine
                        val recurrenceStr = fields.getOrNull(recurrenceIdx)?.trim() ?: ""

                        if (title.isEmpty() || dateTimeStr.isEmpty()) return@forEachLine

                        val millis = parseMillis(dateTimeStr) ?: return@forEachLine
                        val (recType, recInterval, recDays) = parseRecurrenceFull(recurrenceStr)
                        val now = System.currentTimeMillis()

                        reminders.add(
                            ReminderEntity(
                                id = 0,
                                title = title,
                                nextTriggerTime = millis,
                                originalDateTime = millis,
                                recurrenceType = recType,
                                recurrenceInterval = recInterval,
                                recurrenceDaysOfWeek = recDays,
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
                insertedIds.add(dao.insert(reminder))
            }
            Pair(insertedIds.size, null)
        } catch (e: Exception) {
            Pair(0, e.message ?: "Unknown error")
        }
    }

    // ---- Format helpers ----

    private fun formatBackupRow(r: ReminderEntity): String {
        val fields = listOf(
            escapeField(r.title),
            formatMillis(r.nextTriggerTime),
            formatMillis(r.originalDateTime),
            formatRecurrence(r.recurrenceType, r.recurrenceInterval, r.recurrenceDaysOfWeek),
            escapeField(r.notes),
            r.isEnabled.toString(),
            r.isSnoozed.toString(),
            r.snoozeUntil?.let { formatMillis(it) } ?: "",
            formatMillis(r.createdAt),
            r.completedAt?.let { formatMillis(it) } ?: "",
            r.id.toString()
        )
        return fields.joinToString(DELIMITER)
    }

    private fun parseBackupRow(fields: List<String>, columns: List<String>): ReminderEntity? {
        return try {
            val map = columns.zip(fields).toMap()

            val title = map["title"]?.let { unescapeField(it) }?.takeIf { it.isNotEmpty() } ?: return null
            val dateTimeStr = map["datetime"]?.takeIf { it.isNotBlank() } ?: return null
            val originalDateTimeStr = map["originaldatetime"]?.takeIf { it.isNotBlank() } ?: dateTimeStr
            val recurrenceStr = map["recurrence"] ?: ""
            val notes = map["notes"]?.let { unescapeField(it) } ?: ""
            val isEnabled = map["isenabled"]?.toBooleanStrictOrNull() ?: true
            val isSnoozed = map["issnoozed"]?.toBooleanStrictOrNull() ?: false

            val nextTriggerTime = parseMillis(dateTimeStr) ?: return null
            val originalDateTime = parseMillis(originalDateTimeStr) ?: nextTriggerTime
            val snoozeUntil = map["snoozeuntil"]?.takeIf { it.isNotBlank() }?.let { parseMillis(it) }
            val createdAt = map["createdat"]?.takeIf { it.isNotBlank() }?.let { parseMillis(it) }
                ?: System.currentTimeMillis()
            val completedAt = map["completedat"]?.takeIf { it.isNotBlank() }?.let { parseMillis(it) }
            val id = map["id"]?.toLongOrNull() ?: 0L

            val (recType, recInterval, recDays) = parseRecurrenceFull(recurrenceStr)

            ReminderEntity(
                id = id,
                title = title,
                notes = notes,
                nextTriggerTime = nextTriggerTime,
                originalDateTime = originalDateTime,
                recurrenceType = recType,
                recurrenceInterval = recInterval,
                recurrenceDaysOfWeek = recDays,
                isEnabled = isEnabled,
                isSnoozed = isSnoozed,
                snoozeUntil = snoozeUntil,
                createdAt = createdAt,
                updatedAt = System.currentTimeMillis(),
                completedAt = completedAt
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun formatMillis(millis: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
            .format(DATE_FORMAT)

    private fun parseMillis(dateStr: String): Long? =
        try {
            LocalDateTime.parse(dateStr.trim(), DATE_FORMAT)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: Exception) { null }

    private fun formatRecurrence(type: RecurrenceType, interval: Int?, days: List<DayOfWeek>): String =
        when (type) {
            RecurrenceType.NONE -> ""
            RecurrenceType.DAILY -> "DAILY"
            RecurrenceType.EVERY_N_DAYS -> "EVERY_N_DAYS:${interval ?: 1}"
            RecurrenceType.WEEKLY -> {
                if (days.isEmpty()) "WEEKLY"
                else "WEEKLY:" + days.sorted().joinToString(",") { it.name.take(3) }
            }
            RecurrenceType.MONTHLY -> "MONTHLY"
            RecurrenceType.YEARLY -> "YEARLY"
        }

    private data class RecurrenceResult(val type: RecurrenceType, val interval: Int?, val days: List<DayOfWeek>)

    private fun parseRecurrenceFull(value: String): RecurrenceResult {
        if (value.isBlank() || value.equals("NONE", ignoreCase = true)) {
            return RecurrenceResult(RecurrenceType.NONE, null, emptyList())
        }
        if (value.startsWith("EVERY_N_DAYS:", ignoreCase = true)) {
            val n = value.substringAfter(":").trim().toIntOrNull() ?: 1
            return RecurrenceResult(RecurrenceType.EVERY_N_DAYS, n, emptyList())
        }
        if (value.startsWith("WEEKLY:", ignoreCase = true)) {
            val days = value.substringAfter(":").split(",")
                .mapNotNull { abbr -> DayOfWeek.entries.firstOrNull { it.name.startsWith(abbr.trim().uppercase()) } }
            return RecurrenceResult(RecurrenceType.WEEKLY, null, days)
        }
        return try {
            RecurrenceResult(RecurrenceType.valueOf(value.uppercase()), null, emptyList())
        } catch (e: Exception) {
            RecurrenceResult(RecurrenceType.NONE, null, emptyList())
        }
    }

    companion object {
        private const val DELIMITER = "~"
        private const val BACKUP_HEADER =
            "title~datetime~originalDatetime~recurrence~notes~isEnabled~isSnoozed~snoozeUntil~createdAt~completedAt~id"
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        private fun escapeField(value: String): String =
            value.replace("\\", "\\\\").replace("~", "\\~")
                .replace("\n", "\\n").replace("\r", "\\r")

        private fun unescapeField(value: String): String {
            val sb = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                if (value[i] == '\\' && i + 1 < value.length) {
                    when (value[i + 1]) {
                        '\\' -> { sb.append('\\'); i += 2 }
                        '~'  -> { sb.append('~');  i += 2 }
                        'n'  -> { sb.append('\n'); i += 2 }
                        'r'  -> { sb.append('\r'); i += 2 }
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
