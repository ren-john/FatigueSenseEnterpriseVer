package com.example.fatiguesense.enterprise.enterprise

/** Firmware `mode` describes saved settings; an active recovery AP is also a usable local link. */
internal fun requireEspCameraReady(
    mode: String,
    stationConnected: Boolean,
    hotspotActive: Boolean,
    cameraReady: Boolean,
    restarting: Boolean,
) {
    require(!restarting) { "The camera is restarting. Wait about 30 seconds, then tap Connect camera again." }
    require(mode == "ap" || (mode == "sta" && (stationConnected || hotspotActive))) {
        "The camera has saved Wi-Fi settings but has not joined the phone hotspot. Enable the saved 2.4 GHz hotspot, or use FatigueSense_Cam for recovery."
    }
    require(cameraReady) { "The camera is connected, but its video is not ready. Wait a moment and try again. If it still fails, restart the camera." }
}
