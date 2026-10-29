package com.example.fatiguesense.enterprise.enterprise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackendAddressTest {
    @Test fun normalizesRootAndTrailingSlash() {
        assertEquals("https://backend.example.com/api", BackendAddress.apiBase("https://backend.example.com/", false))
        assertEquals("https://backend.example.com/api", BackendAddress.apiBase("https://backend.example.com/api/", false))
    }
    @Test fun allowsLocalHttpOnlyForDebugBuilds() {
        assertEquals("http://192.168.1.10:8081/api", BackendAddress.apiBase("http://192.168.1.10:8081", true))
        assertThrows(IllegalArgumentException::class.java) { BackendAddress.apiBase("http://192.168.1.10:8081/api", false) }
    }
    @Test fun rejectsAmbiguousOrCredentialBearingAddresses() {
        listOf("/api", "https://backend.example.com/website", "https://user:password@backend.example.com/api",
            "https://backend.example.com/api?token=private", "https://backend.example.com/api#fragment").forEach { value ->
            assertThrows(Exception::class.java) { BackendAddress.apiBase(value, true) }
        }
    }
}
