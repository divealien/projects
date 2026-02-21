package com.divealien.reminders.alarm

import android.content.Context
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.divealien.reminders.RemindersApp
import com.divealien.reminders.data.backup.settingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as RemindersApp
        val keepCount = app.settingsDataStore.data
            .map { it[intPreferencesKey("daily_backup_keep")] ?: 7 }
            .first()
        app.backupManager.performDailyBackup(keepCount)
        return Result.success()
    }
}
