package com.example.fatiguesense.enterprise.enterprise

import android.content.Context
import com.example.fatiguesense.enterprise.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class Driver(val uid: String, val orgId: String, val name: String)
data class Assignment(val vehicleId: String, val deviceId: String, val label: String)
data class DriverSession(val driver: Driver, val assignment: Assignment, val sessionId: String)
class ApiError(val status: Int, message: String): Exception(message)

object EnterpriseAuth {
    val driver = MutableStateFlow<Driver?>(null)
    val session = MutableStateFlow<DriverSession?>(null)
    val selection = MutableStateFlow<Assignment?>(null)
    private var firebase: FirebaseAuth? = null
    private var pendingStart: Pair<String, String>? = null
    private val apiBase: String get() = BackendAddress.apiBase(BuildConfig.BACKEND_URL, BuildConfig.DEBUG)
    val configurationError: String? get() = if (listOf(BuildConfig.BACKEND_URL)
        .any { it.isBlank() || it.contains("YOUR_") || it.contains(".invalid") })
        "Backend URL is not configured. See enterprise.properties.example."
        else runCatching { apiBase }.exceptionOrNull()?.message

    private fun initialize(context: Context) {
        if (firebase != null || configurationError != null) return
        val app = checkNotNull(FirebaseApp.initializeApp(context)) {
            "Firebase configuration is missing. Add app/google-services.json and rebuild."
        }
        firebase = FirebaseAuth.getInstance(app)
    }

    // Firebase Task.await cancellation does not cancel the underlying network operation.
    // Drain the actual task before the mode coordinator signs out/deletes Firebase.
    private suspend fun <T> Task<T>.settled(): T {
        val result = withContext(NonCancellable) { await() }
        currentCoroutineContext().ensureActive()
        return result
    }
    suspend fun signIn(context: Context, email: String, password: String): Driver =
        AppModes.policy.cloud(entry = true) { permit ->
            AppModes.policy.dispatch(permit) { initialize(context) }
            check(configurationError == null) { configurationError!! }
            AppModes.policy.dispatch(permit) { firebase!!.signInWithEmailAndPassword(email.trim(), password) }.settled()
            restore(permit) ?: error("Sign-in did not restore an account")
        }
    suspend fun restore(context: Context): Driver? = AppModes.policy.cloud(entry = true) { permit ->
        AppModes.policy.dispatch(permit) { initialize(context) }
        check(configurationError == null) { configurationError!! }
        restore(permit)
    }
    private suspend fun restore(permit: ModePolicy.Permit): Driver? {
        val user = firebase?.currentUser ?: return null
        val claims = AppModes.policy.dispatch(permit) { user.getIdToken(true) }.settled().claims
        val org = claims["orgId"] as? String ?: error("Your account has no organization. Contact your administrator.")
        requireId(org)
        require(claims["role"] == "driver") { "A driver account is required for this app." }
        val owner = CloudOwner(user.uid, org)
        val result = request(permit, "mobileAccess", JSONObject(), owner)
        return AppModes.policy.dispatch(permit) {
            check(firebase?.currentUser?.uid == user.uid) { "Account changed" }
            Driver(user.uid, org, result.optString("displayName", user.email ?: user.uid)).also { driver.value = it }
        }
    }
    suspend fun recover(context: Context, email: String) = AppModes.policy.cloud(entry = true) { permit ->
        AppModes.policy.dispatch(permit) { initialize(context) }
        check(configurationError == null) { configurationError!! }
        AppModes.policy.dispatch(permit) { firebase!!.sendPasswordResetEmail(email.trim()) }.settled()
    }
    suspend fun assignments(): List<Assignment> {
        val rows = call("mobileAssignments", JSONObject()).getJSONArray("assignments")
        return (0 until rows.length()).map { rows.getJSONObject(it).let { row ->
            Assignment(requireId(row.getString("vehicleId")), requireId(row.getString("deviceId")), row.getString("label")) } }
    }
    suspend fun begin(assignment: Assignment): DriverSession {
        val current = driver.value ?: error("Sign in first")
        val key = "${current.uid}:${assignment.vehicleId}:${assignment.deviceId}"
        val requestId = pendingStart?.takeIf { it.first == key }?.second ?: UUID.randomUUID().toString().also { pendingStart = key to it }
        val result = call("mobileStartSession", JSONObject().put("requestId", requestId)
            .put("vehicleId", assignment.vehicleId).put("deviceId", assignment.deviceId))
        check(driver.value == current) { "Account changed" }
        pendingStart = null
        return DriverSession(current, assignment, requireId(result.getString("sessionId"))).also { session.value = it }
    }
    suspend fun heartbeat(session: DriverSession, streaming: Boolean, alertActive: Boolean) {
        val response = call("mobileHeartbeat", JSONObject().put("sessionId", session.sessionId)
            .put("streaming", streaming).put("alertActive", alertActive), session.driver)
        check(response.optBoolean("success")) { "Heartbeat was not acknowledged" }
    }
    internal fun clearAfterDrain() {
        session.value = null; selection.value = null; driver.value = null; pendingStart = null
        firebase?.let { auth -> auth.signOut(); auth.app.delete() }
        firebase = null
        EspConnection.clear()
    }

