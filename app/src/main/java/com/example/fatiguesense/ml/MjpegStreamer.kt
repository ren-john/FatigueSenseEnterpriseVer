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
import java.io.ByteArrayOutputStream
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
                        it.readTimeout   = 5_000
                    }
                    activeConnection = connection
                    if (!isStreaming || networkProvider() != network) continue
                    inputStream = BufferedInputStream(connection.inputStream)
                    streamFrameCount = 0
                    lastStreamFpsTime = SystemClock.elapsedRealtime()
                    Log.i(TAG, "Stream connected")

                    val buffer      = ByteArray(4096)
                    val imageBuffer = ByteArrayOutputStream(32 * 1024)
                    var lastValidFrame = SystemClock.elapsedRealtime()
                    var inImage     = false
                    var prevByte: Byte = 0

                    while (isStreaming && currentCoroutineContext().isActive) {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead == -1) break
                        if (SystemClock.elapsedRealtime() - lastValidFrame > 5_000) {
                            throw java.io.IOException("No valid JPEG for 5 seconds")
                        }

                        for (i in 0 until bytesRead) {
                            val b = buffer[i]
                            if (!inImage) {
                                // Look for JPEG SOI marker: FF D8
                                if (prevByte == 0xFF.toByte() && b == 0xD8.toByte()) {
                                    inImage = true
                                    imageBuffer.reset()
                                    imageBuffer.write(0xFF)
                                    imageBuffer.write(0xD8)
                                }
                            } else {
                                if (imageBuffer.size() >= 1024 * 1024) {
                                    throw java.io.IOException("JPEG exceeds 1 MiB; resetting malformed stream")
                                }
                                imageBuffer.write(b.toInt())
                                // Look for JPEG EOI marker: FF D9
                                if (prevByte == 0xFF.toByte() && b == 0xD9.toByte()) {
                                    val jpegBytes = imageBuffer.toByteArray()
                                    val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                                    if (bitmap != null) {
                                        _streamStatus.value = StreamStatus.STREAMING
                                        _frames.value = bitmap
                                        lastFrameTimeMs = SystemClock.elapsedRealtime()
                                        lastValidFrame = lastFrameTimeMs
                                        streamFrameCount++
                                        val now = SystemClock.elapsedRealtime()
                                        if (now - lastStreamFpsTime >= 1000) {
                                            _streamFps.value = streamFrameCount
                                            streamFrameCount = 0
                                            lastStreamFpsTime = now
                                        }
                                    }
                                    inImage = false
                                }
                            }
                            prevByte = b
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
        private const val TAG = "MjpegStreamer"
    }
}
