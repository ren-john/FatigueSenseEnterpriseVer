package com.example.fatiguesense.enterprise

import com.example.fatiguesense.enterprise.ml.ActiveAlertState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Focused contract test for the MAR/EAR state vocabulary used by the manager.
 * Transition timing is clock-driven in FatigueManager's serialized collector.
 */
class MarYawnAlertLogicTest {
    @Test
    fun stateMachine_hasDistinctTier1MarAndFatigueStatesWithEarPriority() {
        assertEquals(
            listOf(
                ActiveAlertState.NONE,
                ActiveAlertState.TIER1_YAWN,
                ActiveAlertState.MAR_FATIGUE,
                ActiveAlertState.EAR_FATIGUE,
            ),
            ActiveAlertState.values().toList(),
        )
        // EAR_FATIGUE is the terminal/highest-priority state selected immediately
        // whenever the EAR predicate is true.
        assertEquals(ActiveAlertState.EAR_FATIGUE, ActiveAlertState.values().last())
    }
}
