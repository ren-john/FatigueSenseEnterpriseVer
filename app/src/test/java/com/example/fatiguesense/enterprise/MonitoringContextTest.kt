package com.example.fatiguesense.enterprise.enterprise

import org.junit.Assert.*
import org.junit.Test

class MonitoringContextTest {
    private val session = DriverSession(Driver("one", "org", "Driver"), Assignment("car", "esp", "Car"), "session")
    @Test fun localCannotSurviveEnterpriseEntry() {
        val local = MonitoringContext.Local("esp")
        local.requireMode(AppMode.LOCAL, null)
        assertThrows(IllegalStateException::class.java) { local.requireMode(AppMode.ENTERPRISE_ENTRY, null) }
        assertThrows(IllegalStateException::class.java) { local.requireMode(AppMode.ENTERPRISE, CloudOwner("one", "org")) }
    }
    @Test fun enterpriseKeepsOriginalAccountAndOrganization() {
        val enterprise = MonitoringContext.Enterprise(session)
        enterprise.requireMode(AppMode.ENTERPRISE, CloudOwner("one", "org"))
        assertThrows(IllegalStateException::class.java) { enterprise.requireMode(AppMode.LOCAL, null) }
        assertThrows(IllegalStateException::class.java) { enterprise.requireMode(AppMode.ENTERPRISE, CloudOwner("two", "org")) }
        assertThrows(IllegalStateException::class.java) { enterprise.requireMode(AppMode.ENTERPRISE, CloudOwner("one", "other")) }
        assertEquals(session, enterprise.session)
    }
}
