package com.example.fatiguesense.enterprise.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.fatiguesense.enterprise.ml.FatigueEventSeverity
import com.example.fatiguesense.enterprise.ml.FatigueManager
import com.example.fatiguesense.enterprise.ml.toFatigueEvent
import com.example.fatiguesense.enterprise.db.FatigueDatabase
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.fatiguesense.enterprise.ui.components.*
import com.example.fatiguesense.enterprise.ui.theme.*
import java.util.Calendar

import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogSortOrder(val label: String) {
    NEWEST_FIRST("NEWEST FIRST"),
    OLDEST_FIRST("OLDEST FIRST"),
    LONGEST_DURATION("LONGEST DURATION"),
    LOWEST_EAR("LOWEST EAR"),
}

@Composable
fun LogsScreen() {
    val context            = LocalContext.current
    val scope              = rememberCoroutineScope()
    val snackbarHostState  = remember { SnackbarHostState() }
    val tabs               = listOf("ALL", "TODAY", "THIS WEEK")
    var selectedTab        by remember { mutableIntStateOf(0) }
    var severityFilterOn   by remember { mutableStateOf(false) }
    var sortOrder          by remember { mutableStateOf(LogSortOrder.NEWEST_FIRST) }
    var showSortMenu       by remember { mutableStateOf(false) }

    // ── Live data from FatigueManager ────────────────────────────────────────
    var showLegacy by rememberSaveable { mutableStateOf(false) }
    val localEvents by FatigueManager.logEvents.collectAsState()
    val legacyRows by remember { FatigueDatabase.getInstance(context).fatigueEventDao().observeAll() }
        .collectAsState(initial = emptyList())
    val allEvents = if (showLegacy) legacyRows.map { it.toFatigueEvent() } else localEvents

    // ── Apply tab + severity filter + sort order ─────────────────────────────
    val displayedEvents = remember(allEvents, selectedTab, severityFilterOn, sortOrder) {
        val now = System.currentTimeMillis()

        val tabFiltered = when (selectedTab) {
            1 -> {  // TODAY — same calendar day
                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val startOfDay = cal.timeInMillis
                allEvents.filter { it.timestampMs >= startOfDay }
            }
            2 -> {  // THIS WEEK — last 7 days
                val sevenDaysAgo = now - 7L * 24L * 60L * 60L * 1000L
                allEvents.filter { it.timestampMs >= sevenDaysAgo }
            }
            else -> allEvents  // ALL
        }

        val severityFiltered = if (severityFilterOn) {
            tabFiltered.filter { it.severity == FatigueEventSeverity.FATIGUE_ALERT }
        } else {
            tabFiltered
        }

        when (sortOrder) {
            LogSortOrder.NEWEST_FIRST     -> severityFiltered.sortedByDescending { it.timestampMs }
            LogSortOrder.OLDEST_FIRST     -> severityFiltered.sortedBy { it.timestampMs }
            LogSortOrder.LONGEST_DURATION -> severityFiltered.sortedByDescending { it.durationMs }
            LogSortOrder.LOWEST_EAR       -> severityFiltered.sortedBy { it.earValue }
        }
    }

    // ── Build 24-hour chart bars from displayed events ───────────────────────
    val chartBars = remember(displayedEvents) {
        // Bucket events by hour-of-day, normalise to [0..1]
        val buckets = IntArray(24) { 0 }
        displayedEvents.forEach { event ->
            val cal = Calendar.getInstance()
            cal.timeInMillis = event.timestampMs
            buckets[cal.get(Calendar.HOUR_OF_DAY)]++
        }
        val maxCount = buckets.max().coerceAtLeast(1)
        List(24) { hour ->
            val label = "%02d:00".format(hour)
            val value = buckets[hour].toFloat() / maxCount
            // Highlight the busiest hour
            ChartBar(label, value.coerceAtLeast(if (buckets[hour] > 0) 0.05f else 0f),
                isHighlight = buckets[hour] == buckets.max() && buckets[hour] > 0)
        }
    }

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
                Text(if (showLegacy) "Legacy history · ownership unknown; kept separate from Local mode and uploads."
                    else "Local history · stored only on this phone; never uploaded.", modifier = Modifier.padding(16.dp))
                TextButton(onClick = { showLegacy = !showLegacy }) {
                    Text(if (showLegacy) "Show Local-mode history" else "View preserved legacy history")
                }
                // ── Page Header ──────────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (showLegacy) "LEGACY HISTORY" else "LOCAL DETECTION LOG",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                color = FatigueOnSurface,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.sp,
                            ),
                        )
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .background(FatiguePrimary),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "${displayedEvents.size} EVENTS RECORDED",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = FatigueOnSurfaceMuted,
                                    letterSpacing = 0.5.sp,
                                    fontSize = 10.sp,
                                ),
                            )
                        }
                    }
                    IconButton(onClick = {
                        scope.launch {
                            FatigueManager.init(context)
                            when (com.example.fatiguesense.enterprise.utils.LogExportUtils.exportCsv(context, displayedEvents)) {
                                com.example.fatiguesense.enterprise.utils.ExportResult.NO_EVENTS ->
                                    snackbarHostState.showSnackbar("NO EVENTS TO EXPORT")
                                com.example.fatiguesense.enterprise.utils.ExportResult.ERROR ->
                                    snackbarHostState.showSnackbar("FAILED TO EXPORT LOGS")
                                else -> {}
                            }
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = "Export",
                            tint = FatiguePrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

            // ── Tab Row ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                tabs.forEachIndexed { idx, label ->
                    val isSelected = selectedTab == idx
                    Column(
                        modifier = Modifier
                            .clickable { selectedTab = idx }
                            .padding(horizontal = 12.dp, vertical = 0.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = if (isSelected) FatiguePrimary else FatigueOnSurfaceMuted,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                letterSpacing = 0.5.sp,
                            ),
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                        Box(
                            modifier = Modifier
                                .height(2.dp)
                                .width(32.dp)
                                .background(
                                    if (isSelected) FatiguePrimary else Color.Transparent,
                                    RoundedCornerShape(1.dp),
                                ),
                        )
                    }
                }
            }

            Spacer(Modifier.height(2.dp))

            // ── Filter & Sort chip row ─────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                if (severityFilterOn) FatigueError.copy(alpha = 0.15f) else FatigueSurfaceAlt,
                                RoundedCornerShape(4.dp),
                            )
                            .border(
                                1.dp,
                                if (severityFilterOn) FatigueError.copy(alpha = 0.5f) else Color(0xFF3A3A3E),
                                RoundedCornerShape(4.dp),
                            )
                            .clickable { severityFilterOn = !severityFilterOn }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = "HIGH SEVERITY",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (severityFilterOn) FatigueError else FatigueOnSurface,
                                fontSize = 9.sp,
                                letterSpacing = 0.5.sp,
                            ),
                        )
                    }

                    if (sortOrder != LogSortOrder.NEWEST_FIRST) {
                        Box(
                            modifier = Modifier
                                .background(FatiguePrimary.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                .border(1.dp, FatiguePrimary.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                .clickable { sortOrder = LogSortOrder.NEWEST_FIRST }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                        ) {
                            Text(
                                text = "SORT: ${sortOrder.label}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = FatiguePrimary,
                                    fontSize = 9.sp,
                                    letterSpacing = 0.5.sp,
                                ),
                            )
                        }
                    }
                }

                Box {
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(
                            imageVector = Icons.Filled.FilterList,
                            contentDescription = "Sort Options",
                            tint = if (sortOrder != LogSortOrder.NEWEST_FIRST) FatiguePrimary else FatigueOnSurfaceMuted,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                        modifier = Modifier.background(Color(0xFF1E1E22)),
                    ) {
                        LogSortOrder.values().forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = option.label,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = if (sortOrder == option) FatiguePrimary else FatigueOnSurface,
                                            fontWeight = if (sortOrder == option) FontWeight.Bold else FontWeight.Normal,
                                            letterSpacing = 0.5.sp,
                                        ),
                                    )
                                },
                                onClick = {
                                    sortOrder = option
                                    showSortMenu = false
                                },
                                leadingIcon = if (sortOrder == option) {
                                    {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = FatiguePrimary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                } else null,
                            )
                        }
                    }
                }
            }

            // ── Log Cards ────────────────────────────────────────────────────
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (displayedEvents.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "NO EVENTS RECORDED",
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = FatigueOnSurfaceMuted,
                                letterSpacing = 1.sp,
                            ),
                        )
                    }
                } else {
                    displayedEvents.forEach { event ->
                        LogCard(
                            severity = when (event.severity) {
                                FatigueEventSeverity.FATIGUE_ALERT -> LogSeverity.FATIGUE_ALERT
                                FatigueEventSeverity.WARNING       -> LogSeverity.WARNING
                                FatigueEventSeverity.RECOVERY      -> LogSeverity.RECOVERY
                            },
                            dateTime = event.dateTimeLabel,
                            duration = event.durationLabel,
                            earValue = event.earValue,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Day Cycle Chart ──────────────────────────────────────────────
            DayCycleChart(
                bars = chartBars,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}
}