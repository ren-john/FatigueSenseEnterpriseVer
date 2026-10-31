package com.example.fatiguesense.enterprise.enterprise

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SessionClosuresTest {
    @Test fun failureRetainsClosureAndRetryAcknowledgesOnlyAfterServerSuccess() = runBlocking {
        val saved = mutableListOf(LocalSession("old", "driver", "org", endedAt = 100))
        var attempts = 0
        val close: suspend (LocalSession) -> Unit = { if (++attempts == 1) error("offline") }
        val acknowledge: suspend (LocalSession) -> Unit = { saved.remove(it); Unit }
        try {
            SessionClosures.sync({ saved.toList() }, close, acknowledge)
            fail("Offline closure must remain pending")
        } catch (_: IllegalStateException) { }
        assertEquals(1, saved.size)
        SessionClosures.sync({ saved.toList() }, close, acknowledge)
        assertEquals(2, attempts)
        assertTrue(saved.isEmpty())
    }

    @Test fun workerAndRestartSerializeClosureWithoutSendingItTwice() = runBlocking {
        val saved = mutableListOf(LocalSession("old", "driver", "org", endedAt = 100))
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        val close: suspend (LocalSession) -> Unit = { calls++; entered.complete(Unit); finish.await() }
        val acknowledge: suspend (LocalSession) -> Unit = { saved.remove(it); Unit }
        val worker = launch { SessionClosures.sync({ saved.toList() }, close, acknowledge) }
        entered.await()
        val restart = launch(start = CoroutineStart.UNDISPATCHED) { SessionClosures.sync({ saved.toList() }, close, acknowledge) }
        assertEquals(1, calls)
        assertFalse(restart.isCompleted)
        finish.complete(Unit)
        joinAll(worker, restart)
        assertEquals(1, calls)
        assertTrue(saved.isEmpty())
    }
}
