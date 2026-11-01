package com.example.fatiguesense.enterprise.enterprise

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.fatiguesense.enterprise.services.FatigueService
import com.example.fatiguesense.enterprise.ui.theme.FatigueSuccess
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.IOException

private enum class SetupTone { INFO, SUCCESS, WARNING, ERROR }
private enum class SetupConfirmation { USE_SAVED, DIRECT, FORGET_WIFI, RESET_PAIRING }

/** Shared setup; null assignment means personal Local mode, never a synthetic enterprise assignment. */
@Composable
fun EspSetupScreen(driver: Driver? = null, assignment: Assignment? = null, onDone: () -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val local = driver == null
    val setupPrefs = remember(local) { context.getSharedPreferences(if (local) "local-setup" else "enterprise-setup", 0) }
    var savedHost by remember(local, assignment) { mutableStateOf(if (local) EspConnection.savedLocalHost(context) else "") }
    var ip by rememberSaveable(local, assignment) { mutableStateOf(if (local) savedHost.ifBlank { "192.168.4.1" } else EspConnection.host.value.ifBlank { "192.168.4.1" }) }
    var setupPassword by remember { mutableStateOf("safedriving") }
    var legacy by rememberSaveable(local, assignment) { mutableStateOf(false) }
    var manualId by rememberSaveable(assignment) { mutableStateOf("") }
    var ssid by rememberSaveable(local) { mutableStateOf(setupPrefs.getString("ssid", "") ?: "") }
    var password by remember { mutableStateOf("") }
    var connectionOptions by rememberSaveable { mutableStateOf(false) }
    var hotspotOptions by rememberSaveable { mutableStateOf(false) }
    var recoveryOptions by rememberSaveable { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<SetupConfirmation?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by rememberSaveable(local, assignment) { mutableStateOf("") }
    var messageTitle by rememberSaveable(local, assignment) { mutableStateOf("") }
    var tone by rememberSaveable(local, assignment) { mutableStateOf(SetupTone.INFO) }
    var details by remember { mutableStateOf<JSONObject?>(null) }
    val transitioning by FatigueService.transitioning.collectAsState()
    val monitoring by com.example.fatiguesense.enterprise.ml.FatigueManager.isMonitoring.collectAsState()
    val verified by EspConnection.verifiedId.collectAsState()
    val localReady by EspConnection.localReady.collectAsState()
    val configured by EspConnection.configuredAssignment.collectAsState()
    val ready = verified != null && (if (local) localReady else configured == assignment)
    val enabled = !busy && !transitioning && !monitoring

    fun notice(title: String, text: String, kind: SetupTone = SetupTone.INFO) {
        messageTitle = title; message = text; tone = kind
    }
    LaunchedEffect(local, assignment) {
        if (setupPrefs.getBoolean("save-pending", false)) notice(
            "Check the camera before saving again",
            "The last save was interrupted. The camera may have restarted with the new settings. Open Connection options and check its status first.",
            SetupTone.WARNING,
        )
    }
    fun invalidate() {
        EspConnection.clear()
        if (!local) EnterpriseAuth.selection.value = null
        details = null
        message = ""
    }
    fun perform(title: String, action: suspend () -> Unit) {
        focus.clearFocus()
        busy = true
        notice(title, "Keep the camera powered on. This may take a few seconds.")
        scope.launch {
            scroll.animateScrollTo(0)
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                notice("Camera needs attention", if (e is IOException) {
                    "The camera didn’t respond. Make sure you’re connected to FatigueSense_Cam and choose “Stay connected” if Android says there’s no internet. If it still fails, restart the camera and try again."
                } else if (e.message?.contains("HTTP 401") == true) {
                    "The camera setup password wasn’t accepted. Open Connection options and enter the camera’s password, then try again."
                } else e.message ?: "Check the camera’s power and Wi-Fi connection, then try again.", SetupTone.ERROR)
            }
            finally { busy = false }
        }
    }
    suspend fun verify(): JSONObject = if (local) EspConnection.verifyLocal(context, ip, setupPassword, legacy)
        else EspConnection.verify(context, checkNotNull(assignment), checkNotNull(driver), ip, setupPassword, manualId.takeIf { legacy })
    fun cameraReady(status: JSONObject? = null) {
        details = status
        if (!local) EnterpriseAuth.selection.value = assignment
        savedHost = if (local) EspConnection.savedLocalHost(context) else ip
        setupPrefs.edit().putBoolean("save-pending", false).apply()
        val manual = if (status == null) legacy else status.isNull("device_id") || status.optString("device_id").isBlank()
        val recovery = status != null && status.optString("mode") == "sta" && !status.optBoolean("connected") && status.optBoolean("hotspot_active")
        notice("Camera is ready", buildString {
            append("Connected at $ip. Open the dashboard to start monitoring.")
            if (manual) append(" You confirmed this camera manually; its hardware identity could not be checked.")
            if (recovery) append(" Using the camera’s recovery Wi-Fi; it hasn’t joined your phone’s hotspot.")
        }, SetupTone.SUCCESS)
    }
    suspend fun save(body: JSONObject) {
        // Verify identity before changing settings; camera readiness isn't required for recovery.
        val status = EspConnection.status(context, ip, setupPassword)
        val reported = status.optString("device_id").takeUnless { status.isNull("device_id") || it.isBlank() }
        if (local) requireLocalEspIdentity(reported, EspConnection.savedLocalId(context), legacy)
        else requireEspIdentity(reported, checkNotNull(assignment).deviceId, manualId.takeIf { legacy })
        invalidate()
        check(setupPrefs.edit().putBoolean("save-pending", true).commit()) { "Could not record setup progress. No settings sent." }
        notice("Saving camera settings…", "The camera will restart. Keep it powered on.")
        try {
            val result = EspConnection.request(context, ip, setupPassword, body)
            check(result.optBoolean("saved")) { "No save acknowledgement" }
            check(setupPrefs.edit().putString("ssid", ssid).putBoolean("save-pending", false).commit()) { "Could not record save acknowledgement" }
            val direct = body.optString("mode") == "ap"
            if (direct) ip = "192.168.4.1"
            notice("Settings saved · reconnect next", if (direct) {
                "Wait about 30 seconds, join FatigueSense_Cam in Wi-Fi settings, then tap Connect camera."
            } else {
                "Enable your phone’s 2.4 GHz hotspot and wait for the camera to join it. Then return here and reconnect. You may need to enter the camera’s new address."
            }, SetupTone.WARNING)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            notice("The save result is unclear", "The camera may have saved and restarted. Reconnect to it and check its status in Connection options before saving again.", SetupTone.WARNING)
        } finally { password = "" }
    }
    BackHandler(busy) { /* Wait for the current save/verification to settle. */ }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = !busy && !transitioning, onClick = onDone) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to dashboard")
            }
            Text("Camera setup", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        Column(
            Modifier.weight(1f).verticalScroll(scroll).widthIn(max = 560.dp).align(Alignment.CenterHorizontally)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Connect your camera", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(if (local) "Two steps. No account or internet needed." else "Assigned to ${assignment?.label}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!local) Text("Camera ID: ${assignment?.deviceId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (message.isNotBlank() && (tone != SetupTone.SUCCESS || ready)) SetupNotice(messageTitle, message, tone, busy)
            else if (ready) SetupNotice("Camera is ready", "Open the dashboard to start monitoring.", SetupTone.SUCCESS)
            if (monitoring || transitioning) SetupNotice("Finishing monitoring", "Wait for monitoring to stop before changing the camera connection.", SetupTone.WARNING)

            SetupCard {
                SetupStep(1, "Join the camera’s Wi-Fi", "Keep your camera powered on and nearby.")
                Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text("FatigueSense_Cam", fontWeight = FontWeight.SemiBold)
                            Text("Default Wi-Fi password: safedriving", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text("If Android says “No internet”, choose “Stay connected”.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(enabled = enabled, onClick = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open Wi-Fi settings")
                }
            }
            SetupCard {
                SetupStep(2, "Connect to the camera", "Return here after joining its Wi-Fi.")
                OutlinedTextField(
                    ip, { ip = it; invalidate() }, modifier = Modifier.fillMaxWidth(), enabled = enabled,
                    label = { Text("Camera IP address") }, singleLine = true,
                    supportingText = { Text("Use 192.168.4.1 on FatigueSense_Cam.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Button(enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
                    perform("Checking your camera…") { cameraReady(verify()) }
                }) {
                    Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (busy) "Please wait…" else if (ready) "Check connection again" else "Connect camera")
                }
                if (!local || savedHost.isNotBlank()) OutlinedButton(enabled = enabled, modifier = Modifier.fillMaxWidth(), onClick = {
                    perform("Looking for your saved camera…") {
                        ip = if (local) EspConnection.connectLocal(context, setupPassword, legacy)
                            else EspConnection.connect(context, checkNotNull(driver), checkNotNull(assignment), setupPassword, manualId.takeIf { legacy })
                        cameraReady()
                    }
                }) { Text("Reconnect saved camera") }
                SetupExpander("Connection options", "Password, older cameras and connection status", Icons.Default.Tune, connectionOptions, { connectionOptions = !connectionOptions }) {
                    SetupPasswordField(setupPassword, { setupPassword = it; invalidate() }, "Camera setup password", enabled)
                    Text("The default password is already filled in. Change it only if you changed it on the camera.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        Modifier.fillMaxWidth().toggleable(value = legacy, enabled = enabled, role = Role.Checkbox, onValueChange = { legacy = it; invalidate() }).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(legacy, onCheckedChange = null, enabled = enabled)
                        Spacer(Modifier.width(8.dp))
                        Text("My camera uses older firmware", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (legacy) {
                        Text(if (local) "Only enable this if the camera cannot report its ID. You’re confirming this is your camera; the app cannot verify its hardware identity." else "Only use this if the camera cannot report its ID. Enter the assigned ID exactly as shown above.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!local) OutlinedTextField(manualId, { manualId = it; invalidate() }, modifier = Modifier.fillMaxWidth(), enabled = enabled, label = { Text("Assigned camera ID") }, singleLine = true)
                    }
                    TextButton(enabled = enabled, onClick = {
                        perform("Reading camera status…") {
                            details = EspConnection.status(context, ip, setupPassword)
                            notice("Camera status received", "Status alone doesn’t verify the camera. Tap Connect camera before monitoring.")
                        }
                    }) { Text("Check camera status") }
                    details?.let { status ->
                        SetupStatusRow("Camera", if (status.optBoolean("camera_ready")) "Ready" else "Not ready")
                        SetupStatusRow("Phone hotspot", if (status.optBoolean("connected")) "Joined" else "Not joined")
                        SetupStatusRow("Camera Wi-Fi", if (status.optBoolean("hotspot_active")) "Available" else "Off")
                        SetupStatusRow("Restarting", if (status.optBoolean("restarting")) "Yes · wait a moment" else "No")
                    }
                }
            }
            SetupCard {
                SetupExpander("Use your phone’s hotspot", "Optional · connect through your phone instead", Icons.Default.PhonelinkSetup, hotspotOptions, { hotspotOptions = !hotspotOptions }) {
                    Text("You can skip this when using FatigueSense_Cam. To use your phone’s hotspot, set it to 2.4 GHz and enter its details below.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(ssid, { ssid = it }, modifier = Modifier.fillMaxWidth(), enabled = enabled, label = { Text("Phone hotspot name") }, singleLine = true)
                    SetupPasswordField(password, { password = it }, "Hotspot password", enabled)
                    Text("Saving restarts the camera. Then turn on your phone’s hotspot and reconnect to the camera.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(enabled = enabled && ssid.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                        perform("Checking hotspot settings…") {
                            EspConnection.validateCredentials(ssid, password)
                            save(JSONObject().put("mode", "sta").put("ssid", ssid).put("password", password))
                        }
                    }) { Text("Save and restart camera") }
                    TextButton(enabled = enabled, onClick = { confirmation = SetupConfirmation.USE_SAVED }) { Text("Use hotspot saved on camera") }
                    TextButton(enabled = enabled, onClick = { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }) { Text("Open phone hotspot settings") }
                }
            }
            SetupCard {
                SetupExpander("Troubleshooting & device settings", "Recovery help, Wi-Fi reset and pairing", Icons.AutoMirrored.Filled.HelpOutline, recoveryOptions, { recoveryOptions = !recoveryOptions }) {
                    Text("Can’t connect?", fontWeight = FontWeight.SemiBold)
                    Text("1. Restart the camera and wait about 30 seconds.\n2. Join FatigueSense_Cam and stay connected without internet.\n3. Return here, enter 192.168.4.1 and tap Connect camera.", style = MaterialTheme.typography.bodyMedium)
                    Text("If you use your phone’s hotspot, enable it and tap Reconnect saved camera. Automatic discovery needs compatible camera firmware.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider()
                    TextButton(enabled = enabled, onClick = { confirmation = SetupConfirmation.DIRECT }) { Text("Switch camera to direct Wi-Fi") }
                    TextButton(enabled = enabled, onClick = { confirmation = SetupConfirmation.FORGET_WIFI }) { Text("Remove saved hotspot from camera") }
                    TextButton(enabled = enabled, onClick = { confirmation = SetupConfirmation.RESET_PAIRING }) { Text("Pair a different camera") }
                }
            }
        }
        if (ready) Surface(shadowElevation = 8.dp) {
            Button(enabled = enabled, onClick = onDone, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 48.dp)) {
                Text("Continue to dashboard")
            }
        }
    }
    confirmation?.let { action ->
        val (title, explanation) = when (action) {
            SetupConfirmation.USE_SAVED -> "Use the saved hotspot?" to "The camera will restart and try its saved Wi-Fi details. Turn on that phone hotspot, then reconnect."
            SetupConfirmation.DIRECT -> "Switch to camera Wi-Fi?" to "The camera will restart. Join FatigueSense_Cam afterward and connect at 192.168.4.1."
            SetupConfirmation.FORGET_WIFI -> "Remove the saved hotspot?" to "This removes the phone hotspot details from the camera and restarts it in direct Wi-Fi mode."
            SetupConfirmation.RESET_PAIRING -> "Pair a different camera?" to "This clears this app’s camera pairing. Your detection history stays on this phone. You’ll need to connect and verify a camera again."
        }
        AlertDialog(
            onDismissRequest = { confirmation = null }, title = { Text(title) }, text = { Text(explanation) },
            confirmButton = { TextButton(onClick = {
                confirmation = null
                perform(if (action == SetupConfirmation.RESET_PAIRING) "Clearing camera pairing…" else "Updating camera settings…") {
                    when (action) {
                        SetupConfirmation.USE_SAVED -> save(JSONObject().put("mode", "sta"))
                        SetupConfirmation.DIRECT -> save(JSONObject().put("mode", "ap"))
                        SetupConfirmation.FORGET_WIFI -> save(JSONObject().put("mode", "ap").put("forget", true))
                        SetupConfirmation.RESET_PAIRING -> {
                            if (local) EspConnection.forgetLocal(context)
                            else EnterpriseDatabase.get(context).dao().forgetDevice(checkNotNull(driver).orgId, checkNotNull(assignment).vehicleId)
                            savedHost = ""; ip = "192.168.4.1"; legacy = false; manualId = ""; setupPassword = "safedriving"
                            invalidate()
                            notice("Ready to pair another camera", "Join its Wi-Fi and tap Connect camera to verify it.")
                        }
                    }
                }
            }) { Text(if (action == SetupConfirmation.RESET_PAIRING) "Clear pairing" else "Restart camera") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SetupCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun SetupStep(number: Int, title: String, description: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = .14f), shape = RoundedCornerShape(10.dp)) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                Text(number.toString(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SetupNotice(title: String, message: String, tone: SetupTone, busy: Boolean = false) {
    val accent = when (tone) {
        SetupTone.SUCCESS -> FatigueSuccess
        SetupTone.ERROR -> MaterialTheme.colorScheme.error
        SetupTone.WARNING -> MaterialTheme.colorScheme.secondary
        SetupTone.INFO -> MaterialTheme.colorScheme.primary
    }
    Surface(color = accent.copy(alpha = .1f), shape = RoundedCornerShape(16.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = accent, strokeWidth = 2.dp)
            else Icon(when (tone) {
                SetupTone.SUCCESS -> Icons.Default.CheckCircle
                SetupTone.ERROR -> Icons.Default.ErrorOutline
                SetupTone.WARNING -> Icons.Default.Info
                SetupTone.INFO -> Icons.Default.Info
            }, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = accent)
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SetupExpander(title: String, subtitle: String, icon: ImageVector, expanded: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (expanded) "Hide options" else "Show options", onClick = onToggle).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (expanded) "Collapse $title" else "Expand $title")
        }
        if (expanded) content()
    }
}

@Composable
private fun SetupPasswordField(value: String, onValueChange: (String) -> Unit, label: String, enabled: Boolean) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value, onValueChange, modifier = Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text(label) }, singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = if (visible) "Hide $label" else "Show $label")
            }
        },
    )
}

@Composable
private fun SetupStatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
