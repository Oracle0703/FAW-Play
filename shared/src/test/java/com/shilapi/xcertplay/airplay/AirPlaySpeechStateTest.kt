package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class AirPlaySpeechStateTest {
    @Test fun followsConfirmedSpeechTransitionsPerSession() {
        val first = Any()
        val second = Any()
        assertNull(AirPlaySpeechState.active(first))
        observe(first, speech(0L, -1L))
        assertEquals(false, AirPlaySpeechState.active(first))
        assertNull(AirPlaySpeechState.active(second))
        observe(first, speech(1L, 1L))
        assertEquals(true, AirPlaySpeechState.active(first))
        observe(first, speech(1L, 2L))
        assertEquals(true, AirPlaySpeechState.active(first))
        observe(first, speech(0L, -1L))
        assertEquals(false, AirPlaySpeechState.active(first))
    }

    @Test fun missingSpeechAndUnrelatedCommandsPreserveState() {
        val session = Any()
        observe(session, speech(1L, 1L))
        observe(session, mapOf("appStateID" to 2L, "entity" to 0L))
        observe(session, mapOf("appStateID" to 3L, "entity" to 0L))
        AirPlaySpeechState.observe(session, "requestUI", mapOf("appStates" to listOf(speech(0L, -1L))))
        assertEquals(true, AirPlaySpeechState.active(session))
    }

    @Test fun rejectsInvalidTypesDuplicatesAndContradictoryStates() {
        val session = Any()
        observe(session, speech(1L, 1L))
        listOf(null, "0", false, -1L, 3L, 0.5, 4_294_967_296L).forEach {
            observe(session, speech(it, -1L))
            assertEquals(true, AirPlaySpeechState.active(session))
        }
        listOf(null, "-1", false, 0L, 3L, -1.0, 4_294_967_295L).forEach {
            observe(session, speech(0L, it))
            assertEquals(true, AirPlaySpeechState.active(session))
        }
        observe(session, speech(0L, 1L))
        observe(session, speech(1L, -1L))
        observe(session, speech(0L, -1L), speech(1L, 1L))
        assertEquals(true, AirPlaySpeechState.active(session))
    }

    @Test fun accessoryOwnershipDoesNotAuthorizeAnIphoneMusicResume() {
        val session = Any()
        observe(session, speech(0L, -1L))
        observe(session, speech(2L, 1L))
        assertNull(AirPlaySpeechState.active(session))
    }

    @Test fun readsActualBplistIntegerTypes() {
        val session = Any()
        // An Apple-compatible plist uses an eight-byte signed integer for -1.
        val plist = Base64.getDecoder().decode(
            "YnBsaXN0MDDRAQJZYXBwU3RhdGVzoQPTBAUGBwgJWmFwcFN0YXRlSURWZW50aXR5WnNwZWVjaE1vZGUQARAAE///////////CAsVFx4pMDs9PwAAAAAAAAEBAAAAAAAAAAoAAAAAAAAAAAAAAAAAAABI",
        )
        @Suppress("UNCHECKED_CAST")
        val decoded = BplistCodec.decode(plist) as Map<String, Any?>
        AirPlaySpeechState.observe(session, "modesChanged", decoded)
        assertEquals(false, AirPlaySpeechState.active(session))
    }

    private fun speech(entity: Any?, mode: Any?) =
        mapOf("appStateID" to 1L, "entity" to entity, "speechMode" to mode)

    private fun observe(session: Any, vararg states: Map<String, Any?>) =
        AirPlaySpeechState.observe(session, "modesChanged", mapOf("appStates" to states.toList()))
}
