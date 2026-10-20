package com.example.fatiguesense.enterprise.enterprise

import org.junit.Assert.*
import org.junit.Test

class FlagRulesTest {
    private fun event(id: String, time: Long, type: String = "EAR", session: String = "s", uid: String = "driver") =
        EnterpriseEvent(id, uid, "org", "vehicle", "esp", session, time, type, .2f, .6f)

    @Test fun earBoundaryAndDuplicateRevisions() {
        val records = listOf(event("a", 0), event("b", 20000), event("c", 60000))
        assertEquals(1, FlagRules.evaluate(records).size)
        assertEquals(60000L, FlagRules.evaluate(records).single().qualifiedAt)
        assertTrue(FlagRules.evaluate(records.dropLast(1) + event("c", 60001)).isEmpty())
        assertEquals(1, FlagRules.evaluate(records + records + records[0].copy(revision = 2, status = "completed")).size)
    }
    @Test fun historicalBatchOrderDoesNotChangeFlags() {
        val rows = listOf(event("a", 36000000), event("b", 36020000), event("c", 36050000))
        assertEquals(FlagRules.evaluate(rows), FlagRules.evaluate(rows.reversed()))
        assertEquals(36050000L, FlagRules.evaluate(rows).single().qualifiedAt)
        assertTrue(FlagRules.evaluate(listOf(event("a", 0), event("b", 300000), event("c", 600000))).isEmpty())
    }
    @Test fun yawnsNeedSixInTenMinutesAndCannotCrossUsersOrSessions() {
        val rows = (0..5).map { event("y$it", it * 120000L, "MAR") }
        assertEquals("Yawning constantly", FlagRules.evaluate(rows).single().kind)
        assertTrue(FlagRules.evaluate(rows.take(5)).isEmpty())
        assertTrue(FlagRules.evaluate(rows.take(5) + rows.last().copy(timestamp = 600001)).isEmpty())
        assertTrue(FlagRules.evaluate(listOf(event("a", 0), event("b", 1000, uid = "other"), event("c", 2000))).isEmpty())
        assertTrue(FlagRules.evaluate(listOf(event("a", 0), event("b", 1000), event("c", 2000, session = "other"))).isEmpty())
    }
    @Test fun continuousBreachIsOneEpisodeAndLaterBreachIsSeparate() {
        val rows = listOf(0L, 1000, 2000, 3000, 4000, 200000, 201000, 202000).mapIndexed { i, t -> event("e$i", t) }
        assertEquals(2, FlagRules.evaluate(rows).size)
    }
    @Test fun noFaceDoesNotIncrementEyeOrYawnTotals() {
        val row = event("absent", 1000, "NO_FACE").copy(durationMs = 180000)
        val flag = FlagRules.evaluate(listOf(row)).single()
        assertEquals("No face detected", flag.kind); assertEquals(181000L, flag.qualifiedAt)
        assertTrue(FlagRules.evaluate(listOf(row.copy(durationMs = 179999))).isEmpty())
    }
}
