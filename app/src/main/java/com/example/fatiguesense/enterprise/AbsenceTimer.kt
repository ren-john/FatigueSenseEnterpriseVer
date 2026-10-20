package com.example.fatiguesense.enterprise.enterprise

/** Monotonic continuity: no frames or failed inference cannot prove face absence. */
class AbsenceTimer(private val thresholdMs: Long = 180000, private val maximumGapMs: Long = 2000) {
    var startedAt: Long? = null
        private set
    private var previous: Long? = null
    fun reset() { startedAt = null; previous = null }
    fun observe(elapsed: Long, noFace: Boolean, valid: Boolean = true): Boolean {
        if (!valid) { reset(); return false }
        val last = previous
        if (last != null && (elapsed < last || elapsed - last > maximumGapMs)) startedAt = null
        previous = elapsed
        if (!noFace) { startedAt = null; return false }
        if (startedAt == null) startedAt = elapsed
        return elapsed - startedAt!! >= thresholdMs
    }
}
