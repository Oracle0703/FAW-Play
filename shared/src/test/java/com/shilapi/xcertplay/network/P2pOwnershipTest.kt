package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test

class P2pOwnershipTest {
    @Test fun brandedNameFitsTheP2pLimitAndKeepsAStableDeviceSuffix() {
        val prefix = P2pOwnership.prefix("com.shihab.diplay", "device-user-signing-key-id")
        assertTrue(prefix.startsWith("DIRECT-AP-Autunn-"))
        assertTrue((prefix + "aB23").toByteArray().size <= 32)
        assertEquals(prefix, P2pOwnership.prefix("com.shihab.diplay", "device-user-signing-key-id"))
        assertTrue(P2pOwnership.deviceName(prefix).matches(Regex("Autunn-[a-f0-9]{8}")))
    }

    @Test fun upgradesAndReinstallsReclaimOnlyTheSameDevicesOldAndBrandedGroups() {
        val prefix = P2pOwnership.prefix("app", "id")
        val legacy = P2pOwnership.legacyPrefix("app", "id")
        assertTrue(P2pOwnership.canReclaim(true, legacy + "aB23", null, prefix, legacy))
        assertFalse(P2pOwnership.canReclaim(true, P2pOwnership.legacyPrefix("app", "other") + "aB23", null, prefix, legacy))
        val android9 = "DIRECT-ab-${P2pOwnership.deviceName(prefix)}"
        assertTrue(P2pOwnership.canReclaim(true, android9, null, prefix, legacy))
        assertFalse(P2pOwnership.canReclaim(false, android9, null, prefix, legacy))
        assertFalse(P2pOwnership.canReclaim(true, "DIRECT-ab-Autunn-another-car", null, prefix, legacy))
    }

    @Test fun reinstallWithoutPreferencesCanReclaimOnlyItsOwnNamespace() {
        val prefix = P2pOwnership.prefix("com.shihab.diplay", "device-user-signing-key-id")
        val ssid = prefix + "aB23"
        assertTrue(P2pOwnership.canReclaim(true, ssid, null, prefix))
        assertFalse(P2pOwnership.canReclaim(false, ssid, null, prefix))
        assertFalse(P2pOwnership.canReclaim(true, ssid, null, P2pOwnership.prefix("other.app", "device-user-signing-key-id")))
        assertFalse(P2pOwnership.canReclaim(true, ssid, null, P2pOwnership.prefix("com.shihab.diplay", "other-device")))
        assertTrue(ssid.length <= 32)
    }

    @Test fun legacyGroupNeedsExactRecordAndUnknownGroupsNeedExplicitReset() {
        val prefix = P2pOwnership.prefix("app", "id")
        assertTrue(P2pOwnership.canReclaim(true, "DIRECT-xcaB23", "DIRECT-xcaB23", prefix))
        assertFalse(P2pOwnership.canReclaim(true, "DIRECT-xcaB23", null, prefix))
        assertFalse(P2pOwnership.canReclaim(true, null, null, prefix))
        assertFalse(P2pOwnership.canReclaim(true, prefix + "", null, prefix))
        assertFalse(P2pOwnership.canReclaim(true, prefix + "aB23-extra", null, prefix))
    }

    @Test fun rebrandingRecognizesOnlyThisDevicesPreviousNamesWithoutChangingItsStableSuffix() {
        val prefix = P2pOwnership.prefix("app", "id")
        val previous = P2pOwnership.previousPrefix("app", "id")
        val legacy = P2pOwnership.legacyPrefix("app", "id")
        assertEquals(previous.substringAfter("FAW-Play-"), prefix.substringAfter("Autunn-"))
        for (ssid in listOf(previous + "aB23", "DIRECT-ab-${P2pOwnership.deviceName(previous)}")) {
            assertTrue(P2pOwnership.canReclaim(true, ssid, null, prefix, legacy, previous))
            assertFalse(P2pOwnership.canReclaim(false, ssid, null, prefix, legacy, previous))
            assertFalse(P2pOwnership.canReclaim(true, ssid, null,
                P2pOwnership.prefix("app", "other"), P2pOwnership.legacyPrefix("app", "other"),
                P2pOwnership.previousPrefix("app", "other")))
        }
        assertFalse(P2pOwnership.canReclaim(true, previous + "aB23-extra", null, prefix, legacy, previous))
    }
}
