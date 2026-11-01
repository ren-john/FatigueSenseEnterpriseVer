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
import com.example.fatiguesense.enterprise.enterprise.*
import com.example.fatiguesense.enterprise.db.FatigueDatabase
import com.example.fatiguesense.enterprise.ml.toFatigueEvent
import com.example.fatiguesense.enterprise.services.FatigueService
import com.example.fatiguesense.enterprise.utils.LogExportUtils
import com.example.fatiguesense.enterprise.utils.ExportResult
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
fun SettingsScreen(onConnectEsp: () -> Unit = {}) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showClearDialog by remember { mutableStateOf(false) }

    val streamStatus by FatigueManager.streamStatus.collectAsState()
    val assignment by EnterpriseAuth.selection.collectAsState()
    val mode by AppModes.policy.mode.collectAsState()
    val local = mode == AppMode.LOCAL
    val deviceId by EspConnection.verifiedId.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }

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
                            text = "DEVICE ID: ${if (local) deviceId?.takeUnless { it == "manual-local" } ?: "Unverified local camera" else assignment?.deviceId ?: "Not assigned"} // STATUS: ${when (streamStatus) {
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

                    Text(if (local) "Local mode · no account, uploads or cloud access." else "Enterprise mode · account-scoped history and sync.")
                    if (actionError != null) Text(actionError!!, color = MaterialTheme.colorScheme.error)
                    Button(enabled = !busy, onClick = {
                        busy = true; scope.launch {
                            try { FatigueService.stopAndAwait(context); onConnectEsp() }
                            catch (e: Exception) { actionError = e.message ?: "Monitoring cleanup failed" }
                            finally { busy = false }
                        }
                    }) { Text("Connect / manage ESP") }
                    if (local) TextButton(enabled = !busy, onClick = {
                        busy = true; scope.launch {
                            try { AppModes.beginEntry() }
                            catch (e: Exception) { actionError = e.message ?: "Cannot open enterprise sign-in" }
                            finally { busy = false }
                        }
                    }) { Text("Enterprise sign-in") }
                    else {
                        val driver by EnterpriseAuth.driver.collectAsState()
                        Text(driver?.let { "${it.name} · ${it.orgId}" } ?: "Enterprise account unavailable")
                        TextButton(enabled = !busy, onClick = { AppModes.requestLocal() }) { Text("Sign out and return to Local mode") }
                    }
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
                        value = "Check installed ESP firmware",
                        badge = "UNVERIFIED",
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
                                try {
                                    if (local) {
                                        val events = FatigueDatabase.getLocalInstance(context).fatigueEventDao().getAll().map { it.toFatigueEvent() }
                                        when (LogExportUtils.exportCsv(context, events)) {
                                            ExportResult.NO_EVENTS -> snackbarHostState.showSnackbar("No Local-mode events to export")
                                            ExportResult.ERROR -> snackbarHostState.showSnackbar("Failed to export local history")
                                            else -> Unit
                                        }
                                    } else EnterpriseExport.share(context)
                                }
                                catch (e: Exception) { snackbarHostState.showSnackbar(e.message ?: "FAILED TO EXPORT LOGS") }
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    DataActionRow(
                        icon = Icons.Filled.DeleteForever,
                        title = if (local) "CLEAR LOCAL HISTORY" else "CLEAR UPLOADED HISTORY",
                        subtitle = if (local) "Clear Local-mode history; preserve legacy history" else "Clear this account’s uploaded history; keep pending uploads",
                        titleColor = FatigueError,
                        iconBgColor = FatigueError,
                        trailingIcon = Icons.Filled.Warning,
                        trailingIconColor = FatigueError,
                        onClick = {
                            if (!busy) showClearDialog = true
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
                                    text = if (local) "CLEAR LOCAL HISTORY?" else "CLEAR UPLOADED HISTORY?",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp,
                                    ),
                                )
                            },
                            text = {
                                Text(
                                    text = if (local) "Stop monitoring and remove Local-mode events from this phone? Preserved legacy history is retained." else "Remove your completed, uploaded events from this phone? Pending uploads and cloud history are retained.",
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
                                        busy = true
                                        val owner = EnterpriseAuth.driver.value
                                        val actionMode = mode
                                        scope.launch {
                                            try {
                                                FatigueService.stopAndAwait(context)
                                                check(AppModes.policy.mode.value == actionMode) { "Mode changed; retry." }
                                                if (local) FatigueDatabase.getLocalInstance(context).fatigueEventDao().deleteAll()
                                                else {
                                                    check(EnterpriseAuth.driver.value == owner && owner != null) { "Account changed" }
                                                    EnterpriseDatabase.get(context).dao().clearUploaded(owner.uid, owner.orgId)
                                                }
                                                snackbarHostState.showSnackbar("History cleared")
                                            } catch (e: Exception) { actionError = e.message ?: "Failed to clear history" }
                                            finally { busy = false }
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
