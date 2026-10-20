package com.example.fatiguesense.enterprise.enterprise

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class EnterpriseDatabaseTest {
    private val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
        EnterpriseDatabase::class.java).build()
    private val dao get() = db.dao()
    private fun event(id: String = "a", uid: String = "one", org: String = "org") =
        EnterpriseEvent(id, uid, org, "vehicle", "esp", "session", 1000, "EAR", .2f, .4f)
    @After fun close() = db.close()

    @Test fun accountAndOrganizationQueuesAreIsolated() = runBlocking {
        dao.saveEvent(event()); dao.saveEvent(event("b", "two")); dao.saveEvent(event("c", org = "other"))
        assertEquals(listOf("a"), dao.pending("one", "org").map { it.eventId })
    }
    @Test fun lateAcknowledgmentCannotEraseNewCompletionRevision() = runBlocking {
        dao.saveEvent(event())
        dao.saveEvent(event().copy(status = "completed", durationMs = 2000, revision = 2))
        dao.acknowledge("a", 1)
        assertEquals(2, dao.pending("one", "org").single().revision)
        dao.acknowledge("a", 2)
        assertTrue(dao.pending("one", "org").isEmpty())
    }
    @Test fun clearingUploadedHistoryPreservesPendingAndOtherUsers() = runBlocking {
        dao.saveEvent(event()); dao.saveEvent(event("b").copy(uploaded = true, status = "completed"))
        dao.saveEvent(event("c", "two").copy(uploaded = true, status = "completed"))
        dao.clearUploaded("one", "org")
        assertEquals(listOf("a"), dao.history("one", "org").map { it.eventId })
        assertEquals(1, dao.history("two", "org").size)
    }
    @Test fun crashRecoveryRetainsOwnershipAndUnknownEnd() = runBlocking {
        dao.saveEvent(event()); dao.interruptOrphans()
        val recovered = dao.pending("one", "org").single()
        assertEquals("interrupted", recovered.status); assertNull(recovered.durationMs)
        assertEquals(1000L, recovered.timestamp); assertEquals("esp", recovered.deviceId)
    }
    @Test fun addressChangeKeepsEspIdentityAndPairingTime() = runBlocking {
        val paired = PairedDevice("org", "vehicle", "esp", "192.168.4.1", "ap", 1, 1)
        dao.saveDevice(paired)
        dao.saveDevice(paired.copy(lastKnownIp = "192.168.43.4", mode = "sta", lastVerifiedAt = 2))
        val restored = dao.device("org", "vehicle")!!
        assertEquals("esp", restored.espId); assertEquals(1L, restored.pairedAt)
        assertEquals("192.168.43.4", restored.lastKnownIp)
    }
}
