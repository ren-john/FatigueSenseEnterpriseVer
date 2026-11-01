package com.example.fatiguesense.enterprise.enterprise

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.ArrayDeque

/** Failed durable cleanup stays at the head until it succeeds; later sessions cannot pass it. */
internal class MonitoringCleanup {
    private val lock = Mutex()
    private val pending = ArrayDeque<suspend () -> Unit>()

    suspend fun enqueue(action: suspend () -> Unit) = lock.withLock {
        pending.addLast(action)
        drainLocked()
    }
    suspend fun drain() = lock.withLock { drainLocked() }
    private suspend fun drainLocked() {
        while (pending.isNotEmpty()) {
            pending.first.invoke()
            pending.removeFirst()
        }
    }
}
