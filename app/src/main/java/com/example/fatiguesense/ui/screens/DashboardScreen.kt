package com.example.fatiguesense.enterprise.ui.screens

import com.example.fatiguesense.enterprise.ml.ModelProfile

import android.content.Intent
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.fatiguesense.enterprise.ml.CalibrationState
import com.example.fatiguesense.enterprise.ml.FatigueManager
import com.example.fatiguesense.enterprise.ml.StreamStatus
import com.example.fatiguesense.enterprise.services.FatigueService
import com.example.fatiguesense.enterprise.ui.components.*
import com.example.fatiguesense.enterprise.ui.theme.*

@Composable
fun DashboardScreen() {
    val context           = LocalContext.current
    val frame             by FatigueManager.frames.collectAsState()
    val analysis          by FatigueManager.analysisResult.collectAsState()
    val streamStatus      by FatigueManager.streamStatus.collectAsState()
    val streamFps         by FatigueManager.streamFps.collectAsState()
    val isMonitoring      by FatigueManager.isMonitoring.collectAsState()
    val isAdaptiveMode    by FatigueManager.isAdaptiveModeEnabled.collectAsState()
    val warmupProgress    by FatigueManager.warmupProgress.collectAsState()

    val calibrationState  = analysis.calibrationState
    val isWarmingUp       = isAdaptiveMode && calibrationState == CalibrationState.WARMING_UP

    // Session alert count is owned by FatigueManager — survives navigation,
    // resets only when STOP MONITORING is pressed (via FatigueManager.stop()).
    val alertCount by FatigueManager.sessionAlertCount.collectAsState()
    val yawnCount by FatigueManager.sessionYawnCount.collectAsState()

    val grayscaleMatrix = remember { ColorMatrix().apply { setToSaturation(0f) } }

    ScreenSurface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            val enterpriseMessage by FatigueService.message.collectAsState()
            if (enterpriseMessage.isNotBlank()) Text(enterpriseMessage, modifier = Modifier.padding(bottom = 12.dp))

            // ── Stream-loss visual alert banner ───────────────────────────────
            AnimatedVisibility(
                visible = isMonitoring && streamStatus == StreamStatus.LOST,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit  = slideOutVertically(targetOffsetY  = { -it }) + fadeOut(),
            ) {
                StreamLostBanner(modifier = Modifier.padding(bottom = 10.dp))
            }

            // ── Live camera feed ──────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .border(
                        width = if (analysis.isFatigueAlert) 2.dp else 1.dp,
                        color = when {
                            analysis.isFatigueAlert             -> FatigueError
                            streamStatus == StreamStatus.STREAMING -> FatiguePrimary.copy(alpha = 0.5f)
                            else                                -> FatigueSurfaceAlt
                        },
                        shape = RoundedCornerShape(6.dp),
                    )
                    .background(Color(0xFF0C0C0D), RoundedCornerShape(6.dp)),
            ) {
                if (frame != null && isMonitoring) {
                    // ── Grayscale video + overlay canvas ─────────────────────
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(6.dp)),
                    ) {
                        val cW = size.width
                        val cH = size.height
                        val img = frame!!.asImageBitmap()
                        val imgRatio = img.width.toFloat() / img.height.toFloat()
                        val canRatio = cW / cH

                        // Letterbox/pillarbox to fit aspect ratio
                        var dW = cW;  var dH = cH
                        var oX = 0f;  var oY = 0f
                        if (imgRatio > canRatio) {
                            dH = cW / imgRatio; oY = (cH - dH) / 2f
                        } else {
                            dW = cH * imgRatio; oX = (cW - dW) / 2f
                        }

                        drawImage(
                            image     = img,
                            dstOffset = androidx.compose.ui.unit.IntOffset(oX.toInt(), oY.toInt()),
                            dstSize   = androidx.compose.ui.unit.IntSize(dW.toInt(), dH.toInt()),
                            colorFilter = ColorFilter.colorMatrix(grayscaleMatrix),
                        )

                        val scaleX = dW / img.width
                        val scaleY = dH / img.height

                        // Bounding box
                        analysis.boundingBox?.let { box ->
                            val bL = oX + box.left  * scaleX
                            val bT = oY + box.top   * scaleY
                            val bR = oX + box.right * scaleX
                            val bB = oY + box.bottom * scaleY
                            drawRect(
                                color    = if (analysis.isFatigueAlert) FatigueError else FatiguePrimary,
                                topLeft  = Offset(bL, bT),
                                size     = Size(bR - bL, bB - bT),
                                style    = Stroke(width = 3.dp.toPx()),
                            )
                        }

                        // Landmark dots — distinct colours per region
                        analysis.landmarks.forEachIndexed { idx, pt ->
                            val px = oX + pt.x * scaleX
                            val py = oY + pt.y * scaleY
                            val dotColor = when {
                                idx < (if (ModelProfile.legacy) 4 else 6) -> Color(0xFF00E5FF)  // right eye → cyan
                                idx < (if (ModelProfile.legacy) 8 else 12) -> Color(0xFF69FF47)  // left eye → green
                                else    -> Color(0xFFFFD600)  // mouth     → yellow
                            }
                            drawCircle(color = dotColor, radius = 3.5.dp.toPx(), center = Offset(px, py))
                        }

                        // Red overlay tint when fatigue detected (not during warmup)
                        if (analysis.isFatigueAlert && !isWarmingUp) {
                            drawRect(color = FatigueError.copy(alpha = 0.18f), size = size)
                        }
                    }
                } else {
                    // Placeholder when not streaming
                    Icon(
                        imageVector  = Icons.Filled.Videocam,
                        contentDescription = null,
                        tint         = FatigueOnSurfaceMuted.copy(alpha = 0.12f),
                        modifier     = Modifier.size(52.dp).align(Alignment.Center),
                    )
                }

                // ── LIVE / CONNECTING / NO SIGNAL badge (top-left) ───────────
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color(0x99000000), RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(
                                color = when (streamStatus) {
                                    StreamStatus.STREAMING  -> FatigueError
                                    StreamStatus.CONNECTING -> FatigueWarning
                                    StreamStatus.LOST       -> FatigueOnSurfaceMuted
                                },
                                shape = CircleShape,
                            ),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = when (streamStatus) {
                            StreamStatus.STREAMING  -> "LIVE"
                            StreamStatus.CONNECTING -> "CONNECTING"
                            StreamStatus.LOST       -> "NO SIGNAL"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(
                            color        = Color.White,
                            fontFamily   = FontFamily.Monospace,
                            fontSize     = 9.sp,
                            letterSpacing = 1.sp,
                        ),
                    )
                }

                // ── FPS / EAR / MAR overlay (top-right) ──────────────────────
                if (streamFps > 0 || analysis.fps > 0 || analysis.earValid) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(Color(0x99000000), RoundedCornerShape(3.dp))
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text  = if (streamFps > 0) "FPS: $streamFps" else "FPS: ${analysis.fps}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 9.sp,
                            ),
                        )
                        Text(
                            text  = "EAR: ${if (analysis.earValid) "%.2f".format(analysis.ear) else "--"}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (analysis.isFatigueAlert) FatigueError else Color.White,
                                fontFamily = FontFamily.Monospace, fontSize = 9.sp,
                            ),
                        )
                        Text(
                            text  = "MAR: ${if (analysis.marValid) "%.2f".format(analysis.mar) else "--"}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (analysis.isYawnAlert) FatigueWarning else Color.White,
                                fontFamily = FontFamily.Monospace, fontSize = 9.sp,
                            ),
                        )
                    }
                }

                // ── Alert label (bottom-center) ───────────────────────────────
                when {
                    // During warmup: show calibration progress instead of fatigue alerts
                    isWarmingUp && isMonitoring -> Text(
                        text     = "CALIBRATING BASELINE  ${(warmupProgress * 100).toInt()}%",
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                            .background(FatiguePrimary.copy(alpha = 0.88f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium.copy(
                            color         = Color.White,
                            fontWeight    = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            fontFamily    = FontFamily.Monospace,
                        ),
                    )
                    analysis.isFatigueAlert -> Text(
                        text     = "⚠  DROWSINESS DETECTED",
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                            .background(FatigueError.copy(alpha = 0.92f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium.copy(
                            color        = Color.White,
                            fontWeight   = FontWeight.Bold,
                            letterSpacing = 1.sp,
                        ),
                    )
                    analysis.isYawnAlert -> Text(
                        text     = "⚠  YAWN DETECTED",
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                            .background(FatigueWarning.copy(alpha = 0.92f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium.copy(
                            color        = Color.White,
                            fontWeight   = FontWeight.Bold,
                            letterSpacing = 1.sp,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── EAR Progress bar ──────────────────────────────────────────────
            EarProgressBar(
                value     = analysis.ear,
                threshold = FatigueManager.earThreshold,
            )

            Spacer(Modifier.height(16.dp))

            // ── Stat cards ────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatCard(
                    label   = "STATUS",
                    value   = when {
                        !isMonitoring                       -> "IDLE"
                        isWarmingUp                         -> "CALIBR."
                        analysis.isFatigueAlert             -> "ALERT!"
                        analysis.isYawnAlert                -> "YAWN"
                        streamStatus == StreamStatus.LOST   -> "NO SIG"
                        analysis.earValid                   -> "NORMAL"
                        else                                -> "WAIT"
                    },
                    supporting = when {
                        isWarmingUp -> "${(warmupProgress * 100).toInt()}% BASELINE"
                        isMonitoring -> "MONITORING ACTIVE"
                        else -> "PRESS START"
                    },
                    valueColor = when {
                        !isMonitoring                       -> FatigueOnSurfaceMuted
                        isWarmingUp                         -> FatiguePrimary
                        analysis.isFatigueAlert             -> FatigueError
                        analysis.isYawnAlert                -> FatigueWarning
                        streamStatus == StreamStatus.LOST   -> FatigueWarning
                        analysis.earValid                   -> FatigueSuccess
                        else                                -> FatigueOnSurfaceMuted
                    },
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    label      = "EAR",
                    value      = if (analysis.earValid) "%.2f".format(analysis.ear) else "--",
                    supporting = analysis.eyeSelection.label,
                    valueColor = if (analysis.isFatigueAlert) FatigueError else FatiguePrimary,
                    modifier   = Modifier.weight(1f),
                )
                StatCard(
                    label      = "ALERTS",
                    value      = "%02d".format(alertCount),
                    supporting = "EAR / MAR: $alertCount / $yawnCount",
                    valueColor = if (alertCount > 0) FatigueError else FatigueOnSurface,
                    modifier   = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(6.dp))

            // ── Info rows ─────────────────────────────────────────────────────
            InfoRow(label = "FACE", value = when {
                !isMonitoring -> "--"
                analysis.qualityStatus == com.example.fatiguesense.enterprise.ml.FrameQuality.INFERENCE_ERROR -> "Landmark inference error"
                analysis.boundingBox == null -> "No face detected"
                !analysis.earValid -> "Landmarks found; eyes unavailable"
                else -> "Detected (yaw %.0f°)".format(analysis.headYaw)
            })
            InfoRow(label = "MAR (MOUTH OPEN RATIO)", value = if (analysis.marValid) "%.2f".format(analysis.mar) else "--")
            InfoRow(label = "YAWNS (60 S WINDOW)", value = analysis.yawnsInWindow.toString())
            InfoRow(
                label = "YAWN FATIGUE",
                value = if (analysis.isYawnFatigueTriggered) "TRIGGERED" else "TIER 1",
            )
            InfoRow(
                label = "PERCLOS (60 s WINDOW)",
                value = if (analysis.perclos > 0f) "${"%,.1f".format(analysis.perclos * 100)} %" else "--",
            )
            InfoRow(label = "STREAM STATUS",           value = streamStatus.name)
            InfoRow(
                label = "FRAME RATE",
                value = if (streamFps > 0) "$streamFps FPS" else if (analysis.fps > 0) "${analysis.fps} FPS" else "--",
            )

            Spacer(Modifier.height(20.dp))

            // ── Start / Stop button ───────────────────────────────────────────
            if (isMonitoring) {
                OutlinedActionButton(
                    text    = "■  STOP MONITORING",
                    onClick = {
                        context.stopService(Intent(context, FatigueService::class.java))
                    },
                )
            } else {
                ActionButton(
                    text    = "START MONITORING",
                    onClick = {
                        val intent = Intent(context, FatigueService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                    },
                    icon    = Icons.Filled.PlayArrow,
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
