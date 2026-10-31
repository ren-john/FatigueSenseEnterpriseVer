package com.example.fatiguesense.enterprise.ml

/** MediaPipe-specific settings must never reuse custom-model calibration. */
object ModelProfile {
    const val asset = "face_landmarker.task"
    const val label = "MediaPipe Face Landmarker"
    fun preferenceKey(base: String): String = "${base}_mediapipe_v1"
}
