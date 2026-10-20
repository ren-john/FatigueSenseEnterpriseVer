package com.example.fatiguesense.enterprise.enterprise

import android.content.Context
import android.os.SystemClock
import com.example.fatiguesense.enterprise.ml.AnalysisResult
import com.example.fatiguesense.enterprise.ml.FrameQuality
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/** Each analyzed frame, not conflated UI StateFlow updates, drives durable trigger edges. */
object DetectionJournal {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var dao: EnterpriseDao? = null
    private var app: Context? = null
    private var owner: DriverSession? = null
    private val open = mutableMapOf<String, Pair<EnterpriseEvent, Long>>()
    private val absence = AbsenceTimer()
    private var absentWall = 0L
    private var lastFrame = 0L
    val error = MutableStateFlow<String?>(null)
    private val initialized = CompletableDeferred<Unit>()
    init { scope.launch { for (command in commands) try { command() } catch (e: Exception) {
        error.value = "Local event storage failed: ${e.message}. Stop monitoring and check storage."
    } } }
    fun initialize(context: Context) {
        if (app != null) return
        app = context.applicationContext; dao = EnterpriseDatabase.get(context).dao()
        commands.trySend {
            try {
                dao!!.interruptOrphans(); dao!!.closeOrphanSessions(System.currentTimeMillis())
                UploadScheduler.schedule(context); initialized.complete(Unit)
            } catch (e: Exception) { initialized.completeExceptionally(e); throw e }
        }
    }
    suspend fun awaitInitialized() = initialized.await()
    fun start(session: DriverSession) { commands.trySend { interrupt(); owner = session } }
    fun frame(sessionId: String, result: AnalysisResult, wall: Long = System.currentTimeMillis(), elapsed: Long = SystemClock.elapsedRealtime()) {
        commands.trySend {
            if (owner?.sessionId != sessionId) return@trySend
            if (lastFrame > 0 && elapsed - lastFrame > 2000) interrupt()
            lastFrame = elapsed
            val valid = result.qualityStatus != FrameQuality.INFERENCE_ERROR
            if (!valid) { interrupt(); return@trySend }
            edge("EAR", result.isFatigueAlert, wall, elapsed, result)
            edge("MAR", result.isYawnAlert, wall, elapsed, result)
            if (result.qualityStatus == FrameQuality.NO_FACE) {
                if (absence.startedAt == null) absentWall = wall
                if (absence.observe(elapsed, true) && "NO_FACE" !in open) {
                    create("NO_FACE", absentWall, absence.startedAt!!, null, null, 180000)
                }
            } else {
                finish("NO_FACE", elapsed); absence.reset()
            }
        }
    }
    fun gap(sessionId: String) { commands.trySend { if (owner?.sessionId == sessionId) interrupt() } }
    suspend fun stop() {
        val done = CompletableDeferred<Unit>()
        commands.send { try { interrupt(); owner = null } finally { done.complete(Unit) } }
        done.await()
    }
    private suspend fun edge(type: String, active: Boolean, wall: Long, elapsed: Long, result: AnalysisResult) {
        if (active && type !in open) create(type, wall, elapsed,
            result.ear.takeIf { result.earValid }, result.mar.takeIf { result.marValid })
        if (!active) finish(type, elapsed)
    }
    private suspend fun create(type: String, wall: Long, elapsed: Long, ear: Float?, mar: Float?, knownDuration: Long? = null) {
        val identity = owner ?: return
        val event = EnterpriseEvent(UUID.randomUUID().toString(), identity.driver.uid, identity.driver.orgId,
            identity.assignment.vehicleId, identity.assignment.deviceId, identity.sessionId, wall, type, ear, mar,
            durationMs = knownDuration)
        dao!!.saveEvent(event); open[type] = event to elapsed
        app?.let { UploadScheduler.enqueue(it) }
    }
    private suspend fun finish(type: String, elapsed: Long) {
        val (event, start) = open[type] ?: return
        dao!!.saveEvent(event.copy(durationMs = (elapsed - start).coerceAtLeast(0), status = "completed", revision = 2))
        open.remove(type); app?.let { UploadScheduler.enqueue(it) }
    }
    private suspend fun interrupt() {
        val changed = open.isNotEmpty()
        for ((event, _) in open.values) dao!!.saveEvent(event.copy(status = "interrupted", revision = 2))
        open.clear(); absence.reset(); lastFrame = 0
        if (changed) app?.let { UploadScheduler.enqueue(it) }
    }
}
