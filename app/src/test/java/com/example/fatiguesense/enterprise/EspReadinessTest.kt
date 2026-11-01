package com.example.fatiguesense.enterprise.enterprise

import org.junit.Assert.assertThrows
import org.junit.Test

class EspReadinessTest {
    @Test fun directApWorksWithoutStationConnectionOrNewFirmwareFields() {
        requireEspCameraReady("ap", false, false, true, false)
    }
    @Test fun joinedStationWorksWithoutRecoveryHotspot() {
        requireEspCameraReady("sta", true, false, true, false)
    }
    @Test fun recoveryHotspotWorksWhileSavedStationSettingsRemain() {
        requireEspCameraReady("sta", false, true, true, false)
    }
    @Test fun savedStationSettingsAloneDoNotMeanConnected() {
        assertThrows(IllegalArgumentException::class.java) {
            requireEspCameraReady("sta", false, false, true, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireEspCameraReady("unknown", false, true, true, false)
        }
    }
    @Test fun recoveryHotspotDoesNotBypassRestartOrUnavailableCamera() {
        assertThrows(IllegalArgumentException::class.java) {
            requireEspCameraReady("sta", false, true, false, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireEspCameraReady("sta", false, true, true, true)
        }
    }
}
