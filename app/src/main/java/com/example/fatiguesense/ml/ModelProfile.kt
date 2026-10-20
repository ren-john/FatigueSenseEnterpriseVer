package com.example.fatiguesense.enterprise.ml

/** Change ACTIVE only, then rebuild/reinstall. See MODEL_SWITCHING.md. */
object ModelProfile {
    enum class Profile { LEGACY_12PT, COORDINATES_32PT }
    val ACTIVE = Profile.COORDINATES_32PT
    val legacy: Boolean get() = ACTIVE == Profile.LEGACY_12PT
    val asset: String get() = if (legacy) LegacyLandmarkModel.ASSET else LandmarkModel.ASSET
    val label: String get() = if (legacy) "Legacy 12-point" else "Coordinates 32-point"
    val inputShape: IntArray get() = if (legacy) intArrayOf(1, 128, 128, 3) else intArrayOf(1, 3, 192, 192)
    val outputShape: IntArray get() = if (legacy) intArrayOf(1, 24) else intArrayOf(1, 32, 2)
    fun preferenceKey(base: String): String = "${base}_${ACTIVE.name.lowercase()}_v1"
}
