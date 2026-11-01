package com.example.fatiguesense.enterprise.enterprise

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assume.assumeTrue
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Base64
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Explicit, read-only device diagnostic runner; never pairs, uploads, starts monitoring, or changes Wi-Fi. */
class EspDeviceProbe {
    @Test fun readOnlyEspConnectivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Read-only probe requires explicit espProbe=true", arguments.getString("espProbe") == "true")
        val targetContext = instrumentation.targetContext
        val address = EspConnection.validIp(arguments.getString("ip") ?: "192.168.4.1")
        val output = StringBuilder()
        try {
            val manager = targetContext.getSystemService(ConnectivityManager::class.java)
            val wifi = EspConnection.route(targetContext, address)
            val route = "Wi-Fi route selected: ${wifi != null}; default network is Wi-Fi: ${manager.getNetworkCapabilities(manager.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)}"
            output.appendLine(route); Log.i("EspProbe", route)
            for (path in listOf("/health", "/api/wifi", "/")) {
                Log.i("EspProbe", "Checking $path")
                val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "ESP read-only probe").apply { isDaemon = true } }
                val request = executor.submit<String> {
                    val socket = wifi?.socketFactory?.createSocket() ?: java.net.Socket()
                    try {
                        socket.connect(java.net.InetSocketAddress(address, 80), 4000)
                        socket.soTimeout = 4000
                        val auth = if (path == "/api/wifi") "Authorization: Basic " + Base64.encodeToString("admin:safedriving".toByteArray(), Base64.NO_WRAP) + "\r\n" else ""
                        socket.getOutputStream().apply {
                            write("GET $path HTTP/1.1\r\nHost: $address\r\n${auth}Connection: close\r\n\r\n".toByteArray())
                            flush()
                        }
                        val bytes = ByteArray(2048)
                        val count = socket.getInputStream().read(bytes)
                        "$path ${if (count > 0) String(bytes, 0, count, Charsets.UTF_8) else "ESP closed the connection without an HTTP response"}"
                    } finally { socket.close() }
                }
                val result = try { request.get(6, TimeUnit.SECONDS) }
                catch (e: Exception) { "$path ${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}: ${e.cause?.message ?: e.message}" }
                finally { request.cancel(true); executor.shutdownNow() }
                output.appendLine(result); Log.i("EspProbe", result.take(1200))

            }
        } catch (e: Exception) { output.appendLine("Diagnostic error: ${e.javaClass.simpleName}: ${e.message}") }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", output.toString()) })
    }
}
