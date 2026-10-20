package com.example.fatiguesense.enterprise.enterprise

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object EnterpriseExport {
    fun cell(value: String): String {
        val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
    suspend fun share(context: Context) {
        val driver = EnterpriseAuth.driver.value ?: error("Sign in first")
        val file = withContext(Dispatchers.IO) {
            val rows = EnterpriseDatabase.get(context).dao().history(driver.uid, driver.orgId)
            val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val output = File(context.cacheDir, "exports/enterprise-${UUID.randomUUID()}.csv")
            output.parentFile!!.mkdirs()
            output.bufferedWriter().use { writer ->
                writer.appendLine("timestamp,vehicle_id,event_type,EAR,MAR,duration")
                rows.forEach { row -> writer.appendLine(listOf(date.format(Date(row.timestamp)), row.vehicleId,
                    when (row.triggerType) { "EAR" -> "Eye Closure Alert"; "MAR" -> "Yawn Warning"; else -> "No Face Detected" },
                    row.ear?.toString() ?: "", row.mar?.toString() ?: "", row.durationMs?.div(1000.0)?.toString() ?: "")
                    .joinToString(",", transform = ::cell)) }
            }
            output
        }
        check(EnterpriseAuth.driver.value == driver) { "Account changed" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Export your detection logs"))
    }
}
