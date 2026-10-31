package com.example.fatiguesense.enterprise.ml

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.LinkProperties
import android.os.SystemClock
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.example.fatiguesense.enterprise.db.FatigueDatabase
import com.example.fatiguesense.enterprise.db.FatigueEventEntity
import com.example.fatiguesense.enterprise.enterprise.EnterpriseAuth
import com.example.fatiguesense.enterprise.enterprise.EspConnection
import com.example.fatiguesense.enterprise.enterprise.DetectionJournal
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

// ─── FatigueEvent ─────────────────────────────────────────────────────────────

enum class FatigueEventSeverity { FATIGUE_ALERT, WARNING, RECOVERY }
enum class AlertTriggerType { EAR, MAR, YAWN_FATIGUE }
enum class ActiveAlertState { NONE, TIER1_YAWN, MAR_FATIGUE, EAR_FATIGUE }

/**
 * A single logged fatigue event (in-memory mirror of [FatigueEventEntity]).
 *
 * @param timestampMs  Wall-clock time when the alert was first triggered (ms since epoch).
 * @param durationMs   How long the alert state lasted before clearing (0 if still ongoing).
 * @param earValue     EAR value at the moment the alert fired.
 * @param severity     Classification of the event.
 */
data class FatigueEvent(
    val timestampMs: Long,
    val durationMs: Long,
    val earValue: Float,
    val severity: FatigueEventSeverity,
    val triggerType: AlertTriggerType = AlertTriggerType.EAR,
) {
    /** Human-readable timestamp string, e.g. "Apr 2, 2026 | 14:32:08" */
    val dateTimeLabel: String get() {
        val fmt = SimpleDateFormat("MMM d, yyyy | HH:mm:ss", Locale.getDefault())
        return fmt.format(Date(timestampMs))
    }

    /** Human-readable duration string, e.g. "3.2s" */
    val durationLabel: String get() = if (durationMs <= 0L) "--" else "${"%.1f".format(durationMs / 1000.0)}s"
}

/**
 * Singleton that owns the entire monitoring pipeline: networking, streaming,
 * ML inference, watchdog, WiFi binding, Room DB persistence, and ESP32 alert signals.
 *
 * Lifecycle:
 *   - Call [start] from FatigueService.onCreate()
 *   - Call [stop]  from FatigueService.onDestroy()
 *
 * Any screen can observe the public StateFlows without owning lifecycle.
 * Threshold properties can be updated at runtime from the calibration/settings screen
 * and are persisted in SharedPreferences.
 */
object FatigueManager {

    private const val TAG              = "FatigueManager"
    private const val WATCHDOG_POLL_MS = 500L
    private const val WATCHDOG_GAP_MS  = 2_000L

    // SharedPreferences keys
    private const val PREFS_NAME          = "fatiguesense_prefs"
    // Each model keeps its own calibration and manual cutoffs.
    private val PREF_BASELINE_EAR = if (ModelProfile.legacy) "adaptive_baseline_ear" else "adaptive_baseline_ear_coordinates32_v1"
    private val PREF_ADAPTIVE_MODE = ModelProfile.preferenceKey("adaptive_mode_enabled")
    private val PREF_EAR_THRESHOLD = ModelProfile.preferenceKey("manual_ear_threshold")
    private val PREF_MAR_THRESHOLD = ModelProfile.preferenceKey("manual_mar_threshold")
    private const val PREF_ALERT_FRAMES   = "alert_frame_count"

    // ── Internal coroutine scope ───────────────────────────────────────────────
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val alertMutex = Mutex()

    // ── Owned pipeline components ──────────────────────────────────────────────
    private var streamer:  MjpegStreamer?  = null
    private var analyzer: FatigueAnalyzer? = null
    private var streamJob:   Job? = null
    private var watchdogJob: Job? = null
    private var retirement: Job? = null

    suspend fun awaitStopped() { retirement?.join() }

    // ── SharedPreferences & Room DB ───────────────────────────────────────────
    private var prefs: SharedPreferences? = null
    private var db: FatigueDatabase? = null
    private var dbJob: Job? = null

    // ── WiFi binding ───────────────────────────────────────────────────────────
    private var connectivityManager: ConnectivityManager? = null
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // ── Public state ───────────────────────────────────────────────────────────

