package com.example.fatiguesense.enterprise.enterprise

import android.content.Context
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Base64
import android.util.Log
import android.os.SystemClock
import com.example.fatiguesense.enterprise.ml.JpegFrameParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlin.coroutines.resume

object EspConnection {
    val host = MutableStateFlow("")
    val verifiedId = MutableStateFlow<String?>(null)
    val configuredAssignment = MutableStateFlow<Assignment?>(null)
    val localReady = MutableStateFlow(false)
    private var localManual = false
    private var cameraOnlyHost: String? = null
    private var app: Context? = null
    private var setupPasswordInMemory: String? = null
    private var manualIdentity: Pair<Assignment, String>? = null
    val network: Network? get() = app?.let { context ->
        host.value.takeIf { it.isNotBlank() }?.let { runCatching { route(context, it) }.getOrNull() }
    }
    fun clear() { localReady.value = false; verifiedId.value = null; configuredAssignment.value = null; host.value = ""; setupPasswordInMemory = null; manualIdentity = null; cameraOnlyHost = null; localManual = false }
    fun blockCommands() { localReady.value = false; verifiedId.value = null }
    suspend fun prepare(context: Context, driver: Driver, assignment: Assignment) {
        check(configuredAssignment.value == assignment) { "Verify the assigned ESP first." }
        connect(context, driver, assignment, setupPasswordInMemory ?: error("Verify ESP setup access again."),
            manualIdentity?.takeIf { it.first == assignment }?.second)
    }
    suspend fun streamUrl(context: Context, session: DriverSession): String {
        val password = setupPasswordInMemory ?: error("Verify ESP setup access again.")
        check(EnterpriseAuth.session.value?.sessionId == session.sessionId) { "Monitoring session changed" }
        val address = connect(context, session.driver, session.assignment, password,
            manualIdentity?.takeIf { it.first == session.assignment }?.second)
        return "http://$address:81/stream"
    }
    private fun requireCamera(result: JSONObject) = requireEspCameraReady(
        mode = result.optString("mode"),
        stationConnected = result.optBoolean("connected"),
        hotspotActive = result.optBoolean("hotspot_active"),
        cameraReady = result.optBoolean("camera_ready"),
        restarting = result.optBoolean("restarting"),
    )
    fun savedLocalHost(context: Context): String = context.getSharedPreferences("local-esp", 0).getString("ip", "") ?: ""
    fun savedLocalId(context: Context): String? = context.getSharedPreferences("local-esp", 0).getString("device-id", null)
    fun savedLocalLegacy(context: Context): Boolean = context.getSharedPreferences("local-esp", 0).getBoolean("legacy-confirmed", false)
    fun savedLocalCameraOnly(context: Context): Boolean = context.getSharedPreferences("local-esp", 0).getBoolean("camera-only", false)
    fun forgetLocal(context: Context) { clear(); context.getSharedPreferences("local-esp", 0).edit().clear().apply() }
    suspend fun verifyLocal(context: Context, ip: String, password: String, confirmLegacy: Boolean): JSONObject {
        blockCommands()
        check(AppModes.policy.mode.value == AppMode.LOCAL) { "Local mode changed." }
        val address = validIp(ip)
        val result = localStatus(context, address, password, confirmLegacy)
        val reported = result.optString("device_id").takeUnless { result.isNull("device_id") || it.isBlank() }
        requireLocalEspIdentity(reported, savedLocalId(context), confirmLegacy)
        if (!result.optBoolean("camera_only")) requireCamera(result)
        currentCoroutineContext().ensureActive()
        check(AppModes.policy.mode.value == AppMode.LOCAL) { "Local mode changed." }
        val prefs = context.getSharedPreferences("local-esp", 0)
        check(prefs.edit().putString("ip", address).putString("device-id", reported ?: savedLocalId(context))
            .putString("mode", result.optString("mode", "camera-only"))
            .putBoolean("camera-only", result.optBoolean("camera_only"))
            .putBoolean("legacy-confirmed", confirmLegacy && reported == null).commit()) { "Could not save local pairing." }
        app = context.applicationContext; host.value = address
        setupPasswordInMemory = password; localManual = confirmLegacy
        cameraOnlyHost = address.takeIf { result.optBoolean("camera_only") }
        configuredAssignment.value = null
        verifiedId.value = reported ?: "manual-local"
        localReady.value = true
        return result
    }
    suspend fun connectLocal(context: Context, password: String, confirmLegacy: Boolean): String {
        var lastFailure: Exception? = null
        val saved = savedLocalHost(context)
        check(saved.isNotBlank()) { "No local ESP is paired yet. Connect to FatigueSense_Cam, enter 192.168.4.1, and tap Connect camera." }
        if (saved.isNotBlank()) {
            try { verifyLocal(context, saved, password, confirmLegacy); return saved }
            catch (e: CancellationException) { throw e } catch (e: Exception) {
                lastFailure = e
                Log.w("EspConnection", "Saved local ESP verification failed", e)
            }
        }
        val expected = savedLocalId(context)
        if (expected != null) for (ip in discover(context, expected)) {
            try { verifyLocal(context, ip, password, confirmLegacy); return ip }
            catch (e: CancellationException) { throw e } catch (e: Exception) {
                lastFailure = e
                Log.w("EspConnection", "Discovered local ESP verification failed", e)
            }
        }
        throw IllegalStateException("Could not reconnect. ${lastFailure?.message ?: "Enter the ESP IP manually, or join FatigueSense_Cam for recovery."}", lastFailure)
    }
    suspend fun prepareLocal(context: Context) {
        check(localReady.value) { "Connect and verify a local ESP first." }
        verifyLocal(context, host.value, setupPasswordInMemory ?: error("Verify setup access again."), localManual)
    }
    suspend fun localStreamUrl(context: Context, expectedId: String): String {
        check(AppModes.policy.mode.value == AppMode.LOCAL) { "Local monitoring ended." }
        val address = connectLocal(context, setupPasswordInMemory ?: error("Verify setup access again."), localManual)
        check(verifiedId.value == expectedId) { "Local device changed. Stop and verify again." }
        return "http://$address:81/stream"
    }
    fun validIp(input: String): String {
        val value = input.trim()
        require(value.split('.').let { parts -> parts.size == 4 && parts.all {
            it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() in 0..255
        } }) { "Enter a camera IP address such as 192.168.4.1, without http:// or a port." }
        require(value != "0.0.0.0" && value != "255.255.255.255") { "Enter a device address." }
        return value
    }
    fun validateCredentials(ssid: String, password: String) {
        require(!ssid.contains('\u0000') && !password.contains('\u0000')) { "Credentials contain a null character." }
        require(ssid.toByteArray().size in 1..32) { "SSID must be 1–32 UTF-8 bytes." }
        require(password.isEmpty() || password.toByteArray().size in 8..63 || password.matches(Regex("[a-fA-F0-9]{64}"))) {
            "Password must be empty, 8–63 UTF-8 bytes, or 64 hex digits."
        }
    }
    @Suppress("DEPRECATION")
    fun route(context: Context, ip: String): Network? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val address = InetAddress.getByName(validIp(ip))
        return manager.allNetworks.firstOrNull { candidate ->
            manager.getNetworkCapabilities(candidate)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                manager.getLinkProperties(candidate)?.routes?.any { !it.isDefaultRoute && it.matches(address) } == true
        }
    }
    suspend fun status(context: Context, ip: String, password: String) = request(context, ip, password, null)
    /** Camera-only firmware is opt-in and cannot replace a previously identified device. */
    suspend fun localStatus(context: Context, ip: String, password: String, confirmLegacy: Boolean): JSONObject {
        val address = validIp(ip)
        if (cameraOnlyHost != address || !confirmLegacy) {
            try { return status(context, address, password) }
            catch (e: EspHttpException) { if (e.code != 404) throw e }
        }
        check(AppModes.policy.mode.value == AppMode.LOCAL) { "Local mode changed." }
        require(confirmLegacy) {
            "This camera uses camera-only firmware. Open Connection options, enable My camera uses older firmware, then tap Connect camera again."
        }
        requireLocalEspIdentity(null, savedLocalId(context), confirmLegacy)
        probeCameraStream(context, address)
        currentCoroutineContext().ensureActive()
        check(AppModes.policy.mode.value == AppMode.LOCAL) { "Local mode changed." }
        // These fields describe the app's stream probe, not fabricated Wi-Fi settings.
        return JSONObject().put("camera_only", true).put("camera_ready", true)
    }

    private fun openLocalConnection(context: Context, address: String, url: URL): HttpURLConnection {
        val wifiRoute = route(context, address)
        check(address != "192.168.4.1" || wifiRoute != null) {
            "Join FatigueSense_Cam in Android Wi-Fi settings and choose Stay connected without internet, then tap Connect camera again."
        }
        return ((wifiRoute?.openConnection(url) ?: url.openConnection()) as HttpURLConnection).apply {
            connectTimeout = 5000; readTimeout = 5000
            instanceFollowRedirects = false
            setRequestProperty("Connection", "close")
            useCaches = false
        }
    }

    private suspend fun probeCameraStream(context: Context, address: String) = withContext(Dispatchers.IO) {
        val connection = openLocalConnection(context, address, URL("http://$address:81/stream"))
        try {
            val code = connection.responseCode
            check(code == 200) { "Camera video returned HTTP $code. Check that this firmware serves video on port 81 at /stream." }
            val deadline = SystemClock.elapsedRealtime() + 5000
            val parser = JpegFrameParser()
            val buffer = ByteArray(8192)
            var received = 0
            var decoded = false
            connection.inputStream.use { input ->
                while (!decoded && received < 2 * 1024 * 1024) {
                    currentCoroutineContext().ensureActive()
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    check(remaining > 0) { "The camera is connected, but no valid video frame arrived. Restart the camera and try again." }
                    connection.readTimeout = remaining.toInt()
                    val count = input.read(buffer)
                    check(count > 0) { "The camera closed its video before sending a frame. Restart the camera and try again." }
                    received += count
                    parser.consume(buffer, count) { jpeg, size ->
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(jpeg, 0, size, bounds)
                        if (bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096) {
                            BitmapFactory.decodeByteArray(jpeg, 0, size, BitmapFactory.Options().apply { inSampleSize = 8 })?.let {
                                decoded = true
                                it.recycle()
                            }
                        }
                    }
                }
            }
            check(decoded) { "The camera did not send a valid video frame. Restart the camera and try again." }
        } finally { connection.disconnect() }
    }

    suspend fun request(context: Context, ip: String, password: String, settings: JSONObject?): JSONObject = withContext(Dispatchers.IO) {
        val address = validIp(ip)
        val url = URL("http://$address/api/wifi")
        val connection = openLocalConnection(context, address, url)
        try {
            connection.setRequestProperty("Authorization", "Basic " + Base64.encodeToString("admin:$password".toByteArray(), Base64.NO_WRAP))
            if (settings != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("X-Requested-With", "FatigueSense")
                connection.outputStream.use { it.write(settings.toString().toByteArray()) }
            }
            val code = connection.responseCode
            if (code != 200) throw EspHttpException(code)
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 8192) { "ESP response too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            JSONObject(String(bytes, Charsets.UTF_8)).apply { remove("camera_only") }
        } finally { connection.disconnect() }
    }
    suspend fun verify(context: Context, assignment: Assignment, driver: Driver, ip: String, password: String,
                       manualDeviceId: String? = null): JSONObject {
        verifiedId.value = null
        val address = validIp(ip)
        val result = status(context, address, password)
        requireEspIdentity(result.optString("device_id").takeUnless { result.isNull("device_id") }, assignment.deviceId, manualDeviceId)
        requireCamera(result)
        currentCoroutineContext().ensureActive()
        check(AppModes.policy.mode.value == AppMode.ENTERPRISE) { "Enterprise mode changed." }
        check(EnterpriseAuth.driver.value == driver) { "Account changed" }
        val dao = EnterpriseDatabase.get(context).dao()
        val previous = dao.device(driver.orgId, assignment.vehicleId)
        require(previous == null || previous.espId == assignment.deviceId) {
            "A different ESP is paired. Use Reset pairing after the administrator changes the assignment."
        }
        val now = System.currentTimeMillis()
        dao.saveDevice(PairedDevice(driver.orgId, assignment.vehicleId, assignment.deviceId, address,
            result.getString("mode"), previous?.pairedAt ?: now, now))
        app = context.applicationContext; host.value = address
        setupPasswordInMemory = password
        manualIdentity = manualDeviceId?.trim()?.let { assignment to it }
        localReady.value = false
        verifiedId.value = assignment.deviceId
        configuredAssignment.value = assignment
        return result
    }
    @Suppress("DEPRECATION")
    suspend fun discover(context: Context, expectedId: String): List<String> {
        val matches = linkedSetOf<String>()
        withTimeoutOrNull(12000) {
            val manager = context.getSystemService(NsdManager::class.java)
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            val lock = wifi.createMulticastLock("enterprise-discovery").apply { setReferenceCounted(false); acquire() }
            val candidates = Channel<NsdServiceInfo>(Channel.UNLIMITED)
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) {}
                override fun onDiscoveryStopped(type: String) {}
                override fun onStartDiscoveryFailed(type: String, code: Int) { candidates.close(IllegalStateException("Discovery failed ($code). Enter IP manually.")) }
                override fun onStopDiscoveryFailed(type: String, code: Int) {}
                override fun onServiceLost(service: NsdServiceInfo) {}
                override fun onServiceFound(service: NsdServiceInfo) {
                    candidates.trySend(service)
                }
            }
            try {
                manager.discoverServices("_fatiguesense._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
                while (isActive) {
                    // Allow a short collection window after a match so duplicate advertised
                    // identities require explicit manual selection rather than arrival order.
                    val service = if (matches.isEmpty()) candidates.receive()
                        else withTimeoutOrNull(1500) { candidates.receive() } ?: break
                    // Older Android NSD implementations allow only one resolution at a time.
                    val resolved = suspendCancellableCoroutine<NsdServiceInfo?> { continuation ->
                        manager.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                                if (continuation.isActive) continuation.resume(null)
                            }
                            override fun onServiceResolved(info: NsdServiceInfo) {
                                if (continuation.isActive) continuation.resume(info)
                            }
                        })
                    }
                    if (resolved?.attributes?.get("device_id")?.toString(Charsets.UTF_8) == expectedId) {
                        resolved.host?.hostAddress?.let { runCatching { validIp(it) }.getOrNull() }?.let { matches.add(it) }
                    }
                }
            } finally {
                runCatching { manager.stopServiceDiscovery(listener) }
                candidates.close(); if (lock.isHeld) lock.release()
            }
        }
        require(matches.size <= 1) { "Multiple addresses advertise the paired ESP ID. Enter the intended IP manually." }
        return matches.toList()
    }

    suspend fun connect(context: Context, driver: Driver, assignment: Assignment, password: String,
                        manualDeviceId: String? = null): String {
        val saved = EnterpriseDatabase.get(context).dao().device(driver.orgId, assignment.vehicleId)
        if (saved != null && saved.espId == assignment.deviceId) {
            try { verify(context, assignment, driver, saved.lastKnownIp, password, manualDeviceId); return saved.lastKnownIp }
            catch (e: CancellationException) { throw e } catch (_: Exception) { /* discover current address */ }
        }
        for (ip in discover(context, assignment.deviceId)) {
            try { verify(context, assignment, driver, ip, password, manualDeviceId); return ip }
            catch (e: CancellationException) { throw e } catch (_: Exception) { /* offer manual fallback */ }
        }
        error("Could not find the assigned ESP. Enter its IP manually or reconnect to the recovery hotspot.")
    }
}

internal class EspHttpException(val code: Int) : IllegalStateException(
    if (code == 404) "ESP returned HTTP 404: this firmware does not support camera Wi-Fi setup/status. In Local mode, enable My camera uses older firmware under Connection options to connect using video."
    else "ESP returned HTTP $code. Check setup password and connectivity."
)
