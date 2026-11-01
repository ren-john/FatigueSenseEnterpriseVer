package com.example.fatiguesense.enterprise.enterprise

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.fatiguesense.enterprise.ui.FatigueSenseApp
import com.example.fatiguesense.enterprise.ui.theme.FatigueSenseTheme
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date

@Composable
fun EnterpriseApp() {
    val mode by AppModes.policy.mode.collectAsState()
    val modeError by AppModes.error.collectAsState()
    val notice by AppModes.notice.collectAsState()
    val driver by EnterpriseAuth.driver.collectAsState()
    var checking by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    suspend fun restore() {
        checking = true
        try { AppModes.restorePersisted() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = e.message ?: "Account check failed" }
        finally { checking = false }
    }
    LaunchedEffect(Unit) { if (mode == AppMode.RESTORING) restore() }
    BackHandler(mode == AppMode.ENTERPRISE_ENTRY || mode == AppMode.RESTORING) { AppModes.requestLocal() }
    FatigueSenseTheme {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            when {
                mode == AppMode.SWITCHING_TO_LOCAL -> Column(Modifier.padding(32.dp)) {
                    Text(modeError ?: "Finishing monitoring and account cleanup…")
                    if (modeError != null) TextButton(onClick = { AppModes.requestLocal() }) { Text("Retry cleanup") }
                }
                mode == AppMode.RESTORING -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(if (checking) "Restoring enterprise access…" else "Enterprise access needs attention")
                    if (message.isNotBlank()) Text(message)
                    Button(enabled = !checking, onClick = { scope.launch { restore() } }) { Text("Retry") }
                    TextButton(onClick = { AppModes.requestLocal() }) { Text("Return to Local mode") }
                }
                mode == AppMode.ENTERPRISE_ENTRY -> LoginScreen(message) { message = it }
                mode == AppMode.ENTERPRISE && driver == null -> Column(Modifier.padding(24.dp)) {
                    Text("Enterprise account unavailable")
                    TextButton(onClick = { AppModes.requestLocal() }) { Text("Return to Local mode") }
                }
                else -> key(mode, driver?.uid, driver?.orgId) {
                    Column(Modifier.fillMaxSize()) {
                        if (notice != null || message.isNotBlank()) Row(Modifier.padding(horizontal = 12.dp)) {
                            Text(notice ?: message, Modifier.weight(1f))
                            TextButton(onClick = { message = ""; AppModes.dismissNotice() }) { Text("Dismiss") }
                        }
                        Box(Modifier.weight(1f)) { FatigueSenseApp() }
                    }
                }
            }
        }
    }
}

@Composable private fun LoginScreen(initialMessage: String, onMessage: (String) -> Unit) {
    var email by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("FatigueSenseEnterprise", style = MaterialTheme.typography.headlineMedium)
        Text("Sign in with the driver account invited by your administrator.")
        EnterpriseAuth.configurationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true)
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        Button(enabled = !busy && email.isNotBlank() && password.isNotBlank() && EnterpriseAuth.configurationError == null,
            onClick = { busy = true; scope.launch {
                try { AppModes.signIn(email, password); password = "" }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { onMessage(e.message ?: "Sign-in failed") }
                finally { busy = false }
            } }) { Text(if (busy) "Please wait…" else "Sign in") }
        TextButton(enabled = !busy && email.isNotBlank() && EnterpriseAuth.configurationError == null, onClick = {
            busy = true; scope.launch { try { AppModes.recover(email); onMessage("Check your email for password recovery.") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { onMessage(e.message ?: "Recovery failed") } finally { busy = false } }
        }) { Text("Forgot password") }
        TextButton(onClick = { AppModes.requestLocal() }) { Text("Cancel and return to Local mode") }
        if (initialMessage.isNotBlank()) Text(initialMessage)
    }
}

@Composable fun DeviceScreen(driver: Driver, onDone: () -> Unit, onLogout: () -> Unit) {
    val scope = rememberCoroutineScope()
    var assignments by remember(driver) { mutableStateOf(emptyList<Assignment>()) }
    var selected by remember(driver) { mutableStateOf(EnterpriseAuth.selection.value) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun refresh() { busy = true; scope.launch {
        try {
            assignments = EnterpriseAuth.assignments()
            selected = assignments.firstOrNull { it == selected } ?: assignments.firstOrNull()
            EspConnection.clear(); EnterpriseAuth.selection.value = null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = e.message ?: "Cannot load assignments" }
        finally { busy = false }
    } }
    LaunchedEffect(driver) { refresh() }
    Column(Modifier.fillMaxSize()) {
        Row {
            TextButton(enabled = !busy, onClick = ::refresh) { Text("Refresh assignments") }
            TextButton(enabled = !busy, onClick = onLogout) { Text("Sign out to Local") }
        }
        Text(message)
        assignments.forEach { assignment -> TextButton(enabled = !busy, onClick = {
            selected = assignment; EspConnection.clear(); EnterpriseAuth.selection.value = null
        }) { Text("${if (selected == assignment) "✓ " else ""}${assignment.label} · ${assignment.deviceId}") } }
        if (assignments.isEmpty()) Text("No assignments loaded. Ask your administrator to assign a vehicle and ESP.")
        selected?.let { key(it) { EspSetupScreen(driver, it, onDone) } }
    }
}

@Composable fun EnterpriseLogScreen() {
    val context = LocalContext.current
    val driver by EnterpriseAuth.driver.collectAsState()
    val current = driver ?: return
    val rows by remember(current.uid, current.orgId) { EnterpriseDatabase.get(context).dao().observe(current.uid, current.orgId) }
        .collectAsState(initial = emptyList())
    val flags = remember(rows) { FlagRules.evaluate(rows) }
    val sync by remember(current.uid, current.orgId) { EnterpriseDatabase.get(context).dao().observeSync(current.uid, current.orgId) }
        .collectAsState(initial = null)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Your detection logs", style = MaterialTheme.typography.headlineSmall)
        Text("${rows.count { !it.uploaded }} pending · ${rows.count { it.failure != null }} failed")
        sync?.let { state ->
            Text(state.message)
            state.lastSuccess?.let { Text("Last successful sync: ${DateFormat.getDateTimeInstance().format(Date(it))}") }
        }
        Button(onClick = { UploadScheduler.enqueue(context) }) { Text("Retry upload") }
        Text("Flags from original event times", style = MaterialTheme.typography.titleMedium)
        flags.take(100).forEach { Text("${DateFormat.getDateTimeInstance().format(Date(it.qualifiedAt))} · ${it.kind}") }
        HorizontalDivider()
        rows.take(500).forEach { row ->
            Text("${DateFormat.getDateTimeInstance().format(Date(row.timestamp))} · ${row.triggerType} · ${row.status}")
            Text("${row.deviceId} · ${row.failure ?: if (row.uploaded) "Uploaded" else "Pending upload"}", style = MaterialTheme.typography.bodySmall)
        }
        if (rows.isEmpty()) Text("No detections recorded for this account.")
    }
}
