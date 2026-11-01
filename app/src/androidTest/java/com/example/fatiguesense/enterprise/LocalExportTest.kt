package com.example.fatiguesense.enterprise.enterprise

import android.content.ContextWrapper
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fatiguesense.enterprise.ml.AlertTriggerType
import com.example.fatiguesense.enterprise.ml.FatigueEvent
import com.example.fatiguesense.enterprise.ml.FatigueEventSeverity
import com.example.fatiguesense.enterprise.utils.ExportResult
import com.example.fatiguesense.enterprise.utils.LogExportUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class LocalExportTest {
    @Suppress("DEPRECATION")
    @Test fun localCsvSharesOriginalTimeAndTriggerThroughFileProvider() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var chooser: Intent? = null
        val capture = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { chooser = intent }
        }
        val result = withContext(Dispatchers.Main) {
            LogExportUtils.exportCsv(capture, listOf(FatigueEvent(123456789L, 2500, .2f,
                FatigueEventSeverity.WARNING, AlertTriggerType.MAR)))
        }
        assertEquals(ExportResult.SUCCESS, result)
        assertEquals(Intent.ACTION_CHOOSER, chooser!!.action)
        val share = chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val uri = share.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
        val csv = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        assertTrue(csv.lines().first().endsWith("trigger_type"))
        assertTrue(csv.contains("123456789,"))
        assertTrue(csv.contains(",2500,0.2,WARNING,MAR"))
        assertNull(EnterpriseAuth.driver.value)
    }
}
