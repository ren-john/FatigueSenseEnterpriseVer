package com.example.fatiguesense.enterprise

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.fatiguesense.enterprise", appContext.packageName)
    }

    @Test
    fun firebaseConfigurationIsReadableWithoutInitializingCloudSdk() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val options = checkNotNull(FirebaseOptions.fromResource(context))

        assertEquals(context.getString(R.string.google_app_id), options.applicationId)
        assertEquals(context.getString(R.string.project_id), options.projectId)
        assertEquals(context.getString(R.string.google_api_key), options.apiKey)
        assertTrue(FirebaseApp.getApps(context).isEmpty())
    }
}
