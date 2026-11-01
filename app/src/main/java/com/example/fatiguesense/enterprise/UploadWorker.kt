package com.example.fatiguesense.enterprise.enterprise

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object UploadScheduler {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun cancel(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork("event-upload")
            cancelUniqueWork("event-upload-recovery")
        }
    }
    suspend fun cancelAndAwait(context: Context) = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork("event-upload").result.get()
            cancelUniqueWork("event-upload-recovery").result.get()
        }
    }
    fun enqueue(context: Context) = AppModes.policy.schedule {
        WorkManager.getInstance(context).enqueueUniqueWork("event-upload", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<UploadWorker>().setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun schedule(context: Context) = AppModes.policy.schedule {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("event-upload-recovery", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build())
    }
}

class UploadWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
    companion object { private val uploadLock = Mutex() }
    override suspend fun doWork(): Result = uploadLock.withLock { upload() }
    private suspend fun upload(): Result {
        if (!AppModes.policy.allowsWorker()) return Result.success()
        if (EnterpriseAuth.configurationError != null) return Result.success()
        return try {
            runModeGatedWorker(AppModes.policy, Result.success(),
                restore = { AppModes.restorePersisted() },
                upload = { driver -> uploadOwned(driver, EnterpriseDatabase.get(applicationContext).dao()) })
        } catch (e: CancellationException) { throw e }
          catch (e: CloudAccessDenied) { Result.success() }
          catch (e: Exception) { Result.retry() }
    }

    private suspend fun uploadOwned(driver: Driver, dao: EnterpriseDao): Result {
        suspend fun status(message: String, success: Boolean = false) {
            val previous = dao.sync(driver.uid, driver.orgId)
            dao.saveSync(SyncStatus(driver.uid, driver.orgId, message,
                if (success) System.currentTimeMillis() else previous?.lastSuccess))
        }
        return try {
            SessionClosures.sync(dao, driver)
            status("Uploading pending detections…")
            // Bounded run. Periodic recovery or another event schedules the next batch.
            repeat(10) {
                val events = dao.pending(driver.uid, driver.orgId).take(20).toMutableList()
                if (events.isEmpty()) {
                    SessionClosures.sync(dao, driver)
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
                val response = EnterpriseAuth.call("mobileUploadEvents", JSONObject().put("events", payload), driver)
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
          catch (e: CloudAccessDenied) { Result.success() }
          catch (e: ApiError) {
              status(if (e.status == 401 || e.status == 403) "Upload paused. Sign in again or contact your administrator."
                  else "Upload pending: ${e.message}")
              if (e.status == 401 || e.status == 403 || e.status == 404) Result.failure() else Result.retry()
          }
          catch (e: Exception) { status("Upload pending: ${e.message}"); Result.retry() }
    }
}
