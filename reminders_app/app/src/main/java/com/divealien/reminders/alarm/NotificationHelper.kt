package com.divealien.reminders.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.divealien.reminders.R
import com.divealien.reminders.domain.model.Reminder
import com.divealien.reminders.ui.pending.PendingNotificationsActivity
import com.divealien.reminders.util.Constants

class NotificationHelper(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val prefs =
        context.getSharedPreferences("pending_reminders", Context.MODE_PRIVATE)

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
        addPendingId(reminder.id)
        updateNotification(alert = true)
    }

    fun cancelNotification(reminderId: Long) {
        removePendingId(reminderId)
        updateNotification(alert = false)
    }

    fun getPendingIds(): Set<Long> {
        return prefs.getStringSet(PREFS_KEY, emptySet())
            ?.mapNotNull { it.toLongOrNull() }
            ?.toSet() ?: emptySet()
    }

    fun clearAllPendingIds() {
        prefs.edit().putStringSet(PREFS_KEY, emptySet()).apply()
    }

    private fun addPendingId(id: Long) {
        val ids = getPendingIds().toMutableSet()
        ids.add(id)
        prefs.edit().putStringSet(PREFS_KEY, ids.map { it.toString() }.toSet()).apply()
    }

    private fun removePendingId(id: Long) {
        val ids = getPendingIds().toMutableSet()
        ids.remove(id)
        prefs.edit().putStringSet(PREFS_KEY, ids.map { it.toString() }.toSet()).apply()
    }

    private fun updateNotification(alert: Boolean) {
        val count = getPendingIds().size
        if (count == 0) {
            notificationManager.cancel(NOTIFICATION_ID)
            return
        }

        val title = if (count == 1) "1 reminder pending" else "$count reminders pending"
        val contentPendingIntent = buildContentPendingIntent()
        val deletePendingIntent = buildDeletePendingIntent()

        val builder = NotificationCompat.Builder(context, Constants.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("Tap to manage. Swipe to snooze all.")
            .setContentIntent(contentPendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(false)
            .setOngoing(false)
            .setOnlyAlertOnce(!alert)

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun buildDeletePendingIntent(): PendingIntent {
        val intent = Intent(context, DismissReceiver::class.java).apply {
            action = DismissReceiver.ACTION_DISMISS
        }
        return PendingIntent.getBroadcast(
            context,
            DELETE_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildContentPendingIntent(): PendingIntent {
        val intent = Intent(context, PendingNotificationsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            CONTENT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val NOTIFICATION_ID = Int.MAX_VALUE
        private const val CONTENT_REQUEST_CODE = Int.MAX_VALUE - 1
        private const val DELETE_REQUEST_CODE = Int.MAX_VALUE - 2
        private const val PREFS_KEY = "pending_ids"
    }
}
