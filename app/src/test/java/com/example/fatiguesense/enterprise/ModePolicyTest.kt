package com.example.fatiguesense.enterprise.enterprise

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ModePolicyTest {
    private val alice = Driver("alice", "org-a", "Alice")
    private val owner = CloudOwner(alice.uid, alice.orgId)
    private fun enterprise(): ModePolicy = ModePolicy().apply { beginEntry(); activate(owner, entryAttempt()) }
    private suspend fun denied(block: suspend () -> Unit) {
        try { block(); fail("Cloud request should have been denied") } catch (_: CloudAccessDenied) { }
    }

    @Test fun defaultLocalIgnoresCachedAccountAndOldJobs() = runBlocking {
        val policy = ModePolicy()
        var restored = false
        var uploaded = false
        val result = runModeGatedWorker(policy, "skipped", { restored = true; alice }) { uploaded = true; "uploaded" }
        assertEquals(AppMode.LOCAL, policy.mode.value)
        assertEquals("skipped", result)
        assertFalse(restored)
        assertFalse(uploaded)
        denied { policy.cloud(expected = owner) { fail("Cached login must not grant access") } }
        denied { policy.cloud(entry = true) { fail("Local must not refresh authentication") } }
    }

    @Test fun onlyExplicitPreferencePermitsRestoration() = runBlocking {
        val policy = ModePolicy(enterpriseOptIn = true)
        assertEquals(AppMode.RESTORING, policy.mode.value)
        denied { policy.cloud(expected = owner) { } }
        val result = runModeGatedWorker(policy, "skipped", {
            policy.cloud(entry = true) { permit -> policy.dispatch(permit) { Unit } }
            policy.activate(owner, policy.entryAttempt())
            alice
        }) { assertEquals(alice, it); "uploaded" }
        assertEquals("uploaded", result)
        assertEquals(AppMode.ENTERPRISE, policy.mode.value)
    }

    @Test fun intentionalEntryAllowsAuthButNotUploadsOrScheduling() = runBlocking {
        val policy = ModePolicy()
        policy.beginEntry()
        policy.cloud(entry = true) { permit -> policy.dispatch(permit) { Unit } }
        var scheduled = false
        policy.schedule { scheduled = true }
        assertFalse(scheduled)
        val result = runModeGatedWorker(policy, "skipped", { fail("Entry must not restore for a worker"); null }) { "uploaded" }
        assertEquals("skipped", result)
        denied { policy.cloud(expected = owner) { } }
    }

    @Test fun cloudDispatchRequiresSameAccountAndOrganization() = runBlocking {
        val policy = enterprise()
        denied { policy.cloud(expected = CloudOwner("bob", owner.orgId)) { } }
        denied { policy.cloud(expected = CloudOwner(owner.uid, "org-b")) { } }
        denied { policy.cloud(entry = true) { } }
        policy.cloud(expected = owner) { permit -> policy.dispatch(permit) { Unit } }
    }

    @Test fun workerRechecksModeAfterRestorationBeforeUploading() = runBlocking {
        val policy = enterprise()
        denied {
            runModeGatedWorker(policy, "skipped", {
                policy.revoke().awaitAll(); policy.completeLocal(); alice
            }) { fail("Late restore result must not upload"); "uploaded" }
        }
    }

    @Test fun workerCannotUseAnotherOrganizationsRestorationResult() = runBlocking {
        val policy = enterprise()
        denied {
            runModeGatedWorker(policy, "skipped", { alice.copy(orgId = "org-b") }) {
                fail("Wrong organization must never upload"); "uploaded"
            }
        }
    }

    @Test fun transitionWaitsForSdkRequestAndCleanupBeforeLocal() = runBlocking {
        val policy = enterprise()
        val entered = CompletableDeferred<Unit>()
        val releaseSdk = CompletableDeferred<Unit>()
        val trace = mutableListOf<String>()
        var lateResultPublished = false
        val request = launch {
            policy.cloud(expected = owner) { permit ->
                entered.complete(Unit)
                withContext(NonCancellable) { releaseSdk.await() }
                // Simulates a late Firebase result; dispatch must reject it after revocation.
                try { policy.dispatch(permit) { lateResultPublished = true } } catch (_: CloudAccessDenied) { }
                trace.add("request drained")
            }
        }
        entered.await()
        val transition = launch(start = CoroutineStart.UNDISPATCHED) {
            policy.returnToLocal(
                { trace.add("preference withdrawn") },
                { trace.add("workers cancelled") },
                { trace.add("monitoring stopped; closure queued") },
                { trace.add("auth cleared") },
            )
        }
        assertEquals(AppMode.SWITCHING_TO_LOCAL, policy.mode.value)
        assertFalse(transition.isCompleted)
        assertFalse(lateResultPublished)
        assertEquals(listOf("preference withdrawn", "workers cancelled", "monitoring stopped; closure queued"), trace)
        denied { policy.cloud(expected = owner) { } }
        releaseSdk.complete(Unit)
        joinAll(request, transition)
        assertEquals(AppMode.LOCAL, policy.mode.value)
        assertFalse(lateResultPublished)
        assertEquals("auth cleared", trace.last())
        assertTrue(trace.indexOf("request drained") < trace.indexOf("auth cleared"))
    }

    @Test fun cancellingTransitionCallerStillCompletesCleanup() = runBlocking {
        val policy = enterprise()
        val cleanupStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cleared = false
        val transition = launch {
            policy.returnToLocal({}, {}, { cleanupStarted.complete(Unit); release.await() }, { cleared = true })
        }
        cleanupStarted.await()
        transition.cancel()
        assertEquals(AppMode.SWITCHING_TO_LOCAL, policy.mode.value)
        release.complete(Unit)
        transition.join()
        assertTrue(cleared)
        assertEquals(AppMode.LOCAL, policy.mode.value)
    }

    @Test fun previousPermitCannotDispatchAfterReentryEvenForSameAccount() = runBlocking {
        val policy = enterprise()
        lateinit var stale: ModePolicy.Permit
        policy.cloud(expected = owner) { stale = it }
        policy.returnToLocal({}, {}, {}, {})
        policy.beginEntry(); policy.activate(owner, policy.entryAttempt())
        denied { policy.dispatch(stale) { fail("Previous generation must be rejected") } }
    }

    @Test fun cancelledEntryCannotActivateAfterUserStartsAnotherAttempt() = runBlocking {
        val policy = ModePolicy()
        policy.beginEntry()
        val cancelled = policy.entryAttempt()
        policy.returnToLocal({}, {}, {}, {})
        policy.beginEntry()
        assertFalse(policy.isCurrent(cancelled))
        try {
            policy.activate(owner, cancelled)
            fail("A late login must not complete a new entry")
        } catch (_: IllegalStateException) { }
        assertEquals(AppMode.ENTERPRISE_ENTRY, policy.mode.value)
        policy.activate(owner, policy.entryAttempt())
        assertEquals(AppMode.ENTERPRISE, policy.mode.value)
    }

    @Test fun schedulingStopsAsSoonAsTransitionBegins() = runBlocking {
        val policy = enterprise()
        var calls = 0
        policy.schedule { calls++ }
        policy.revoke().awaitAll()
        policy.schedule { calls++ }
        policy.completeLocal()
        policy.schedule { calls++ }
        assertEquals(1, calls)
    }

    @Test fun failedCleanupDoesNotClaimLocalIsActive() = runBlocking {
        val policy = enterprise()
        try {
            policy.returnToLocal({}, {}, { error("storage unavailable") }, { fail("Cannot clear auth before durable cleanup") })
            fail("Expected cleanup failure")
        } catch (_: IllegalStateException) { }
        assertEquals(AppMode.SWITCHING_TO_LOCAL, policy.mode.value)
        denied { policy.cloud(expected = owner) { } }
    }
}
