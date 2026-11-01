package com.example.fatiguesense.enterprise.enterprise

import org.junit.Assert.assertThrows
import org.junit.Test

class EspIdentityTest {
    @Test fun reportedIdentityMustMatchEvenWithManualConfirmation() {
        requireEspIdentity("ESP:Ab_1", "ESP:Ab_1", null)
        assertThrows(IllegalArgumentException::class.java) { requireEspIdentity("other", "ESP:Ab_1", "ESP:Ab_1") }
        assertThrows(IllegalArgumentException::class.java) { requireEspIdentity("esp:ab_1", "ESP:Ab_1", "ESP:Ab_1") }
    }
    @Test fun missingIdentityRequiresExplicitMatchingManualInput() {
        for (reported in listOf(null, "", " ")) {
            requireEspIdentity(reported, "ESP:Ab_1", " ESP:Ab_1 ")
            for (manual in listOf(null, "", "other", "esp:ab_1")) {
                assertThrows(IllegalArgumentException::class.java) { requireEspIdentity(reported, "ESP:Ab_1", manual) }
            }
        }
    }

    @org.junit.Test fun localKnownIdentityCannotBeBypassed() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { requireLocalEspIdentity("other", "known", true) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { requireLocalEspIdentity(null, "known", true) }
        requireLocalEspIdentity("known", "known", false)
    }
    @org.junit.Test fun localLegacyNeedsExplicitConfirmation() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { requireLocalEspIdentity(null, null, false) }
        requireLocalEspIdentity(null, null, true)
        requireLocalEspIdentity("new", null, false)
    }
}
