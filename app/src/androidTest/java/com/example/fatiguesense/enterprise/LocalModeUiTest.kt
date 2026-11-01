package com.example.fatiguesense.enterprise.enterprise

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.fatiguesense.enterprise.MainActivity
import com.google.firebase.FirebaseApp
import org.junit.Assert.*
import org.junit.Before
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class LocalModeUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Before fun resetSetup() = runBlocking {
        com.example.fatiguesense.enterprise.services.FatigueService.stopAndAwait(ui.activity)
        AppModes.toLocal()
        EspConnection.forgetLocal(ui.activity)
        ui.activity.getSharedPreferences("local-setup", 0).edit().clear().commit()
        Unit
    }

    @Test fun localLaunchHasDashboardCalibrationHistoryAndSettingsWithoutFirebase() {
        ui.onNodeWithText("Local mode · events stay on this phone").assertIsDisplayed()
        ui.onNodeWithText("CALIBRATION").performClick()
        ui.onNodeWithText("DETECTION CALIBRATION").assertIsDisplayed()
        ui.onNodeWithText("LOGS").performClick()
        ui.onNodeWithText("LOCAL DETECTION LOG").assertExists()
        ui.onNodeWithText("SETTINGS").performClick()
        ui.onNodeWithText("Enterprise sign-in").assertExists()
        assertEquals(AppMode.LOCAL, AppModes.policy.mode.value)
        assertTrue(FirebaseApp.getApps(ui.activity).isEmpty())
    }

    @Test fun cancellingEnterpriseEntryReturnsToUsableLocalDashboard() {
        ui.onNodeWithText("SETTINGS").performClick()
        ui.onNodeWithText("Enterprise sign-in").performScrollTo().performClick()
        ui.waitUntil(10_000) { AppModes.policy.mode.value == AppMode.ENTERPRISE_ENTRY }
        ui.onNodeWithText("Cancel and return to Local mode").performScrollTo().performClick()
        ui.waitUntil(10_000) { AppModes.policy.mode.value == AppMode.LOCAL }
        ui.onNodeWithText("Local mode · events stay on this phone").assertIsDisplayed()
        assertNull(EnterpriseAuth.driver.value)
        assertTrue(FirebaseApp.getApps(ui.activity).isEmpty())
    }

    @Test fun setupRestoresNonsecretFieldsAfterActivityRecreation() {
        ui.onNodeWithText("CONNECT ESP").performScrollTo().performClick()
        ui.onNodeWithText("Camera IP address").performTextReplacement("192.168.4.42")
        ui.onNodeWithText("Use your phone’s hotspot").performScrollTo().performClick()
        ui.onNodeWithText("Phone hotspot name").performScrollTo().performTextReplacement("LocalHotspot")
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("192.168.4.42").assertExists()
        ui.onNodeWithText("LocalHotspot").assertExists()
        assertEquals(AppMode.LOCAL, AppModes.policy.mode.value)
        assertTrue(FirebaseApp.getApps(ui.activity).isEmpty())
    }

    @Test fun firstConnectionUsesEnteredIpAndKeepsErrorsVisible() {
        EspConnection.forgetLocal(ui.activity)
        ui.onNodeWithText("CONNECT ESP").performScrollTo().performClick()
        ui.onNodeWithText("Reconnect saved camera").assertDoesNotExist()
        ui.onNodeWithText("Camera setup password").assertDoesNotExist()
        ui.onNodeWithText("Phone hotspot name").assertDoesNotExist()
        ui.onNodeWithText("Remove saved hotspot from camera").assertDoesNotExist()
        ui.onNodeWithText("Camera IP address").performTextReplacement("invalid-ip")
        ui.onNodeWithText("Connect camera").performScrollTo().performClick()
        ui.onNodeWithText("Enter a camera IP address such as 192.168.4.1, without http:// or a port.").assertIsDisplayed()
        assertEquals(AppMode.LOCAL, AppModes.policy.mode.value)
    }

    @Test fun changingCameraRequiresConfirmation() {
        val prefs = ui.activity.getSharedPreferences("local-esp", 0)
        prefs.edit().putString("ip", "192.168.4.23").putString("device-id", "ui-test-camera").commit()
        ui.onNodeWithText("CONNECT ESP").performScrollTo().performClick()
        ui.onNodeWithText("Troubleshooting & device settings").performScrollTo().performClick()
        ui.onNodeWithText("Pair a different camera").performScrollTo().performClick()
        ui.onNodeWithText("Pair a different camera?").assertIsDisplayed()
        ui.onNodeWithText("Cancel").performClick()
        assertEquals("ui-test-camera", EspConnection.savedLocalId(ui.activity))
        ui.onNodeWithText("Pair a different camera").performScrollTo().performClick()
        ui.onNodeWithText("Clear pairing").performClick()
        ui.waitUntil(5_000) { EspConnection.savedLocalId(ui.activity) == null }
        ui.onNodeWithText("Ready to pair another camera").assertIsDisplayed()
        ui.onNodeWithText("Reconnect saved camera").assertDoesNotExist()
        assertEquals(AppMode.LOCAL, AppModes.policy.mode.value)
    }
}
