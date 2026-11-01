package com.example.fatiguesense.enterprise.enterprise

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** RESTORING is allowed only by the new, explicitly persisted enterprise preference. */
enum class AppMode { LOCAL, ENTERPRISE_ENTRY, RESTORING, ENTERPRISE, SWITCHING_TO_LOCAL }
data class CloudOwner(val uid: String, val orgId: String)
class CloudAccessDenied : IllegalStateException("Cloud access is disabled for this mode or account")

/** No Android/Firebase dependencies: all cloud entry points share this admission/drain policy. */
class ModePolicy(enterpriseOptIn: Boolean = false) {
    private val lock = Any()
    private val mutableMode = MutableStateFlow(if (enterpriseOptIn) AppMode.RESTORING else AppMode.LOCAL)
    val mode = mutableMode.asStateFlow()
    private var generation = 0L
    private var owner: CloudOwner? = null
    private val active = mutableMapOf<Job, CompletableDeferred<Unit>>()
    class Permit internal constructor(internal val generation: Long, internal val owner: CloudOwner?, internal val entry: Boolean)

    fun beginEntry() = synchronized(lock) {
        check(mode.value == AppMode.LOCAL)
        generation++; mutableMode.value = AppMode.ENTERPRISE_ENTRY
    }
    class EntryAttempt internal constructor(internal val generation: Long)
    fun entryAttempt(): EntryAttempt = synchronized(lock) {
        check(mode.value == AppMode.ENTERPRISE_ENTRY || mode.value == AppMode.RESTORING)
        EntryAttempt(generation)
    }
    fun isCurrent(attempt: EntryAttempt): Boolean = synchronized(lock) { attempt.generation == generation }
    fun activate(identity: CloudOwner, attempt: EntryAttempt) = synchronized(lock) {
        check(attempt.generation == generation) { "Enterprise entry was cancelled" }
        check(mode.value == AppMode.ENTERPRISE_ENTRY || mode.value == AppMode.RESTORING)
        owner = identity; mutableMode.value = AppMode.ENTERPRISE
    }
    fun enterpriseOwner(): CloudOwner? = synchronized(lock) { owner.takeIf { mode.value == AppMode.ENTERPRISE } }
    fun allowsWorker(): Boolean = mode.value == AppMode.ENTERPRISE || mode.value == AppMode.RESTORING

    fun schedule(action: () -> Unit) = synchronized(lock) {
        if (mode.value == AppMode.ENTERPRISE && owner != null) action()
    }

    suspend fun <T> cloud(entry: Boolean = false, expected: CloudOwner? = null, block: suspend (Permit) -> T): T = coroutineScope {
        currentCoroutineContext().ensureActive()
        val job = currentCoroutineContext().job
        val finished = CompletableDeferred<Unit>()
        val permit = synchronized(lock) {
            val allowed = if (entry) mode.value == AppMode.ENTERPRISE_ENTRY || mode.value == AppMode.RESTORING
                else mode.value == AppMode.ENTERPRISE && expected != null && expected == owner
            if (!allowed) throw CloudAccessDenied()
            Permit(generation, expected, entry).also { active[job] = finished }
        }
        try { block(permit) } finally {
            synchronized(lock) { active.remove(job); finished.complete(Unit) }
        }
    }

    /** Recheck immediately before token/HTTP dispatch and before publishing late results. */
    fun <T> dispatch(permit: Permit, action: () -> T): T = synchronized(lock) {
        val allowed = permit.generation == generation && if (permit.entry)
            mode.value == AppMode.ENTERPRISE_ENTRY || mode.value == AppMode.RESTORING
        else mode.value == AppMode.ENTERPRISE && permit.owner == owner
        if (!allowed) throw CloudAccessDenied()
        action()
    }

    /** Revoke first; cancellation alone is insufficient for non-cancellable SDK/HTTP calls. */
    fun revoke(): List<Deferred<Unit>> = synchronized(lock) {
        generation++; mutableMode.value = AppMode.SWITCHING_TO_LOCAL
        val draining = active.values.toList()
        active.keys.toList().forEach { it.cancel(CancellationException("Returning to Local mode")) }
        draining
    }
    fun completeLocal() = synchronized(lock) {
        check(mode.value == AppMode.SWITCHING_TO_LOCAL && active.isEmpty())
        owner = null; mutableMode.value = AppMode.LOCAL
    }
}
