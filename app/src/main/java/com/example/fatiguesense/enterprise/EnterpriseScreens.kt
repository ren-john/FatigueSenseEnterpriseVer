package com.example.fatiguesense.enterprise.enterprise

import android.content.Intent
import android.provider.Settings
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
import com.example.fatiguesense.enterprise.ml.FatigueManager
import com.example.fatiguesense.enterprise.services.FatigueService
import com.example.fatiguesense.enterprise.ui.FatigueSenseApp
import com.example.fatiguesense.enterprise.ui.theme.FatigueSenseTheme
import kotlinx.coroutines.*
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

@Composable
fun EnterpriseApp() {
    val context = LocalContext.current
    val driver by EnterpriseAuth.driver.collectAsState()
    val verified by EspConnection.verifiedId.collectAsState()
    val session by EnterpriseAuth.session.collectAsState()
    var checking by remember { mutableStateOf(true) }
    var showMonitoring by rememberSaveable(driver?.uid) { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        DetectionJournal.initialize(context)
        try { EnterpriseAuth.initialize(context); EnterpriseAuth.restore() }
        catch (e: Exception) { message = e.message ?: "Account check failed" }
        checking = false
    }
    FatigueSenseTheme {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (checking) Box(Modifier.padding(32.dp)) { Text("Checking your account…") }
            else if (driver == null) LoginScreen(message)
            else if (session == null && (!showMonitoring || verified == null)) DeviceScreen(driver!!, onDone = { showMonitoring = true }, onLogout = {
                EnterpriseAuth.signOut(); showMonitoring = false
            })
            else Column(Modifier.fillMaxSize()) {
                val journalError by DetectionJournal.error.collectAsState()
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(driver!!.name, Modifier.weight(1f).padding(top = 12.dp))
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        context.stopService(Intent(context, FatigueService::class.java))
                        scope.launch {
                            FatigueManager.stop(); FatigueService.cleanup?.join(); DetectionJournal.stop()
                            showMonitoring = false; busy = false
                        }
                    }) { Text("Device / account") }
                }
                if (journalError != null) Text(journalError!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                Box(Modifier.weight(1f)) { FatigueSenseApp() }
            }
        }
    }
}

@Composable private fun LoginScreen(initialMessage: String) {
    var email by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf(initialMessage) }; var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("FatigueSenseEnterprise", style = MaterialTheme.typography.headlineMedium)
        Text("Sign in with the driver account invited by your administrator.")
        EnterpriseAuth.configurationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true)
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        Button(enabled = !busy && email.isNotBlank() && password.isNotBlank() && EnterpriseAuth.configurationError == null,
            onClick = { busy = true; scope.launch {
                try { EnterpriseAuth.signIn(email, password); password = "" }
                catch (e: Exception) { message = e.message ?: "Sign-in failed" }
                finally { busy = false }
            } }) { Text(if (busy) "Please wait…" else "Sign in") }
        TextButton(enabled = !busy && email.isNotBlank() && EnterpriseAuth.configurationError == null, onClick = {
            busy = true; scope.launch { try { EnterpriseAuth.recover(email); message = "Check your email for password recovery." }
            catch (e: Exception) { message = e.message ?: "Recovery failed" } finally { busy = false } }
        }) { Text("Forgot password") }
        if (message.isNotBlank()) Text(message)
    }
}