    private val _frames = MutableStateFlow<Bitmap?>(null)
    val frames: StateFlow<Bitmap?> = _frames

    private val _analysisResult = MutableStateFlow(AnalysisResult())
    val analysisResult: StateFlow<AnalysisResult> = _analysisResult

    private val _streamStatus = MutableStateFlow(StreamStatus.LOST)
    val streamStatus: StateFlow<StreamStatus> = _streamStatus

    private val _streamFps = MutableStateFlow(0)
    val streamFps: StateFlow<Int> = _streamFps

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring

    // ── Adaptive calibration public state ─────────────────────────────────────

    private val _isAdaptiveModeEnabled = MutableStateFlow(true)
    val isAdaptiveModeEnabled: StateFlow<Boolean> = _isAdaptiveModeEnabled

    private val _baselineEar = MutableStateFlow(0f)
    val baselineEar: StateFlow<Float> = _baselineEar

    private val _warmupProgress = MutableStateFlow(0f)
    val warmupProgress: StateFlow<Float> = _warmupProgress

    // ── Event log (Room-backed, observed as Flow) ─────────────────────────────

    private val _logEvents = MutableStateFlow<List<FatigueEvent>>(emptyList())
    val logEvents: StateFlow<List<FatigueEvent>> = _logEvents

    // ── Session alert counter ─────────────────────────────────────────────────
    // Incremented on each rising edge during a monitoring session.
    // Reset only when stop() is called (i.e. STOP MONITORING button).
    private val _sessionAlertCount = MutableStateFlow(0)
    val sessionAlertCount: StateFlow<Int> = _sessionAlertCount
    private val _sessionYawnCount = MutableStateFlow(0)
    val sessionYawnCount: StateFlow<Int> = _sessionYawnCount
    private val _activeAlertState = MutableStateFlow(ActiveAlertState.NONE)
    val activeAlertState: StateFlow<ActiveAlertState> = _activeAlertState

    /** Rising-edge tracking: when the alert started and what the EAR was. */
    private var alertStartMs: Long = 0L
    private var alertStartEar: Float = 0f
    private var wasAlertActive: Boolean = false
    private var wasYawnActive: Boolean = false
    private var wasYawnFatigueTriggered: Boolean = false
    private var lastMarFatigueGroupSent: Int = 0
    private var yawnStartMs: Long = 0L
    private var yawnStartEar: Float = 0f
    private var marRecoveryCandidateAt: Long = 0L
    private val EAR_TO_MAR_DEBOUNCE_MS = 1_500L
    private val ACTIVE_HOLD_MS = 3_000L
    private var stateAbsentSince = 0L

    /**
     * Initializes preferences, Room database, and log events flow.
     * Safe to call multiple times or at app startup.
     */
    fun init(context: Context) {
        val appCtx = context.applicationContext
        if (prefs == null) {
            prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedBaseline     = prefs!!.getFloat(PREF_BASELINE_EAR,  0f)
            val savedAdaptiveMode = prefs!!.getBoolean(PREF_ADAPTIVE_MODE, true)
            earThreshold   = prefs!!.getFloat(PREF_EAR_THRESHOLD, 0.22f)
            marThreshold   = prefs!!.getFloat(PREF_MAR_THRESHOLD, 0.60f)
            alertFrameCount = prefs!!.getInt(PREF_ALERT_FRAMES, 8)

            _isAdaptiveModeEnabled.value = savedAdaptiveMode
            if (savedBaseline > 0f) {
                _baselineEar.value = savedBaseline
            }
        }
        if (db == null) {
            val database = FatigueDatabase.getInstance(appCtx)
            db = database
            dbJob?.cancel()
            dbJob = managerScope.launch {
                database.fatigueEventDao().observeAll().collect { entities ->
                    _logEvents.value = entities.map { it.toFatigueEvent() }
                }
            }
        }
    }

    /** Clears all accumulated log events from Room DB and in-memory state. */
    fun clearLogs(context: Context? = null) {
        managerScope.launch(Dispatchers.IO) {
            context?.let { init(it) }
            db?.fatigueEventDao()?.deleteAll()
            Log.i(TAG, "Log events cleared")
        }
    }

