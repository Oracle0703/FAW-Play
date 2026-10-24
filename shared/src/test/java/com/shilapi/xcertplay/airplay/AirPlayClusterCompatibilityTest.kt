package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AirPlayClusterCompatibilityTest {
    private val config = AirPlayConfig(
        deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
        sourceVersion = "1", main = AirPlayDisplayConfig(1920, 720),
        cluster = CarPlayClusterDisplay.config(1920, 720, content = CarPlayClusterDisplay.Content.INSTRUMENTS),
        receiverManagedClusterUi = false,
    )
    private val nativeUrl = CarPlayClusterDisplay.Content.INSTRUMENTS.url

    @Test fun compatibilityKeepsStandaloneAlternateFeatureAndHonorsPeerAndEventLimits() {
        assertEquals(listOf("viewAreas", "iAPChannel", "altScreen"),
            setupEnabledFeatures(config, listOf("viewAreas", "iAPChannel", "altScreen", "uiContext", "unknown")))
        assertEquals(listOf("altScreen", "iAPChannel"), setupEnabledFeatures(config, listOf("altScreen")))
        assertTrue("altScreen" in setupEnabledFeatures(config, null))
        assertFalse("uiContext" in setupEnabledFeatures(config, null))
        assertEquals(listOf("viewAreas"),
            setupEnabledFeatures(config, listOf("viewAreas", "iAPChannel", "altScreen", "uiContext"), false))
        assertEquals(listOf("iAPChannel"), setupEnabledFeatures(config, emptyList<Any>()))
        assertFalse("altScreen" in setupEnabledFeatures(config.copy(cluster = null), listOf("altScreen")))
    }

    @Test fun compatibilityDoesNotAdvertiseOrApplyUiContextAndKeepsNativeEtaInitialUrl() {
        val commands = mutableListOf<Map<String, Any?>>()
        val alternateSelections = mutableListOf<String>()
        val control = AirPlayViewAreaControl(config) { commands.add(it); true }
        control.selectAlternate = { alternateSelections.add(it); true }
        // The peer offers MAP first; compatibility must not silently select that instead of INSTRUMENTS.
        val before = control.info(mapOf("altScreenURLs" to listOf(
            CarPlayClusterDisplay.MAP_URL, CarPlayClusterDisplay.INSTRUMENTS_URL)))
        assertNativeInitialUrl(before)
        assertFalse(before.containsKey("uiContextNowOnDisplayURLs"))
        assertFalse(before.containsKey("uiContextLastOnDisplayURLs"))

        control.negotiate(listOf("viewAreas", "altScreen", "uiContext"), true)
        control.screenReady(111)
        control.receive("suggestUI", mapOf("uuid" to AirPlayInfoPlist.ALT_UUID,
            "urls" to listOf(CarPlayClusterDisplay.MAP_URL)))
        control.receive("changeUIContext", mapOf("uuid" to AirPlayInfoPlist.MAIN_UUID, "url" to "music:main"))
        control.flush()

        assertTrue(control.acceptsAlternate())
        assertEquals(nativeUrl, control.initialAlternateUrl())
        val after = control.info(emptyMap())
        assertNativeInitialUrl(after)
        assertFalse(after.containsKey("uiContextNowOnDisplayURLs"))
        assertFalse(after.containsKey("uiContextLastOnDisplayURLs"))
        assertTrue(commands.isEmpty())
        assertTrue(alternateSelections.isEmpty())
    }

    @Test fun compatibilityStillCompletesAlternateViewAreaOnlyAfterTheRequestAck() {
        val commands = mutableListOf<Map<String, Any?>>()
        val control = AirPlayViewAreaControl(config) { commands.add(it); true }
        control.negotiate(listOf("altScreen", "uiContext", "viewAreas"), true)
        control.responseStarted()
        control.receive("requestViewArea", mapOf("displayUUID" to AirPlayInfoPlist.ALT_UUID, "viewArea" to 0))
        control.flush()
        assertTrue(commands.isEmpty())
        control.responseSent()
        assertEquals("updateViewArea", commands.single()["type"])
        assertEquals(AirPlayInfoPlist.ALT_UUID, (commands.single()["params"] as Map<*, *>)["uuid"])
        assertThrows(IllegalArgumentException::class.java) {
            control.receive("requestViewArea", mapOf("displayUUID" to "unknown", "viewArea" to 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            control.receive("requestViewArea", mapOf("displayUUID" to AirPlayInfoPlist.ALT_UUID, "viewArea" to 1))
        }
    }

    @Test fun defaultModernNegotiationStillRequiresAlternateUiContextPair() {
        val modern = config.copy(receiverManagedClusterUi = true)
        assertTrue(AirPlayConfig("test", "test", "test", "1", AirPlayDisplayConfig(1920, 720))
            .receiverManagedClusterUi)
        assertEquals(listOf("iAPChannel"), setupEnabledFeatures(modern, listOf("altScreen")))
        assertEquals(listOf("altScreen", "uiContext", "iAPChannel"),
            setupEnabledFeatures(modern, listOf("altScreen", "uiContext")))
        val control = AirPlayViewAreaControl(modern) { true }
        assertTrue(control.info(emptyMap()).containsKey("uiContextNowOnDisplayURLs"))
        control.negotiate(listOf("altScreen", "uiContext"), true)
        assertTrue(control.info(emptyMap()).containsKey("uiContextNowOnDisplayURLs"))
    }

    private fun assertNativeInitialUrl(info: Map<String, Any?>) {
        val displays = info["displays"] as List<*>
        assertEquals(2, displays.size)
        assertEquals(nativeUrl, (displays[1] as Map<*, *>)["initialURL"])
        assertTrue(nativeUrl.endsWith("?showETA=yes"))
    }
}
