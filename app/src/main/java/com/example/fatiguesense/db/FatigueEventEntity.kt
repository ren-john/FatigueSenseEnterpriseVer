package com.example.fatiguesense.enterprise.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.fatiguesense.enterprise.ml.FatigueEventSeverity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persisted representation of a single fatigue event.
 * Mirrors [com.example.fatiguesense.enterprise.ml.FatigueEvent] but is owned by Room.
 */
@Entity(tableName = "fatigue_events")
data class FatigueEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestampMs: Long,
    val durationMs: Long,
    val earValue: Float,
    val severity: String,           // stored as enum name string
    val triggerType: String = "EAR", // EAR, MAR, or YAWN_FATIGUE
) {
    val dateTimeLabel: String get() {
        val fmt = SimpleDateFormat("MMM d, yyyy | HH:mm:ss", Locale.getDefault())
        return fmt.format(Date(timestampMs))
    }

    val durationLabel: String get() =
        if (durationMs <= 0L) "--" else "${"%.1f".format(durationMs / 1000.0)}s"

    fun toSeverityEnum(): FatigueEventSeverity =
        runCatching { FatigueEventSeverity.valueOf(severity) }
            .getOrDefault(FatigueEventSeverity.FATIGUE_ALERT)
}
