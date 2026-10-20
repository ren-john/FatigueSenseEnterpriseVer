package com.example.fatiguesense.enterprise.enterprise

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Base64
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
    private var app: Context? = null
    private var setupPasswordInMemory: String? = null
    val network: Network? get() = app?.let { context ->
        host.value.takeIf { it.isNotBlank() }?.let { runCatching { route(context, it) }.getOrNull() }
    }
    fun clear() { verifiedId.value = null; host.value = ""; setupPasswordInMemory = null }
    fun blockCommands() { verifiedId.value = null }
    suspend fun streamUrl(context: Context, session: DriverSession): String {
        val password = setupPasswordInMemory ?: error("Verify ESP setup access again.")
        check(EnterpriseAuth.session.value?.sessionId == session.sessionId) { "Monitoring session changed" }
        val address = connect(context, session.driver, session.assignment, password)
        return "http://$address:81/stream"
    }
    fun validIp(input: String): String {
        val value = input.trim()
        require(value.split('.').let { parts -> parts.size == 4 && parts.all {
            it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() in 0..255
        } }) { "Enter an IPv4 address without a protocol or port." }
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
    suspend fun request(context: Context, ip: String, password: String, settings: JSONObject?): JSONObject = withContext(Dispatchers.IO) {
        val address = validIp(ip)
        val url = URL("http://$address/api/wifi")
        val connection = (route(context, address)?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        try {
            connection.connectTimeout = 5000; connection.readTimeout = 5000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Basic " + Base64.encodeToString("admin:$password".toByteArray(), Base64.NO_WRAP))
            if (settings != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("X-Requested-With", "FatigueSense")
                connection.outputStream.use { it.write(settings.toString().toByteArray()) }
            }
            check(connection.responseCode == 200) { "ESP returned HTTP ${connection.responseCode}. Check setup password and connectivity." }
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
            JSONObject(String(bytes, Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
    suspend fun verify(context: Context, assignment: Assignment, driver: Driver, ip: String, password: String): JSONObject {
        val address = validIp(ip)
        val result = status(context, address, password)
        require(result.optString("device_id") == assignment.deviceId) {
            "ESP identity missing or does not match the assigned device. Firmware must expose device_id."
        }
        require(!result.optBoolean("restarting")) { "ESP is restarting. Wait and retry." }
        require(result.optString("mode") == "ap" || (result.optString("mode") == "sta" && result.optBoolean("connected"))) {
            "Settings are saved, but the ESP has not joined the hotspot yet."
        }
        require(result.optBoolean("camera_ready")) { "ESP connected, but camera is unavailable." }
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
        verifiedId.value = assignment.deviceId
        return result
    }
    @Suppress("DEPRECATION")
    suspend fun discover(context: Context, expectedId: String): List<String> = withTimeoutOrNull(12000) {
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
            var matched: String? = null
            while (matched == null) {
                val service = candidates.receive()
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
                    matched = resolved.host?.hostAddress?.let { runCatching { validIp(it) }.getOrNull() }
                }
            }
            listOf(matched)
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
            candidates.close(); if (lock.isHeld) lock.release()
        }
    } ?: emptyList()

    suspend fun connect(context: Context, driver: Driver, assignment: Assignment, password: String): String {
        val saved = EnterpriseDatabase.get(context).dao().device(driver.orgId, assignment.vehicleId)
        if (saved != null && saved.espId == assignment.deviceId) {
            try { verify(context, assignment, driver, saved.lastKnownIp, password); return saved.lastKnownIp }
            catch (e: CancellationException) { throw e } catch (_: Exception) { /* discover current address */ }
        }
        for (ip in discover(context, assignment.deviceId)) {
            try { verify(context, assignment, driver, ip, password); return ip }
            catch (e: CancellationException) { throw e } catch (_: Exception) { /* offer manual fallback */ }
        }
        error("Could not find the assigned ESP. Enter its IP manually or reconnect to the recovery hotspot.")
    }
}
