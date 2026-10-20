package com.example.fatiguesense.enterprise.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.fatiguesense.enterprise.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin


// ─── Data Models ──────────────────────────────────────────────────────────────

data class BottomBarItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val iconSelected: ImageVector = icon,
)

enum class LogSeverity { FATIGUE_ALERT, WARNING, RECOVERY }

fun LogSeverity.displayColor(): Color = when (this) {
    LogSeverity.FATIGUE_ALERT -> FatigueError
    LogSeverity.WARNING       -> FatigueWarning
    LogSeverity.RECOVERY      -> FatigueSuccess
}

fun LogSeverity.displayLabel(): String = when (this) {
    LogSeverity.FATIGUE_ALERT -> "FATIGUE ALERT"
    LogSeverity.WARNING       -> "WARNING"
    LogSeverity.RECOVERY      -> "RECOVERY DETECTED"
}

// ─── AppTopBar ────────────────────────────────────────────────────────────────

@Composable
fun AppTopBar(
    ipAddress: String = "192.168.4.1",
    isConnected: Boolean = true,
    onSettingsClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(FatigueBackground)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.RemoveRedEye,
            contentDescription = null,
            tint = FatiguePrimary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = "FATIGUESENSE",
            style = MaterialTheme.typography.titleSmall.copy(
                color = FatiguePrimary,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp,
            ),
        )
        // Vertical pipe divider
        Text(
            text = "  |  ",
            style = MaterialTheme.typography.bodySmall.copy(
                color = FatigueOnSurfaceMuted.copy(alpha = 0.4f),
            ),
        )
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(if (isConnected) FatigueSuccess else FatigueError, CircleShape),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = ipAddress,
            style = MaterialTheme.typography.bodySmall.copy(
                color = FatigueOnSurfaceMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            ),
        )
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = onSettingsClick,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = "Settings",
                tint = FatigueOnSurfaceMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ─── AppBottomBar ─────────────────────────────────────────────────────────────

@Composable
fun AppBottomBar(
    items: List<BottomBarItem>,
    selectedRoute: String,
    onSelect: (BottomBarItem) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(FatigueSurface),
    ) {
        // Orange top-line indicator per-tab
        Row(modifier = Modifier.fillMaxWidth()) {
            items.forEach { item ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(
                            if (selectedRoute == item.route) FatiguePrimary
                            else Color.Transparent
                        ),
                )
            }
        }
        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
        ) {
            items.forEach { item ->
                val selected = selectedRoute == item.route
                NavigationBarItem(
                    selected = selected,
                    onClick = { onSelect(item) },
                    icon = {
                        Icon(
                            imageVector = if (selected) item.iconSelected else item.icon,
                            contentDescription = item.label,
                            tint = if (selected) FatiguePrimary else FatigueOnSurfaceMuted,
                            modifier = Modifier.size(22.dp),
                        )
                    },
                    label = {
                        Text(
                            text = item.label.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                                letterSpacing = 0.5.sp,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedTextColor = FatiguePrimary,
                        unselectedTextColor = FatigueOnSurfaceMuted,
                        indicatorColor = Color.Transparent,
                        selectedIconColor = FatiguePrimary,
                        unselectedIconColor = FatigueOnSurfaceMuted,
                    ),
                )
            }
        }
    }
}

// ─── LiveFeedCard (Dashboard) ─────────────────────────────────────────────────

@Composable
fun LiveFeedCard(
    earValue: Float = 0.18f,
    earThreshold: Float = 0.28f,
    fps: Float = 12.0f,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161618)),
        shape = RoundedCornerShape(6.dp),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column {
            // ── Camera preview area ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(158.dp)
                    .background(Color(0xFF0C0C0D)),
            ) {
                // LIVE badge (top-left)
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .background(Color(0x66000000), RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(FatigueError, CircleShape),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "LIVE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            letterSpacing = 1.sp,
                        ),
                    )
                }
                // FPS counter (top-right)
                Text(
                    text = "FPS: ${"%.1f".format(fps)}",
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .background(Color(0x66000000), RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                    ),
                )
                // Camera icon placeholder
                Icon(
                    imageVector = Icons.Filled.Videocam,
                    contentDescription = null,
                    tint = FatigueOnSurfaceMuted.copy(alpha = 0.12f),
                    modifier = Modifier
                        .size(52.dp)
                        .align(Alignment.Center),
                )
            }

            // ── EAR value + progress bar ──
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "EYE ASPECT RATIO (EAR)",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = FatigueOnSurfaceMuted,
                            letterSpacing = 0.5.sp,
                            fontSize = 10.sp,
                        ),
                    )
                    Text(
                        text = "%.2f".format(earValue),
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = FatiguePrimary,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
                Spacer(Modifier.height(8.dp))
                EarProgressBar(
                    value = earValue,
                    threshold = earThreshold,
                )
            }
        }
    }
}