    /** Resets all thresholds, adaptive settings, and baseline calibration to default values. */
    fun resetToDefaults() {
        earThreshold = 0.22f
        marThreshold = 0.60f
        alertFrameCount = 8
        setAdaptiveMode(true)
        resetCalibration()
        Log.i(TAG, "All calibration settings reset to default values")
    }

    // ── Runtime-adjustable thresholds (manual mode) ───────────────────────────

    private val _earThreshold = MutableStateFlow(0.22f)
    val earThresholdFlow: StateFlow<Float> = _earThreshold

    var earThreshold: Float = 0.22f
        set(value) {
            field = value
            _earThreshold.value = value
            prefs?.edit()?.putFloat(PREF_EAR_THRESHOLD, value)?.apply()
            if (!_isAdaptiveModeEnabled.value) {
                analyzer?.setManualMode(value)
            }
        }

    private val _marThreshold = MutableStateFlow(0.60f)
    val marThresholdFlow: StateFlow<Float> = _marThreshold

    var marThreshold: Float = 0.60f
        set(value) {
            field = value
            _marThreshold.value = value
            prefs?.edit()?.putFloat(PREF_MAR_THRESHOLD, value)?.apply()
            analyzer?.marThreshold = value
        }

    private val _alertFrameCount = MutableStateFlow(8)
    val alertFrameCountFlow: StateFlow<Int> = _alertFrameCount

    var alertFrameCount: Int = 8
        set(value) {
            field = value
            _alertFrameCount.value = value
            prefs?.edit()?.putInt(PREF_ALERT_FRAMES, value)?.apply()
            analyzer?.alertFrameCount = value
        }

    // ── Adaptive mode toggle ──────────────────────────────────────────────────

    fun setAdaptiveMode(enabled: Boolean) {
        _isAdaptiveModeEnabled.value = enabled
        prefs?.edit()?.putBoolean(PREF_ADAPTIVE_MODE, enabled)?.apply()
        if (enabled) {
            analyzer?.setAdaptiveMode()
        } else {
            analyzer?.setManualMode(earThreshold)
        }
        Log.i(TAG, "Adaptive mode ${if (enabled) "enabled" else "disabled"}")
    }

