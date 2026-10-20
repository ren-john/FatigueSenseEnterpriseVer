package com.example.fatiguesense.enterprise.ui.screens

import com.example.fatiguesense.enterprise.ml.ModelProfile

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.fatiguesense.enterprise.ml.CalibrationState
import com.example.fatiguesense.enterprise.ml.FatigueManager
import com.example.fatiguesense.enterprise.ml.FrameQuality
import com.example.fatiguesense.enterprise.ui.components.*
import com.example.fatiguesense.enterprise.ui.theme.*

/**
 * Face Setup / Calibration screen.
 *
 * In Adaptive Mode (default):
 *   - Shows warmup progress bar while the baseline is being established.
 *   - Displays auto-derived baseline EAR and effective threshold once READY.
 *   - Sliders are shown but disabled (greyed out).
 *   - "RE-CALIBRATE BASELINE" button resets the warmup.
 *
 * In Manual Mode:
 *   - Sliders are fully interactive and feed directly into the threshold.
 *   - Calibration status card is hidden.
 */
@Composable
fun FaceSetupScreen() {
    // ── Mirror FatigueManager thresholds into local state ────────────────────────
    val earThreshold    by FatigueManager.earThresholdFlow.collectAsState()
    val marThreshold    by FatigueManager.marThresholdFlow.collectAsState()
    val alertFrameCount by FatigueManager.alertFrameCountFlow.collectAsState()

    val analysis          by FatigueManager.analysisResult.collectAsState()
    val isMonitoring      by FatigueManager.isMonitoring.collectAsState()
    val isAdaptiveMode    by FatigueManager.isAdaptiveModeEnabled.collectAsState()
    val baselineEar       by FatigueManager.baselineEar.collectAsState()
    val warmupProgress    by FatigueManager.warmupProgress.collectAsState()

    val calibrationState  = analysis.calibrationState
    val qualityStatus     = analysis.qualityStatus

    var showResetDialog by remember { mutableStateOf(false) }

    // Animated progress for the warmup bar
    val animatedProgress by animateFloatAsState(
        targetValue  = warmupProgress,
        animationSpec = tween(durationMillis = 400),
        label        = "warmup_progress",
    )

    ScreenSurface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            // ── Header ────────────────────────────────────────────────────────
            Text(
                text  = "DETECTION CALIBRATION",
                style = MaterialTheme.typography.headlineSmall.copy(
                    color         = FatigueOnSurface,
                    fontWeight    = FontWeight.ExtraBold,
                    letterSpacing = 1.sp,
                ),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text  = if (isAdaptiveMode)
                    "Adaptive mode — baseline derived automatically from your face"
                else
                    "Manual mode — adjust thresholds to match your facial geometry",
                style = MaterialTheme.typography.bodySmall.copy(
                    color    = FatigueOnSurfaceMuted,
                    fontSize = 12.sp,
                ),
            )

            Spacer(Modifier.height(16.dp))

            // ── Live status pill ──────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                when {
                    !isMonitoring              -> StatusPill(
                        text  = "⚠  START MONITORING FROM DASHBOARD FIRST",
                        color = FatigueWarning,
                    )
                    analysis.boundingBox != null -> StatusPill(
                        text  = "✓  FACE DETECTED — LIVE",
                        color = FatigueSuccess,
                    )
                    else                       -> StatusPill(
                        text  = "  SEARCHING FOR FACE…",
                        color = FatigueOnSurfaceMuted,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 00. Adaptive / Manual Mode Switch ────────────────────────────
            SectionHeader(title = "00. CALIBRATION MODE")
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(FatigueSurfaceAlt, RoundedCornerShape(6.dp))
                    .padding(14.dp),
            ) {
                Row(
                    modifier             = Modifier.fillMaxWidth(),
                    verticalAlignment    = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text  = if (isAdaptiveMode) "ADAPTIVE (AUTO)" else "MANUAL",
                            style = MaterialTheme.typography.labelMedium.copy(
                                color      = FatigueOnSurface,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                fontSize   = 11.sp,
                            ),
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text  = if (isAdaptiveMode)
                                "Baseline EAR derived from your first ~150 qualified frames"
                            else
                                "You control the exact EAR / MAR cutoffs via the sliders below",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color    = FatigueOnSurfaceMuted,
                                fontSize = 11.sp,
                            ),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked         = isAdaptiveMode,
                        onCheckedChange = { FatigueManager.setAdaptiveMode(it) },
                        colors          = SwitchDefaults.colors(
                            checkedThumbColor   = FatigueBackground,
                            checkedTrackColor   = FatiguePrimary,
                            uncheckedThumbColor = FatigueOnSurfaceMuted,
                            uncheckedTrackColor = FatigueSurfaceAlt,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 01. Adaptive Calibration Status (shown only in Adaptive mode) ─
            if (isAdaptiveMode) {
                SectionHeader(title = "01. ADAPTIVE CALIBRATION STATUS")
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(FatigueSurfaceAlt, RoundedCornerShape(6.dp))
                        .padding(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {

                        // State chip
                        val (stateLabel, stateColor) = when (calibrationState) {
                            CalibrationState.WARMING_UP -> "WARMING UP" to FatigueWarning
                            CalibrationState.READY      -> "CALIBRATED" to FatigueSuccess
                            CalibrationState.MANUAL     -> "MANUAL OVERRIDE" to FatigueOnSurfaceMuted
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(stateColor.copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    text  = stateLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color      = stateColor,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize   = 10.sp,
                                    ),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            // Quality gate badge (only meaningful when monitoring)
                            if (isMonitoring) {
                                val (qLabel, qColor) = qualityGateDisplay(qualityStatus)
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(qColor.copy(alpha = 0.12f))
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                ) {
                                    Text(
                                        text  = qLabel,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color      = qColor,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize   = 10.sp,
                                        ),
                                    )
                                }
                            }
                        }

                        // Warmup progress bar
                        Column {
                            Row(
                                modifier              = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text  = "WARMUP PROGRESS",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color      = FatigueOnSurfaceMuted,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize   = 10.sp,
                                    ),
                                )
                                Text(
                                    text  = "${(animatedProgress * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color      = FatiguePrimary,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize   = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress      = { animatedProgress },
                                modifier      = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color         = when (calibrationState) {
                                    CalibrationState.READY -> FatigueSuccess
                                    else                   -> FatiguePrimary
                                },
                                trackColor    = FatigueBackground,
                            )
                        }

                        // Baseline readouts (shown once calibration is READY)
                        if (calibrationState == CalibrationState.READY && baselineEar > 0f) {
                            HorizontalDivider(color = FatigueSurfaceAlt.copy(alpha = 0f), thickness = 0.dp)
                            Row(
                                modifier              = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                // Baseline EAR
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text  = "BASELINE EAR",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color      = FatigueOnSurfaceMuted,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize   = 10.sp,
                                        ),
                                    )
                                    Text(
                                        text  = "%.3f".format(baselineEar),
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            color      = FatiguePrimary,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                    )
                                    Text(
                                        text  = "open-eye average",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color    = FatigueOnSurfaceMuted,
                                            fontSize = 10.sp,
                                        ),
                                    )
                                }
                                // Effective threshold
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text  = "EFFECTIVE THRESHOLD",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color      = FatigueOnSurfaceMuted,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize   = 10.sp,
                                        ),
                                    )
                                    Text(
                                        text  = "%.3f".format(analysis.computedEarThreshold),
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            color      = FatigueError,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                    )
                                    Text(
                                        text  = "–30% drop trigger",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color    = FatigueOnSurfaceMuted,
                                            fontSize = 10.sp,
                                        ),
                                    )
                                }
                            }
                        }

                        // Re-calibrate button
                        OutlinedButton(
                            onClick  = { FatigueManager.resetCalibration() },
                            modifier = Modifier.fillMaxWidth(),
                            shape    = RoundedCornerShape(6.dp),
                            colors   = ButtonDefaults.outlinedButtonColors(
                                contentColor = FatiguePrimary,
                            ),
                        ) {
                            Icon(
                                imageVector        = Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier           = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text  = "RE-CALIBRATE BASELINE",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily    = FontFamily.Monospace,
                                    fontWeight    = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    fontSize      = 11.sp,
                                ),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }

            // ── Live metric readout (only when face detected) ─────────────────
            val sectionOffset = if (isAdaptiveMode) 2 else 1
            if (isMonitoring && analysis.earValid) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatCard(
                        label      = "CURRENT EAR",
                        value      = "%.3f".format(analysis.ear),
                        supporting = "BOTH EYES AVG",
                        valueColor = when {
                            analysis.isFatigueAlert -> FatigueError
                            analysis.ear < analysis.computedEarThreshold -> FatigueWarning
                            else -> FatiguePrimary
                        },
                        modifier   = Modifier.weight(1f),
                    )
                    StatCard(
                        label      = "CURRENT MAR",
                        value      = if (analysis.marValid) "%.3f".format(analysis.mar) else "--",
                        supporting = "MOUTH OPEN RATIO",
                        valueColor = when {
                            analysis.isYawnAlert -> FatigueWarning
                            analysis.mar > marThreshold -> FatigueWarning
                            else -> FatiguePrimary
                        },
                        modifier   = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            // ── EAR Threshold slider ──────────────────────────────────────────
            SectionHeader(title = "0${sectionOffset}. EAR THRESHOLD")
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(FatigueSurfaceAlt, RoundedCornerShape(6.dp))
                    .padding(14.dp),
            ) {
                Column {
                    if (isAdaptiveMode) {
                        Text(
                            text  = "CONTROLLED BY ADAPTIVE MODE",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color      = FatigueOnSurfaceMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize   = 10.sp,
                                letterSpacing = 1.sp,
                            ),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text  = if (analysis.computedEarThreshold > 0f)
                                "Active: %.3f (–30%% of baseline %.3f)".format(
                                    analysis.computedEarThreshold, baselineEar)
                            else
                                "Pending warmup…",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color    = FatigueOnSurfaceMuted,
                                fontSize = 11.sp,
                            ),
                        )
                    } else {
                        SettingsSlider(
                            label         = "EAR THRESHOLD",
                            description   = "Eye closure ratio that triggers a fatigue alert",
                            value         = earThreshold,
                            onValueChange = {
                                FatigueManager.earThreshold = it
                            },
                            valueRange    = 0.10f..0.35f,
                            minLabel      = "0.10 (STRICT)",
                            maxLabel      = "0.35 (RELAXED)",
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── MAR Threshold slider ──────────────────────────────────────────
            SectionHeader(title = "0${sectionOffset + 1}. MAR THRESHOLD")
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(FatigueSurfaceAlt, RoundedCornerShape(6.dp))
                    .padding(14.dp),
            ) {
                SettingsSlider(
                    label         = "MAR THRESHOLD",
                    description   = "Mouth open ratio that triggers a yawn alert",
                    value         = marThreshold,
                    onValueChange = {
                        FatigueManager.marThreshold = it
                    },
                    valueRange    = 0.40f..0.90f,
                    minLabel      = "0.40 (STRICT)",
                    maxLabel      = "0.90 (RELAXED)",
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Alert Sensitivity ─────────────────────────────────────────────
            SectionHeader(title = "0${sectionOffset + 2}. ALERT SENSITIVITY")
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(FatigueSurfaceAlt, RoundedCornerShape(6.dp))
                    .padding(14.dp),
            ) {
                SettingsStepper(
                    label         = "CONSECUTIVE FRAMES",
                    description   = "Frames below threshold before alert fires",
                    value         = alertFrameCount,
                    onDecrement   = {
                        if (alertFrameCount > 1) {
                            FatigueManager.alertFrameCount = alertFrameCount - 1
                        }
                    },
                    onIncrement   = {
                        FatigueManager.alertFrameCount = alertFrameCount + 1
                    },
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── Landmark map reference ────────────────────────────────────────
            SectionHeader(title = "0${sectionOffset + 3}. LANDMARK MAP (CONFIRMED)")
            Spacer(Modifier.height(6.dp))
            InfoRow(label = "ACTIVE MODEL", value = ModelProfile.label)
            if (ModelProfile.legacy) {
                InfoRow(label = "POINTS 0–3", value = "LEFT EYE (4 points)")
                InfoRow(label = "POINTS 4–7", value = "RIGHT EYE (4 points)")
                InfoRow(label = "POINTS 8–11", value = "MOUTH (4 points)")
                InfoRow(label = "MODEL INPUT", value = "128 × 128 RGB float32 (NHWC)")
                InfoRow(label = "MODEL OUTPUT", value = "24 values (12 pts × x,y)")
            } else {
                InfoRow(label = "POINTS 0–5",   value = "RIGHT EYE (6 points)")
                InfoRow(label = "POINTS 6–11",  value = "LEFT EYE (6 points)")
                InfoRow(label = "POINTS 12–23", value = "OUTER LIP (12 points)")
                InfoRow(label = "POINTS 24–31", value = "INNER LIP (8 points)")
                InfoRow(label = "MODEL INPUT",  value = "192 × 192  RGB float32 (NCHW)")
                InfoRow(label = "MODEL OUTPUT", value = "64 values (32 pts × x,y)")
            }

            Spacer(Modifier.height(16.dp))

            // ── Reset to Defaults ─────────────────────────────────────────────
            SectionHeader(title = "0${sectionOffset + 4}. RESET TO DEFAULTS")
            Spacer(Modifier.height(10.dp))
            Button(
                onClick  = { showResetDialog = true },
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(6.dp),
                colors   = ButtonDefaults.buttonColors(
                    containerColor = FatigueError.copy(alpha = 0.15f),
                    contentColor   = FatigueError,
                ),
            ) {
                Icon(
                    imageVector        = Icons.Filled.RestartAlt,
                    contentDescription = null,
                    modifier           = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text  = "RESET ALL CALIBRATION TO DEFAULTS",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily    = FontFamily.Monospace,
                        fontWeight    = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        fontSize      = 11.sp,
                    ),
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }

    // ── Reset confirmation dialog ─────────────────────────────────────────────
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            icon = {
                Icon(
                    imageVector        = Icons.Filled.Warning,
                    contentDescription = null,
                    tint               = FatigueError,
                )
            },
            title = {
                Text(
                    text  = "RESET TO DEFAULTS?",
                    style = MaterialTheme.typography.titleMedium.copy(
                        color      = FatigueOnSurface,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp,
                    ),
                )
            },
            text = {
                Text(
                    text  = "This will reset all thresholds and re-enable adaptive calibration. Your recorded logs will not be affected.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color    = FatigueOnSurfaceMuted,
                        fontSize = 13.sp,
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        FatigueManager.resetToDefaults()
                        showResetDialog = false
                    },
                    shape  = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FatigueError,
                        contentColor   = Color.White,
                    ),
                ) {
                    Text(
                        text  = "RESET",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showResetDialog = false },
                    shape   = RoundedCornerShape(6.dp),
                    colors  = ButtonDefaults.outlinedButtonColors(
                        contentColor = FatigueOnSurfaceMuted,
                    ),
                ) {
                    Text(
                        text  = "CANCEL",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            },
            containerColor = FatigueSurface,
            tonalElevation = 0.dp,
            shape          = RoundedCornerShape(10.dp),
        )
    }
}

/** Returns a display (label, color) pair for the quality gate status. */
@Composable
private fun qualityGateDisplay(quality: FrameQuality): Pair<String, Color> = when (quality) {
    FrameQuality.OK                -> "GATE: PASSED" to FatigueSuccess
    FrameQuality.WARMING_UP_ACCEPTED -> "GATE: ACCEPTED" to FatiguePrimary
    FrameQuality.HEAD_TURNED       -> "CALIBRATION: FACE FORWARD" to FatigueWarning
    FrameQuality.FACE_CLIPPED      -> "CALIBRATION: CENTER FACE" to FatigueWarning
    FrameQuality.NO_FACE           -> "NO FACE DETECTED" to FatigueOnSurfaceMuted
    FrameQuality.UNRELIABLE_EYES   -> "EYES UNAVAILABLE" to FatigueWarning
    FrameQuality.INFERENCE_ERROR  -> "MODEL ERROR" to FatigueError
}
