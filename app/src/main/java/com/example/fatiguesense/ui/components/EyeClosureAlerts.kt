package com.example.fatiguesense.enterprise.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.fatiguesense.enterprise.ml.EyeClosureAlertSettings
import com.example.fatiguesense.enterprise.ml.EyeClosurePopup

@Composable
fun EyeClosureAlertSettingsEditor(settings: EyeClosureAlertSettings, onSave: (EyeClosureAlertSettings) -> Unit) {
    var count by rememberSaveable(settings.closureCount) { mutableStateOf(settings.closureCount.toString()) }
    var seconds by rememberSaveable(settings.windowSeconds) { mutableStateOf(settings.windowSeconds.toString()) }
    val countValue = count.toIntOrNull()
    val secondsValue = seconds.toIntOrNull()
    val countValid = countValue != null && countValue in 1..100
    val secondsValid = secondsValue != null && secondsValue in 1..3600
    val changed = countValue != settings.closureCount || secondsValue != settings.windowSeconds
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Show a popup after repeated eye closures within a rolling time window.", style = MaterialTheme.typography.bodyMedium)
        Text("Each closure must meet the EAR threshold and consecutive-frame sensitivity above. Eyes must reopen before another closure counts.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = count, onValueChange = { count = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Eye closures to trigger popup") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = !countValid, supportingText = { Text("1–100 closures") },
        )
        OutlinedTextField(
            value = seconds, onValueChange = { seconds = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Time window (seconds)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = !secondsValid, supportingText = { Text("1–3600 seconds") },
        )
        Button(enabled = countValid && secondsValid && changed, onClick = {
            onSave(EyeClosureAlertSettings(checkNotNull(countValue), checkNotNull(secondsValue)))
        }, modifier = Modifier.fillMaxWidth()) { Text("Save eye-closure alert") }
        Text("Current: ${settings.closureCount} closures in ${settings.windowSeconds} seconds. Saving starts a new counting window.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun EyeClosureAlertDialog(alert: EyeClosurePopup, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Repeated eye closures") },
        text = { Text("${alert.closureCount} eye closures detected within ${alert.windowSeconds} seconds.") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Acknowledge") } },
    )
}
