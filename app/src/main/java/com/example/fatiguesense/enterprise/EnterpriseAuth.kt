package com.example.fatiguesense.enterprise.enterprise

import android.content.Context
import com.example.fatiguesense.enterprise.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Driver(val uid: String, val orgId: String, val name: String)
data class Assignment(val vehicleId: String, val deviceId: String, val label: String)
data class DriverSession(val driver: Driver, val assignment: Assignment, val sessionId: String)
class ApiError(val status: Int, message: String): Exception(message)

object EnterpriseAuth {
    val driver = MutableStateFlow<Driver?>(null)
    val session = MutableStateFlow<DriverSession?>(null)
    val selection = MutableStateFlow<Assignment?>(null)
    private var firebase: FirebaseAuth? = null
    val configurationError: String? get() = if (listOf(BuildConfig.FIREBASE_API_KEY,
        BuildConfig.FIREBASE_APP_ID, BuildConfig.FIREBASE_PROJECT_ID, BuildConfig.BACKEND_URL)
        .any { it.isBlank() || it.contains("YOUR_") || it.contains(".invalid") })
        "Firebase/backend placeholders are not configured. See enterprise.properties.example." else null

    fun initialize(context: Context) {
        if (firebase != null || configurationError != null) return
        require(URL(BuildConfig.BACKEND_URL).protocol == "https") { "Backend must use HTTPS" }
        val app = FirebaseApp.getApps(context).firstOrNull { it.name == "enterprise" }
            ?: FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
                .setApiKey(BuildConfig.FIREBASE_API_KEY).setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID).build(), "enterprise")
        firebase = FirebaseAuth.getInstance(app)
    }
    suspend fun signIn(email: String, password: String) {
        check(configurationError == null) { configurationError!! }
        firebase!!.signInWithEmailAndPassword(email.trim(), password).await()
        try { restore() } catch (e: Exception) { firebase?.signOut(); throw e }
    }
    suspend fun restore(): Driver? {
        val user = firebase?.currentUser ?: return null
        val claims = user.getIdToken(true).await().claims
        val org = claims["orgId"] as? String ?: error("Your account has no organization. Contact your administrator.")
        requireId(org)
        require(claims["role"] == "driver") { "A driver account is required for this app." }
        val result = call("mobileAccess", JSONObject())
        check(firebase?.currentUser?.uid == user.uid) { "Account changed" }
        return Driver(user.uid, org, result.optString("displayName", user.email ?: user.uid)).also { driver.value = it }
    }
    suspend fun recover(email: String) {
        check(configurationError == null) { configurationError!! }
        firebase!!.sendPasswordResetEmail(email.trim()).await()
    }
    suspend fun assignments(): List<Assignment> {
        val rows = call("mobileAssignments", JSONObject()).getJSONArray("assignments")
        return (0 until rows.length()).map { rows.getJSONObject(it).let { row ->
            Assignment(requireId(row.getString("vehicleId")), requireId(row.getString("deviceId")), row.getString("label")) } }
    }
    suspend fun begin(assignment: Assignment): DriverSession {
        val current = driver.value ?: error("Sign in first")
        val result = call("mobileStartSession", JSONObject().put("vehicleId", assignment.vehicleId).put("deviceId", assignment.deviceId))
        check(driver.value == current) { "Account changed" }
        return DriverSession(current, assignment, requireId(result.getString("sessionId"))).also { session.value = it }
    }
    fun signOut() { session.value = null; selection.value = null; driver.value = null; firebase?.signOut(); EspConnection.clear() }

    private fun requireId(value: String): String {
        require(value.isNotBlank() && value.length <= 128 && value.none { it.isISOControl() || it == '/' }) { "Server returned an invalid identifier" }
        return value
    }

    suspend fun call(action: String, data: JSONObject, expectedUid: String? = null): JSONObject {
        check(configurationError == null) { configurationError!! }
        val user = firebase?.currentUser ?: error("Sign in first")
        check(expectedUid == null || user.uid == expectedUid) { "Account changed" }
        val token = user.getIdToken(false).await().token ?: error("Authentication expired")
        check(firebase?.currentUser?.uid == user.uid) { "Account changed" }
        return withContext(Dispatchers.IO) {
            val connection = URL("${BuildConfig.BACKEND_URL}/$action").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"; connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000; connection.readTimeout = 30000
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
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
                if (code !in 200..299) throw ApiError(code,
                    runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull() ?: "Server returned $code")
                val json = JSONObject(body)
                check(firebase?.currentUser?.uid == user.uid) { "Account changed" }
                json.getJSONObject("result")
            } finally { connection.disconnect() }
        }
    }
}
