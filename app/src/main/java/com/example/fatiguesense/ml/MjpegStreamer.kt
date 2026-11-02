package com.example.fatiguesense.enterprise.ml

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.net.Network
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL

// ─── Stream Status ────────────────────────────────────────────────────────────

enum class StreamStatus { CONNECTING, STREAMING, LOST }

// ─── MjpegStreamer ────────────────────────────────────────────────────────────

/**
 * Pure coroutine-based MJPEG parser using HttpURLConnection.
 * Lifecycle-safe (no SurfaceView threading issues).
 * On error, waits 2 seconds then automatically reconnects while [isStreaming] is true.
 */
class MjpegStreamer(private val networkProvider: () -> Network?) {

    private val _frames = MutableStateFlow<Bitmap?>(null)
    val frames: StateFlow<Bitmap?> = _frames

    private val _streamStatus = MutableStateFlow(StreamStatus.CONNECTING)
    val streamStatus: StateFlow<StreamStatus> = _streamStatus

    private val _streamFps = MutableStateFlow(0)
    val streamFps: StateFlow<Int> = _streamFps

    private var streamFrameCount = 0
    private var lastStreamFpsTime = SystemClock.elapsedRealtime()

    /** Updated each time a valid JPEG frame is decoded. Used by the watchdog. */
    @Volatile var lastFrameTimeMs: Long = 0L
        private set

    @Volatile private var isStreaming = false
    @Volatile private var activeConnection: HttpURLConnection? = null

    fun reconnect() {
        activeConnection?.disconnect()
    }

    /**
     * Starts the MJPEG receive loop. Suspends until [stopStream] is called.
     * Must be launched in a coroutine with [Dispatchers.IO].
     */
    suspend fun startStream(streamUrl: suspend () -> String) {
        isStreaming = true
        withContext(Dispatchers.IO) {
            while (isStreaming && currentCoroutineContext().isActive) {
                _streamStatus.value = StreamStatus.CONNECTING
                var connection: HttpURLConnection? = null
                var inputStream: BufferedInputStream? = null
                try {
                    val url = URL(streamUrl())
                    val network = networkProvider()
                    connection = ((network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection).also {
                        it.instanceFollowRedirects = false
                        it.connectTimeout = 5_000
                        it.readTimeout   = FRAME_TIMEOUT_MS.toInt()
                        it.useCaches = false
                        it.setRequestProperty("Connection", "close")
                    }
                    activeConnection = connection
                    if (!isStreaming || networkProvider() != network) continue
                    inputStream = BufferedInputStream(connection.inputStream)
                    streamFrameCount = 0
                    lastStreamFpsTime = SystemClock.elapsedRealtime()
                    Log.i(TAG, "Stream connected")

                    val buffer = ByteArray(16 * 1024)
                    val parser = JpegFrameParser()
                    var lastValidFrame = SystemClock.elapsedRealtime()
                    var statsStarted = lastValidFrame
                    var receivedBytes = 0L
                    var decodedFrames = 0
                    var maxReadMs = 0L
                    var maxDecodeMs = 0L

                    while (isStreaming && currentCoroutineContext().isActive) {
                        val readStarted = SystemClock.elapsedRealtime()
                        val bytesRead = inputStream.read(buffer)
                        maxReadMs = maxOf(maxReadMs, SystemClock.elapsedRealtime() - readStarted)
                        if (bytesRead == -1) throw java.io.EOFException("Camera closed stream")
                        receivedBytes += bytesRead
                        parser.consume(buffer, bytesRead) { jpegBytes, size ->
                            val decodeStarted = SystemClock.elapsedRealtime()
                            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, size)
                            maxDecodeMs = maxOf(maxDecodeMs, SystemClock.elapsedRealtime() - decodeStarted)
                            if (bitmap != null) {
                                val now = SystemClock.elapsedRealtime()
                                // Publish freshness before STREAMING so the watchdog never
                                // observes a new connection with the previous session's time.
                                lastFrameTimeMs = now
                                lastValidFrame = now
                                _streamStatus.value = StreamStatus.STREAMING
                                _frames.value = bitmap
                                streamFrameCount++
                                decodedFrames++
                                val elapsed = now - lastStreamFpsTime
                                if (elapsed >= 1000) {
                                    _streamFps.value = (streamFrameCount * 1000L / elapsed).toInt()
                                    streamFrameCount = 0
                                    lastStreamFpsTime = now
                                }
                            }
                        }
                        val statsNow = SystemClock.elapsedRealtime()
                        if (statsNow - statsStarted >= 5_000) {
                            Log.d(TAG, "Stream stats: frames=$decodedFrames bytes=$receivedBytes " +
                                "elapsedMs=${statsNow - statsStarted} maxReadMs=$maxReadMs maxDecodeMs=$maxDecodeMs")
                            statsStarted = statsNow
                            receivedBytes = 0L
                            decodedFrames = 0
                            maxReadMs = 0L
                            maxDecodeMs = 0L
                        }
                        if (SystemClock.elapsedRealtime() - lastValidFrame > FRAME_TIMEOUT_MS) {
                            throw java.io.IOException("No valid JPEG for $FRAME_TIMEOUT_MS ms")
                        }
                    }

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Stream error: ${e.message}")
                } finally {
                    activeConnection = null
                    _streamStatus.value = StreamStatus.LOST
                    _streamFps.value = 0
                    try { inputStream?.close() }  catch (_: Exception) {}
                    try { connection?.disconnect() } catch (_: Exception) {}
                }

                if (isStreaming) {
                    Log.i(TAG, "Retrying in 2 s…")
                    delay(2_000)
                }
            }
        }
    }

    fun stopStream() {
        isStreaming = false
        reconnect()
        _streamStatus.value = StreamStatus.LOST
        Log.i(TAG, "Stream stopped")
    }

    companion object {
        const val FRAME_TIMEOUT_MS = 5_000L
        private const val TAG = "MjpegStreamer"
    }
}