// ─── EarProgressBar ───────────────────────────────────────────────────────────

@Composable
fun EarProgressBar(
    value: Float,
    threshold: Float,
    minValue: Float = 0f,
    maxValue: Float = 0.5f,
    modifier: Modifier = Modifier,
) {
    val progress = ((value - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
    val thresholdPos = ((threshold - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
        ) {
            val trackH = 6.dp.toPx()
            val ty = (size.height - trackH) / 2f

            // Background
            drawRoundRect(
                color = Color(0xFF2E2E32),
                topLeft = Offset(0f, ty),
                size = Size(size.width, trackH),
                cornerRadius = CornerRadius(trackH / 2),
            )
            // Fill
            drawRoundRect(
                color = FatiguePrimary,
                topLeft = Offset(0f, ty),
                size = Size(size.width * progress, trackH),
                cornerRadius = CornerRadius(trackH / 2),
            )
            // Threshold marker
            val mx = size.width * thresholdPos
            drawLine(
                color = Color.White.copy(alpha = 0.9f),
                start = Offset(mx, 0f),
                end = Offset(mx, size.height),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        Spacer(Modifier.height(5.dp))
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "%.1f".format(minValue),
                modifier = Modifier.align(Alignment.CenterStart),
                style = MaterialTheme.typography.labelSmall.copy(
                    color = FatigueOnSurfaceMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                ),
            )
            Text(
                text = "THRESHOLD: %.2f".format(threshold),
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.labelSmall.copy(
                    color = FatigueOnSurfaceMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                ),
            )
            Text(
                text = "%.1f".format(maxValue),
                modifier = Modifier.align(Alignment.CenterEnd),
                style = MaterialTheme.typography.labelSmall.copy(
                    color = FatigueOnSurfaceMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                ),
            )
        }
    }
}

// ─── StatCard ─────────────────────────────────────────────────────────────────

@Composable
fun StatCard(
    label: String,
    value: String,
    supporting: String,
    valueColor: Color = FatigueOnSurface,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = FatigueSurfaceAlt),
        shape = RoundedCornerShape(6.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = FatigueOnSurfaceMuted,
                    letterSpacing = 0.8.sp,
                    fontSize = 9.sp,
                ),
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge.copy(
                    color = valueColor,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = supporting,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = FatigueOnSurfaceMuted,
                    fontSize = 9.sp,
                ),
            )
        }
    }
}

// ─── InfoRow ──────────────────────────────────────────────────────────────────

@Composable
fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = FatigueOnSurface,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 13.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = FatigueOnSurfaceMuted,
                    letterSpacing = 0.3.sp,
                    fontSize = 12.sp,
                ),
            )
            if (trailingContent != null) {
                trailingContent()
            } else {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = valueColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    ),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(FatigueSurfaceAlt),
        )
    }
}

// ─── Buttons ──────────────────────────────────────────────────────────────────

@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = FatiguePrimary),
        contentPadding = PaddingValues(vertical = 14.dp),
        shape = RoundedCornerShape(4.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = FatigueBackground,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(
                color = FatigueBackground,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            ),
        )
    }
}

@Composable
fun OutlinedActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = FatiguePrimary),
        border = androidx.compose.foundation.BorderStroke(1.dp, FatiguePrimary),
        contentPadding = PaddingValues(vertical = 14.dp),
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(
                color = FatiguePrimary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            ),
        )
    }
}

// ─── SectionHeader ────────────────────────────────────────────────────────────

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(
                color = FatiguePrimary,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            ),
        )
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(FatigueSurfaceAlt),
        )
    }
}

// ─── LogCard ─────────────────────────────────────────────────────────────────

@Composable
fun LogCard(
    severity: LogSeverity,
    dateTime: String,
    duration: String,
    earValue: Float,
    modifier: Modifier = Modifier,
) {
    val color = severity.displayColor()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        // Left severity border
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(
                    color,
                    RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp),
                ),
        )
        // Card content
        Column(
            modifier = Modifier
                .weight(1f)
                .background(
                    FatigueSurfaceAlt,
                    RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp),
                )
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            // Severity row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .background(color.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = when (severity) {
                            LogSeverity.FATIGUE_ALERT -> Icons.Filled.Circle
                            LogSeverity.WARNING       -> Icons.Filled.Warning
                            LogSeverity.RECOVERY      -> Icons.Filled.CheckCircle
                        },
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(11.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = severity.displayLabel(),
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = color,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                    ),
                )
            }
            Spacer(Modifier.height(5.dp))
            Text(
                text = dateTime,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = FatigueOnSurfaceMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                ),
            )
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "DURATION",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = FatigueOnSurfaceMuted,
                            fontSize = 8.sp,
                            letterSpacing = 0.5.sp,
                        ),
                    )
                    Text(
                        text = duration,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                        ),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "EAR VALUE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = FatigueOnSurfaceMuted,
                            fontSize = 8.sp,
                            letterSpacing = 0.5.sp,
                        ),
                    )
                    Text(
                        text = "%.2f".format(earValue),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                        ),
                    )
                }
            }
        }
    }
}

