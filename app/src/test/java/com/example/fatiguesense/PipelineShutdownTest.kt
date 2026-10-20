package com.example.fatiguesense.enterprise

import com.example.fatiguesense.enterprise.ml.retirePipeline
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PipelineShutdownTest {
    @Test fun releaseWaitsForNonCancellableNativeWork() = runBlocking {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val returned = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        val worker = launch(Dispatchers.Default) {
            entered.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
            returned.set(true)
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val retirement = launch(start = CoroutineStart.UNDISPATCHED) {
            retirePipeline(worker) {
                assertTrue("Native work must return before close", returned.get())
                released.set(true)
            }
        }
        try {
            assertTrue(worker.isCancelled)
            assertFalse("Cancellation alone must not release resources", released.get())
        } finally {
            finish.countDown()
        }
        withTimeout(5_000) { retirement.join() }
        assertTrue(released.get())
    }

    @Test fun releaseWithoutStartedPipeline() = runBlocking {
        var released = false
        retirePipeline(null) { released = true }
        assertTrue(released)
    }
}