@Composable private fun DeviceScreen(driver: Driver, onDone: () -> Unit, onLogout: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var assignments by remember { mutableStateOf(emptyList<Assignment>()) }
    var selected by remember { mutableStateOf(EnterpriseAuth.selection.value) }
    var ip by remember { mutableStateOf(EspConnection.host.value.ifBlank { "192.168.4.1" }) }
    var setupPassword by remember { mutableStateOf("safedriving") }
    var ssid by remember { mutableStateOf(context.getSharedPreferences("enterprise-setup", 0).getString("ssid", "") ?: "") }
    var password by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Join the ESP hotspot for setup, or enable the configured phone hotspot to reconnect.") }
    val serviceMessage by FatigueService.message.collectAsState()
    fun perform(action: suspend () -> Unit) {
        busy = true
        scope.launch { try { action() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "Connection failed" } finally { busy = false } }
    }
    LaunchedEffect(driver.uid) {
        try { assignments = EnterpriseAuth.assignments(); if (selected !in assignments) selected = assignments.firstOrNull() }
        catch (e: Exception) { message = e.message ?: "Cannot load assignments" }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Connect your assigned ESP", style = MaterialTheme.typography.headlineSmall)
        Text(driver.name)
        assignments.forEach { assignment ->
            TextButton(enabled = !busy, onClick = { selected = assignment }) {
                Text("${if (selected == assignment) "✓ " else ""}${assignment.label} · ${assignment.deviceId}")
            }
        }
        if (assignments.isEmpty()) Text("No assignments loaded. Your administrator must assign a vehicle and ESP.")
        OutlinedTextField(ip, { ip = it }, label = { Text("ESP IP (manual fallback)") }, singleLine = true)
        OutlinedTextField(setupPassword, { setupPassword = it }, label = { Text("ESP setup password") }, visualTransformation = PasswordVisualTransformation())
        Button(enabled = !busy && selected != null, onClick = { perform {
            message = "Trying saved IP, then automatic discovery…"
            ip = EspConnection.connect(context, driver, selected!!, setupPassword)
            EnterpriseAuth.selection.value = selected; message = "ESP verified at $ip. Camera ready."
        } }) { Text("Find my ESP automatically") }
        Button(enabled = !busy && selected != null, onClick = { perform {
            EspConnection.verify(context, selected!!, driver, ip, setupPassword)
            EnterpriseAuth.selection.value = selected; message = "ESP identity and camera verified."
        } }) { Text("Verify entered IP") }
        HorizontalDivider()
        Text("Phone hotspot setup", style = MaterialTheme.typography.titleMedium)
        Text("Enter your phone hotspot credentials. Use 2.4 GHz; enable the hotspot manually after settings are saved.")
        OutlinedTextField(ssid, { ssid = it }, label = { Text("Phone hotspot name") })
        OutlinedTextField(password, { password = it }, label = { Text("Phone hotspot password") }, visualTransformation = PasswordVisualTransformation())
        suspend fun save(body: JSONObject) {
            val assignment = selected ?: error("Select an assignment first")
            val status = EspConnection.status(context, ip, setupPassword)
            require(status.optString("device_id") == assignment.deviceId) { "ESP identity missing or mismatched. Update firmware before provisioning." }
            EspConnection.clear()
            try {
                val result = EspConnection.request(context, ip, setupPassword, body)
                check(result.optBoolean("saved")) { "ESP did not confirm settings saved" }
                context.getSharedPreferences("enterprise-setup", 0).edit().putString("ssid", ssid).apply()
                password = ""; message = "SAVED. ESP restarting. Enable your phone hotspot in Settings, then find/verify the ESP. Saved does not mean connected."
            } catch (e: Exception) {
                message = "Save outcome unknown: ${e.message}. Check ESP status before retrying; it may already have saved and restarted."
            }
        }
        Button(enabled = !busy && selected != null, onClick = { perform {
            EspConnection.validateCredentials(ssid, password)
            save(JSONObject().put("mode", "sta").put("ssid", ssid).put("password", password))
        } }) { Text("Save hotspot settings") }
        Row {
            TextButton(enabled = !busy && selected != null, onClick = { perform { save(JSONObject().put("mode", "sta")) } }) { Text("Use saved Wi-Fi") }
            TextButton(enabled = !busy && selected != null, onClick = { perform { save(JSONObject().put("mode", "ap")) } }) { Text("Use ESP hotspot") }
        }
        TextButton(enabled = !busy && selected != null, onClick = { perform { save(JSONObject().put("mode", "ap").put("forget", true)) } }) { Text("Forget ESP Wi-Fi credentials") }
        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }) { Text("Open Android Wi-Fi / hotspot settings") }
        Text("Recovery: wait about 30 seconds, reconnect to FatigueSense_Cam, then check 192.168.4.1 and correct the settings.")
        Text(message)
        if (serviceMessage.isNotBlank()) Text(serviceMessage)
        val verified by EspConnection.verifiedId.collectAsState()
        Button(enabled = !busy && selected != null && verified == selected?.deviceId, onClick = {
            EnterpriseAuth.selection.value = selected; onDone()
        }) { Text("Open monitoring dashboard") }
        TextButton(enabled = !busy && selected != null, onClick = { perform {
            EnterpriseDatabase.get(context).dao().forgetDevice(driver.orgId, selected!!.vehicleId)
            EspConnection.clear(); message = "Local pairing reset. Verify the administrator-assigned device again."
        } }) { Text("Reset local pairing") }
        TextButton(enabled = !busy, onClick = onLogout) { Text("Sign out") }
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
