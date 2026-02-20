package com.divealien.reminders.ui.pending

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.divealien.reminders.ui.theme.RemindersTheme

class PendingNotificationsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            RemindersTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PendingNotificationsScreen(
                        onDone = { finishAndRemoveTask() },
                        onEditReminder = { id ->
                            val intent = Intent(this@PendingNotificationsActivity, com.divealien.reminders.ui.MainActivity::class.java).apply {
                                putExtra("edit_reminder_id", id)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            startActivity(intent)
                        }
                    )
                }
            }
        }
    }

}
