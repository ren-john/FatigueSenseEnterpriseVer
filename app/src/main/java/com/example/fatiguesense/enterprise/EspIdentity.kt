package com.example.fatiguesense.enterprise.enterprise

/** Temporary compatibility for firmware that omits identity; reported mismatches always fail. */
internal fun requireEspIdentity(reportedId: String?, assignedId: String, manualId: String?) {
    if (!reportedId.isNullOrBlank()) {
        require(reportedId == assignedId) { "This camera does not match your assigned camera. Check the camera or ask your administrator to update the assignment." }
    } else {
        require(manualId?.trim() == assignedId) {
            "This camera cannot report its ID. Open Connection options, enable My camera uses older firmware, and enter the assigned camera ID exactly."
        }
    }
}

/** A known local hardware ID must never be bypassed by the legacy checkbox. */
internal fun requireLocalEspIdentity(reportedId: String?, savedId: String?, confirmLegacy: Boolean) {
    if (!savedId.isNullOrBlank()) {
        require(reportedId == savedId) { "This is a different camera from the one paired with this app. To switch, open Troubleshooting & device settings and choose Pair a different camera." }
    } else if (reportedId.isNullOrBlank()) {
        require(confirmLegacy) { "This camera cannot report its ID. If this is your camera, open Connection options and enable My camera uses older firmware to confirm it manually." }
    }
}
