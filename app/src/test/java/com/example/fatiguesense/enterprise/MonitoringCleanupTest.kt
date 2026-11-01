package com.example.fatiguesense.enterprise.enterprise

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MonitoringCleanupTest {
    @Test fun storageFailureRemainsPendingAndRetryUsesOriginalClosure() = runBlocking {
        val queue = MonitoringCleanup()
        val trace = mutableListOf<String>()
        var unavailable = true
        try {
            queue.enqueue {
                if (unavailable) error("storage unavailable")
                trace.add("original account and end timestamp saved")
            }
            fail("Storage error must propagate to the transition")
        } catch (_: IllegalStateException) { }
        assertTrue(trace.isEmpty())
        unavailable = false
        queue.drain()
        queue.drain()
        assertEquals(listOf("original account and end timestamp saved"), trace)
    }

    @Test fun subsequentCleanupCannotPassFailedPredecessor() = runBlocking {
        val queue = MonitoringCleanup()
        val trace = mutableListOf<String>()
        var unavailable = true
        try { queue.enqueue { if (unavailable) error("disk full"); trace.add("first") } }
        catch (_: IllegalStateException) { }
        try { queue.enqueue { trace.add("second") } } catch (_: IllegalStateException) { }
        assertTrue(trace.isEmpty())
        unavailable = false
        queue.drain()
        assertEquals(listOf("first", "second"), trace)
    }
}
