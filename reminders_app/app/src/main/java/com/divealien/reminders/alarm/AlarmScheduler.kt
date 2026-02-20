package com.divealien.reminders.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.divealien.reminders.domain.model.Reminder
import com.divealien.reminders.util.Constants

class AlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun schedule(reminder: Reminder) {
        if (!reminder.isEnabled) {
            Log.d(TAG, "Skipping reminder ${reminder.id} — disabled")
            return
        }

        val triggerTime = if (reminder.isSnoozed && reminder.snoozeUntil != null) {
            reminder.snoozeUntil
        } else {
            reminder.nextTriggerTime
        }

        // Don't schedule alarms in the past
        if (triggerTime <= System.currentTimeMillis()) {
            Log.w(TAG, "Skipping reminder ${reminder.id} — trigger time is in the past " +
                    "(trigger=$triggerTime, now=${System.currentTimeMillis()})")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                Log.e(TAG, "Cannot schedule exact alarms — permission not granted. " +
                        "Falling back to inexact alarm for reminder ${reminder.id}")
                scheduleInexact(reminder.id, triggerTime)
                return
            }
        }

        val pendingIntent = createPendingIntent(reminder.id)

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
            val delayMs = triggerTime - System.currentTimeMillis()
            Log.d(TAG, "Scheduled exact alarm for reminder ${reminder.id} — " +
                    "fires in ${delayMs / 1000}s (triggerTime=$triggerTime)")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException scheduling exact alarm for reminder ${reminder.id}", e)
            scheduleInexact(reminder.id, triggerTime)
        }
    }

    private fun scheduleInexact(reminderId: Long, triggerTime: Long) {
        val pendingIntent = createPendingIntent(reminderId)
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerTime,
            pendingIntent
        )
        Log.d(TAG, "Scheduled inexact alarm for reminder $reminderId as fallback")
    }

    private fun createPendingIntent(reminderId: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(Constants.EXTRA_REMINDER_ID, reminderId)
        }
        return PendingIntent.getBroadcast(
            context,
            reminderId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun cancel(reminderId: Long) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminderId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        Log.d(TAG, "Cancelled alarm for reminder $reminderId")
    }

    companion object {
        private const val TAG = "AlarmScheduler"
    }
}
