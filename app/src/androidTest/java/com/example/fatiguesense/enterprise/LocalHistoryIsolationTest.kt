package com.example.fatiguesense.enterprise.enterprise

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fatiguesense.enterprise.db.FatigueDatabase
import com.example.fatiguesense.enterprise.db.FatigueEventEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class LocalHistoryIsolationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun event(time: Long) = FatigueEventEntity(timestampMs = time, durationMs = 500, earValue = .2f, severity = "WARNING", triggerType = "MAR")

    @Test fun separateLocalStorageSurvivesRestartAndCannotEnterAccountQueues() = runBlocking {
        val name = "isolation-${UUID.randomUUID()}.db"
        var local = Room.databaseBuilder(context, FatigueDatabase::class.java, name).build()
        val legacy = Room.inMemoryDatabaseBuilder(context, FatigueDatabase::class.java).build()
        val enterprise = Room.inMemoryDatabaseBuilder(context, EnterpriseDatabase::class.java).build()
        try {
            legacy.fatigueEventDao().insert(event(1))
            local.fatigueEventDao().insert(event(2))
            enterprise.dao().saveEvent(EnterpriseEvent("enterprise", "one", "org", "car", "esp", "session", 3, "EAR", .2f, null))
            local.close()
            local = Room.databaseBuilder(context, FatigueDatabase::class.java, name).build()
            assertEquals(listOf(2L), local.fatigueEventDao().getAll().map { it.timestampMs })
            assertEquals(listOf("enterprise"), enterprise.dao().pending("one", "org").map { it.eventId })
            assertTrue(enterprise.dao().pending("two", "org").isEmpty())
            assertTrue(enterprise.dao().pending("one", "other").isEmpty())
            local.fatigueEventDao().deleteAll()
            assertTrue(local.fatigueEventDao().getAll().isEmpty())
            assertEquals(listOf(1L), legacy.fatigueEventDao().getAll().map { it.timestampMs })
            assertEquals(1, enterprise.dao().pending("one", "org").size)
        } finally {
            local.close(); legacy.close(); enterprise.close(); context.deleteDatabase(name)
        }
    }

    @Test fun legacyVersionOneMigrationPreservesRowsWithoutImportingToLocal() = runBlocking {
        val name = "legacy-migration-${UUID.randomUUID()}.db"
        context.openOrCreateDatabase(name, 0, null).use { sql ->
            sql.execSQL("CREATE TABLE fatigue_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, timestampMs INTEGER NOT NULL, durationMs INTEGER NOT NULL, earValue REAL NOT NULL, severity TEXT NOT NULL)")
            sql.execSQL("INSERT INTO fatigue_events VALUES (1, 123, 456, 0.2, 'WARNING')")
            sql.version = 1
        }
        val migrated = Room.databaseBuilder(context, FatigueDatabase::class.java, name)
            .addMigrations(FatigueDatabase.MIGRATION_1_2).build()
        try {
            val row = migrated.fatigueEventDao().getAll().single()
            assertEquals(123L, row.timestampMs)
            assertEquals(456L, row.durationMs)
            assertEquals("EAR", row.triggerType)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
