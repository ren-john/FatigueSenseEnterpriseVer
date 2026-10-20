package com.example.fatiguesense.enterprise.enterprise

data class DriverFlag(val kind: String, val qualifiedAt: Long, val evidence: List<String>)

/** Event-time evaluation; never groups separate driver sessions or counts revisions twice. */
object FlagRules {
    fun evaluate(events: List<EnterpriseEvent>): List<DriverFlag> = events
        .groupBy { listOf(it.orgId, it.uid, it.sessionId) }.values.flatMap { group ->
            val unique = group.groupBy { it.eventId }.values.map { rows -> rows.maxBy { it.revision } }
            val flags = mutableListOf<DriverFlag>()
            listOf(Triple("EAR", 3, 60000L), Triple("MAR", 6, 600000L)).forEach { (type, count, window) ->
                val points = unique.filter { it.triggerType == type }.sortedWith(compareBy({ it.timestamp }, { it.eventId }))
                var left = 0
                var breachUntil = Long.MIN_VALUE
                for (right in points.indices) {
                    val now = points[right].timestamp
                    while (now - points[left].timestamp > window) left++
                    if (right - left + 1 >= count) {
                        if (now > breachUntil) flags += DriverFlag(if (type == "EAR") "Drowsy" else "Yawning constantly",
                            now, points.subList(left, right + 1).map { it.eventId })
                        breachUntil = points[right - count + 1].timestamp + window
                    }
                }
            }
            unique.filter { it.triggerType == "NO_FACE" && (it.durationMs ?: 0) >= 180000 }
                .forEach { flags += DriverFlag("No face detected", it.timestamp + 180000, listOf(it.eventId)) }
            flags
        }.sortedByDescending { it.qualifiedAt }
}
