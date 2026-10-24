package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test
import com.shilapi.xcertplay.airplay.AirPlayResourceModes.*

class AirPlayResourceModesTest {
    @Test fun requestsDoNotInventOwnershipAndTheTwoResourcesAreIndependent() {
        val sent = mutableListOf<Map<String, Any?>>()
        val modes = AirPlayResourceModes { sent += it; true }
        modes.observe("modesChanged", mapOf("resources" to listOf(
            mapOf("resourceID" to 1, "entity" to 1), mapOf("resourceID" to 2, "entity" to 2))))
        assertTrue(modes.state.screenAllowed)
        assertFalse(modes.state.mainAudioAllowed)
        modes.request(Resource.MAIN_AUDIO, Transfer.RELEASE, "user-play")
        assertFalse(modes.state.mainAudioAllowed)
        assertTrue(modes.observe("modesChanged", mapOf("resources" to listOf(mapOf("resourceID" to 2, "entity" to 1)))))
        assertTrue(modes.state.mainAudioAllowed)
        assertTrue(modes.state.screenAllowed)
        val resource = ((sent.single()["params"] as Map<*, *>)["resources"] as List<*>).single() as Map<*, *>
        assertEquals(mapOf("resourceID" to 2, "transferType" to 2), resource)
    }

    @Test fun duplicatesMalformedValuesAndUnrelatedCommandsCannotReplaceAnOwner() {
        val modes = AirPlayResourceModes { true }
        val original = mapOf("resources" to listOf(mapOf("resourceID" to 1, "entity" to 2)))
        assertTrue(modes.observe("modesChanged", original))
        assertFalse(modes.observe("modesChanged", original))
        for (values in listOf(
            listOf(mapOf("resourceID" to 1, "entity" to 1), mapOf("resourceID" to 1, "entity" to 2)),
            listOf(mapOf("resourceID" to 1, "entity" to 7)),
            listOf(mapOf("resourceID" to 1, "entity" to 1.5)),
            listOf(mapOf("resourceID" to 1.2, "entity" to 1)),
            listOf(mapOf("resourceID" to 1, "entity" to "1")))) {
            assertFalse(modes.observe("modesChanged", mapOf("resources" to values)))
            assertEquals(Entity.VEHICLE, modes.state.screen)
        }
        assertFalse(modes.observe("changeModes", mapOf("resources" to listOf(mapOf("resourceID" to 1, "entity" to 1)))))
    }

    @Test fun eventReadinessFlushesOnlyTheLatestIntentAndCloseRevokesQueuedRequests() {
        var ready = false
        val sent = mutableListOf<Map<String, Any?>>()
        val modes = AirPlayResourceModes { if (ready) { sent += it; true } else false }
        modes.request(Resource.SCREEN, Transfer.BORROW, "reverse", exclusive = true)
        modes.request(Resource.SCREEN, Transfer.RETURN, "reverse-ended")
        modes.request(Resource.MAIN_AUDIO, Transfer.TAKE, "radio")
        assertTrue(sent.isEmpty())
        ready = true; modes.flush(); modes.flush()
        assertEquals(2, sent.size)
        fun resource(index: Int) = ((sent[index]["params"] as Map<*, *>)["resources"] as List<*>).single() as Map<*, *>
        assertEquals(mapOf("resourceID" to 1, "transferType" to 4), resource(0))
        assertEquals(500, resource(1)["takeConstraint"])
        assertFalse(resource(1).containsKey("unborrowConstraint"))
        ready = false
        modes.request(Resource.SCREEN, Transfer.TAKE, "home")
        modes.close(); ready = true; modes.flush()
        assertFalse(modes.request(Resource.SCREEN, Transfer.RELEASE, "late"))
        assertEquals(2, sent.size)
    }

    @Test fun borrowUsesOnlyItsOwnConstraintAndRoundTripsThroughBinaryPlist() {
        lateinit var command: Map<*, *>
        val modes = AirPlayResourceModes { command = BplistCodec.decode(BplistCodec.encode(it)) as Map<*, *>; true }
        modes.request(Resource.SCREEN, Transfer.BORROW, "reverse", exclusive = true)
        val resource = ((command["params"] as Map<*, *>)["resources"] as List<*>).single() as Map<*, *>
        assertEquals(3L, resource["transferType"])
        assertEquals(1000L, resource["unborrowConstraint"])
        assertFalse(resource.containsKey("takeConstraint"))
        assertFalse(resource.containsKey("borrowConstraint"))
    }
}
