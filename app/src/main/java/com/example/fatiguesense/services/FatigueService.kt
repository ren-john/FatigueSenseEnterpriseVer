package com.example.fatiguesense.enterprise.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.Context
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.fatiguesense.enterprise.MainActivity
import com.example.fatiguesense.enterprise.ml.FatigueManager
import com.example.fatiguesense.enterprise.ml.StreamStatus
import com.example.fatiguesense.enterprise.ml.ActiveAlertState
import com.example.fatiguesense.enterprise.enterprise.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

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
        val transitioning = MutableStateFlow(false)
        private val alive = MutableStateFlow(false)
        private val pendingCleanup = MonitoringCleanup()
        var cleanup: Deferred<Unit>? = null
            private set
        private suspend fun awaitCleanup() {
            cleanup?.join()
            pendingCleanup.drain()
        }
        suspend fun stopAndAwait(context: Context) {
            if (context.stopService(Intent(context, FatigueService::class.java))) alive.first { !it }
            awaitCleanup()
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var startedSession: DriverSession? = null
    private var startedContext: MonitoringContext? = null
    private var terminalMessage: String? = null

    override fun onCreate() {
        super.onCreate()
        alive.value = true
        transitioning.value = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        scope.launch {
            try {
                awaitCleanup()
                FatigueManager.awaitStopped()
                if (AppModes.policy.mode.value == AppMode.LOCAL) {
                    message.value = "Checking local ESP…"
                    EspConnection.prepareLocal(this@FatigueService)
                    val local = MonitoringContext.Local(checkNotNull(EspConnection.verifiedId.value))
                    local.requireMode(AppModes.policy.mode.value, AppModes.policy.enterpriseOwner())
                    startedContext = local
                    FatigueManager.start(this@FatigueService, local)
                    message.value = "Local monitoring · stored only on this phone"
                    transitioning.value = false
                    return@launch
                }
                check(AppModes.policy.mode.value == AppMode.ENTERPRISE) { "Enterprise mode is required for this monitoring flow." }
                DetectionJournal.initialize(this@FatigueService)
                DetectionJournal.awaitInitialized()
                val driver = EnterpriseAuth.driver.value ?: error("Sign in first")
                message.value = "Finishing previous session…"
                SessionClosures.sync(EnterpriseDatabase.get(this@FatigueService).dao(), driver)
                val assignment = EnterpriseAuth.selection.value ?: error("Select and verify your assigned ESP first.")
                message.value = "Checking assigned ESP…"
                EspConnection.prepare(this@FatigueService, driver, assignment)
                message.value = "Authorizing monitoring session…"
                startedSession = EnterpriseAuth.begin(assignment)
                val session = startedSession!!
                EnterpriseDatabase.get(this@FatigueService).dao().saveSession(LocalSession(session.sessionId, session.driver.uid, session.driver.orgId))
                message.value = ""
                startedContext = MonitoringContext.Enterprise(session)
                FatigueManager.start(this@FatigueService, startedContext!!)
                transitioning.value = false
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
                            terminalMessage = "Monitoring access ended. Sign in again or contact your administrator."
                            message.value = terminalMessage!!
                            stopSelf(); return@launch
                        }
                        message.value = "Monitoring locally; dashboard update pending."
                    } catch (e: Exception) {
                        message.value = "Monitoring locally; dashboard update pending."
                    }
                    delay(30000)
                }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                  terminalMessage = e.message ?: "Session authorization failed"
                  message.value = terminalMessage!!; stopSelf()
              }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY // New sessions require online authorization after process death.
    }

    override fun onDestroy() {
        super.onDestroy()
        transitioning.value = true
        FatigueManager.stop()
        scope.cancel()
        val endedAt = System.currentTimeMillis()
        cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO).async {
            pendingCleanup.enqueue {
                try {
                    scope.coroutineContext[Job]?.join()
                    FatigueManager.awaitStopped()
                    if (startedContext is MonitoringContext.Enterprise) DetectionJournal.stop()
                    val ended = startedSession
                    if (EnterpriseAuth.session.value == ended) EnterpriseAuth.session.value = null
                    if (ended != null) {
                        val dao = EnterpriseDatabase.get(applicationContext).dao()
                        dao.closeSession(ended.sessionId, endedAt)
                        message.value = "Closing monitoring session…"
                        try {
                            SessionClosures.sync(dao, ended.driver)
                            message.value = ""
                        } catch (e: Exception) {
                            message.value = "Monitoring stopped. Server closure pending; reconnect and retry Start."
                        }
                    }
                } finally {
                    if (startedSession != null) UploadScheduler.enqueue(applicationContext)
                    terminalMessage?.let { message.value = it }
                    transitioning.value = false
                }
            }
        }
        alive.value = false
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