// ─── DayCycleChart ────────────────────────────────────────────────────────────

data class ChartBar(val timeLabel: String, val value: Float, val isHighlight: Boolean = false)

@Composable
fun DayCycleChart(
    bars: List<ChartBar>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(FatigueSurfaceAlt, RoundedCornerShape(6.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "DAY CYCLE TELEMETRY",
            style = MaterialTheme.typography.labelSmall.copy(
                color = FatigueOnSurfaceMuted,
                letterSpacing = 1.sp,
                fontSize = 9.sp,
            ),
        )
        Spacer(Modifier.height(12.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
        ) {
            val count = bars.size
            val spacing = 3.dp.toPx()
            val barW = ((size.width - spacing * (count - 1)) / count).coerceAtLeast(2f)
            val maxBarH = size.height

            bars.forEachIndexed { i, bar ->
                val barH = (maxBarH * bar.value).coerceAtLeast(4.dp.toPx())
                val x = i * (barW + spacing)
                val y = size.height - barH

                drawRoundRect(
                    color = when {
                        bar.isHighlight -> FatiguePrimary
                        bar.value > 0.65f -> Color(0xFFEB3B5A).copy(alpha = 0.65f)
                        else -> Color(0xFF3A3A40)
                    },
                    topLeft = Offset(x, y),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        // Time axis labels – only show 5 spread evenly
        val labelIndices = listOf(0, bars.size / 4, bars.size / 2, 3 * bars.size / 4, bars.size - 1)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            labelIndices.forEach { idx ->
                if (idx < bars.size) {
                    Text(
                        text = bars[idx].timeLabel,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = FatigueOnSurfaceMuted,
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
            }
        }
    }
}

// ─── Settings: Slider ─────────────────────────────────────────────────────────

@Composable
fun SettingsSlider(
    label: String,
    description: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0.18f..0.35f,
    minLabel: String = "0.18 (STRICT)",
    maxLabel: String = "0.35 (RELAXED)",
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = FatigueOnSurface,
                        letterSpacing = 0.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = FatigueOnSurfaceMuted,
                        fontSize = 10.sp,
                    ),
                )
            }
            Text(
                text = "%.2f".format(value),
                style = MaterialTheme.typography.titleMedium.copy(
                    color = FatiguePrimary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
        Spacer(Modifier.height(6.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = FatiguePrimary,
                activeTrackColor = FatiguePrimary,
                inactiveTrackColor = Color(0xFF3A3A3E),
            ),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = minLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = FatigueOnSurfaceMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                ),
            )
            Text(
                text = maxLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = FatigueOnSurfaceMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                ),
            )
        }
    }
}

// ─── Settings: Stepper ────────────────────────────────────────────────────────

@Composable
fun SettingsStepper(
    label: String,
    description: String,
    value: Int,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    color = FatigueOnSurface,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp,
                ),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = FatigueOnSurfaceMuted,
                    fontSize = 10.sp,
                ),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(Color(0xFF2A2A2E), RoundedCornerShape(4.dp))
                    .clickable(onClick = onDecrement),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Remove,
                    contentDescription = "Decrease",
                    tint = FatigueOnSurface,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    color = FatigueOnSurface,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = Modifier.widthIn(min = 20.dp),
                textAlign = TextAlign.Center,
            )
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(Color(0xFF2A2A2E), RoundedCornerShape(4.dp))
                    .clickable(onClick = onIncrement),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Increase",
                    tint = FatigueOnSurface,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

// ─── Settings: Toggle ─────────────────────────────────────────────────────────

@Composable
fun SettingsToggle(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    color = FatigueOnSurface,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp,
                ),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = FatigueOnSurfaceMuted,
                    fontSize = 10.sp,
                ),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = FatigueBackground,
                checkedTrackColor = FatiguePrimary,
                uncheckedThumbColor = FatigueOnSurfaceMuted,
                uncheckedTrackColor = Color(0xFF3A3A3E),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

// ─── Settings: DeviceInfoCard ─────────────────────────────────────────────────

