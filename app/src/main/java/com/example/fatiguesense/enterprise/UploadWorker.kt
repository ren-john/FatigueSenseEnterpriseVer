package com.example.fatiguesense.enterprise.enterprise

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object UploadScheduler {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun enqueue(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("event-upload", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<UploadWorker>().setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("event-upload-recovery", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build())
    }
}

class UploadWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
    companion object { private val uploadLock = Mutex() }
    override suspend fun doWork(): Result = uploadLock.withLock { upload() }
    private suspend fun upload(): Result {
        if (EnterpriseAuth.configurationError != null) return Result.success()
        var identity = EnterpriseAuth.driver.value
        val dao = EnterpriseDatabase.get(applicationContext).dao()
        suspend fun status(message: String, success: Boolean = false) {
            val owner = identity ?: return
            val previous = dao.sync(owner.uid, owner.orgId)
            dao.saveSync(SyncStatus(owner.uid, owner.orgId, message,
                if (success) System.currentTimeMillis() else previous?.lastSuccess))
        }
        return try {
            EnterpriseAuth.initialize(applicationContext)
            val driver = EnterpriseAuth.restore() ?: return Result.success()
            identity = driver
            status("Uploading pending detections…")
            // Bounded run. Periodic recovery or another event schedules the next batch.
            repeat(10) {
                val events = dao.pending(driver.uid, driver.orgId).take(20).toMutableList()
                if (events.isEmpty()) {
                    for (ended in dao.pendingClosures(driver.uid, driver.orgId)) {
                        val closed = EnterpriseAuth.call("mobileEndSession", JSONObject().put("sessionId", ended.sessionId).put("endedAt", ended.endedAt), driver.uid)
                        check(closed.optBoolean("success")) { "Session closure not acknowledged" }
                        dao.acknowledgeSession(ended.sessionId)
                    }
                    status("Sync complete", true)
                    return Result.success()
                }
                val payload = JSONArray()
                events.forEach { event -> payload.put(JSONObject().apply {
                    put("eventId", event.eventId); put("schemaVersion", 1)
                    put("sessionId", event.sessionId); put("vehicleId", event.vehicleId); put("deviceId", event.deviceId)
                    put("timestamp", event.timestamp); put("triggerType", event.triggerType)
                    put("EAR", event.ear ?: JSONObject.NULL); put("MAR", event.mar ?: JSONObject.NULL)
                    put("duration", event.durationMs?.div(1000.0) ?: JSONObject.NULL)
                    put("status", event.status); put("revision", event.revision)
                }) }
                while (JSONObject().put("data", JSONObject().put("events", payload)).toString().toByteArray().size > 16384) {
                    payload.remove(payload.length() - 1)
                    events.removeAt(events.lastIndex)
                }
                check(events.isNotEmpty()) { "An event exceeds the backend's 16 KiB limit." }
                val response = EnterpriseAuth.call("mobileUploadEvents", JSONObject().put("events", payload), driver.uid)
                val accepted = response.getJSONArray("accepted")
                val submitted = events.associateBy { it.eventId }
                for (i in 0 until accepted.length()) {
                    val row = accepted.getJSONObject(i); val id = row.getString("eventId"); val revision = row.getInt("revision")
                    if (submitted[id]?.revision == revision) dao.acknowledge(id, revision)
                }
                val rejected = response.optJSONArray("rejected") ?: JSONArray()
                for (i in 0 until rejected.length()) {
                    val row = rejected.getJSONObject(i); val event = submitted[row.getString("eventId")] ?: continue
                    if (row.optBoolean("permanent") && row.optInt("revision") == event.revision)
                        dao.fail(event.eventId, event.revision, row.optString("message", "Rejected"))
                }
                if (accepted.length() == 0 && rejected.length() == 0) return Result.retry()
            }
            Result.retry()
        } catch (e: CancellationException) { throw e }
          catch (e: ApiError) {
              status(if (e.status == 401 || e.status == 403) "Upload paused. Sign in again or contact your administrator."
                  else "Upload pending: ${e.message}")
              if (e.status == 401 || e.status == 403 || e.status == 404) Result.failure() else Result.retry()
          }
          catch (e: Exception) { status("Upload pending: ${e.message}"); Result.retry() }
    }
}
