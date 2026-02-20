package com.divealien.reminders.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.divealien.reminders.ui.completed.CompletedRemindersScreen
import com.divealien.reminders.ui.edit.ReminderEditScreen
import com.divealien.reminders.ui.list.ReminderListScreen
import com.divealien.reminders.ui.settings.SettingsScreen
import com.divealien.reminders.ui.snooze.SnoozeScreen

object Routes {
    const val LIST = "list"
    const val EDIT = "edit/{id}"
    const val SETTINGS = "settings"
    const val SNOOZE = "snooze/{reminderId}"
    const val COMPLETED = "completed"

    fun editRoute(id: Long = 0L) = "edit/$id"
    fun snoozeRoute(reminderId: Long) = "snooze/$reminderId"
}

@Composable
fun NavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.LIST) {

        composable(Routes.LIST) {
            ReminderListScreen(
                onAddReminder = { navController.navigate(Routes.editRoute(0)) },
                onEditReminder = { id -> navController.navigate(Routes.editRoute(id)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }

        composable(
            route = Routes.EDIT,
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id") ?: 0L
            ReminderEditScreen(
                reminderId = id,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onViewCompleted = { navController.navigate(Routes.COMPLETED) }
            )
        }

        composable(Routes.COMPLETED) {
            CompletedRemindersScreen(
                onNavigateBack = { navController.popBackStack() },
                onEditReminder = { id -> navController.navigate(Routes.editRoute(id)) }
            )
        }

        composable(
            route = Routes.SNOOZE,
            arguments = listOf(navArgument("reminderId") { type = NavType.LongType })
        ) { backStackEntry ->
            val reminderId = backStackEntry.arguments?.getLong("reminderId") ?: return@composable
            SnoozeScreen(
                reminderId = reminderId,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
