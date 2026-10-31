package com.example.fatiguesense.enterprise.enterprise

/** Temporary compatibility for firmware that omits identity; reported mismatches always fail. */
internal fun requireEspIdentity(reportedId: String?, assignedId: String, manualId: String?) {
    if (!reportedId.isNullOrBlank()) {
        require(reportedId == assignedId) { "ESP identity does not match the assigned device." }
    } else {
        require(manualId?.trim() == assignedId) {
            "Firmware does not report device_id. Enable manual confirmation and enter the assigned device ID exactly."
        }
    }
}
