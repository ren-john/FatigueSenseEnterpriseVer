package com.example.fatiguesense.enterprise.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.fatiguesense.enterprise.ml.FatigueManager
import com.example.fatiguesense.enterprise.ml.StreamStatus
import com.example.fatiguesense.enterprise.ui.components.AppBottomBar
import com.example.fatiguesense.enterprise.ui.components.AppTopBar
import com.example.fatiguesense.enterprise.ui.components.BottomBarItem
import com.example.fatiguesense.enterprise.ui.navigation.AppNavHost
import com.example.fatiguesense.enterprise.ui.navigation.Routes
import com.example.fatiguesense.enterprise.ui.navigation.navigateSingleTopTo
import com.example.fatiguesense.enterprise.ui.theme.FatigueSenseTheme

@Composable
fun FatigueSenseApp() {
    val context        = androidx.compose.ui.platform.LocalContext.current
    val navController  = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute   = backStackEntry?.destination?.route ?: Routes.Dashboard

    // Ensure FatigueManager DB and logs are initialized immediately at startup
    LaunchedEffect(Unit) {
        FatigueManager.init(context)
    }

    // Live connection status driven by FatigueManager
    val streamStatus by FatigueManager.streamStatus.collectAsState()
    val isConnected   = streamStatus == StreamStatus.STREAMING

    val bottomNavItems = listOf(
        BottomBarItem(
            route        = Routes.Dashboard,
            label        = "Dashboard",
            icon         = Icons.Outlined.BarChart,
            iconSelected = Icons.Filled.BarChart,
        ),
        BottomBarItem(
            route        = Routes.FaceSetup,
            label        = "Calibration",
            icon         = Icons.Outlined.Tune,
            iconSelected = Icons.Filled.Tune,
        ),
        BottomBarItem(
            route        = Routes.Logs,
            label        = "Logs",
            icon         = Icons.AutoMirrored.Outlined.List,
            iconSelected = Icons.AutoMirrored.Filled.List,
        ),
        BottomBarItem(
            route        = Routes.Settings,
            label        = "Settings",
            icon         = Icons.Outlined.Settings,
            iconSelected = Icons.Filled.Settings,
        ),
    )

    val showChrome = currentRoute != Routes.Connection

    FatigueSenseTheme {
        if (!showChrome) {
            AppNavHost(navController = navController)
        } else {
            Scaffold(
                topBar = {
                    AppTopBar(
                        ipAddress       = com.example.fatiguesense.enterprise.enterprise.EspConnection.host.collectAsState().value,
                        isConnected     = isConnected,
                        onSettingsClick = {
                            navController.navigateSingleTopTo(Routes.Settings)
                        },
                    )
                },
                bottomBar = {
                    AppBottomBar(
                        items         = bottomNavItems,
                        selectedRoute = currentRoute,
                        onSelect      = { navController.navigateSingleTopTo(it.route) },
                    )
                },
            ) { innerPadding ->
                Box(modifier = Modifier.padding(innerPadding)) {
                    AppNavHost(navController = navController)
                }
            }
        }
    }
}
