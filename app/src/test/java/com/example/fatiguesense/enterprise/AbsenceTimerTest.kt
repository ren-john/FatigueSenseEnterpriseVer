package com.example.fatiguesense.enterprise.enterprise

import org.junit.Assert.*
import org.junit.Test

class AbsenceTimerTest {
    @Test fun continuousFramesQualifyOnlyAtThreeMinutes() {
        val timer = AbsenceTimer()
        for (time in 0L until 180000 step 1000) assertFalse(timer.observe(time, true))
        assertTrue(timer.observe(180000, true))
        assertFalse(timer.observe(181000, false))
        assertNull(timer.startedAt)
    }
    @Test fun noFramesOrInferenceFailureResetsEvidence() {
        val timer = AbsenceTimer()
        timer.observe(0, true)
        assertFalse(timer.observe(180000, true))
        assertEquals(180000L, timer.startedAt)
        assertFalse(timer.observe(181000, true, false))
        assertNull(timer.startedAt)
    }
    @Test fun newFaceOrExplicitStopBreaksContinuity() {
        val timer = AbsenceTimer(3000)
        timer.observe(0, true); timer.observe(1000, true); timer.observe(2000, false)
        assertFalse(timer.observe(3000, true))
        timer.reset(); assertFalse(timer.observe(6000, true))
    }
}