    /** Clears the saved baseline and re-starts the warmup window. */
    fun resetCalibration() {
        prefs?.edit()?.remove(PREF_BASELINE_EAR)?.apply()
        _baselineEar.value    = 0f
        _warmupProgress.value = 0f
        analyzer?.resetCalibration()
        Log.i(TAG, "Calibration reset requested")
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start(context: Context) {
        check(retirement?.isCompleted != false) { "Previous monitoring pipeline is still stopping." }
        val enterpriseSession = EnterpriseAuth.session.value ?: return
        if (EspConnection.verifiedId.value != enterpriseSession.assignment.deviceId) return
        if (_isMonitoring.value) {
            Log.w(TAG, "start() called while already monitoring — ignored")
            return
        }
        _isMonitoring.value = true
        Log.i(TAG, "Starting monitoring pipeline")

        init(context)
        DetectionJournal.start(enterpriseSession)
        Esp32AlertSender.startSession(context)
        val appCtx = context.applicationContext

        val s = MjpegStreamer { EspConnection.network }
        val a = FatigueAnalyzer(
            context             = appCtx,
            earThreshold        = earThreshold,
            marThreshold        = marThreshold,
            alertFrameCount     = alertFrameCount,
            adaptiveModeEnabled = _isAdaptiveModeEnabled.value,
            dropRatio           = 0.30f,
            priorBaselineEar    = _baselineEar.value,
        )
        if (!_isAdaptiveModeEnabled.value) {
            a.setManualMode(earThreshold)
        }
        streamer  = s
        analyzer  = a

        // ESP traffic uses its local route; phone-hotspot mode uses unbound routing.
        EspNetwork.network = EspConnection.network

        streamJob = managerScope.launch {
            // 1. MJPEG stream
            launch { s.startStream { EspConnection.streamUrl(appCtx, enterpriseSession) } }

            // 2. Pipe frames into analyzer (non-blocking for full-speed display)
            val isProcessing = AtomicBoolean(false)
            launch {
                s.frames.collect { bmp ->
                    _frames.value = bmp
                    if (bmp != null && isProcessing.compareAndSet(false, true)) {
                        launch(Dispatchers.Default) {
                            try {
                                a.processFrame(bmp)
                                a.lastObservation?.let { DetectionJournal.frame(enterpriseSession.sessionId, it) }
                                    ?: DetectionJournal.gap(enterpriseSession.sessionId)
                            } finally {
                                isProcessing.set(false)
                            }
                        }
                    }
                }
            }

            // 3. Forward stream status & stream FPS
            launch { s.streamStatus.collect {
                if (!_isMonitoring.value) return@collect
                _streamStatus.value = it
                if (it == StreamStatus.LOST) { EspConnection.blockCommands(); DetectionJournal.gap(enterpriseSession.sessionId) }
            } }
            launch { s.streamFps.collect { _streamFps.value = it } }

            // 4. Forward analysis results + persist baseline + accumulate log events
            launch {
                a.result.collect { result ->
                    _analysisResult.value = result

                    // Mirror calibration telemetry to manager StateFlows
                    _warmupProgress.value = result.warmupProgress
                    if (result.baselineEar > 0f && result.baselineEar != _baselineEar.value) {
                        _baselineEar.value = result.baselineEar
                        prefs?.edit()?.putFloat(PREF_BASELINE_EAR, result.baselineEar)?.apply()
                        Log.i(TAG, "Baseline EAR persisted: ${result.baselineEar}")
                    }

                    val isAlertNow = result.isFatigueAlert
                    val now = System.currentTimeMillis()

                    managerScope.launch {
                      alertMutex.withLock {
                    if (isAlertNow && !wasAlertActive) {
                        // Rising edge — alert just started
                        alertStartMs   = now
                        alertStartEar  = result.ear
                        wasAlertActive = true
                        _sessionAlertCount.value++
                        // Signal ESP32 buzzer + red LED (dormant until isEnabled = true)
                        _activeAlertState.value = ActiveAlertState.EAR_FATIGUE
                        launch { Esp32AlertSender.onAlertRisingEdge().await() }
                    } else if (!isAlertNow && wasAlertActive) {
                        // Falling edge — alert cleared; persist event
                        val durationMs = now - alertStartMs
                        val event = FatigueEvent(
                            timestampMs = alertStartMs,
                            durationMs  = durationMs,
                            earValue    = alertStartEar,
                            severity    = FatigueEventSeverity.FATIGUE_ALERT,
                            triggerType = AlertTriggerType.EAR,
                        )
                        persistAndEmitEvent(event)
                        wasAlertActive = false
                        Log.i(TAG, "Fatigue event logged: duration=${durationMs}ms ear=${alertStartEar}")
                        // Hardware clear is emitted only after the active-state hold.
                    }

                    // Yawn alert (single-fire, non-edge-tracked for simplicity)
                    if (result.isYawnAlert && !wasYawnActive) {
                        wasYawnActive = true
                        yawnStartMs = now
                        yawnStartEar = result.ear
                        _sessionYawnCount.value++
                        val marFatigueGroup = result.yawnsInWindow / 3
                        if (!isAlertNow && result.isYawnFatigueTriggered &&
                            marFatigueGroup > lastMarFatigueGroupSent &&
                            _activeAlertState.value != ActiveAlertState.EAR_FATIGUE) {
                            _activeAlertState.value = ActiveAlertState.MAR_FATIGUE
                            lastMarFatigueGroupSent = marFatigueGroup
                            launch { Esp32AlertSender.onYawnFatigue().await() }
                        } else if (!isAlertNow &&
                            _activeAlertState.value != ActiveAlertState.EAR_FATIGUE) {
                            _activeAlertState.value = ActiveAlertState.TIER1_YAWN
                            launch { Esp32AlertSender.onYawnAlert().await() }
                        }
                    } else if (!result.isYawnAlert) {
                        if (wasYawnActive) {
                            persistAndEmitEvent(
                                FatigueEvent(
                                    timestampMs = yawnStartMs,
                                    durationMs = (now - yawnStartMs).coerceAtLeast(0L),
                                    earValue = yawnStartEar,
                                    severity = FatigueEventSeverity.WARNING,
                                    triggerType = AlertTriggerType.MAR,
                                )
                            )
                        }
                        wasYawnActive = false
                    }
                    val marFatigueGroup = result.yawnsInWindow / 3
                    if (result.isYawnFatigueTriggered &&
                        marFatigueGroup > lastMarFatigueGroupSent &&
                        !isAlertNow && _activeAlertState.value != ActiveAlertState.EAR_FATIGUE) {
                        Log.i(TAG, "MAR fatigue threshold reached: ${result.yawnsInWindow} yawns in 60s; sending yawn_fatigue")
                        _activeAlertState.value = ActiveAlertState.MAR_FATIGUE
                        lastMarFatigueGroupSent = marFatigueGroup
                        launch { Esp32AlertSender.onYawnFatigue().await() }
                    }
                    wasYawnFatigueTriggered = result.isYawnFatigueTriggered
                    if (result.yawnsInWindow < 3) {
                        lastMarFatigueGroupSent = 0
                    }
                    // Serialized alert state machine: EAR wins immediately. Recovery
                    // is deliberately held, and EAR->MAR requires a fresh 1.5 s yawn.
                    val current = _activeAlertState.value
                    when {
                        isAlertNow && current != ActiveAlertState.EAR_FATIGUE -> {
                            _activeAlertState.value = ActiveAlertState.EAR_FATIGUE
                            stateAbsentSince = 0L
                            launch { Esp32AlertSender.onAlertRisingEdge().await() }
                        }
                        !isAlertNow && result.isYawnFatigueTriggered &&
                            current == ActiveAlertState.EAR_FATIGUE &&
                            marFatigueGroup > lastMarFatigueGroupSent -> {
                            // The third yawn may have occurred while EAR was
                            // active. Dispatch MAR immediately after EAR clears;
                            // do not require a second yawn-trigger edge.
                            _activeAlertState.value = ActiveAlertState.MAR_FATIGUE
                            marRecoveryCandidateAt = 0L
                            stateAbsentSince = 0L
                            Log.i(TAG, "EAR cleared with MAR fatigue already triggered; sending yawn_fatigue")
                            lastMarFatigueGroupSent = marFatigueGroup
                            launch { Esp32AlertSender.onYawnFatigue().await() }
                        }
                        !isAlertNow && result.isYawnAlert &&
                            current == ActiveAlertState.EAR_FATIGUE -> {
                            if (marRecoveryCandidateAt == 0L) marRecoveryCandidateAt = now
                            if (now - marRecoveryCandidateAt >= EAR_TO_MAR_DEBOUNCE_MS) {
                                _activeAlertState.value = ActiveAlertState.MAR_FATIGUE
                                marRecoveryCandidateAt = 0L
                                stateAbsentSince = 0L
                                lastMarFatigueGroupSent = marFatigueGroup
                                launch { Esp32AlertSender.onYawnFatigue().await() }
                            }
                        }
                        !isAlertNow && !result.isYawnAlert &&
                            current != ActiveAlertState.NONE -> {
                            marRecoveryCandidateAt = 0L
                            if (current == ActiveAlertState.EAR_FATIGUE) {
                                _activeAlertState.value = ActiveAlertState.NONE
                                stateAbsentSince = 0L
                                Log.i(TAG, "EAR recovered with no MAR alert; clearing ESP32 immediately")
                                launch { Esp32AlertSender.onAlertFallingEdge().await() }
                            } else {
                                if (stateAbsentSince == 0L) stateAbsentSince = now
                                if (now - stateAbsentSince >= ACTIVE_HOLD_MS) {
                                    _activeAlertState.value = ActiveAlertState.NONE
                                    stateAbsentSince = 0L
                                    launch { Esp32AlertSender.onAlertFallingEdge().await() }
                                }
                            }
                        }
                        isAlertNow -> {
                            stateAbsentSince = 0L
                            marRecoveryCandidateAt = 0L
                        }
                    }
                      }
                    }
                }
            }
        }

        // 5. Independent watchdog — fires even if the ML pipeline is stuck
        watchdogJob = managerScope.launch {
            while (isActive) {
                delay(WATCHDOG_POLL_MS)
                val lastFrame = s.lastFrameTimeMs
                if (lastFrame > 0 &&
                    SystemClock.elapsedRealtime() - lastFrame > WATCHDOG_GAP_MS &&
                    _streamStatus.value == StreamStatus.STREAMING
                ) {
                    Log.w(TAG, "Watchdog: no frame for ${WATCHDOG_GAP_MS}ms — marking LOST")
                    _streamStatus.value = StreamStatus.LOST
                    DetectionJournal.gap(enterpriseSession.sessionId)
                } else if (lastFrame > 0 &&
                    SystemClock.elapsedRealtime() - lastFrame <= WATCHDOG_GAP_MS &&
                    s.streamStatus.value == StreamStatus.STREAMING) {
                    _streamStatus.value = StreamStatus.STREAMING
                }
            }
        }
    }

    fun stop() {
        if (!_isMonitoring.value) {
            Log.w(TAG, "stop() called while not monitoring — ignored")
            return
        }
        Log.i(TAG, "Stopping monitoring pipeline")
        _isMonitoring.value = false

        val stoppingJob = streamJob
        val stoppingWatchdog = watchdogJob
        val stoppingAnalyzer = analyzer
        stoppingJob?.cancel()
        streamer?.stopStream()
        watchdogJob?.cancel()
        streamJob   = null
        watchdogJob = null

        // Cancellation cannot interrupt native Interpreter.run(). Join every
        // pipeline child before releasing the interpreter and face detector.
        retirement = managerScope.launch {
            stoppingWatchdog?.join()
            retirePipeline(stoppingJob) { stoppingAnalyzer?.close() }
            Log.i(TAG, "Monitoring resources released after inference completed")
        }
        streamer = null
        analyzer = null

        _frames.value         = null
        _analysisResult.value = AnalysisResult()
        _streamStatus.value   = StreamStatus.LOST
        _sessionAlertCount.value = 0
        _sessionYawnCount.value = 0
        wasYawnFatigueTriggered = false
        lastMarFatigueGroupSent = 0
        _activeAlertState.value = ActiveAlertState.NONE
        Esp32AlertSender.stopSession()
        Esp32AlertSender.sendForcedClear()

        if (wasYawnActive) {
            val now = System.currentTimeMillis()
            val event = FatigueEvent(
                timestampMs = yawnStartMs,
                durationMs = (now - yawnStartMs).coerceAtLeast(0L),
                earValue = yawnStartEar,
                severity = FatigueEventSeverity.WARNING,
                triggerType = AlertTriggerType.MAR,
            )
            managerScope.launch { persistAndEmitEvent(event) }
            wasYawnActive = false
        }

        // If an alert was still active when monitoring stopped, close it out
        if (wasAlertActive) {
            val now = System.currentTimeMillis()
            val event = FatigueEvent(
                timestampMs = alertStartMs,
                durationMs  = now - alertStartMs,
                earValue    = alertStartEar,
                severity    = FatigueEventSeverity.FATIGUE_ALERT,
                triggerType = AlertTriggerType.EAR,
            )
            managerScope.launch { persistAndEmitEvent(event) }
            wasAlertActive = false
        }

        unbindWifiNetwork()
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private suspend fun persistAndEmitEvent(event: FatigueEvent) {
        // Write to Room DB
        db?.fatigueEventDao()?.insert(
            FatigueEventEntity(
                timestampMs = event.timestampMs,
                durationMs  = event.durationMs,
                earValue    = event.earValue,
                severity    = event.severity.name,
                triggerType = event.triggerType.name,
            )
        )
        // Update in-memory StateFlow (newest first)
        _logEvents.value = listOf(event) + _logEvents.value
    }

    // ── WiFi AP binding ───────────────────────────────────────────────────────

    private fun unbindWifiNetwork() { EspNetwork.network = null }
}

// ── Extension: FatigueEventEntity → FatigueEvent ─────────────────────────────

private fun FatigueEventEntity.toFatigueEvent() = FatigueEvent(
    timestampMs = timestampMs,
    durationMs  = durationMs,
    earValue    = earValue,
    severity    = toSeverityEnum(),
    triggerType = runCatching { AlertTriggerType.valueOf(triggerType) }.getOrDefault(AlertTriggerType.EAR),
)
