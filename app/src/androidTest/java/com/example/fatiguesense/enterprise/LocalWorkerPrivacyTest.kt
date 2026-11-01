package com.example.fatiguesense.enterprise.enterprise

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.firebase.FirebaseApp
import org.junit.Assert.*
import org.junit.Test

class LocalWorkerPrivacyTest {
    @Test fun previouslyQueuedWorkerCannotInitializeFirebaseInLocalMode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(AppMode.LOCAL, AppModes.policy.mode.value)
        val work = OneTimeWorkRequestBuilder<UploadWorker>().build()
        val manager = WorkManager.getInstance(context)
        manager.enqueue(work).result.get()
        val deadline = SystemClock.elapsedRealtime() + 20_000
        var state: WorkInfo.State? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            state = manager.getWorkInfoById(work.id).get()?.state
            if (state?.isFinished == true) break
            SystemClock.sleep(50)
        }
        assertEquals(WorkInfo.State.SUCCEEDED, state)
        assertNull(EnterpriseAuth.driver.value)
        assertTrue("Local work must never initialize Firebase", FirebaseApp.getApps(context).isEmpty())
    }
}
