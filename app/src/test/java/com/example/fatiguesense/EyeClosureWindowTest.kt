package com.example.fatiguesense.enterprise

import com.example.fatiguesense.enterprise.ml.EyeClosureAlertSettings
import com.example.fatiguesense.enterprise.ml.EyeClosureWindow
import org.junit.Assert.*
import org.junit.Test

class EyeClosureWindowTest {
    private fun tracker(count: Int = 3, seconds: Int = 60) = EyeClosureWindow(EyeClosureAlertSettings(count, seconds))

    @Test fun repeatedFramesOfOneClosureCountOnlyOnce() {
        val window = tracker()
        repeat(100) {
            val result = window.observe(it * 100L, true, true)
            assertEquals(1, result.count)
            assertFalse(result.triggered)
        }
    }

    @Test fun briefClosuresMustQualifyBeforeTheyCount() {
        val window = tracker(count = 1)
        assertEquals(0, window.observe(0, true, false).count)
        assertEquals(0, window.observe(100, false, false).count)
        assertEquals(0, window.observe(200, true, false).count)
        assertTrue(window.observe(300, true, true).triggered)
    }

    @Test fun thirdSeparateClosureTriggersPopup() {
        val window = tracker()
        assertFalse(window.observe(0, true, true).triggered)
        window.observe(100, false, false)
        assertFalse(window.observe(1000, true, true).triggered)
        window.observe(1100, false, false)
        val result = window.observe(2000, true, true)
        assertEquals(3, result.count)
        assertTrue(result.triggered)
    }

    @Test fun unavailableEyesAndUnqualifiedClosedFramesDoNotRearmAClosure() {
        val window = tracker(count = 2)
        window.observe(0, true, true)
        window.observe(100, null, false)
        window.observe(200, true, false)
        assertEquals(1, window.observe(300, true, true).count)
        window.observe(400, false, false)
        assertTrue(window.observe(500, true, true).triggered)
    }

    @Test fun windowExcludesClosureExactlyAtExpiry() {
        val window = tracker(count = 2, seconds = 10)
        window.observe(0, true, true)
        window.observe(100, false, false)
        val result = window.observe(10_000, true, true)
        assertEquals(1, result.count)
        assertFalse(result.triggered)
    }

    @Test fun noRepeatedPopupWhileCountRemainsAboveThreshold() {
        val window = tracker(count = 2)
        window.observe(0, true, true)
        window.observe(100, false, false)
        assertTrue(window.observe(1000, true, true).triggered)
        window.observe(1100, false, false)
        assertFalse(window.observe(2000, true, true).triggered)
        assertFalse(window.observe(3000, null, false).triggered)
    }

    @Test fun popupRearmsOnlyAfterCountFallsBelowThreshold() {
        val window = tracker(count = 2, seconds = 10)
        window.observe(0, true, true)
        window.observe(100, false, false)
        window.observe(1000, true, true)
        window.observe(1100, false, false)
        window.observe(2000, true, true)
        assertEquals(2, window.observe(10_000, false, false).count)
        assertEquals(1, window.observe(11_000, false, false).count)
        assertTrue(window.observe(11_500, true, true).triggered)
    }

    @Test fun expiryAndNewClosureInSameObservationCanRetrigger() {
        val window = tracker(count = 2, seconds = 10)
        window.observe(0, true, true)
        window.observe(100, false, false)
        window.observe(1000, true, true)
        window.observe(1100, false, false)
        assertTrue(window.observe(10_500, true, true).triggered)
    }

    @Test fun longHeldClosureDoesNotCountAgainAfterWindowExpires() {
        val window = tracker(count = 1, seconds = 2)
        assertTrue(window.observe(0, true, true).triggered)
        val held = window.observe(3000, true, true)
        assertEquals(0, held.count)
        assertFalse(held.triggered)
        window.observe(3100, false, false)
        assertTrue(window.observe(3200, true, true).triggered)
    }

    @Test fun unavailableEyesStillExpireOldEventsWithoutAddingNewOnes() {
        val window = tracker(count = 1, seconds = 2)
        window.observe(0, true, true)
        val expired = window.observe(2000, null, true)
        assertEquals(0, expired.count)
        assertFalse(expired.triggered)
    }

    @Test fun newWindowStartsWithoutPreviousSessionOrSettingsHistory() {
        val previous = tracker(count = 2)
        previous.observe(0, true, true)
        val fresh = tracker(count = 2)
        assertFalse(fresh.observe(1000, true, true).triggered)
        assertEquals(1, fresh.observe(1100, true, true).count)
    }

    @Test fun invalidLimitsAreRejected() {
        for ((count, seconds) in listOf(0 to 60, 101 to 60, 3 to 0, 3 to 3601)) {
            try {
                EyeClosureAlertSettings(count, seconds)
                fail("Invalid limits must not be accepted: $count in $seconds seconds")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
