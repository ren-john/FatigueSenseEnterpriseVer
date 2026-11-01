package com.example.fatiguesense.enterprise.utils

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.fatiguesense.enterprise.ml.FatigueEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportResult {
    SUCCESS,
    NO_EVENTS,
    ALREADY_IN_PROGRESS,
    ERROR,
}

object LogExportUtils {

    @Volatile
    private var isExporting: Boolean = false

    /**
     * Exports fatigue events list as CSV and launches system share chooser.
     * Prevents multiple concurrent or spam-click invocations.
     */
    suspend fun exportCsv(context: Context, events: List<FatigueEvent>): ExportResult {
        if (isExporting) return ExportResult.ALREADY_IN_PROGRESS
        if (events.isEmpty()) return ExportResult.NO_EVENTS

        isExporting = true
        return try {
            val csv = buildString {
                appendLine("timestamp,datetime,duration_ms,ear_value,severity,trigger_type")
                events.forEach { e ->
                    appendLine("${e.timestampMs},\"${e.dateTimeLabel}\",${e.durationMs},${e.earValue},${e.severity.name},${e.triggerType.name}")
                }
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = withContext(Dispatchers.IO) {
                File(context.cacheDir, "fatiguesense_log_$timestamp.csv").apply { writeText(csv) }
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file,
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("FatigueSense Export", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Export FatigueSense Log"))
            ExportResult.SUCCESS
        } catch (e: Exception) {
            ExportResult.ERROR
        } finally {
            delay(1000L)
            isExporting = false
        }
    }
}
