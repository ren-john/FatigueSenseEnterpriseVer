package com.example.fatiguesense.enterprise.ml

data class EyeClosureAlertSettings(
    val closureCount: Int = 3,
    val windowSeconds: Int = 60,
) {
    init {
        require(closureCount in 1..100) { "Enter 1–100 eye closures." }
        require(windowSeconds in 1..3600) { "Enter a time window of 1–3600 seconds." }
    }
}

data class EyeClosurePopup(val id: Long, val closureCount: Int, val windowSeconds: Int)

internal data class EyeClosureWindowResult(val count: Int, val triggered: Boolean)

/** Counts qualified closures once until a valid open-eye observation rearms detection. */
internal class EyeClosureWindow(private val settings: EyeClosureAlertSettings) {
    private val closures = ArrayDeque<Long>()
    private var closureCounted = false
    private var thresholdReached = false

    /** Uses monotonic milliseconds. null means the eyes could not be observed. */
    fun observe(nowMs: Long, eyesClosed: Boolean?, qualified: Boolean): EyeClosureWindowResult {
        val windowMs = settings.windowSeconds * 1000L
        while (closures.isNotEmpty() && nowMs - closures.first() >= windowMs) closures.removeFirst()
        if (closures.size < settings.closureCount) thresholdReached = false

        if (eyesClosed == false) closureCounted = false
        if (eyesClosed == true && qualified && !closureCounted) {
            closures.addLast(nowMs)
            closureCounted = true
        }
        val triggered = closures.size >= settings.closureCount && !thresholdReached
        thresholdReached = closures.size >= settings.closureCount
        return EyeClosureWindowResult(closures.size, triggered)
    }
}
