package com.example.fatiguesense.enterprise.enterprise

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fatiguesense.enterprise.MainActivity
import com.example.fatiguesense.enterprise.ml.FatigueManager
import com.example.fatiguesense.enterprise.ml.Esp32AlertSender
import com.example.fatiguesense.enterprise.ml.StreamStatus
import com.example.fatiguesense.enterprise.db.FatigueDatabase
import com.example.fatiguesense.enterprise.services.FatigueService
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.io.IOException
import java.util.Collections

/** Requires the loopback fixture and adb reverse, never a real ESP or backend. */
class LocalEspRuntimeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var fixtureEnabled = false
    private var previousProxy: ProxySelector? = null
    private val destinations = Collections.synchronizedList(mutableListOf<URI>())
    @Before fun prepare() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("espFixture") == "true")
        fixtureEnabled = true
        previousProxy = ProxySelector.getDefault()
        // A process-local HTTP proxy reaches fixture ports without privileged adb listeners.
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI): MutableList<Proxy> {
                destinations.add(uri)
                return mutableListOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 18080)))
            }
            override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) = Unit
        })
        FatigueService.stopAndAwait(context)
        AppModes.toLocal()
        EspConnection.forgetLocal(context)
        fixture("/test/reset")
    }
    @After fun cleanup() = runBlocking {
        if (fixtureEnabled) {
            FatigueService.stopAndAwait(context)
            AppModes.toLocal()
            EspConnection.forgetLocal(context)
            fixture("/test/reset")
            ProxySelector.setDefault(previousProxy)
        }
    }
    private fun fixture(path: String, body: JSONObject? = null) {
        fixtureResponse(path, body)
    }
    private fun fixtureResponse(path: String, body: JSONObject? = null): JSONObject {
        val connection = URL("http://127.0.0.1:18080$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.connectTimeout = 3000; connection.readTimeout = 3000
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            check(connection.responseCode == 200)
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
    private fun waitFor(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(description, condition())
    }
    private suspend fun expectRejected(action: suspend () -> Unit) {
        try { action(); fail("Expected connection rejection") }
        catch (_: IllegalArgumentException) { /* Firmware status or identity rejected. */ }
    }
    @Test fun phoneAlertsNeverActivateEspBuzzerAndReconnectStillClearsIt() = runBlocking {
        EspConnection.verifyLocal(context, "127.0.0.1", "safedriving", false)
        Esp32AlertSender.startSession(context)
        try {
            // Clear/off commands must still reach the verified ESP.
            assertTrue(Esp32AlertSender.sendForcedClear().await())
            assertTrue(Esp32AlertSender.onAlertRisingEdge().await())
            assertTrue(Esp32AlertSender.onYawnAlert().await())
            assertTrue(Esp32AlertSender.onYawnFatigue().await())
            // Cover the former two-second activation heartbeat as well.
            SystemClock.sleep(2_200)
            assertTrue(Esp32AlertSender.onNetworkAvailable().await())
            assertTrue(Esp32AlertSender.onAlertFallingEdge().await())
        } finally {
            Esp32AlertSender.stopSession()
            assertTrue(Esp32AlertSender.sendForcedClear().await())
        }
        val received = fixtureResponse("/test/alerts").getJSONArray("alerts")
        val commands = (0 until received.length()).map { android.net.Uri.parse(received.getString(it)) }
        assertEquals("Startup, explicit clear, reconnect, recovery and stop", 5, commands.size)
        assertTrue("Only buzzer clear commands may reach HTTP: $commands", commands.all {
            it.getQueryParameter("type") == "clear" && it.getQueryParameter("outputs") == "buzzer"
        })
    }
    @Test fun savedAcknowledgementAndUnexpectedIdentityCannotGrantReadiness() = runBlocking {
        EspConnection.verifyLocal(context, "127.0.0.1", "safedriving", false)
        assertTrue(EspConnection.localReady.value)
        fixture("/test/status", JSONObject().put("device_id", "other"))
        expectRejected { EspConnection.verifyLocal(context, "127.0.0.1", "safedriving", true) }
        assertFalse(EspConnection.localReady.value); assertNull(EspConnection.verifiedId.value)
        fixture("/test/status", JSONObject().put("device_id", JSONObject.NULL))
        expectRejected { EspConnection.verifyLocal(context, "127.0.0.1", "safedriving", true) }
        fixture("/test/reset")
        val saved = EspConnection.request(context, "127.0.0.1", "safedriving", JSONObject().put("mode", "sta"))
        assertTrue(saved.getBoolean("saved"))
        expectRejected { EspConnection.verifyLocal(context, "127.0.0.1", "safedriving", false) }
        assertFalse(EspConnection.localReady.value)
    }
    @Test fun legacyFirmwareRequiresConfirmationAndCameraReadiness() = runBlocking {
        fixture("/test/status", JSONObject().put("device_id", JSONObject.NULL))
        expectRejected { EspConnection.verifyLocal(context, "127.0.0.1", "safedriving", false) }
        EspConnection.verifyLocal(context, "127.0.0.1", "safedriving", true)
        assertEquals("manual-local", EspConnection.verifiedId.value)
        fixture("/test/status", JSONObject().put("camera_ready", false))
        expectRejected { EspConnection.verifyLocal(context, "127.0.0.1", "safedriving", true) }
        assertFalse(EspConnection.localReady.value)
    }
    @Test fun setupApAddressDoesNotFallBackToAnUnrelatedNetwork() = runBlocking {
        assertNull("The loopback fixture does not provide an ESP Wi-Fi route", EspConnection.route(context, "192.168.4.1"))
        try {
            EspConnection.status(context, "192.168.4.1", "safedriving")
            fail("An ESP setup request must not fall back to the default network")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Stay connected without internet"))
        }
        assertTrue(FirebaseApp.getApps(context).isEmpty())
    }
    @Test fun cameraOnlyFirmwareRequiresConfirmationAndCannotReplaceKnownIdentity() = runBlocking {
        fixture("/test/status", JSONObject().put("api_code", 404))
        expectRejected { EspConnection.verifyLocal(context, "127.0.0.1", "safedriving", false) }
        assertFalse(EspConnection.localReady.value)
        assertTrue("No stream probe before confirmation", destinations.none { it.path == "/stream" })
        fixture("/test/reset")
        EspConnection.verifyLocal(context, "127.0.0.1", "safedriving", false)
        fixture("/test/status", JSONObject().put("api_code", 404))
        expectRejected { EspConnection.verifyLocal(context, "127.0.0.1", "safedriving", true) }
        assertEquals("fixture-esp", EspConnection.savedLocalId(context))
        assertFalse(EspConnection.localReady.value)
    }
    @Test fun cameraOnlyFallbackDoesNotBypassAuthenticationOrMissingVideo() = runBlocking {
        fixture("/test/status", JSONObject().put("api_code", 401))
        try {
            EspConnection.verifyLocal(context, "127.0.0.1", "safedriving", true)
            fail("Authentication failure must not enter camera-only mode")
        } catch (e: EspHttpException) { assertEquals(401, e.code) }
        assertTrue(destinations.none { it.path == "/stream" })
        fixture("/test/status", JSONObject().put("api_code", 404))
        fixture("/test/stream?enabled=0")
        try {
            EspConnection.verifyLocal(context, "127.0.0.1", "safedriving", true)
            fail("A successful stream HTTP response without frames must not grant readiness")
        } catch (_: IllegalStateException) { /* No decoded video frame. */ }
        assertFalse(EspConnection.localReady.value)
        assertNull(EspConnection.verifiedId.value)
        assertEquals("", EspConnection.savedLocalHost(context))
    }
    @Test fun cameraOnlyFirmwareStreamsReconnectsAndRemembersManualPairing() = runBlocking {
        fixture("/test/status", JSONObject().put("api_code", 404))
        val result = EspConnection.verifyLocal(context, "127.0.0.1", "safedriving", true)
        assertTrue(result.getBoolean("camera_only"))
        assertTrue(EspConnection.savedLocalLegacy(context))
        assertTrue(EspConnection.savedLocalCameraOnly(context))
        assertNull(EspConnection.savedLocalId(context))
        assertEquals("manual-local", EspConnection.verifiedId.value)
        // Re-entering setup restores the saved choice; reconnect must probe again.
        EspConnection.clear()
        EspConnection.connectLocal(context, "safedriving", EspConnection.savedLocalLegacy(context))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.startForegroundService(Intent(it, FatigueService::class.java)) }
            waitFor("Camera-only firmware should supply monitoring frames") {
                FatigueManager.isMonitoring.value && FatigueManager.frames.value != null
            }
            fixture("/test/stream?enabled=0")
            waitFor("Lost legacy video must block alarm commands") {
                FatigueManager.streamStatus.value == StreamStatus.LOST && EspConnection.verifiedId.value == null
            }
            fixture("/test/stream?enabled=1")
            waitFor("Legacy reconnect must verify video again") {
                FatigueManager.streamStatus.value == StreamStatus.STREAMING && EspConnection.verifiedId.value == "manual-local"
            }
            FatigueService.stopAndAwait(context)
            assertFalse(FatigueManager.isMonitoring.value)
            assertNull(FatigueManager.frames.value)
            assertTrue(FirebaseApp.getApps(context).isEmpty())
        }
    }
    @Test fun recoveryHotspotCanStreamWithSavedStationSettings() = runBlocking {
        fixture("/test/status", JSONObject().put("mode", "sta").put("connected", false).put("hotspot_active", true))
        EspConnection.verifyLocal(context, "127.0.0.1", "safedriving", false)
        assertTrue(EspConnection.localReady.value)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.startForegroundService(Intent(it, FatigueService::class.java)) }
            waitFor("Recovery hotspot must support local camera frames") {
                FatigueManager.isMonitoring.value && FatigueManager.frames.value != null
            }
            FatigueService.stopAndAwait(context)
            assertFalse(FatigueManager.isMonitoring.value)
            assertTrue(FirebaseApp.getApps(context).isEmpty())
        }
    }
    @Test fun localForegroundMonitoringReconnectStopAndModeEntryNeedNoAccount() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            repeat(3) {
                EspConnection.verifyLocal(context, "127.0.0.1", "safedriving", false)
                scenario.onActivity { it.startForegroundService(Intent(it, FatigueService::class.java)) }
                waitFor("Local frames should arrive") { FatigueManager.isMonitoring.value && FatigueManager.frames.value != null }
                assertNull(EnterpriseAuth.session.value); assertNull(EnterpriseAuth.driver.value)
                assertTrue(FirebaseApp.getApps(context).isEmpty())
                if (it == 0) {
                    fixture("/test/stream?enabled=0")
                    waitFor("Stream loss must block commands") { FatigueManager.streamStatus.value == StreamStatus.LOST && EspConnection.verifiedId.value == null }
                    fixture("/test/stream?enabled=1")
                    waitFor("Reconnect must verify the original local device") {
                        FatigueManager.streamStatus.value == StreamStatus.STREAMING && EspConnection.verifiedId.value == "fixture-esp"
                    }
                }
                FatigueService.stopAndAwait(context)
                assertFalse(FatigueManager.isMonitoring.value)
                assertNull(FatigueManager.frames.value)
            }
            EspConnection.verifyLocal(context, "127.0.0.1", "safedriving", false)
            scenario.onActivity { it.startForegroundService(Intent(it, FatigueService::class.java)) }
            waitFor("Monitoring restarts before entry") { FatigueManager.frames.value != null }
            AppModes.beginEntry()
            assertEquals(AppMode.ENTERPRISE_ENTRY, AppModes.policy.mode.value)
            assertFalse(FatigueManager.isMonitoring.value)
            assertNull(FatigueManager.frames.value)
            assertNull(EspConnection.verifiedId.value)
            AppModes.toLocal()
            assertTrue(FirebaseApp.getApps(context).isEmpty())
            assertTrue("Unexpected HTTP destination: $destinations", destinations.all { it.host == "127.0.0.1" && it.port in listOf(-1, 80, 81) })
        }
    }

    @Test fun activeLocalAlertIsPersistedBeforeEnterpriseEntryAndNeverEntersOutbox() = runBlocking {
        FatigueManager.init(context)
        val adaptive = FatigueManager.isAdaptiveModeEnabled.value
        val ear = FatigueManager.earThreshold
        val mar = FatigueManager.marThreshold
        val frames = FatigueManager.alertFrameCount
        val dao = FatigueDatabase.getLocalInstance(context).fatigueEventDao()
        val before = dao.getAll().map { it.id }.toSet()
        try {
            FatigueManager.setAdaptiveMode(false)
            FatigueManager.earThreshold = .9f // Force an alert using the retained real-face fixture.
            FatigueManager.marThreshold = 2f
            FatigueManager.alertFrameCount = 1
            fixture("/test/frame?kind=face")
            EspConnection.verifyLocal(context, "127.0.0.1", "safedriving", false)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.startForegroundService(Intent(it, FatigueService::class.java)) }
                waitFor("The real detector must trigger an EAR event") { FatigueManager.sessionAlertCount.value > 0 }
                AppModes.beginEntry()
                assertFalse(FatigueManager.isMonitoring.value)
                val added = dao.getAll().filter { it.id !in before }
                assertEquals(1, added.size)
                assertEquals("EAR", added.single().triggerType)
                assertTrue(added.single().durationMs >= 0)
                assertTrue(EnterpriseDatabase.get(context).dao().pending("one", "org").isEmpty())
                AppModes.toLocal()
                assertEquals(added, dao.getAll().filter { it.id !in before })
                assertTrue(FirebaseApp.getApps(context).isEmpty())
            }
        } finally {
            FatigueService.stopAndAwait(context)
            FatigueManager.earThreshold = ear; FatigueManager.marThreshold = mar
            FatigueManager.alertFrameCount = frames; FatigueManager.setAdaptiveMode(adaptive)
        }
    }
}
