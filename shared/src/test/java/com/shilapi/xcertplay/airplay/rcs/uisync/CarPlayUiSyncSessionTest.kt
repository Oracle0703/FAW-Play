package com.shilapi.xcertplay.airplay.rcs.uisync

import org.junit.Assert.*
import org.junit.Test

class CarPlayUiSyncSessionTest {
    @Test
    fun remoteInitiatedResetWaitsForCompletionBeforeReady() {
        val f = fixture()
        f.session.receive(reset())
        assertEquals(1, f.target.resets)
        assertEquals(CarPlayUiSyncSessionState.AWAITING_RESET_COMPLETE_REMOTE_INITIATED, f.session.state)
        assertEquals(CarPlayUiSyncMessageType.RESET, f.sent.single().type)
        f.session.receive(complete())
        assertEquals(CarPlayUiSyncSessionState.READY, f.session.state)
    }

    @Test
    fun selfInitiatedResetCompletesReciprocalHandshake() {
        val f = fixture()
        f.session.begin(5)
        assertEquals(CarPlayUiSyncSessionState.AWAITING_RESET_SELF_INITIATED, f.session.state)
        f.session.receive(reset(ssn = 5))
        assertEquals(CarPlayUiSyncSessionState.READY, f.session.state)
        assertEquals(listOf(CarPlayUiSyncMessageType.RESET, CarPlayUiSyncMessageType.RESET_COMPLETE),
            f.sent.map { it.type })
    }

    @Test
    fun acknowledgedDuplicateCommandNeverAppliesTwice() {
        val f = ready()
        val command = command()
        f.session.receive(command)
        val response = f.sent.last()
        f.session.receive(command)
        assertEquals(1, f.target.applied)
        assertEquals(response, f.sent.last())
        assertEquals(CarPlayUiSyncMessageType.COMMAND_ACK, response.type)
        assertEquals(command.packetSequenceNumber, response.acknowledgementSequenceNumber)
        assertTrue(response.noAck)
    }

    @Test
    fun duplicateNoAckCommandDoesNotApplyOrSendAgain() {
        val f = ready()
        val before = f.sent.size
        f.session.receive(command().copy(noAck = true))
        f.session.receive(command().copy(noAck = true))
        assertEquals(1, f.target.applied)
        assertEquals(before, f.sent.size)
    }

    @Test
    fun unsupportedOperationIsNotAcknowledgedAndPresentationBecomesInactive() {
        val f = ready()
        f.target.accept = false
        val before = f.sent.size
        assertThrows(CarPlayUiSyncProtocolException::class.java) { f.session.receive(command()) }
        assertEquals(before, f.sent.size)
        assertEquals(1, f.target.inactive)
    }

