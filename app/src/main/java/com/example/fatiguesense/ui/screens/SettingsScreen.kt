package com.example.fatiguesense.enterprise.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.fatiguesense.enterprise.ml.Esp32AlertSender
import com.example.fatiguesense.enterprise.ml.FatigueManager
import com.example.fatiguesense.enterprise.ml.StreamStatus
import com.example.fatiguesense.enterprise.enterprise.EnterpriseAuth
import com.example.fatiguesense.enterprise.ui.components.*
import com.example.fatiguesense.enterprise.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showClearDialog by remember { mutableStateOf(false) }

    val streamStatus by FatigueManager.streamStatus.collectAsState()
    val assignment by EnterpriseAuth.selection.collectAsState()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent,
    ) { innerPadding ->
        ScreenSurface {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState()),
            ) {
                // ── System Configuration Header Card ──────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color(0xFF1F1F24), Color(0xFF161618)),
                            ),
                        )
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                ) {
                    Text(
                        text = "✦",
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 4.dp),
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = FatigueOnSurfaceMuted.copy(alpha = 0.15f),
                            fontSize = 32.sp,
                        ),
                    )
                    Column {
                        Text(
                            text = "SYSTEM CONFIGURATION",
                            style = MaterialTheme.typography.titleLarge.copy(
                                color = FatigueOnSurface,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.5.sp,
                            ),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "DEVICE ID: ${assignment?.deviceId ?: "Not assigned"} // STATUS: ${when (streamStatus) {
                            StreamStatus.STREAMING  -> "LINK STABLE"
                            StreamStatus.CONNECTING -> "CONNECTING"
                            StreamStatus.LOST       -> "DISCONNECTED"
                        }}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = FatigueOnSurfaceMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                letterSpacing = 0.5.sp,
                            ),
                        )
                    }
                }

                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(20.dp))

                    // ── 01. DEVICE ────────────────────────────────────────────
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "01.",
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = FatiguePrimary,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "DEVICE",
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = FatigueOnSurface,
                                letterSpacing = 1.5.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                    }
                    Spacer(Modifier.height(10.dp))

                    DeviceInfoCard(
                        label = "IP ADDRESS",
                        value = com.example.fatiguesense.enterprise.enterprise.EspConnection.host.collectAsState().value,
                        trailingIcon = Icons.Filled.Wifi,
                    )
                    Spacer(Modifier.height(8.dp))
                    DeviceInfoCard(
                        label = "FIRMWARE",
                        value = "v1.0.2-STABLE",
                        badge = "LATEST",
                        badgeColor = FatigueSuccess,
                    )
                    Spacer(Modifier.height(12.dp))
                    ActionButton(
                        text = "PING DEVICE",
                        icon = Icons.Filled.Wifi,
                        onClick = {
                            scope.launch {
                                val reachable = Esp32AlertSender.sendPing()
                                snackbarHostState.showSnackbar(
                                    message = if (reachable) "✓  DEVICE REACHABLE (PING SENT)" else "✗  NO RESPONSE FROM DEVICE",
                                )
                            }
                        },
                    )

                    Spacer(Modifier.height(24.dp))

                    // ── 02. DATA ──────────────────────────────────────────────
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "02.",
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = FatiguePrimary,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "DATA",
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = FatigueOnSurface,
                                letterSpacing = 1.5.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                    }
                    Spacer(Modifier.height(10.dp))

                    DataActionRow(
                        icon = Icons.Filled.FileDownload,
                        title = "EXPORT CSV",
                        subtitle = "Export your detection events with original timestamps",
                        titleColor = FatigueOnSurface,
                        iconBgColor = FatigueOnSurface,
                        trailingIcon = Icons.Filled.ChevronRight,
                        trailingIconColor = FatigueOnSurfaceMuted,
                        onClick = {
                            scope.launch {
                                try { com.example.fatiguesense.enterprise.enterprise.EnterpriseExport.share(context) }
                                catch (e: Exception) { snackbarHostState.showSnackbar(e.message ?: "FAILED TO EXPORT LOGS") }
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    DataActionRow(
                        icon = Icons.Filled.DeleteForever,
                        title = "CLEAR LOCAL LOGS",
                        subtitle = "Clear uploaded local history; keep pending uploads",
                        titleColor = FatigueError,
                        iconBgColor = FatigueError,
                        trailingIcon = Icons.Filled.Warning,
                        trailingIconColor = FatigueError,
                        onClick = {
                            showClearDialog = true
                        },
                    )

                    if (showClearDialog) {
                        AlertDialog(
                            onDismissRequest = { showClearDialog = false },
                            containerColor = Color(0xFF1E1E22),
                            titleContentColor = FatigueOnSurface,
                            textContentColor = FatigueOnSurfaceMuted,
                            icon = {
                                Icon(
                                    imageVector = Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = FatigueError,
                                )
                            },
                            title = {
                                Text(
                                    text = "CLEAR UPLOADED LOCAL LOGS?",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp,
                                    ),
                                )
                            },
                            text = {
                                Text(
                                    text = "Remove your completed, uploaded events from this phone? Pending uploads and cloud history are retained.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            confirmButton = {
                                Button(
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = FatigueError,
                                        contentColor = Color.White,
                                    ),
                                    onClick = {
                                        showClearDialog = false
                                        scope.launch {
                                            val driver = com.example.fatiguesense.enterprise.enterprise.EnterpriseAuth.driver.value
                                            if (driver != null) {
                                                com.example.fatiguesense.enterprise.enterprise.EnterpriseDatabase.get(context).dao().clearUploaded(driver.uid, driver.orgId)
                                                snackbarHostState.showSnackbar("UPLOADED LOCAL HISTORY CLEARED")
                                            }
                                        }
                                    },
                                ) {
                                    Text(
                                        text = "CLEAR LOGS",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp,
                                        ),
                                    )
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = { showClearDialog = false },
                                ) {
                                    Text(
                                        text = "CANCEL",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            color = FatigueOnSurfaceMuted,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp,
                                        ),
                                    )
                                }
                            },
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    // ── Build Info ────────────────────────────────────────────
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "BUILD HASH: 49FA21_0X00",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = FatigueOnSurfaceMuted.copy(alpha = 0.4f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 8.sp,
                                letterSpacing = 0.5.sp,
                            ),
                        )
                        Text(
                            text = "ARCHITECTURE: ESP32-WROVER-E",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = FatigueOnSurfaceMuted.copy(alpha = 0.4f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 8.sp,
                                letterSpacing = 0.5.sp,
                            ),
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}
