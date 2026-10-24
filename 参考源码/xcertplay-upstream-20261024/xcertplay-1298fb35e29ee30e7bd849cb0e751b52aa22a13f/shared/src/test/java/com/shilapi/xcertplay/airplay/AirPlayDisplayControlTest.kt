package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class AirPlayDisplayControlTest {
    private fun config() = AirPlayConfig(
        deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:02", sourceVersion = "366.0",
        main = AirPlayDisplayConfig(1280, 1280, viewAreas = listOf(
            AirPlayViewArea(1280, 720), AirPlayViewArea(720, 1280),
        )),
    )

    private fun clusterConfig(initialUrl: String? = null) = config().copy(
        hevc = true,
        cluster = AirPlayDisplayConfig(1920, 720, primaryInputDevice = 0, initialUrl = initialUrl),
    )

    @Test fun declaresInitialIndexAndAdjacentAreas() {
        val display = (AirPlayInfoPlist.build(config().copy(main = config().main.copy(initialViewArea = 1)))["displays"] as List<*>).first() as Map<*, *>
        assertEquals(1, display["initialViewArea"])
        assertEquals(listOf(0), display["adjacentViewAreas"])
        assertEquals(2, (display["viewAreas"] as List<*>).size)
        val params = AirPlayInfoPlist.viewAreaCommand(1, 2)["params"] as Map<*, *>
        assertEquals(300, params["animationDurationMillis"])
        assertEquals(listOf(0), params["adjacentViewAreas"])
        assertEquals(AirPlayInfoPlist.MAIN_UUID, params["uuid"])
    }

    @Test fun refusesUnnegotiatedSwitchAndCoalescesPendingCommands() {
        val sent = mutableListOf<Map<String, Any?>>()
        var writable = false
        val control = AirPlayDisplayControlFactory.create(config(), { if (writable) { sent += it; true } else false }, {})
        assertFalse(control.showViewArea(1))
        control.negotiate(listOf("viewAreas"), true)
        assertTrue(control.acceptsViewAreas())
        assertFalse(control.showViewArea(2))
        assertTrue(control.showViewArea(1))
        assertTrue(control.showViewArea(0))
        writable = true
        control.responseStarted()
        control.flush()
        assertTrue(sent.isEmpty())
        control.responseSent()
        assertEquals(1, sent.size)
        assertEquals(0, (sent.single()["params"] as Map<*, *>)["viewAreaIndex"])
    }

    @Test fun phoneFeatureProposalIsNarrowedToWhatTheAccessoryImplements() {
        val control = AirPlayDisplayControlFactory.create(clusterConfig(), { true }, {})
        assertEquals(listOf("uiContext", "viewAreas", "altScreen", "hevc", "iAPChannel"), control.negotiate(PHONE_FEATURES, true))

        // Without the event channel the phone cannot drive the alternate UI.
        val noEvents = AirPlayDisplayControlFactory.create(clusterConfig(), { true }, {})
        assertEquals(listOf("viewAreas", "hevc"), noEvents.negotiate(PHONE_FEATURES, false))
        val noCluster = AirPlayDisplayControlFactory.create(config(), { true }, {})
        assertEquals(listOf("viewAreas", "iAPChannel"), noCluster.negotiate(PHONE_FEATURES, true))
        assertFalse(noCluster.acceptsAlternateScreen())
        // altScreen and uiContext are validated as a pair by the phone.
        val unpaired = AirPlayDisplayControlFactory.create(clusterConfig(), { true }, {})
        assertEquals(listOf("viewAreas"), unpaired.negotiate(listOf("altScreen", "viewAreas"), true))
    }

    @Test fun clusterShowsTheChosenAltScreenUrlOnlyOnceItsStreamIsUp() {
        val sent = mutableListOf<Map<String, Any?>>()
        val control = AirPlayDisplayControlFactory.create(clusterConfig(), { sent += it; true }, {})
        control.negotiate(PHONE_FEATURES, true)

        val info = control.info(mapOf("altScreenURLs" to PHONE_ALT_URLS, "uiContextURLs" to listOf("maps:")))
        val displays = (info["displays"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf(AirPlayInfoPlist.MAIN_UUID, AirPlayInfoPlist.ALT_UUID), displays.map { it["uuid"] })
        assertEquals(PHONE_ALT_URLS.first(), displays[1]["initialURL"])
        assertFalse(info.containsKey("altScreenURLs"))

        control.flush()
        assertTrue(sent.isEmpty())
        // SETUP type 111: the showUI follows the stream response, never precedes it.
        control.responseStarted()
        control.screenReady(111)
        control.flush()
        assertTrue(sent.isEmpty())
        control.responseSent()
        val showUi = sent.single()
        assertEquals("showUI", showUi["type"])
        assertEquals(mapOf("uuid" to AirPlayInfoPlist.ALT_UUID, "url" to PHONE_ALT_URLS.first()), showUi["params"])
    }

    @Test fun configuredClusterUrlWinsWhenThePhoneOffersIt() {
        val sent = mutableListOf<Map<String, Any?>>()
        val wanted = PHONE_ALT_URLS[1]
        val control = AirPlayDisplayControlFactory.create(clusterConfig(initialUrl = wanted), { sent += it; true }, {})
        control.negotiate(PHONE_FEATURES, true)
        control.info(mapOf("altScreenURLs" to PHONE_ALT_URLS))
        control.screenReady(111)
        control.flush()
        assertEquals(wanted, (sent.single()["params"] as Map<*, *>)["url"])

        // A later suggestion naming an alternate-screen URL is routed to the alternate display.
        sent.clear()
        control.receive("suggestUI", mapOf("urls" to listOf("maps:", PHONE_ALT_URLS[2])))
        control.flush()
        assertEquals(mapOf("uuid" to AirPlayInfoPlist.ALT_UUID, "url" to PHONE_ALT_URLS[2]), sent.single()["params"])
    }

    @Test fun viewAreaRequestsOnlyConfirmTheShownArea() {
        val sent = mutableListOf<Map<String, Any?>>()
        val control = AirPlayDisplayControlFactory.create(config(), { sent += it; true }, {})
        control.negotiate(listOf("viewAreas"), true)
        assertThrows(IllegalArgumentException::class.java) {
            control.receive("requestViewArea", mapOf("displayUUID" to AirPlayInfoPlist.MAIN_UUID, "viewArea" to 1L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            control.receive("requestViewArea", mapOf("displayUUID" to AirPlayInfoPlist.ALT_UUID, "viewArea" to 0L))
        }
        control.receive("requestViewArea", mapOf("displayUUID" to AirPlayInfoPlist.MAIN_UUID, "viewArea" to mapOf("viewAreaIndex" to 0L)))
        control.flush()
        assertEquals(0, (sent.single()["params"] as Map<*, *>)["viewAreaIndex"])
    }

    private companion object {
        /** Feature proposal and alternate-screen URLs sent by an iOS 26 phone. */
        val PHONE_FEATURES = listOf(
            "uiContext", "viewAreas", "cornerMasks", "focusTransfer", "h.264Level5.1", "mainBuffered", "altScreen",
            "enhancedSiri", "hevc", "fileTransfer", "uiSync", "vehicleStateProtocol", "sessionManagement",
            "videoPlayback", "logTransfer", "iAPChannel",
        )
        val PHONE_ALT_URLS = listOf(
            "maps:/car/instrumentcluster/map", "maps:/car/instrumentcluster/instructioncard", "maps:/car/instrumentcluster",
        )
    }
}
