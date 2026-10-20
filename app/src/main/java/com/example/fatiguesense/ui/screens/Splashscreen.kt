package com.example.fatiguesense.enterprise.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.fatiguesense.enterprise.ui.components.*
import com.example.fatiguesense.enterprise.ui.theme.*

// Fake terminal text that scrolls as a visual backdrop
private val TERMINAL_TEXT = """
FS_BOOT_v1.0.2-STABLE
[ESP32] WROVER-E INIT...
WIFI.LINK.SEARCH [OK]
CAM_INIT: 640x480@15fps [OK]
EAR_MODEL_LOAD: baseline.json [OK]
FACE_DB_INIT: enrolled [OK]
SOCKET: connecting 192.168.4.1:81...
[STREAM] mjpeg handler ready
[DETECT] ear_threshold=0.22
[DETECT] alert_frames=3
[TILT] pitch_roll monitor: ON
[SYS] uptime=0s mem=312KB
FS-ESP32-9921 READY
""".trimIndent()

@Composable
fun SplashScreen(onConnect: () -> Unit = {}) {
    var ipAddress by remember { mutableStateOf("192.168.4.1") }
    var isPinging by remember { mutableStateOf(true) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FatigueBackground),
    ) {
        // ── Terminal text background ──────────────────────────────────────────
        Text(
            text = (TERMINAL_TEXT + "\n").repeat(6),
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                color = FatigueOnSurfaceMuted.copy(alpha = 0.06f),
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                lineHeight = 13.sp,
            ),
        )

        // ── Main content ──────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Hexagon logo
            HexagonLogo(size = 88.dp)

            Spacer(Modifier.height(20.dp))

            // App name
            Text(
                text = "FATIGUESENSE",
                style = MaterialTheme.typography.headlineMedium.copy(
                    color = FatigueOnSurface,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 3.sp,
                ),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "STAY ALERT. STAY ALIVE.",
                style = MaterialTheme.typography.labelMedium.copy(
                    color = FatigueOnSurfaceMuted,
                    letterSpacing = 2.sp,
                ),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(36.dp))

            // ── Status card ───────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(FatigueSurfaceAlt, RoundedCornerShape(6.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Pulsing dot
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(
                            if (isPinging) FatigueWarning else FatigueSuccess,
                            androidx.compose.foundation.shape.CircleShape,
                        ),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = "@LOADING",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = FatigueOnSurfaceMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.sp,
                            letterSpacing = 0.5.sp,
                        ),
                    )
                    Text(
                        text = if (isPinging) "Pinging $ipAddress..." else "Link stable",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = FatigueOnSurface,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── Connect Button ────────────────────────────────────────────────
            ActionButton(
                text = "CONNECT TO DEVICE",
                onClick = onConnect,
            )

            Spacer(Modifier.height(20.dp))

            // ── IP Address field ─────────────────────────────────────────────
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "DEVICE IP ADDRESS",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = FatigueOnSurfaceMuted,
                        fontSize = 9.sp,
                        letterSpacing = 0.8.sp,
                    ),
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(FatigueSurfaceAlt, RoundedCornerShape(4.dp))
                        .border(1.dp, Color(0xFF3A3A3E), RoundedCornerShape(4.dp))
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = ipAddress,
                        onValueChange = { ipAddress = it },
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            color = FatigueOnSurface,
                            fontFamily = FontFamily.Monospace,
                        ),
                        singleLine = true,
                    )
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Edit IP",
                        tint = FatigueOnSurfaceMuted,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }

        // ── Footer ────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "■ ESP32-CAM-INIT  ■ WIFI.LINK.SEARCH",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = FatigueOnSurfaceMuted.copy(alpha = 0.35f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 7.sp,
                ),
            )
            Text(
                text = "V1/V2",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = FatigueOnSurfaceMuted.copy(alpha = 0.35f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 7.sp,
                ),
            )
        }
    }
}