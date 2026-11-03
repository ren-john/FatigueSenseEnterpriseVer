package com.example.fatiguesense.enterprise

import android.app.ActivityManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.fatiguesense.enterprise.ml.PhoneEarAlarm
import com.example.fatiguesense.enterprise.ml.AnalysisResult
import com.example.fatiguesense.enterprise.ml.StreamStatus
import com.example.fatiguesense.enterprise.ml.shouldPlayPhoneAlert
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneEarAlarmInstrumentedTest {
    @Test fun bundledMp3PlaysForEarAndYawnAndReleasesOnRecoveryStreamLossAndStop() {
        // A resumed activity also satisfies Android 15's audio-focus requirement.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // RESUMED can precede window focus and the process's foreground priority.
            // Wait for both before exercising the real Android audio-focus request.
            val deadline = SystemClock.elapsedRealtime() + 5_000L
            var foreground = false
            while (!foreground && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { activity ->
                    val process = ActivityManager.RunningAppProcessInfo()
                    ActivityManager.getMyMemoryState(process)
                    foreground = activity.hasWindowFocus() &&
                        process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                }
                if (!foreground) SystemClock.sleep(50)
            }
            assertTrue("Unlock the phone and allow the activity to take focus", foreground)
            scenario.onActivity { activity ->
                val alarm = PhoneEarAlarm(activity)
                try {
                    assertFalse(alarm.isPlaying)
                    alarm.setAlertActive(shouldPlayPhoneAlert(AnalysisResult(isFatigueAlert = true), StreamStatus.STREAMING))
                    assertTrue("Bundled MP3 should play for an EAR alert", alarm.isPlaying)
                    alarm.setAlertActive(shouldPlayPhoneAlert(AnalysisResult(isYawnAlert = true), StreamStatus.STREAMING))
                    assertTrue("EAR to yawn must retain the phone alarm", alarm.isPlaying)
                    alarm.setAlertActive(shouldPlayPhoneAlert(AnalysisResult(), StreamStatus.STREAMING))
                    assertFalse("Recovery must stop playback", alarm.isPlaying)
                    alarm.setAlertActive(shouldPlayPhoneAlert(AnalysisResult(isYawnAlert = true), StreamStatus.STREAMING))
                    assertTrue("A yawn-only alert must play the MP3", alarm.isPlaying)
                    alarm.setAlertActive(shouldPlayPhoneAlert(AnalysisResult(isYawnAlert = true), StreamStatus.LOST))
                    assertFalse("Stream loss must stop a yawn alarm", alarm.isPlaying)
                    alarm.setAlertActive(shouldPlayPhoneAlert(AnalysisResult(isFatigueAlert = true), StreamStatus.LOST))
                    assertFalse("A stale EAR alert must not play without streaming", alarm.isPlaying)
                    alarm.setAlertActive(shouldPlayPhoneAlert(AnalysisResult(isFatigueAlert = true), StreamStatus.STREAMING))
                    assertTrue("Reconnected EAR alert must play again", alarm.isPlaying)
                    alarm.close()
                    assertFalse("Monitoring stop must release playback", alarm.isPlaying)
                    alarm.setAlertActive(true)
                    assertFalse("A queued update must not restart a closed session", alarm.isPlaying)
                } finally {
                    alarm.close()
                }
            }
        }
    }
}
