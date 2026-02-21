package com.divealien.reminders

import android.app.Application
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.divealien.reminders.alarm.AlarmScheduler
import com.divealien.reminders.alarm.BackupWorker
import com.divealien.reminders.alarm.NotificationHelper
import com.divealien.reminders.data.backup.BackupManager
import com.divealien.reminders.data.backup.settingsDataStore
import com.divealien.reminders.data.local.ReminderDatabase
import com.divealien.reminders.data.repository.ReminderRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class RemindersApp : Application() {

    lateinit var database: ReminderDatabase
        private set
    lateinit var repository: ReminderRepository
        private set
    lateinit var alarmScheduler: AlarmScheduler
        private set
    lateinit var notificationHelper: NotificationHelper
        private set
    lateinit var backupManager: BackupManager
        private set

    override fun onCreate() {
        super.onCreate()

        database = ReminderDatabase.getInstance(this)
        alarmScheduler = AlarmScheduler(this)
        notificationHelper = NotificationHelper(this)

        val dao = database.reminderDao()
        backupManager = BackupManager(this, dao)

        repository = ReminderRepository(
            dao = dao,
            onMutation = { backupManager.requestBackup() }
        )

        notificationHelper.createChannel()

        // Schedule daily versioned backup
        val dailyBackupRequest = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "daily_backup",
            ExistingPeriodicWorkPolicy.KEEP,
            dailyBackupRequest
        )

        // Auto-cleanup old completed reminders
        CoroutineScope(Dispatchers.IO).launch {
            val retentionDays = settingsDataStore.data
                .map { it[intPreferencesKey("retention_days")] ?: 30 }
                .first()
            val cutoff = System.currentTimeMillis() - retentionDays * 86400000L
            dao.deleteCompletedOlderThan(cutoff)
        }
    }

}
