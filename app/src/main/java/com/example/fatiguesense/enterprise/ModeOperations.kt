package com.example.fatiguesense.enterprise.enterprise

import kotlinx.coroutines.*

/** Old jobs must be rejected before either restoration or opening an upload batch. */
internal suspend fun <T> runModeGatedWorker(
    policy: ModePolicy,
    skipped: T,
    restore: suspend () -> Driver?,
    upload: suspend (Driver) -> T,
): T {
    if (!policy.allowsWorker()) return skipped
    val driver = restore() ?: return skipped
    return policy.cloud(expected = CloudOwner(driver.uid, driver.orgId)) { upload(driver) }
}

/** Dependencies are explicit so ordering and non-cancellable request draining can be tested. */
internal suspend fun ModePolicy.returnToLocal(
    persistWithdrawal: () -> Unit,
    cancelWorkers: suspend () -> Unit,
    stopMonitoring: suspend () -> Unit,
    clearAuth: () -> Unit,
) = withContext(NonCancellable) {
    if (mode.value == AppMode.LOCAL) return@withContext
    persistWithdrawal()
    val draining = revoke()
    cancelWorkers()
    stopMonitoring()
    draining.awaitAll()
    clearAuth()
    completeLocal()
}
