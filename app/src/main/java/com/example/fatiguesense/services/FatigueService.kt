package com.example.fatiguesense.enterprise.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.fatiguesense.enterprise.MainActivity
import com.example.fatiguesense.enterprise.ml.FatigueManager
import com.example.fatiguesense.enterprise.ml.StreamStatus
import com.example.fatiguesense.enterprise.ml.ActiveAlertState
import com.example.fatiguesense.enterprise.enterprise.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Foreground service that keeps the monitoring pipeline alive when the app is
 * backgrounded or the screen is off.
 *
 * Start: context.startForegroundService(Intent(context, FatigueService::class.java))
 * Stop:  context.stopService(Intent(context, FatigueService::class.java))
 */
class FatigueService : Service() {

    companion object {
        const val CHANNEL_ID      = "fatiguesense_monitoring"
        const val NOTIFICATION_ID = 1001
        val message = MutableStateFlow("")
        var cleanup: Job? = null
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var startedSession: DriverSession? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        scope.launch {
            try {
                cleanup?.join()
                DetectionJournal.awaitInitialized()
                message.value = "Authorizing monitoring session…"
                val assignment = EnterpriseAuth.selection.value ?: error("Select and verify your assigned ESP first.")
                check(EspConnection.verifiedId.value == assignment.deviceId) { "Verify ESP connection first." }
                startedSession = EnterpriseAuth.begin(assignment)
                val session = startedSession!!
                EnterpriseDatabase.get(this@FatigueService).dao().saveSession(LocalSession(session.sessionId, session.driver.uid, session.driver.orgId))
                message.value = ""
                FatigueManager.start(this@FatigueService)
                while (isActive) {
                    UploadScheduler.enqueue(this@FatigueService)
                    try {
                        EnterpriseAuth.heartbeat(session,
                            FatigueManager.streamStatus.value == StreamStatus.STREAMING,
                            FatigueManager.activeAlertState.value != ActiveAlertState.NONE)
                        message.value = ""
                    } catch (e: CancellationException) { throw e }
                      catch (e: ApiError) {
                        if (e.status == 401 || e.status == 403) {
                            message.value = "Monitoring access ended. Sign in again or contact your administrator."
                            stopSelf(); return@launch
                        }
                        message.value = "Monitoring locally; dashboard update pending."
                    } catch (e: Exception) {
                        message.value = "Monitoring locally; dashboard update pending."
                    }
                    delay(30000)
                }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { message.value = e.message ?: "Session authorization failed"; stopSelf() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY // New sessions require online authorization after process death.
    }

    override fun onDestroy() {
        super.onDestroy()
        FatigueManager.stop()
        scope.cancel()
        val ended = startedSession
        cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            DetectionJournal.stop()
            if (EnterpriseAuth.session.value == ended) EnterpriseAuth.session.value = null
            if (ended != null) EnterpriseDatabase.get(applicationContext).dao().closeSession(ended.sessionId, System.currentTimeMillis())
            UploadScheduler.enqueue(applicationContext)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "FatigueSense Monitoring",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Persistent notification while driver fatigue monitoring is active"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FatigueSenseEnterprise")
            .setContentText("Monitoring service is running. Open the app for connection status.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
