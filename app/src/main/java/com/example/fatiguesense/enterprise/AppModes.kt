package com.example.fatiguesense.enterprise.enterprise

import android.app.Application
import android.annotation.SuppressLint
import com.example.fatiguesense.enterprise.services.FatigueService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Application-wide owner of transitions; UI cancellation cannot leave a half-finished sign-out. */
object AppModes {
    private lateinit var app: Application
    lateinit var policy: ModePolicy
        private set
    private val transitions = Mutex()
    private val restoration = Mutex()
    private val entryOperations = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice = mutableNotice.asStateFlow()
    fun dismissNotice() { mutableNotice.value = null }

    @Synchronized fun initialize(application: Application) {
        if (::policy.isInitialized) return
        app = application
        policy = ModePolicy(app.getSharedPreferences("app-mode", 0).getBoolean("enterprise-opt-in-v1", false))
        if (policy.mode.value == AppMode.LOCAL) UploadScheduler.cancel(app)
    }
    @SuppressLint("UseKtx") // commit() success is required before publishing the new mode.
    private fun persist(enterprise: Boolean) {
        check(app.getSharedPreferences("app-mode", 0).edit().putBoolean("enterprise-opt-in-v1", enterprise).commit()) {
            "Could not save app mode"
        }
    }
    suspend fun beginEntry() = transitions.withLock {
        mutableNotice.value = null
        FatigueService.stopAndAwait(app)
        EspConnection.clear()
        policy.beginEntry()
    }
    suspend fun signIn(email: String, password: String) = entryOperations.withLock {
        val attempt = policy.entryAttempt()
        try {
            val driver = EnterpriseAuth.signIn(app, email, password)
            transitions.withLock {
                check(policy.mode.value == AppMode.ENTERPRISE_ENTRY)
                check(policy.isCurrent(attempt)) { "Enterprise entry was cancelled" }
                persist(true)
                policy.activate(CloudOwner(driver.uid, driver.orgId), attempt)
                DetectionJournal.initialize(app)
                UploadScheduler.schedule(app)
                UploadScheduler.enqueue(app)
            }
        } catch (e: Exception) {
            withContext(NonCancellable) {
                if (policy.isCurrent(attempt)) {
                    toLocal(attempt)
                    if (e !is CancellationException) mutableNotice.value = e.message ?: "Enterprise sign-in failed"
                }
            }
            throw e
        }
    }
    suspend fun recover(email: String) = entryOperations.withLock {
        val attempt = policy.entryAttempt()
        var outcome: String? = null
        try { EnterpriseAuth.recover(app, email); outcome = "Check your email for password recovery." }
        catch (e: Exception) {
            if (e !is CancellationException) outcome = e.message ?: "Recovery failed"
            throw e
        } finally { withContext(NonCancellable) {
            if (policy.isCurrent(attempt)) { toLocal(attempt); mutableNotice.value = outcome }
        } }
    }
    suspend fun restorePersisted(): Driver? = restoration.withLock {
        if (policy.mode.value == AppMode.ENTERPRISE) return@withLock EnterpriseAuth.driver.value
        if (policy.mode.value != AppMode.RESTORING) return@withLock null
        // On failure retain explicit opt-in and RESTORING, so retry or offline return to Local is possible.
        val attempt = policy.entryAttempt()
        val driver = EnterpriseAuth.restore(app) ?: error("Sign in again or return to Local mode.")
        transitions.withLock {
            check(policy.mode.value == AppMode.RESTORING)
            policy.activate(CloudOwner(driver.uid, driver.orgId), attempt)
            DetectionJournal.initialize(app)
            UploadScheduler.schedule(app)
        }
        driver
    }
    suspend fun toLocal(attempt: ModePolicy.EntryAttempt? = null) = withContext(NonCancellable + Dispatchers.Main.immediate) {
        try { transitions.withLock {
            if (attempt != null && !policy.isCurrent(attempt)) return@withLock
            mutableError.value = null
            policy.returnToLocal(
                persistWithdrawal = { persist(false) },
                cancelWorkers = { UploadScheduler.cancelAndAwait(app) },
                stopMonitoring = { FatigueService.stopAndAwait(app) },
                clearAuth = { EnterpriseAuth.clearAfterDrain() },
            )
        } } catch (e: Exception) {
            mutableError.value = "Cleanup could not finish: ${e.message}. Resolve the error before returning to Local mode."
            throw e
        }
    }
    fun requestLocal() { scope.launch { try { toLocal() } catch (_: Exception) { /* Exposed through error. */ } } }
}
