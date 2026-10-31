package com.example.fatiguesense.enterprise

import android.app.ActivityManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.fatiguesense.enterprise.ml.PhoneEarAlarm
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneEarAlarmInstrumentedTest {
    @Test fun bundledMp3PlaysOnAlertAndReleasesOnRecoveryAndStop() {
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
                    alarm.setAlertActive(true)
                    assertTrue("Bundled MP3 should play for an EAR alert", alarm.isPlaying)
                    alarm.setAlertActive(true)
                    assertTrue(alarm.isPlaying)
                    alarm.setAlertActive(false)
                    assertFalse("Recovery must stop playback", alarm.isPlaying)
                    alarm.setAlertActive(true)
                    assertTrue("A new alert must play again", alarm.isPlaying)
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