    private fun requireId(value: String): String {
        require(value.isNotBlank() && value.length <= 128 && value.none { it.isISOControl() || it == '/' }) { "Server returned an invalid identifier" }
        return value
    }

    suspend fun call(action: String, data: JSONObject, expectedDriver: Driver? = null): JSONObject {
        val identity = expectedDriver ?: driver.value ?: throw CloudAccessDenied()
        val owner = CloudOwner(identity.uid, identity.orgId)
        return AppModes.policy.cloud(expected = owner) { permit -> request(permit, action, data, owner) }
    }

    private suspend fun request(permit: ModePolicy.Permit, action: String, data: JSONObject, owner: CloudOwner): JSONObject {
        check(configurationError == null) { configurationError!! }
        val user = firebase?.currentUser ?: error("Sign in first")
        check(user.uid == owner.uid) { "Account changed" }
        val tokenResult = AppModes.policy.dispatch(permit) { user.getIdToken(false) }.settled()
        check(tokenResult.claims["orgId"] == owner.orgId && tokenResult.claims["role"] == "driver") { "Account access changed" }
        val token = tokenResult.token ?: error("Authentication expired")
        check(firebase?.currentUser?.uid == user.uid) { "Account changed" }
        return withContext(Dispatchers.IO) {
            // The request holds its admission lease until blocking I/O actually exits. Cancellation
            // stops future dispatch; timeouts bound draining even when the platform ignores interrupt.
            val connection = AppModes.policy.dispatch(permit) {
                check(firebase?.currentUser?.uid == owner.uid) { "Account changed" }
                URL("$apiBase/$action").openConnection() as HttpURLConnection
            }
            try {
                connection.requestMethod = "POST"; connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000; connection.readTimeout = 30000
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                currentCoroutineContext().ensureActive()
                AppModes.policy.dispatch(permit) { Unit }
                connection.outputStream.use { it.write(JSONObject().put("data", data).toString().toByteArray()) }
                val code = connection.responseCode
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 262144) { "Server response too large" }
                        output.write(buffer, 0, count)
                    }
                    output.toString("UTF-8")
                } ?: "{}"
                currentCoroutineContext().ensureActive()
                AppModes.policy.dispatch(permit) {
                    check(firebase?.currentUser?.uid == owner.uid) { "Account changed" }
                    if (code !in 200..299) throw ApiError(code,
                        runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull() ?: "Server returned $code")
                    JSONObject(body).getJSONObject("result")
                }
            } finally { connection.disconnect() }
        }
    }
}
