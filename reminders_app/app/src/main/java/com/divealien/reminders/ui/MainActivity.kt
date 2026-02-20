package com.divealien.reminders.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.divealien.reminders.ui.navigation.NavGraph
import com.divealien.reminders.ui.navigation.Routes
import com.divealien.reminders.ui.theme.RemindersTheme
import com.divealien.reminders.util.Constants

class MainActivity : ComponentActivity() {

    private var pendingSnoozeId by mutableStateOf<Long?>(null)
    private var pendingNewReminder by mutableStateOf(false)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Permission result — app works either way, just without notifications if denied
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        checkForSnoozeIntent(intent)
        checkForShortcutIntent(intent)

        setContent {
            RemindersTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    NavGraph(navController = navController)

                    val snoozeId = pendingSnoozeId
                    LaunchedEffect(snoozeId) {
                        if (snoozeId != null) {
                            navController.navigate(Routes.snoozeRoute(snoozeId)) {
                                launchSingleTop = true
                            }
                            pendingSnoozeId = null
                        }
                    }

                    val newReminder = pendingNewReminder
                    LaunchedEffect(newReminder) {
                        if (newReminder) {
                            navController.navigate(Routes.editRoute(0)) {
                                launchSingleTop = true
                            }
                            pendingNewReminder = false
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkForSnoozeIntent(intent)
        checkForShortcutIntent(intent)
    }

    private fun checkForSnoozeIntent(intent: Intent?) {
        if (intent == null) return
        val isSnooze = intent.getBooleanExtra(Constants.EXTRA_SNOOZE_FLAG, false)
        val reminderId = intent.getLongExtra(Constants.EXTRA_REMINDER_ID, -1)
        if (isSnooze && reminderId != -1L) {
            pendingSnoozeId = reminderId
            intent.removeExtra(Constants.EXTRA_SNOOZE_FLAG)
            intent.removeExtra(Constants.EXTRA_REMINDER_ID)
        }
    }

    private fun checkForShortcutIntent(intent: Intent?) {
        if (intent?.getStringExtra("shortcut_action") == "new_reminder") {
            pendingNewReminder = true
            intent.removeExtra("shortcut_action")
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