    @Test
    fun reusedPacketWithDifferentPayloadCannotChangePresentation() {
        val f = ready()
        f.session.receive(command())
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            f.session.receive(command().copy(payload = mapOf("command" to "targetAppearanceChange",
                "appearanceMode" to "light")))
        }
        assertEquals(1, f.target.applied)
    }

    @Test
    fun commandBeforeResetAndUnsentAckAreRejected() {
        val notReady = fixture()
        assertThrows(CarPlayUiSyncProtocolException::class.java) { notReady.session.receive(command()) }
        assertEquals(0, notReady.target.applied)
        val f = ready()
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            f.session.receive(command().copy(acknowledgementSequenceNumber = 20))
        }
        assertEquals(0, f.target.applied)
    }

    @Test
    fun newSessionResetDropsPriorPacketWindowAndOldSessionCannotReturn() {
        val f = ready()
        f.session.receive(command())
        f.session.receive(reset(ssn = 2))
        f.session.receive(complete(ssn = 2))
        f.session.receive(command().copy(sessionSequenceNumber = 2))
        assertEquals(2, f.target.applied)
        assertEquals(2, f.target.resets)
        assertThrows(CarPlayUiSyncProtocolException::class.java) { f.session.receive(reset(ssn = 1)) }
    }

    @Test
    fun outboundAckWindowIsBoundedAndCumulativeAckReleasesIt() {
        val f = ready(CarPlayUiSyncInteropPolicy.EXPERIMENTAL_V3.copy(maximumUnacknowledgedCommands = 1))
        val payload = CarPlayUiSyncCommandPayload(CarPlayUiSyncCommand.REQUEST_FOCUS, mapOf("focusToken" to 1L))
        val first = f.session.command(payload)
        assertThrows(IllegalStateException::class.java) { f.session.command(payload) }
        f.session.receive(CarPlayUiSyncMessageFactory.commandAck(1, 2, first.packetSequenceNumber, noAck = true))
        assertNotNull(f.session.command(payload))
    }

    @Test
    fun closeIsIdempotentAndNoPreviousTargetSurvives() {
        val f = ready()
        f.session.close()
        f.session.close()
        assertEquals(1, f.target.inactive)
        assertThrows(IllegalStateException::class.java) { f.session.receive(command()) }
    }

    @Test
    fun localLayoutIsAppliedBeforeSendingWithoutWaitingForPhoneEcho() {
        val f = ready()
        val payload = CarPlayUiSyncCommandPayload(CarPlayUiSyncCommand.REQUEST_LAYOUT,
            mapOf("layout" to HongqiHs5UiLayouts.NAVIGATION,
                "activeComponents" to listOf("navigation")))
        val sent = f.session.requestLocalLayout(payload)
        assertEquals(1, f.target.applied)
        assertEquals(payload, sent.commandPayload())
        assertEquals(sent, f.sent.last())
    }

    @Test
    fun rejectedLocalLayoutSendsNothingAndReleasesPresentation() {
        val f = ready()
        val before = f.sent.size
        f.target.accept = false
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            f.session.requestLocalLayout(CarPlayUiSyncCommandPayload(CarPlayUiSyncCommand.REQUEST_LAYOUT,
                mapOf("layout" to HongqiHs5UiLayouts.NAVIGATION)))
        }
        assertEquals(before, f.sent.size)
        assertEquals(1, f.target.inactive)
    }

    @Test
    fun otherOutboundCommandsDoNotApplyTheirDirectionalEffectLocally() {
        val f = ready()
        val focus = CarPlayUiSyncCommandPayload(CarPlayUiSyncCommand.REQUEST_FOCUS,
            mapOf("focusToken" to 1L))
        f.session.command(focus)
        assertEquals(0, f.target.applied)
        assertThrows(IllegalArgumentException::class.java) { f.session.requestLocalLayout(focus) }
        assertEquals(0, f.target.applied)
    }

    @Test
    fun idleResetDeadlineReleasesHostWithoutClaimingReady() {
        var now = 0L
        val f = fixture(clock = { now })
        now = 15_000L
        assertThrows(CarPlayUiSyncProtocolException::class.java) { f.session.checkTimeout() }
        assertEquals(1, f.target.inactive)
        assertTrue(f.sent.isEmpty())
    }

    @Test
    fun unacknowledgedLocalLayoutExpiresWithoutResending() {
        var now = 0L
        val f = ready(clock = { now })
        f.session.requestLocalLayout(CarPlayUiSyncCommandPayload(CarPlayUiSyncCommand.REQUEST_LAYOUT,
            mapOf("layout" to HongqiHs5UiLayouts.NAVIGATION)))
        val count = f.sent.size
        now = 10_000L
        assertThrows(CarPlayUiSyncProtocolException::class.java) { f.session.checkTimeout() }
        assertEquals(count, f.sent.size)
        assertEquals(1, f.target.applied)
        assertEquals(1, f.target.inactive)
    }

    @Test
    fun realAckClearsDeadlineAndNoAckCommandCreatesNone() {
        var now = 0L
        val f = ready(clock = { now })
        val payload = CarPlayUiSyncCommandPayload(CarPlayUiSyncCommand.REQUEST_FOCUS,
            mapOf("focusToken" to 1L))
        val sent = f.session.command(payload)
        f.session.receive(CarPlayUiSyncMessageFactory.commandAck(1, 2, sent.packetSequenceNumber, noAck = true))
        f.session.command(payload, noAck = true)
        now = 60_000L
        f.session.checkTimeout()
        assertEquals(CarPlayUiSyncSessionState.READY, f.session.state)
        assertEquals(0, f.target.inactive)
    }

    @Test
    fun sendFailureAfterLocalApplyReleasesPresentationInsteadOfLeavingSuccessfulState() {
        val target = Target()
        var failSend = false
        val session = CarPlayUiSyncSession(target, {
            if (failSend) throw java.io.IOException("synthetic send failure")
        })
        session.receive(reset())
        session.receive(complete())
        failSend = true
        assertThrows(java.io.IOException::class.java) {
            session.requestLocalLayout(CarPlayUiSyncCommandPayload(CarPlayUiSyncCommand.REQUEST_LAYOUT,
                mapOf("layout" to HongqiHs5UiLayouts.NAVIGATION)))
        }
        assertEquals(1, target.applied)
        assertEquals(1, target.inactive)
    }

    @Test
    fun hostExceptionIsNeverAcknowledgedAndClosesStateMachine() {
        val f = ready()
        f.target.failApply = true
        val before = f.sent.size
        assertThrows(IllegalStateException::class.java) { f.session.receive(command()) }
        assertEquals(before, f.sent.size)
        assertEquals(1, f.target.inactive)
    }

    @Test
    fun explicitResetHostFailureAlsoReleasesPresentationWithoutSending() {
        var inactive = 0
        val sent = mutableListOf<CarPlayUiSyncMessage>()
        val target = object : CarPlayUiSyncTarget {
            override fun reset(): Boolean = throw IllegalStateException("synthetic reset failure")
            override fun apply(payload: CarPlayUiSyncCommandPayload) = true
            override fun inactive() { inactive++ }
        }
        val session = CarPlayUiSyncSession(target, { sent += it })
        assertThrows(IllegalStateException::class.java) { session.begin(1) }
        assertTrue(sent.isEmpty())
        assertEquals(1, inactive)
    }

    private fun ready(policy: CarPlayUiSyncInteropPolicy = CarPlayUiSyncInteropPolicy.EXPERIMENTAL_V3,
        clock: () -> Long = { System.nanoTime() / 1_000_000L }) =
        fixture(policy, clock).also { it.session.receive(reset()); it.session.receive(complete()) }

    private fun fixture(policy: CarPlayUiSyncInteropPolicy = CarPlayUiSyncInteropPolicy.EXPERIMENTAL_V3,
        clock: () -> Long = { System.nanoTime() / 1_000_000L }): Fixture {
        val target = Target()
        val sent = mutableListOf<CarPlayUiSyncMessage>()
        return Fixture(target, sent, CarPlayUiSyncSession(target, { sent += it }, policy, clock))
    }

    private fun reset(ssn: Long = 1) = CarPlayUiSyncMessageFactory.reset(ssn, 0, 0,
        version = CarPlayUiSyncProtocolVersion.V3)
    private fun complete(ssn: Long = 1) = CarPlayUiSyncMessageFactory.resetComplete(ssn, 1, 0)
    private fun command() = CarPlayUiSyncMessageFactory.command(1, 2, 0,
        CarPlayUiSyncCommand.TARGET_APPEARANCE_CHANGE, mapOf("appearanceMode" to "dark"))

    private data class Fixture(val target: Target, val sent: MutableList<CarPlayUiSyncMessage>,
        val session: CarPlayUiSyncSession)
    private class Target : CarPlayUiSyncTarget {
        var resets = 0
        var applied = 0
        var inactive = 0
        var accept = true
        var failApply = false
        override fun reset(): Boolean { resets++; return true }
        override fun apply(payload: CarPlayUiSyncCommandPayload): Boolean {
            applied++
            if (failApply) throw IllegalStateException("synthetic host failure")
            return accept
        }
        override fun inactive() { inactive++ }
    }
}
