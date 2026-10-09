package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.AirPlayPhoneState.CallKeyState
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2Parameter
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import com.shilapi.xcertplay.iap2.wire.Iap2WireCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AirPlayPhoneStateTest {
    @Test fun recordedRingingConnectingConnectedEndedSequenceDoesNotUseOwnershipAsCallState() {
        val session = Any()
        observe(session, phone(1L))
        assertEquals(CallKeyState.UNKNOWN, AirPlayPhoneState.callKeyState(session))
        for ((state, expected) in listOf(2 to CallKeyState.RINGING,
            3 to CallKeyState.IN_CALL, 4 to CallKeyState.IN_CALL, 0 to CallKeyState.IDLE)) {
            AirPlayPhoneState.observeCallState(session, call(state))
            assertEquals(expected, AirPlayPhoneState.callKeyState(session))
            assertEquals(true, AirPlayPhoneState.active(session))
        }
    }

    @Test fun rejectedCallClearsItsStateAndDuplicateEndIsHarmless() {
        val session = Any()
        AirPlayPhoneState.observeCallState(session, call(2))
        repeat(2) {
            AirPlayPhoneState.observeCallState(session, call(0,
                extra = listOf(Iap2Parameter(11, Iap2WireCodec.u8(1)))))
            assertEquals(CallKeyState.IDLE, AirPlayPhoneState.callKeyState(session))
        }
    }

    @Test fun waitingCallTakesPriorityAndEndingItDoesNotEraseAnotherCall() {
        val session = Any()
        AirPlayPhoneState.observeCallState(session, call(4, "active-call"))
        AirPlayPhoneState.observeCallState(session, call(2, "waiting-call"))
        assertEquals(CallKeyState.RINGING, AirPlayPhoneState.callKeyState(session))
        AirPlayPhoneState.observeCallState(session, call(0, "waiting-call"))
        assertEquals(CallKeyState.IN_CALL, AirPlayPhoneState.callKeyState(session))
    }

    @Test fun missingMalformedDuplicateAndUnrelatedParametersCannotTurnRingingIntoDrop() {
        val session = Any()
        AirPlayPhoneState.observeCallState(session, call(2))
        val invalid = listOf(
            Iap2Frame(0x4155, byteArrayOf(0, 3, 0, 2)),
            Iap2Frame(0x4155, ByteArray(0)),
            Iap2Frame(0x5001, call(4).payload),
            call(4, extra = listOf(Iap2Parameter(2, Iap2WireCodec.u8(4)))),
            call(4, extra = listOf(Iap2Parameter(4, Iap2WireCodec.string("other")))),
            frame(Iap2Parameter(2, Iap2WireCodec.u8(4))),
            frame(Iap2Parameter(4, Iap2WireCodec.string("call-test"))),
            frame(Iap2Parameter(2, byteArrayOf(0, 4)), Iap2Parameter(4, Iap2WireCodec.string("call-test"))),
            frame(Iap2Parameter(2, byteArrayOf()), Iap2Parameter(4, Iap2WireCodec.string("call-test"))),
            frame(Iap2Parameter(2, byteArrayOf(4)), Iap2Parameter(4, byteArrayOf(65))),
            call(255), call(4, ""), call(4, "bad\nidentifier"), call(4, "x".repeat(129)),
        )
        invalid.forEach {
            AirPlayPhoneState.observeCallState(session, it)
            assertEquals(CallKeyState.RINGING, AirPlayPhoneState.callKeyState(session))
        }
    }

    @Test fun replacementSessionAndExplicitCleanupCannotReuseOldCallState() {
        val first = Any()
        val second = Any()
        AirPlayPhoneState.observeCallState(first, call(4))
        assertEquals(CallKeyState.UNKNOWN, AirPlayPhoneState.callKeyState(second))
        AirPlayPhoneState.observeCallState(second, call(2))
        AirPlayPhoneState.forgetCalls(first)
        assertEquals(CallKeyState.UNKNOWN, AirPlayPhoneState.callKeyState(first))
        assertEquals(CallKeyState.RINGING, AirPlayPhoneState.callKeyState(second))
    }

    private fun call(state: Int, identifier: String = "call-test", extra: List<Iap2Parameter> = emptyList()) =
        frame(*(listOf(Iap2Parameter(2, Iap2WireCodec.u8(state)),
            Iap2Parameter(4, Iap2WireCodec.string(identifier))) + extra).toTypedArray())

    private fun frame(vararg parameters: Iap2Parameter) =
        Iap2Frame(0x4155, Iap2ParameterList.of(*parameters).encode())

    @Test
    fun initialIdleAndCallTransitionsAreIsolatedToTheirSession() {
        val first = Any()
        val second = Any()
        assertNull(AirPlayPhoneState.active(first))
        observe(first, phone(0L))
        assertEquals(false, AirPlayPhoneState.active(first))
        assertNull(AirPlayPhoneState.active(second))
        observe(first, phone(1L))
        assertEquals(true, AirPlayPhoneState.active(first))
        observe(first, phone(0L))
        assertEquals(false, AirPlayPhoneState.active(first))
    }

    @Test
    fun navigationAndSpeechUpdatesDoNotReplaceTelephoneOwnership() {
        val session = Any()
        observe(session, phone(1L))
        observe(session, mapOf("appStateID" to 3L, "entity" to 0L))
        observe(session, mapOf("appStateID" to 1L, "entity" to 0L, "speechMode" to -1L))
        assertEquals(true, AirPlayPhoneState.active(session))
        AirPlayPhoneState.observe(session, "requestUI", mapOf("appStates" to listOf(phone(0L))))
        assertEquals(true, AirPlayPhoneState.active(session))
    }

    @Test
    fun initialPlistWithAllThreeAppStatesIdentifiesIdlePhone() {
        val session = Any()
        val params = mapOf("appStates" to listOf(
            phone(0L),
            mapOf("appStateID" to 1L, "entity" to 0L, "speechMode" to -1L),
            mapOf("appStateID" to 3L, "entity" to 0L),
        ))
        @Suppress("UNCHECKED_CAST")
        val decoded = BplistCodec.decode(BplistCodec.encode(params)) as Map<String, Any?>
        AirPlayPhoneState.observe(session, "modesChanged", decoded)
        assertEquals(false, AirPlayPhoneState.active(session))
    }

    @Test
    fun malformedOrAmbiguousUpdatesPreserveLastKnownCallState() {
        val session = Any()
        observe(session, phone(1L))
        listOf(null, "0", false, -1L, 3L, 0.5, 4_294_967_296L).forEach {
            observe(session, phone(it))
            assertEquals(true, AirPlayPhoneState.active(session))
        }
        listOf("2", 2.5, 4_294_967_298L).forEach {
            observe(session, mapOf("appStateID" to it, "entity" to 0L))
            assertEquals(true, AirPlayPhoneState.active(session))
        }
        observe(session, phone(0L), phone(1L))
        assertEquals(true, AirPlayPhoneState.active(session))
        AirPlayPhoneState.observe(session, "modesChanged", emptyMap())
        assertEquals(true, AirPlayPhoneState.active(session))
    }

    @Test
    fun accessoryOwnershipLeavesTelephoneHandlingToExistingHidPath() {
        val session = Any()
        observe(session, phone(0L))
        observe(session, phone(2L))
        assertNull(AirPlayPhoneState.active(session))
    }

    private fun phone(entity: Any?): Map<String, Any?> = mapOf("appStateID" to 2L, "entity" to entity)
    private fun observe(session: Any, vararg states: Map<String, Any?>) =
        AirPlayPhoneState.observe(session, "modesChanged", mapOf("appStates" to states.toList()))
}
