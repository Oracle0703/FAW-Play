package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Keep the selected native cluster page and its ETA option through iPhone UI negotiation. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AirPlayClusterUrlNegotiationTest {
    private val map = CarPlayClusterDisplay.MAP_URL
    private val instruments = CarPlayClusterDisplay.INSTRUMENTS_URL
    private val nativeWithEta = CarPlayClusterDisplay.Content.INSTRUMENTS.url

    @Test fun bareCapabilitiesKeepNativePageWithEtaInInfoAndShowUi() {
        val fixture = fixture(listOf(map, instruments))
        assertEquals(nativeWithEta, fixture.control.initialAlternateUrl())
        assertEquals(nativeWithEta, alternateInitialUrl(fixture.control.info(emptyMap())))
        assertTrue(nativeWithEta in fixture.control.info(emptyMap())["uiContextNowOnDisplayURLs"] as List<*>)
        fixture.control.flush()
        assertTrue(fixture.alternate.isEmpty())
        fixture.control.responseStarted()
        fixture.control.screenReady(111)
        fixture.control.flush()
        assertTrue(fixture.alternate.isEmpty())
        fixture.control.responseSent()
        assertEquals(listOf(nativeWithEta), fixture.alternate)
        assertTrue(fixture.main.isEmpty())
    }

    @Test fun bareSuggestUiRetainsNativePageAndEtaInsteadOfFirstMapOffer() {
        val fixture = fixture(listOf(map, instruments), preferred = map)
        fixture.control.screenReady(111)
        fixture.control.flush()
        fixture.alternate.clear()
        fixture.control.preferredAlternate = { nativeWithEta }
        fixture.control.receive("suggestUI", mapOf("urls" to listOf(map, instruments)))
        fixture.control.flush()
        assertEquals(listOf(nativeWithEta), fixture.alternate)
        assertEquals(nativeWithEta, fixture.control.initialAlternateUrl())
        assertTrue(fixture.main.isEmpty())
    }

    @Test fun bareChangeUiContextRetainsEtaOnTheSelectedNativePage() {
        val fixture = fixture(listOf(map, instruments), preferred = map)
        fixture.control.screenReady(111)
        fixture.control.flush()
        fixture.alternate.clear()
        fixture.control.preferredAlternate = { nativeWithEta }
        fixture.control.receive("changeUIContext", mapOf("uuid" to AirPlayInfoPlist.ALT_UUID,
            "url" to instruments))
        fixture.control.flush()
        assertEquals(listOf(nativeWithEta), fixture.alternate)
        assertEquals(nativeWithEta, alternateInitialUrl(fixture.control.info(emptyMap())))
    }

    @Test fun aNativeEtaSuggestionWithoutUuidIsRoutedToTheAlternateOwner() {
        val fixture = fixture(listOf(map, instruments), preferred = map)
        fixture.control.screenReady(111)
        fixture.control.flush()
        fixture.alternate.clear()
        fixture.control.preferredAlternate = { nativeWithEta }
        fixture.control.receive("suggestUI", mapOf("urls" to listOf(nativeWithEta)))
        fixture.control.flush()
        assertEquals(listOf(nativeWithEta), fixture.alternate)
        assertEquals(nativeWithEta, fixture.control.initialAlternateUrl())
        assertTrue("A cluster suggestion must not emit a main-display showUI", fixture.main.isEmpty())
    }

    @Test fun aRestartReplaysTheNativePageWithEtaAfterABareSuggestion() {
        val fixture = fixture(listOf(map, instruments, nativeWithEta))
        fixture.control.screenReady(111)
        fixture.control.flush()
        assertEquals(listOf(nativeWithEta), fixture.alternate)
        fixture.control.screenClosed(111)
        fixture.control.receive("suggestUI", mapOf("displayUUID" to AirPlayInfoPlist.ALT_UUID,
            "urls" to listOf(instruments)))
        fixture.control.flush()
        assertEquals(listOf(nativeWithEta), fixture.alternate)
        fixture.control.screenReady(111)
        fixture.control.flush()
        assertEquals(listOf(nativeWithEta, nativeWithEta), fixture.alternate)
        assertEquals(nativeWithEta, fixture.control.initialAlternateUrl())
    }

    @Test fun defaultNativeEtaPagesSurviveInfoAndRepeatedSetupWithBareCapabilities() {
        for (content in listOf(CarPlayClusterDisplay.Content.MAP, CarPlayClusterDisplay.Content.INSTRUMENTS)) {
            val requested = content.url
            val fixture = fixture(listOf(map, instruments), preferred = requested)
            assertEquals(requested, fixture.control.initialAlternateUrl())
            assertEquals(requested, alternateInitialUrl(fixture.control.info(emptyMap())))
            assertTrue(requested in fixture.control.info(emptyMap())["uiContextNowOnDisplayURLs"] as List<*>)
            fixture.control.screenReady(111)
            fixture.control.flush()
            assertEquals(listOf(requested), fixture.alternate)

            fixture.control.screenClosed(111)
            fixture.control.negotiate(listOf("altScreen", "uiContext"), eventAvailable = true)
            assertEquals(requested, alternateInitialUrl(fixture.control.info(mapOf(
                "altScreenURLs" to listOf(instruments, map)))))
            fixture.control.responseStarted()
            fixture.control.screenReady(111)
            fixture.control.flush()
            assertEquals("A SETUP response must precede the restored default page", listOf(requested), fixture.alternate)
            fixture.control.responseSent()
            assertEquals(listOf(requested, requested), fixture.alternate)
            assertTrue(fixture.main.isEmpty())
        }
    }

    @Test fun bareAndExactSuggestionsKeepTheDefaultNativeEtaPageOnTheAlternateScreen() {
        for (content in listOf(CarPlayClusterDisplay.Content.MAP, CarPlayClusterDisplay.Content.INSTRUMENTS)) {
            val requested = content.url
            for (offered in listOf(listOf(map, instruments), listOf(requested))) {
                val fixture = fixture(listOf(map, instruments), preferred = map)
                fixture.control.screenReady(111)
                fixture.control.flush()
                fixture.alternate.clear()
                fixture.control.preferredAlternate = { requested }
                fixture.control.receive("suggestUI", mapOf("urls" to offered))
                fixture.control.flush()
                assertEquals(listOf(requested), fixture.alternate)
                assertEquals(requested, alternateInitialUrl(fixture.control.info(emptyMap())))
                assertTrue("A cluster suggestion must not replace the main screen", fixture.main.isEmpty())
            }
        }
    }

    @Test fun phoneManagedClusterKeepsTheDefaultNativeEtaUrl() {
        val config = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1",
            AirPlayDisplayConfig(960, 540), cluster = CarPlayClusterDisplay.config(1920, 720,
                content = CarPlayClusterDisplay.Content.INSTRUMENTS),
            receiverManagedClusterUi = false)
        val control = AirPlayViewAreaControl(config) { error("No receiver context should be sent") }
        assertEquals(listOf("altScreen", "iAPChannel"), control.negotiate(listOf("altScreen", "uiContext"), true))
        val info = control.info(mapOf("altScreenURLs" to listOf(map, instruments)))
        assertEquals(nativeWithEta, alternateInitialUrl(info))
        assertFalse(info.containsKey("uiContextNowOnDisplayURLs"))
        control.screenReady(111)
        control.flush()
        control.screenClosed(111)
        control.negotiate(listOf("altScreen", "uiContext"), true)
        assertEquals(nativeWithEta, alternateInitialUrl(control.info(emptyMap())))
    }

    @Test fun removedLayoutQueriesDoNotMatchBareNativeCapabilities() {
        for (endpoint in listOf(map, instruments)) {
            for (oldLayout in listOf("leftAligned", "rightAligned", "topAligned")) {
                val requested = "$endpoint?showETA=yes&maneuverLayout=$oldLayout"
                val fixture = fixture(listOf(map, instruments), preferred = requested)
                assertEquals("Removed query must not match a bare page: $requested", map,
                    fixture.control.initialAlternateUrl())
                assertEquals(map, alternateInitialUrl(fixture.control.info(emptyMap())))
                fixture.control.screenReady(111)
                fixture.control.flush()
                assertEquals(listOf(map), fixture.alternate)
                assertTrue(fixture.main.isEmpty())
            }
        }
    }

    @Test fun malformedOrUnknownLayoutQueriesDoNotMatchABareEndpoint() {
        for (preferred in listOf(
            "$instruments?showETA=yes&maneuverLayout=bottomAligned",
            "$instruments?showETA=yes&maneuverLayout=none",
            "$instruments?showETA=yes&maneuverLayout=",
            "$instruments?showETA=yes&maneuverLayout=rightAligned&unknown=value",
            "$instruments?maneuverLayout=rightAligned&showETA=yes",
            "$instruments?showETA=yes&maneuverLayout=rightAligned&maneuverLayout=leftAligned",
            "$instruments?showETA=yes&maneuverLayout=RightAligned",
            "$instruments?showETA=no&maneuverLayout=rightAligned",
            "$instruments?showETA=yes&maneuverLayout=rightAligned#extra",
            "$instruments/instructioncard?showETA=yes&maneuverLayout=rightAligned",
            "music:/queue?showETA=yes&maneuverLayout=rightAligned",
        )) {
            val fixture = fixture(listOf(map, preferred.substringBefore('?')), preferred)
            assertEquals("Must not strip or normalize $preferred", map, fixture.control.initialAlternateUrl())
        }
    }

    @Test fun explicitEtaOffIsPreservedRatherThanMatchedToEtaOn() {
        val etaOff = "$instruments?showETA=no"
        val fixture = fixture(listOf(map, etaOff))
        assertEquals(map, fixture.control.initialAlternateUrl())
        fixture.control.screenReady(111)
        fixture.control.flush()
        fixture.alternate.clear()
        fixture.control.receive("suggestUI", mapOf("uuid" to AirPlayInfoPlist.ALT_UUID,
            "urls" to listOf(etaOff)))
        fixture.control.flush()
        assertEquals(listOf(etaOff), fixture.alternate)
        assertEquals(etaOff, fixture.control.initialAlternateUrl())
    }

    @Test fun otherQueriesAndPathsAreNotCapabilitiesForTheNativeEtaPage() {
        for (offered in listOf(
            "$instruments?unknown=value",
            "$instruments?showETA=yes&unknown=value",
            "$instruments/instructioncard?showETA=yes",
            "maps:/car/other?showETA=yes",
        )) {
            val fixture = fixture(listOf(map, offered))
            assertEquals("Must not match $offered to $nativeWithEta", map, fixture.control.initialAlternateUrl())
            fixture.control.screenReady(111)
            fixture.control.flush()
            fixture.alternate.clear()
            fixture.control.receive("suggestUI", mapOf("uuid" to AirPlayInfoPlist.ALT_UUID,
                "urls" to listOf(offered)))
            fixture.control.flush()
            assertEquals("Must preserve the explicit offered URL", listOf(offered), fixture.alternate)
        }
    }

    @Test fun arbitraryUriQueriesAreNotRemovedToMatchBareCapabilities() {
        for (preferred in listOf("music:/queue?showETA=yes", "$instruments?unknown=value")) {
            val bare = preferred.substringBefore('?')
            val fixture = fixture(listOf(map, bare), preferred)
            assertEquals("Only the supported cluster ETA query has bare-page compatibility", map,
                fixture.control.initialAlternateUrl())
        }
    }

    @Test fun anUnavailableNativePageStillFallsBackToTheOfferedMap() {
        val fixture = fixture(listOf(map))
        assertEquals(map, fixture.control.initialAlternateUrl())
        assertEquals(map, alternateInitialUrl(fixture.control.info(emptyMap())))
        fixture.control.screenReady(111)
        fixture.control.flush()
        assertEquals(listOf(map), fixture.alternate)
        fixture.control.receive("suggestUI", mapOf("uuid" to AirPlayInfoPlist.ALT_UUID,
            "urls" to listOf(instruments)))
        fixture.control.flush()
        assertEquals(listOf(map), fixture.alternate)
        assertEquals(map, fixture.control.initialAlternateUrl())
    }

    private fun fixture(capabilities: List<String>, preferred: String = nativeWithEta): Fixture {
        val alternate = mutableListOf<String>()
        val main = mutableListOf<Map<String, Any?>>()
        val config = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1",
            AirPlayDisplayConfig(960, 540),
            cluster = AirPlayDisplayConfig(1600, 600, initialUrl = preferred))
        val control = AirPlayViewAreaControl(config) { main.add(it); true }
        control.preferredAlternate = { preferred }
        control.selectAlternate = { alternate.add(it); true }
        control.info(mapOf("altScreenURLs" to capabilities))
        control.negotiate(listOf("altScreen", "uiContext"), eventAvailable = true)
        return Fixture(control, alternate, main)
    }

    private fun alternateInitialUrl(info: Map<String, Any?>): Any? =
        ((info["displays"] as List<*>)[1] as Map<*, *>)["initialURL"]

    private data class Fixture(
        val control: AirPlayViewAreaControl,
        val alternate: MutableList<String>,
        val main: MutableList<Map<String, Any?>>,
    )
}