@Composable
fun DeviceInfoCard(
    label: String,
    value: String,
    badge: String? = null,
    badgeColor: Color = FatigueSuccess,
    trailingIcon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FatigueSurfaceAlt),
        shape = RoundedCornerShape(6.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = FatigueOnSurfaceMuted,
                        letterSpacing = 0.5.sp,
                        fontSize = 9.sp,
                    ),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
            if (badge != null) {
                Box(
                    modifier = Modifier
                        .background(badgeColor.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
                        .border(1.dp, badgeColor.copy(alpha = 0.3f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = badgeColor,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            fontSize = 9.sp,
                        ),
                    )
                }
            }
            if (trailingIcon != null) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = trailingIcon,
                    contentDescription = null,
                    tint = FatigueOnSurfaceMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// ─── Settings: DataActionRow ──────────────────────────────────────────────────

@Composable
fun DataActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    titleColor: Color = FatigueOnSurface,
    iconBgColor: Color = FatigueOnSurface,
    trailingIcon: ImageVector = Icons.Filled.ChevronRight,
    trailingIconColor: Color = FatigueOnSurfaceMuted,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = FatigueSurfaceAlt),
        shape = RoundedCornerShape(6.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(iconBgColor.copy(alpha = 0.12f), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconBgColor,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = titleColor,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                    ),
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = FatigueOnSurfaceMuted,
                        fontSize = 10.sp,
                    ),
                )
            }
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = trailingIconColor,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

// ─── Settings: CameraDropdown ─────────────────────────────────────────────────

@Composable
fun CameraDropdown(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                color = FatigueOnSurfaceMuted,
                fontSize = 9.sp,
                letterSpacing = 0.5.sp,
            ),
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(FatigueBackground, RoundedCornerShape(4.dp))
                .border(1.dp, Color(0xFF3A3A3E), RoundedCornerShape(4.dp))
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selected,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = FatigueOnSurfaceMuted,
                    modifier = Modifier.size(14.dp),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, fontFamily = FontFamily.Monospace) },
                        onClick = { onSelect(option); expanded = false },
                    )
                }
            }
        }
    }
}

// ─── Face Step Pill ───────────────────────────────────────────────────────────

@Composable
fun FaceStepPill(
    number: String,
    label: String,
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val bg = if (isActive) FatiguePrimary.copy(alpha = 0.2f) else FatigueSurfaceAlt
    val border = if (isActive) FatiguePrimary else Color.Transparent
    val numColor = if (isActive) FatiguePrimary else FatigueOnSurfaceMuted
    val labelColor = if (isActive) FatigueOnSurface else FatigueOnSurfaceMuted

    Box(
        modifier = modifier
            .background(bg, RoundedCornerShape(4.dp))
            .border(1.dp, border, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "[$number]",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = numColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                ),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = labelColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 8.sp,
                    letterSpacing = 0.3.sp,
                ),
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ─── StatusPill ───────────────────────────────────────────────────────────────

@Composable
fun StatusPill(
    text: String,
    color: Color = FatigueSuccess,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                color = color,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                fontSize = 10.sp,
            ),
        )
    }
}

// ─── Hexagon (Splash) ─────────────────────────────────────────────────────────

@Composable
fun HexagonLogo(size: Dp = 96.dp, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val cx = this.size.width / 2
            val cy = this.size.height / 2
            val r = this.size.width / 2f * 0.88f
            val path = Path().apply {
                for (i in 0 until 6) {
                    val angle = (Math.PI / 3.0 * i - Math.PI / 6.0)
                    val px = (cx + r * cos(angle)).toFloat()
                    val py = (cy + r * sin(angle)).toFloat()
                    if (i == 0) moveTo(px, py) else lineTo(px, py)
                }
                close()
            }
            drawPath(path, FatiguePrimary)
        }
        Icon(
            imageVector = Icons.Filled.RemoveRedEye,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.38f),
        )
    }
}

// ─── ScreenSurface ────────────────────────────────────────────────────────────

@Composable
fun ScreenSurface(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = FatigueBackground,
    ) {
        content()
    }
}

// ─── StreamLostBanner ─────────────────────────────────────────────────────────

/**
 * Pulsing red banner shown in DashboardScreen when the MJPEG stream is lost
 * while monitoring is active. Provides a clear visual indication without audio.
 */
@Composable
fun StreamLostBanner(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "banner_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.75f,
        targetValue  = 1.00f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "banner_alpha",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(FatigueError.copy(alpha = alpha), RoundedCornerShape(6.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.SignalWifiStatusbarConnectedNoInternet4,
            contentDescription = null,
            tint     = Color.White,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text  = "STREAM CONNECTION LOST",
                style = MaterialTheme.typography.labelMedium.copy(
                    color        = Color.White,
                    fontWeight   = FontWeight.Bold,
                    letterSpacing = 1.sp,
                ),
            )
            Text(
                text  = "Attempting to reconnect to ESP32-CAM…",
                style = MaterialTheme.typography.labelSmall.copy(
                    color    = Color.White.copy(alpha = 0.80f),
                    fontSize = 10.sp,
                ),
            )
        }
    }
}