package com.example.fatiguesense.enterprise.ui.navigation

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.example.fatiguesense.enterprise.services.FatigueService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.example.fatiguesense.enterprise.enterprise.*
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
            val mode by AppModes.policy.mode.collectAsState()
            val driver by EnterpriseAuth.driver.collectAsState()
            val done = { navController.navigateSingleTopTo(Routes.Dashboard); Unit }
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            var cleaned by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            suspend fun clean() {
                error = null
                try { FatigueService.stopAndAwait(context); cleaned = true }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "Monitoring cleanup failed" }
            }
            LaunchedEffect(Unit) { clean() }
            if (!cleaned) Column {
                Text(error ?: "Finishing monitoring cleanup…")
                if (error != null) TextButton(onClick = { scope.launch { clean() } }) { Text("Retry cleanup") }
                TextButton(onClick = done) { Text("Back to dashboard") }
            }
            else if (mode == AppMode.LOCAL) EspSetupScreen(onDone = done)
            else driver?.let { DeviceScreen(it, onDone = done, onLogout = { AppModes.requestLocal() }) }
        }
        composable(Routes.Dashboard) { DashboardScreen(onConnectEsp = { navController.navigateSingleTopTo(Routes.Connection) }) }
        composable(Routes.FaceSetup)  { FaceSetupScreen() }
        composable(Routes.Logs) {
            val mode by AppModes.policy.mode.collectAsState()
            if (mode == AppMode.LOCAL) LogsScreen() else EnterpriseLogScreen()
        }
        composable(Routes.Settings) { SettingsScreen(onConnectEsp = { navController.navigateSingleTopTo(Routes.Connection) }) }
    }
}

fun NavHostController.navigateSingleTopTo(route: String) =
    navigate(route) {
        launchSingleTop = true
        restoreState = true
        popUpTo(graph.startDestinationId) { saveState = true }
    }
