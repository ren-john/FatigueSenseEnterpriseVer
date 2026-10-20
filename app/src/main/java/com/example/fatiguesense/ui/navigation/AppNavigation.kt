package com.example.fatiguesense.enterprise.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.fatiguesense.enterprise.ui.screens.*

object Routes {
    const val Connection = "connection"
    const val Dashboard  = "dashboard"
    const val FaceSetup  = "face_setup"
    const val Logs       = "logs"
    const val Settings   = "settings"
}

@Composable
fun AppNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.Dashboard) {
        composable(Routes.Connection) {
            SplashScreen(onConnect = { navController.navigateSingleTopTo(Routes.Dashboard) })
        }
        composable(Routes.Dashboard) { DashboardScreen() }
        composable(Routes.FaceSetup)  { FaceSetupScreen() }
        composable(Routes.Logs)       { com.example.fatiguesense.enterprise.enterprise.EnterpriseLogScreen() }
        composable(Routes.Settings)   { SettingsScreen() }
    }
}

fun NavHostController.navigateSingleTopTo(route: String) =
    navigate(route) {
        launchSingleTop = true
        restoreState = true
        popUpTo(graph.startDestinationId) { saveState = true }
    }
