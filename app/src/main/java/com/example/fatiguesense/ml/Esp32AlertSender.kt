package com.example.fatiguesense.enterprise.ml

import android.content.Context
import android.net.Network
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.HttpURLConnection
import java.net.URL

/** Ordered ESP32 alert transport. The firmware contract is type + one 32-bit seq. */
object Esp32AlertSender {
    private const val TAG = "Esp32AlertSender"
    private const val TIMEOUT_MS = 1_500
    private const val ATTEMPTS = 3 // initial request plus exactly two retries
    private const val RETRY_BACKOFF_MS = 150L
    private const val PREFS = "fatiguesense_esp32"
    private const val SESSION = "session_id"
    private const val COUNTER = "sequence_counter"
    private const val HEARTBEAT_MS = 2_000L

    var isEnabled = true
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var sessionId = 0
    private var counter = 0
    private var context: Context? = null
    @Volatile private var currentAlertType: String? = null
    @Volatile private var generation = 0L

    @Synchronized
    fun startSession(context: Context) {
        generation++
        this.context = context.applicationContext
        val prefs = this.context!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sessionId = (prefs.getInt(SESSION, 0) + 1) and 0xffff
        counter = 0
        prefs.edit().putInt(SESSION, sessionId).putInt(COUNTER, counter).apply()
        currentAlertType = null
        heartbeatJob?.cancel()
        // Session initialization is complete before the first forced clear.
        sendForcedClear()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                currentAlertType?.let {
                    if (it == "yawn" || it == "fatigue") sendType(it)
                }
            }
        }
    }

    @Synchronized fun stopSession() {
        generation++
        heartbeatJob?.cancel()
        heartbeatJob = null
        currentAlertType = null
    }

    fun onAlertRisingEdge() = sendType("fatigue")
    fun onAlertFallingEdge() = sendType("clear")
    fun onYawnAlert() = sendType("yawn")
    fun onYawnFatigue() = sendType("yawn_fatigue")
    fun sendForcedClear() = sendType("clear", forced = true)
    fun onNetworkAvailable() = sendType(currentAlertType ?: "clear", forced = true)
    suspend fun sendPing(): Boolean = withContext(Dispatchers.IO) {
        if (!isEnabled) return@withContext true
        request("/health") // Silent reachability check; legacy /ping blinks an LED.
    }

    private fun sendType(type: String, forced: Boolean = false): Deferred<Boolean> {
        // Queued commands must not spill into a replacement monitoring session.
        val network = com.example.fatiguesense.enterprise.enterprise.EspConnection.network
        val targetHost = com.example.fatiguesense.enterprise.enterprise.EspConnection.host.value
        val commandGeneration = generation
        return scope.async {
            if (commandGeneration != generation || targetHost != com.example.fatiguesense.enterprise.enterprise.EspConnection.host.value) return@async false
            if (type == "yawn" || type == "fatigue") currentAlertType = type
            if (type == "clear") currentAlertType = null
            Log.i(TAG, "Dispatching alert type=$type forced=$forced")
            send(type, forced, network, targetHost, commandGeneration)
        }
    }

    private suspend fun send(type: String, forced: Boolean, network: Network?, targetHost: String, commandGeneration: Long): Boolean = mutex.withLock {
        if (commandGeneration != generation) return@withLock false
        if (!isEnabled) return@withLock true
        val seq = synchronized(this) {
            counter = (counter + 1) and 0xffff
            context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
                ?.putInt(COUNTER, counter)?.apply()
            ((sessionId.toLong() and 0xffffL) shl 16) or (counter.toLong() and 0xffffL)
        }
        val suffix = "&seq=$seq" + if (forced) "&forced=1" else ""
        repeat(ATTEMPTS) { attempt ->
            val path = "/alert?type=$type$suffix&outputs=buzzer"
            if (commandGeneration != generation || targetHost != com.example.fatiguesense.enterprise.enterprise.EspConnection.host.value) return@withLock false
            if (request(path, network, targetHost)) {
                Log.i(TAG, "Alert acknowledged: type=$type seq=$seq attempt=${attempt + 1}")
                return@withLock true
            }
            if (attempt < ATTEMPTS - 1) delay(RETRY_BACKOFF_MS)
        }
        Log.e(TAG, "Alert delivery failed after $ATTEMPTS attempts: type=$type seq=$seq")
        false
    }

    private fun request(path: String, network: Network? = com.example.fatiguesense.enterprise.enterprise.EspConnection.network,
                        targetHost: String = com.example.fatiguesense.enterprise.enterprise.EspConnection.host.value): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            if (com.example.fatiguesense.enterprise.enterprise.EspConnection.verifiedId.value == null) return false
            val url = URL("http://$targetHost$path")
            connection = ((network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
            }
            val code = connection.responseCode
            Log.i(TAG, "ESP32 command $path -> HTTP $code")
            code in 200..299
        } catch (e: Exception) {
            Log.w(TAG, "ESP32 command failed: ${e.message}")
            false
        } finally {
            connection?.disconnect()
        }
    }
}
