package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AirPlayDisplayNegotiationTest {
    private val config = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1",
        AirPlayDisplayConfig(960, 540), cluster = AirPlayDisplayConfig(1600, 600, initialUrl = "maps:cluster"),
        hevc = true, videoInCar = true)

    @Test fun optionalFeaturesUseIntersectionAndAlternateFeaturesArePaired() {
        assertEquals(listOf("viewAreas", "iAPChannel"), setupEnabledFeatures(config, listOf("viewAreas", "unknown", "altScreen")))
        assertEquals(listOf("hevc", "viewAreas"), setupEnabledFeatures(config,
            listOf("hevc", "viewAreas", "iAPChannel", "altScreen", "uiContext", "videoPlayback"), eventAvailable = false))
        assertFalse("hevc" in setupEnabledFeatures(config.copy(hevc = false), listOf("hevc")))
        assertEquals(listOf("iAPChannel"), setupEnabledFeatures(config, emptyList<Any>()))
        assertFalse("uiContext" in setupEnabledFeatures(config, null))
        assertTrue("altScreen" in setupEnabledFeatures(config, null))
        assertFalse("videoPlayback" in setupEnabledFeatures(config, null))
    }

    @Test fun infoAndStreamAdmissionFollowAcceptedFeatures() {
        val control = AirPlayViewAreaControl(config) { true }
        assertEquals(2, (control.info(emptyMap())["displays"] as List<*>).size)
        control.negotiate(listOf("viewAreas"), eventAvailable = true)
        val info = control.info(emptyMap())
        assertEquals(1, (info["displays"] as List<*>).size)
        assertFalse(control.acceptsAlternate())
        assertFalse(info.containsKey("hevcInfo"))
        assertFalse(info.containsKey("videoPlaybackInfo"))
        assertFalse(info.containsKey("uiContextNowOnDisplayURLs"))
    }

    @Test fun contextSelectionWaitsForAckAndAlternateStreamAndUsesTheClusterOwner() {
        val commands = mutableListOf<Map<String, Any?>>()
        val alternate = mutableListOf<String>()
        val control = AirPlayViewAreaControl(config) { commands.add(it); true }
        control.preferredAlternate = { "maps:retained" }
        control.selectAlternate = { alternate.add(it); true }
        control.info(mapOf("altScreenURLs" to listOf("maps:cluster", "maps:retained")))
        control.negotiate(listOf("altScreen", "uiContext", "viewAreas"), true)
        val info = control.info(emptyMap())
        val displays = info["displays"] as List<*>
        assertEquals("maps:retained", (displays[1] as Map<*, *>)["initialURL"])
        assertFalse(info.containsKey("altScreenURLs"))
        control.flush()
        assertTrue(alternate.isEmpty())
        control.responseStarted()
        control.screenReady(111)
        control.receive("suggestUI", mapOf("uuid" to AirPlayInfoPlist.MAIN_UUID, "urls" to listOf("music:main")))
        control.flush()
        assertTrue(commands.isEmpty())
        assertTrue(alternate.isEmpty())
        control.responseSent()
        assertEquals(listOf("maps:retained"), alternate)
        assertEquals("showUI", commands.single()["type"])
        assertEquals(AirPlayInfoPlist.MAIN_UUID, (commands.single()["params"] as Map<*, *>)["uuid"])
        control.flush()
        assertEquals(1, alternate.size)
        control.screenClosed(111)
        control.receive("changeUIContext", mapOf("uuid" to AirPlayInfoPlist.ALT_UUID, "url" to "maps:cluster"))
        control.flush()
        assertEquals(1, alternate.size)
    }

    @Test fun unnegotiatedContextCannotSendUiCommandsAndUnknownDisplaysAreRejected() {
        val commands = mutableListOf<Map<String, Any?>>()
        val control = AirPlayViewAreaControl(config) { commands.add(it); true }
        control.receive("suggestUI", mapOf("urls" to listOf("music:main")))
        control.flush()
        assertTrue(commands.isEmpty())
        control.negotiate(listOf("altScreen", "uiContext"), true)
        assertThrows(IllegalArgumentException::class.java) {
            control.receive("changeUIContext", mapOf("uuid" to "unknown", "url" to "music:main"))
        }
    }
}
