package com.divealien.reminders.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.divealien.reminders.R
import com.divealien.reminders.domain.model.Reminder
import com.divealien.reminders.ui.MainActivity
import com.divealien.reminders.util.Constants
import com.divealien.reminders.util.DateTimeUtils

class NotificationHelper(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createChannel() {
        val channel = NotificationChannel(
            Constants.NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_description)
            enableVibration(true)
            setBypassDnd(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun showNotification(reminder: Reminder) {
        val completePendingIntent = buildCompletePendingIntent(reminder)
        val snoozePendingIntent = buildSnoozePendingIntent(reminder)

        val timeText = DateTimeUtils.formatTime(reminder.nextTriggerTime)
        val bigText = if (reminder.notes.isNotBlank())
            "$timeText\n${reminder.notes}" else timeText

        val builder = NotificationCompat.Builder(context, Constants.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(reminder.title)
            .setContentText(timeText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(snoozePendingIntent)
            .addAction(0, "Complete", completePendingIntent)
            .addAction(0, "Snooze", snoozePendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(false)
            .setOngoing(true)
            .setGroup(GROUP_KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_ALL)

        notificationManager.notify(reminder.id.toInt(), builder.build())
        updateGroupSummary()
    }

    private fun buildCompletePendingIntent(reminder: Reminder): PendingIntent {
        val intent = Intent(context, CompleteReceiver::class.java).apply {
            action = Constants.ACTION_COMPLETE
            putExtra(Constants.EXTRA_REMINDER_ID, reminder.id)
        }
        return PendingIntent.getBroadcast(
            context,
            (reminder.id * 10).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildSnoozePendingIntent(reminder: Reminder): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(Constants.EXTRA_REMINDER_ID, reminder.id)
            putExtra(Constants.EXTRA_SNOOZE_FLAG, true)
        }
        return PendingIntent.getActivity(
            context,
            (reminder.id * 10 + 1).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateGroupSummary() {
        val summary = NotificationCompat.Builder(context, Constants.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Reminders")
            .setStyle(NotificationCompat.InboxStyle().setSummaryText("Reminders"))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()

        notificationManager.notify(SUMMARY_ID, summary)
    }

    fun cancelNotification(reminderId: Long) {
        notificationManager.cancel(reminderId.toInt())

        val activeNotifications = notificationManager.activeNotifications
        val remainingGrouped = activeNotifications.count {
            it.id != SUMMARY_ID && it.notification.group == GROUP_KEY
        }
        if (remainingGrouped == 0) {
            notificationManager.cancel(SUMMARY_ID)
        }
    }

    companion object {
        private const val GROUP_KEY = "com.divealien.reminders.REMINDER_GROUP"
        private const val SUMMARY_ID = Int.MAX_VALUE
    }
}
