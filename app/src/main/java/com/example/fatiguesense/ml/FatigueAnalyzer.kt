package com.example.fatiguesense.enterprise.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.DataType
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

// ─── Calibration Enums ────────────────────────────────────────────────────────

enum class CalibrationState {
    /** Collecting qualified frames to derive the personal baseline EAR. */
    WARMING_UP,
    /** Baseline established; adaptive thresholding is active. */
    READY,
    /** User has overridden with manual slider values. */
    MANUAL,
}

enum class FrameQuality {
    /** Frame passed all quality checks and was accepted. */
    OK,
    /** No face was detected. */
    NO_FACE,
    /** Turned head: landmark monitoring continues; baseline collection pauses. */
    HEAD_TURNED,
    /** Edge crop: padded inference continues; baseline collection pauses. */
    FACE_CLIPPED,
    /** Frame would have been accepted, but is still in warmup phase. */
    WARMING_UP_ACCEPTED,
    UNRELIABLE_EYES,
    INFERENCE_ERROR,
}

// ─── Result Model ─────────────────────────────────────────────────────────────

data class AnalysisResult(
    val boundingBox: Rect? = null,
    val landmarks: List<PointF> = emptyList(),
    val fps: Int = 0,
    val ear: Float = 0f,
    val mar: Float = 0f,
    val earValid: Boolean = false,
    val marValid: Boolean = false,
    val eyeSelection: LandmarkModel.EyeSelection = LandmarkModel.EyeSelection.NONE,
    val headYaw: Float = 0f,
    val isFatigueAlert: Boolean = false,
    val isYawnAlert: Boolean = false,
    val yawnsInWindow: Int = 0,
    val isYawnFatigueTriggered: Boolean = false,
    val isYawnActive: Boolean = false,
    // ── Calibration metadata ───────────────────────────────────────────────
    val calibrationState: CalibrationState = CalibrationState.WARMING_UP,
    val warmupProgress: Float = 0f,            // 0.0 → 1.0
    val qualityStatus: FrameQuality = FrameQuality.NO_FACE,
    val baselineEar: Float = 0f,               // µEAR after trimmed mean
    val computedEarThreshold: Float = 0f,      // active threshold (adaptive or manual)
    // ── PERCLOS ──────────────────────────────────────────────────
    val perclos: Float = 0f,                   // 0.0 → 1.0 (fraction of closed-eye frames)
)

// ─── FatigueAnalyzer ──────────────────────────────────────────────────────────

/**
 * Runs ML Kit face detection + custom TFLite landmark inference on each frame.
 *
 * ModelProfile selects the matching asset, crop and formulas.
 * Coordinates32 layout: 32 points in iBUG order 36..67.
 *   Points 0–5 and 6–11 are the two eyes; points 12–31 are the mouth.
 * Input is planar RGB float32 [1,3,192,192]; output is [1,32,2].
 * Side views run inference. Eye selection matches the Python geometry heuristic;
 * it is not a visibility score. Only baseline collection requires a frontal face.
 *
 * Adaptive calibration pipeline:
 *   1. Calibration gate: pause baseline collection for turned/clipped faces.
 *      Landmark inference and eligible eye/mouth monitoring continue.
 *   2. Warmup Accumulator (#2): collect WARMUP_TARGET qualified EAR samples, then compute
 *      baseline via trimmed mean (drop bottom 10%, top 5%).
 *   3. Relative-Drop Threshold (#4): earThreshold = baselineEar * (1 − dropRatio).
 *      In MANUAL mode, the caller-supplied earThreshold is used as-is.
 */
