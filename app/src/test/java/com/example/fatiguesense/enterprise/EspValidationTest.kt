package com.example.fatiguesense.enterprise.enterprise

import org.junit.Assert.*
import org.junit.Test

class EspValidationTest {
    @Test fun ipv4RejectsUrlsAndRedirectDestinations() {
        assertEquals("192.168.4.1", EspConnection.validIp(" 192.168.4.1 "))
        listOf("https://example.com", "192.168.4.1:81", "1.2.3.256", "1.2.3.04", "0.0.0.0").forEach {
            assertThrows(IllegalArgumentException::class.java) { EspConnection.validIp(it) }
        }
    }
    @Test fun credentialsUseUtf8BytesAndPreserveSpaces() {
        EspConnection.validateCredentials(" leading and trailing ", " password ")
        EspConnection.validateCredentials("é".repeat(16), "")
        EspConnection.validateCredentials("hotspot", "a".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { EspConnection.validateCredentials("é".repeat(17), "password") }
        assertThrows(IllegalArgumentException::class.java) { EspConnection.validateCredentials("hotspot", "short") }
        assertThrows(IllegalArgumentException::class.java) { EspConnection.validateCredentials("hotspot", "g".repeat(64)) }
    }
    @Test fun csvEscapesQuotesAndFormulaPrefixes() {
        assertEquals("\"'=1+1\"", EnterpriseExport.cell("=1+1"))
        assertEquals("\"a\"\"b\"", EnterpriseExport.cell("a\"b"))
    }
}
