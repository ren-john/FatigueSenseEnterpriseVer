package com.example.fatiguesense.enterprise

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth

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
    fun firebaseUsesGeneratedAndroidConfiguration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = checkNotNull(FirebaseApp.initializeApp(context))

        assertEquals(FirebaseApp.DEFAULT_APP_NAME, app.name)
        assertEquals(context.getString(R.string.google_app_id), app.options.applicationId)
        assertEquals(context.getString(R.string.project_id), app.options.projectId)
        assertEquals(context.getString(R.string.google_api_key), app.options.apiKey)
        assertSame(app, FirebaseAuth.getInstance().app)
    }
}