class FatigueAnalyzer(
    private val context: Context,
    var earThreshold: Float = 0.22f,
    var marThreshold: Float = 0.60f,
    var alertFrameCount: Int = 8,
    /** When true, auto-derive earThreshold from the personal open-eye baseline. */
    var adaptiveModeEnabled: Boolean = true,
    /** Fraction of the baseline EAR below which fatigue is flagged (default = 30 % drop). */
    var dropRatio: Float = 0.30f,
    /** Pre-existing baseline from a previous session (0f = no prior baseline). */
    var priorBaselineEar: Float = 0f,
    /** Number of consecutive qualified windows required to raise a yawn. */
    var yawnStreakRequired: Int = MIN_YAWN_FRAMES,
    var yawnRecoveryThreshold: Float = marThreshold - YAWN_RESET_HYSTERESIS_DELTA,
) {
    private val _result = MutableStateFlow(AnalysisResult())
    val result: StateFlow<AnalysisResult> = _result

    // ── ML Kit face detector — FAST mode ─────────────────────────────────────
    // Euler angles (headEulerAngleX/Y/Z) are returned by ML Kit for every
    // detected Face regardless of classification mode.
    private val faceDetectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .build()

    private val faceDetector = FaceDetection.getClient(faceDetectorOptions)

    private var interpreter: Interpreter? = null
    private val inputBuffer = ByteBuffer.allocateDirect(LandmarkModel.INPUT_BYTES)
        .order(ByteOrder.nativeOrder())
    private val outputArray = Array(1) { Array(LandmarkModel.POINT_COUNT) { FloatArray(2) } }
    private var framePixels = IntArray(0)

    // ── FPS tracking ─────────────────────────────────────────────────────────
    private var lastFrameTime = System.currentTimeMillis()
    private var frameCount = 0
    private var currentFps = 0

    // ── Hysteresis counters ───────────────────────────────────────────────────
    private var consecutiveLowEarFrames = 0
    private var consecutiveYawnFrames = 0
    private var yawnActive = false
    private val yawnTimestampsMs = ArrayDeque<Long>()

    // ── Adaptive calibration state ────────────────────────────────────────────
    companion object {
        private const val TAG = "FatigueAnalyzer"

        /** Number of qualified samples needed to lock in a baseline. */
        const val WARMUP_TARGET = 150

        /**
         * PERCLOS rolling window size in frames.
         * At ~20 FPS this is ~60 seconds of data — the standard academic window.
         * Adjust down (e.g. 300) for quicker response at lower FPS.
         */
        const val PERCLOS_WINDOW = 1200

        /** Hard minimum floor for computed EAR threshold to prevent un-alertable low threshold. */
        const val MIN_EAR_THRESHOLD_FLOOR = 0.18f

        /** Minimum EAR required to consider a sample open-eyed for baseline warmup (filters blinks/closed eyes). */
        const val MIN_WARMUP_EAR = 0.18f
        const val YAWN_WINDOW_MS = 60_000L
        const val YAWN_ALERT_THRESHOLD = 3
        const val MIN_YAWN_FRAMES = 3
        const val YAWN_RESET_HYSTERESIS_DELTA = 0.08f


    }

    private val warmupBuffer = mutableListOf<Float>()   // raw qualified EAR samples
    private var baselineEar: Float = priorBaselineEar   // µEAR after trimmed mean
    private var calibrationState: CalibrationState =
        if (priorBaselineEar > 0f) CalibrationState.READY else CalibrationState.WARMING_UP

    // Effective EAR threshold — recalculated whenever baseline changes
    private var computedEarThreshold: Float = earThreshold

    // ── PERCLOS rolling window ─────────────────────────────────────────────
    // Stores 1 (closed) or 0 (open) for each recent qualified frame.
    // When the buffer is full, oldest entry is evicted (circular via ArrayDeque).
    private val perclosWindow = ArrayDeque<Int>(PERCLOS_WINDOW)

    init {
        if (priorBaselineEar > 0f) {
            computedEarThreshold = if (adaptiveModeEnabled)
                calculateAdaptiveThreshold(priorBaselineEar)
            else
                earThreshold
            Log.i(TAG, "Restored prior baseline EAR=$priorBaselineEar → threshold=$computedEarThreshold")
        }
        initializeInterpreter()
    }

    private fun calculateAdaptiveThreshold(rawBaseline: Float): Float {
        // Adaptive mode must not become less sensitive than the configured
        // threshold; the baseline-derived value is an automatic floor.
        return maxOf(
            rawBaseline * (1f - dropRatio),
            earThreshold,
            MIN_EAR_THRESHOLD_FLOOR,
        )
    }

    // ── Reset warmup (called when user presses "Re-calibrate") ────────────────
    fun resetCalibration() {
        warmupBuffer.clear()
        perclosWindow.clear()
        baselineEar = 0f
        computedEarThreshold = earThreshold
        calibrationState = CalibrationState.WARMING_UP
        consecutiveLowEarFrames = 0
        consecutiveYawnFrames = 0
        yawnActive = false
        yawnTimestampsMs.clear()
        Log.i(TAG, "Calibration reset — entering WARMING_UP")
    }

    // ── Update manual threshold (called from slider) ──────────────────────────
    fun setManualMode(threshold: Float) {
        adaptiveModeEnabled = false
        calibrationState = CalibrationState.MANUAL
        earThreshold = threshold
        computedEarThreshold = threshold
    }

    // ── Re-enable adaptive mode (called when toggle flipped back) ────────────
    fun setAdaptiveMode() {
        adaptiveModeEnabled = true
        if (baselineEar > 0f) {
            calibrationState = CalibrationState.READY
            computedEarThreshold = calculateAdaptiveThreshold(baselineEar)
        } else {
            calibrationState = CalibrationState.WARMING_UP
        }
    }

    private fun initializeInterpreter() {
        // CPU avoids GPU delegate thread affinity across coroutine dispatchers.
        // This model has only built-in LiteRT operators; no Flex runtime is needed.
        val candidate = Interpreter(loadModelFile(context, ModelProfile.asset),
            Interpreter.Options().apply { numThreads = 4 })
        try {
            val input = candidate.getInputTensor(0)
            val output = candidate.getOutputTensor(0)
            require(input.dataType() == DataType.FLOAT32 &&
                input.shape().contentEquals(ModelProfile.inputShape)) {
                "Unexpected landmark input: ${input.shape().contentToString()} ${input.dataType()}"
            }
            require(output.dataType() == DataType.FLOAT32 &&
                output.shape().contentEquals(ModelProfile.outputShape)) {
                "Unexpected landmark output: ${output.shape().contentToString()} ${output.dataType()}"
            }
            interpreter = candidate
            Log.i(TAG, "Loaded ${ModelProfile.label}: ${ModelProfile.asset}, CPU (4 threads)")
        } catch (e: Exception) {
            candidate.close()
            throw e
        }
    }

    private fun loadModelFile(context: Context, filename: String): ByteBuffer =
        context.assets.openFd(filename).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY,
                    descriptor.startOffset, descriptor.declaredLength)
            }
        }

    var lastObservation: AnalysisResult? = null
        private set

    suspend fun processFrame(bitmap: Bitmap) = withContext(Dispatchers.Default) {
        lastObservation = null
        // FPS calculation
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFrameTime >= 1000) {
            currentFps = frameCount
            frameCount = 0
            lastFrameTime = now
        }

        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val faces = Tasks.await(faceDetector.process(image))

            if (faces.isEmpty()) {
                consecutiveLowEarFrames = 0
                consecutiveYawnFrames = 0
                yawnActive = false
                val yawnsInWindow = activeYawnsInWindow(now)
                _result.value = AnalysisResult(
                    fps              = currentFps,
                    calibrationState = calibrationState,
                    warmupProgress   = warmupProgress(),
                    qualityStatus    = FrameQuality.NO_FACE,
                    baselineEar      = baselineEar,
                    computedEarThreshold = computedEarThreshold,
                    yawnsInWindow    = yawnsInWindow,
                    isYawnFatigueTriggered = yawnsInWindow >= YAWN_ALERT_THRESHOLD,
                )
                lastObservation = _result.value
                return@withContext
            }

            // Legacy selects by width; coordinates32 matches Python largest-area selection.
            val face = faces.maxByOrNull {
                it.boundingBox.width().toLong() * (if (ModelProfile.legacy) 1 else it.boundingBox.height())
            }!!
            val boundingBox = face.boundingBox

            val eligibility = LandmarkModel.frameEligibility(
                boundingBox.left.toFloat(), boundingBox.top.toFloat(),
                boundingBox.right.toFloat(), boundingBox.bottom.toFloat(),
                bitmap.width, bitmap.height,
                face.headEulerAngleY, face.headEulerAngleX, face.headEulerAngleZ,
            )
            if (!eligibility.runLandmarks || (ModelProfile.legacy && !eligibility.calibrate)) {
                consecutiveLowEarFrames = 0
                consecutiveYawnFrames = 0
                yawnActive = false
                _result.value = AnalysisResult(fps = currentFps,
                    calibrationState = calibrationState, warmupProgress = warmupProgress(),
                    boundingBox = boundingBox, baselineEar = baselineEar,
                    computedEarThreshold = computedEarThreshold,
                    qualityStatus = if (eligibility.headTurned) FrameQuality.HEAD_TURNED else FrameQuality.FACE_CLIPPED)
                lastObservation = _result.value
                return@withContext
            }

            val modelPoints = try {
                if (ModelProfile.legacy) {
                    LegacyLandmarkModel.infer(bitmap, boundingBox, checkNotNull(interpreter))
                } else {
                    val crop = LandmarkModel.crop(
                        boundingBox.left.toFloat(), boundingBox.top.toFloat(),
                        boundingBox.right.toFloat(), boundingBox.bottom.toFloat(),
                    )
                    val pixelCount = bitmap.width * bitmap.height
                    if (framePixels.size != pixelCount) framePixels = IntArray(pixelCount)
                    bitmap.getPixels(framePixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    LandmarkModel.writeInput(framePixels, bitmap.width, bitmap.height, crop, inputBuffer)
                    checkNotNull(interpreter) { "Landmark interpreter is unavailable" }
                        .run(inputBuffer, outputArray)
                    LandmarkModel.decode(outputArray[0], crop)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Landmark inference failed", e)
                consecutiveLowEarFrames = 0
                consecutiveYawnFrames = 0
                yawnActive = false
                _result.value = AnalysisResult(boundingBox = boundingBox, fps = currentFps,
                    qualityStatus = FrameQuality.INFERENCE_ERROR,
                    calibrationState = calibrationState, warmupProgress = warmupProgress())
                return@withContext
            }
            val pts = modelPoints.map { PointF(it.x, it.y) }
            val cropPoints = if (ModelProfile.legacy) emptyList() else outputArray[0].map {
                LandmarkModel.Point(it[0] * (LandmarkModel.SIZE - 1), it[1] * (LandmarkModel.SIZE - 1))
            }
            val selectedEye = if (ModelProfile.legacy) {
                LandmarkModel.SelectedEye(LegacyLandmarkModel.ear(modelPoints), LandmarkModel.EyeSelection.BOTH)
            } else LandmarkModel.selectEyes(
                LandmarkModel.assessEye(cropPoints, modelPoints, 0, bitmap.width, bitmap.height),
                LandmarkModel.assessEye(cropPoints, modelPoints, 6, bitmap.width, bitmap.height),
            )
            // Keep unavailable UI values finite; validity controls alerts and PERCLOS.
            val avgEAR = if (selectedEye.valid) selectedEye.ear else 0f
            val marValid = ModelProfile.legacy || LandmarkModel.validMouth(cropPoints, modelPoints, bitmap.width, bitmap.height)
            val mar = if (ModelProfile.legacy) LegacyLandmarkModel.mar(modelPoints) else if (marValid) LandmarkModel.mouthAspectRatio(cropPoints) else 0f

            // ── Warmup Accumulator #2 ────────────────────────────────────────
            // Only accumulate during WARMING_UP if avgEAR >= MIN_WARMUP_EAR (filters blinks & closed eyes)
            var qualityStatus = when {
                !selectedEye.valid -> FrameQuality.UNRELIABLE_EYES
                eligibility.headTurned -> FrameQuality.HEAD_TURNED
                eligibility.faceClipped -> FrameQuality.FACE_CLIPPED
                else -> FrameQuality.OK
            }
            if (calibrationState == CalibrationState.WARMING_UP && adaptiveModeEnabled) {
                if (eligibility.calibrate && selectedEye.selection == LandmarkModel.EyeSelection.BOTH &&
                    selectedEye.valid && avgEAR >= MIN_WARMUP_EAR) {
                    warmupBuffer.add(avgEAR)
                    qualityStatus = FrameQuality.WARMING_UP_ACCEPTED

                    if (warmupBuffer.size >= WARMUP_TARGET) {
                        // ── Trimmed mean: drop bottom 10%, top 5% ────────────────
                        val sorted = warmupBuffer.sorted()
                        val dropLow  = (sorted.size * 0.10f).toInt()
                        val dropHigh = (sorted.size * 0.05f).toInt()
                        val trimmed  = sorted.drop(dropLow).dropLast(dropHigh)
                        baselineEar = trimmed.average().toFloat()

                        computedEarThreshold = calculateAdaptiveThreshold(baselineEar)
                        calibrationState     = CalibrationState.READY
                        Log.i(TAG, "Warmup complete — baseline=${"%.4f".format(baselineEar)}" +
                            " threshold=${"%.4f".format(computedEarThreshold)}" +
                            " (samples=${warmupBuffer.size}, dropLow=$dropLow, dropHigh=$dropHigh)")
                    }
                }
            }

            // ── Hysteresis & Alert Evaluation ─────────────────────────────────
            // During WARMING_UP (before baseline is established), fall back to manual earThreshold
            // so fatigue alerts and ESP32 buzzer triggers still operate safely.
            val activeThreshold = if (calibrationState == CalibrationState.WARMING_UP && baselineEar <= 0f)
                earThreshold
            else
                computedEarThreshold

            val eyeClosed = selectedEye.valid && avgEAR < activeThreshold && (!ModelProfile.legacy || avgEAR > 0f)
            if (eyeClosed) consecutiveLowEarFrames++ else consecutiveLowEarFrames = 0
            // MAR qualification is frame-hysteretic, but yawn frequency is
            // explicitly time-based so it is stable across camera frame rates.
            val nowMs = System.currentTimeMillis()
            val smoothedMar = mar
            if (!marValid) {
                consecutiveYawnFrames = 0
                yawnActive = false
            } else if (smoothedMar > marThreshold) {
                consecutiveYawnFrames++
                if (consecutiveYawnFrames >= yawnStreakRequired) {
                    if (!yawnActive) {
                        yawnActive = true
                        yawnTimestampsMs.addLast(nowMs)
                    }
                    consecutiveYawnFrames = yawnStreakRequired
                }
            } else if (smoothedMar < (marThreshold - YAWN_RESET_HYSTERESIS_DELTA)) {
                consecutiveYawnFrames = 0
                yawnActive = false
            }
            val yawnsInWindow = activeYawnsInWindow(nowMs)

            val isFatigueAlert = consecutiveLowEarFrames >= alertFrameCount
            val isYawnAlert = yawnActive
            val isYawnFatigueTriggered = yawnsInWindow >= YAWN_ALERT_THRESHOLD

            // ── PERCLOS rolling window update ────────────────────────────
            var currentPerclos = 0f
            if (selectedEye.valid) {
                if (perclosWindow.size >= PERCLOS_WINDOW) perclosWindow.removeFirst()
                perclosWindow.addLast(if (eyeClosed) 1 else 0)
            }
            currentPerclos = if (perclosWindow.isNotEmpty())
                perclosWindow.sum().toFloat() / perclosWindow.size
            else 0f

            _result.value = AnalysisResult(
                boundingBox          = boundingBox,
                landmarks            = pts,
                fps                  = currentFps,
                ear                  = avgEAR,
                mar                  = mar,
                earValid             = selectedEye.valid,
                marValid             = marValid,
                eyeSelection         = selectedEye.selection,
                headYaw              = face.headEulerAngleY,
                isFatigueAlert       = isFatigueAlert,
                isYawnAlert          = isYawnAlert,
                yawnsInWindow        = yawnsInWindow,
                isYawnFatigueTriggered = isYawnFatigueTriggered,
                isYawnActive          = yawnActive,
                calibrationState     = calibrationState,
                warmupProgress       = warmupProgress(),
                qualityStatus        = qualityStatus,
                baselineEar          = baselineEar,
                computedEarThreshold = computedEarThreshold,
                perclos              = currentPerclos,
            )
            lastObservation = _result.value

        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
        }
    }

    /** Returns warmup progress as a fraction [0.0, 1.0]. */
    private fun activeYawnsInWindow(nowMs: Long): Int {
        while (yawnTimestampsMs.isNotEmpty() &&
            nowMs - yawnTimestampsMs.first() >= YAWN_WINDOW_MS) {
            yawnTimestampsMs.removeFirst()
        }
        return yawnTimestampsMs.size
    }

    private fun warmupProgress(): Float =
        if (calibrationState == CalibrationState.READY || calibrationState == CalibrationState.MANUAL)
            1f
        else
            (warmupBuffer.size.toFloat() / WARMUP_TARGET).coerceIn(0f, 1f)

    fun close() {
        interpreter?.close()
        faceDetector.close()
        Log.i(TAG, "FatigueAnalyzer closed")
    }
}
