package com.shilapi.xcertplay.airplay.rcs.caf

import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.hud.*
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class Hs5CafVehiclePluginTest {
    @Test fun configAndReadReturnRealValuesAndNonzeroUnknownErrors() = fixture { f ->
        f.message(CarAccessoryMessages.configRequest(1, 1))
        assertEquals(1, f.next(CafCommand.CONFIG_RESPONSE).second.configTree(CafFirmwareConfigTreeDecoder).accessories.size)
        f.message(CarAccessoryMessages.readRequest(1, 2, listOf(DRIVER, FAN, 999)))
        val response = f.next(CafCommand.READ_RESPONSE).second
        assertEquals(25.0, response.valuesMap()[DRIVER])
        assertEquals(3L, response.valuesMap()[FAN])
        assertEquals(setOf(999L), response.errors().keys)
        assertNotEquals(0L, response.errors()[999L])
    }

    @Test fun subscriptionUpdatesAreIndependentAndUnregisterStopsThatIid() = fixture { f ->
        val second = f.open()
        f.message(CarAccessoryMessages.registerRequest(1, 1, CafRegistration.Identifiers(listOf(DRIVER))))
        f.next(CafCommand.REGISTER_RESPONSE)
        f.message(CarAccessoryMessages.registerRequest(1, 2, CafRegistration.Identifiers(listOf(FAN))), second)
        f.next(CafCommand.REGISTER_RESPONSE)
        f.source.update(HongqiVehicleField.DRIVER_TEMPERATURE, 26.0)
        f.source.update(HongqiVehicleField.FAN, 4.0)
        val notifications = listOf(f.next(CafCommand.UPDATE_NOTIFY), f.next(CafCommand.UPDATE_NOTIFY))
        assertEquals(mapOf(DRIVER to 26.0), notifications.single { it.first === f.session }.second.valuesMap())
        assertEquals(mapOf(FAN to 4L), notifications.single { it.first === second }.second.valuesMap())
        f.message(CarAccessoryMessages.unregisterRequest(1, 3, listOf(DRIVER)))
        f.next(CafCommand.UNREGISTER_RESPONSE)
        f.source.update(HongqiVehicleField.DRIVER_TEMPERATURE, 27.0)
        f.source.update(HongqiVehicleField.FAN, 5.0)
        val next = f.next(CafCommand.UPDATE_NOTIFY)
        assertSame(second, next.first)
        assertEquals(mapOf(FAN to 5L), next.second.valuesMap())
    }

    @Test fun unavailableStateInvalidatesSubscribedMeasurementWithoutZeroFallback() = fixture { f ->
        f.message(CarAccessoryMessages.registerRequest(1, 1, CafRegistration.Identifiers(listOf(DRIVER))))
        f.next(CafCommand.REGISTER_RESPONSE)
        f.source.snapshot = f.source.snapshot.copy(status = HongqiVehicleConnectionState.UNAVAILABLE)
        val update = f.next(CafCommand.UPDATE_NOTIFY).second.valuesMap()
        assertTrue(update.containsKey(DRIVER))
        assertNull(update[DRIVER])
        f.message(CarAccessoryMessages.readRequest(1, 2, listOf(DRIVER)))
        val read = f.next(CafCommand.READ_RESPONSE).second
        assertTrue(read.valuesMap().isEmpty())
        assertNotEquals(0L, read.errors()[DRIVER])
    }

    @Test fun writeAcknowledgementWaitsForConfirmationAndRetransmissionNeverResends() = fixture { f ->
        val frame = CarAccessoryMessages.writeRequest(1, 10, mapOf(DRIVER to 26.0))
        f.message(frame)
        val pending = f.source.nextCommand()
        f.message(frame)
        f.message(CarAccessoryMessages.readRequest(1, 11, listOf(DRIVER)))
        assertEquals(25.0, f.next(CafCommand.READ_RESPONSE).second.valuesMap()[DRIVER])
        assertEquals(1, f.source.calls.get())
        f.source.update(HongqiVehicleField.DRIVER_TEMPERATURE, 26.0)
        pending.callback(HongqiVehicleControlResult(pending.command, HongqiVehicleControlStatus.CONFIRMED))
        assertEquals(mapOf(DRIVER to 0L), f.next(CafCommand.WRITE_RESPONSE).second.errors())
        f.message(frame)
        assertEquals(mapOf(DRIVER to 0L), f.next(CafCommand.WRITE_RESPONSE).second.errors())
        assertEquals(1, f.source.calls.get())
    }

    @Test fun batchWritesAreSequentialAndTimeoutIsFailureWithoutRetry() = fixture { f ->
        f.message(CarAccessoryMessages.writeRequest(1, 10, linkedMapOf(DRIVER to 26.0, FAN to 4L)))
        val first = f.source.nextCommand()
        assertTrue(first.command is HongqiVehicleControlCommand.Temperature)
        assertEquals(1, f.source.calls.get())
        first.callback(HongqiVehicleControlResult(first.command, HongqiVehicleControlStatus.CONFIRMED))
        val second = f.source.nextCommand()
        assertEquals(HongqiVehicleControlCommand.Fan(4), second.command)
        // A repeated callback from the prior operation cannot complete the next operation.
        first.callback(HongqiVehicleControlResult(first.command, HongqiVehicleControlStatus.CONFIRMED))
        second.callback(HongqiVehicleControlResult(second.command, HongqiVehicleControlStatus.TIMED_OUT))
        val errors = f.next(CafCommand.WRITE_RESPONSE).second.errors()
        assertEquals(0L, errors[DRIVER])
        assertNotEquals(0L, errors[FAN])
        f.message(CarAccessoryMessages.readRequest(1, 11, listOf(FAN)))
        f.next(CafCommand.READ_RESPONSE)
        assertEquals(2, f.source.calls.get())
    }

    @Test fun unknownAndMalformedWritesNeverReachVehicle() = fixture { f ->
        f.message(CarAccessoryMessages.writeRequest(1, 10,
            linkedMapOf(999L to 1L, DRIVER to 99.0, FAN to 2.5)))
        val errors = f.next(CafCommand.WRITE_RESPONSE).second.errors()
        assertEquals(setOf(999L, DRIVER, FAN), errors.keys)
        assertTrue(errors.values.all { it != 0L })
        assertEquals(0, f.source.calls.get())
    }

    @Test fun closingChannelCancelsPendingAndDropsRemainingCommandsAndLateCallback() = fixture { f ->
        f.message(CarAccessoryMessages.writeRequest(1, 10, linkedMapOf(DRIVER to 26.0, FAN to 4L)))
        val first = f.source.nextCommand()
        f.session.close()
        assertTrue(first.cancelled.await(2, TimeUnit.SECONDS))
        assertTrue(f.source.released.await(2, TimeUnit.SECONDS))
        first.callback(HongqiVehicleControlResult(first.command, HongqiVehicleControlStatus.CONFIRMED))
        // A fresh channel is a new lifetime; the previous unissued fan command must not follow it.
        val second = f.open()
        f.message(CarAccessoryMessages.readRequest(1, 12, listOf(FAN)), second)
        f.next(CafCommand.READ_RESPONSE)
        assertEquals(1, f.source.calls.get())
    }

    @Test fun closingChannelCancelsVehicleCommandEvenWhenNetworkWriterIsBlocked() = fixture { f ->
        f.message(CarAccessoryMessages.writeRequest(1, 10, mapOf(DRIVER to 26.0)))
        val pending = f.source.nextCommand()
        f.blockReadResponse.set(true)
        f.message(CarAccessoryMessages.readRequest(1, 11, listOf(DRIVER)))
        assertTrue(f.writerBlocked.await(2, TimeUnit.SECONDS))
        try {
            f.session.close()
            assertTrue("Command cancellation waited behind network IO", pending.cancelled.await(200, TimeUnit.MILLISECONDS))
            assertTrue("Vehicle lease waited behind network IO", f.source.released.await(200, TimeUnit.MILLISECONDS))
        } finally { f.unblockWriter.countDown() }
    }

    @Test fun lifecycleNotifiesEachSharedHandlerOnceAndClosingUnopenedChannelDoesNotOpenIt() {
        var opened = 0
        var closed = 0
        val handler = object : CafPluginHandler {
            override fun onSessionOpened(session: CafProtocolSession) { opened++ }
            override fun onSessionClosed(session: CafProtocolSession) { closed++ }
        }
        val registrations = listOf(1L, 2L).map {
            CafPluginRegistration(it, "1.0", CafPluginRegistry.DEFAULT_CLIENT_TYPES, handler)
        }
        val registry = CafPluginRegistry.from(registrations)
        val session = session(registry)
        session.onStreamOpened(session.stream)
        session.onStreamOpened(session.stream)
        session.close()
        session.onStreamClosed(session.stream, null)
        assertEquals(1, opened)
        assertEquals(1, closed)
        session(registry).close()
        assertEquals(1, opened)
        assertEquals(1, closed)
    }

    private class Pending(val command: HongqiVehicleControlCommand,
        val callback: (HongqiVehicleControlResult) -> Unit,
        val cancelled: CountDownLatch = CountDownLatch(1))

    private class Source : Hs5CafVehicleSource {
        val calls = AtomicInteger()
        val released = CountDownLatch(1)
        val commands: BlockingQueue<Pending> = LinkedBlockingQueue()
        @Volatile var snapshot = HongqiVehicleSnapshot(HongqiVehicleConnectionState.READY,
            mapOf(HongqiVehicleField.DRIVER_TEMPERATURE to HongqiVehicleReading(25.0, 1_000L),
                HongqiVehicleField.PASSENGER_TEMPERATURE to HongqiVehicleReading(26.0, 1_000L),
                HongqiVehicleField.FAN to HongqiVehicleReading(3.0, 1_000L)),
            capturedAtElapsedMillis = 1_000L)
        override fun acquire() = Closeable { released.countDown() }
        override fun snapshot() = snapshot
        override fun submit(command: HongqiVehicleControlCommand, callback: (HongqiVehicleControlResult) -> Unit): Closeable {
            calls.incrementAndGet()
            val pending = Pending(command, callback)
            commands.add(pending)
            return Closeable { pending.cancelled.countDown() }
        }
        @Synchronized fun update(field: HongqiVehicleField, value: Double) {
            snapshot = snapshot.copy(readings = snapshot.readings + (field to HongqiVehicleReading(value, 1_000L)))
        }
        fun nextCommand(): Pending = checkNotNull(commands.poll(2, TimeUnit.SECONDS)) { "No command received" }
    }

    private class Fixture : Closeable {
        val source = Source()
        val frames: BlockingQueue<Pair<CafProtocolSession, CafRcsFrame>> = LinkedBlockingQueue()
        val blockReadResponse = AtomicBoolean()
        val writerBlocked = CountDownLatch(1)
        val unblockWriter = CountDownLatch(1)
        val plugin = Hs5CafVehiclePlugin(source, { 1_000L }, { session, frame ->
            frames.add(session to frame)
            if (blockReadResponse.get() && frame.envelope.message.command == CafCommand.READ_RESPONSE) {
                writerBlocked.countDown()
                unblockWriter.await(2, TimeUnit.SECONDS)
            }
            Unit
        }, 20L)
        val sessions = mutableListOf<CafProtocolSession>()
        val session = open()
        fun open(): CafProtocolSession = session(CafPluginRegistry.from(listOf(plugin.registration()))).also {
            sessions.add(it)
            it.onStreamOpened(it.stream)
        }
        fun message(frame: CafRcsFrame, target: CafProtocolSession = session) =
            target.onMessage(target.stream, frame.asRcsMessage())
        fun next(command: CafCommand): Pair<CafProtocolSession, CafMessageReader> {
            val result = checkNotNull(frames.poll(2, TimeUnit.SECONDS)) { "No $command frame" }
            assertEquals(command, result.second.envelope.message.command)
            return result.first to CarAccessoryMessages.reader(result.second.rcsBody)
        }
        override fun close() { unblockWriter.countDown(); sessions.forEach { it.close() }; plugin.close() }
    }

    private fun fixture(test: (Fixture) -> Unit) = Fixture().use(test)

    companion object {
        private const val DRIVER = Hs5CafVehicleModel.DRIVER_TEMPERATURE_IID
        private const val FAN = Hs5CafVehicleModel.FAN_IID
        private fun session(registry: CafPluginRegistry): CafProtocolSession {
            val stream = RcsDataStream.open(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, ByteArray(32), ByteArray(32))
            return CafProtocolSession(stream.clientType, stream, registry, ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1)
        }
    }
}
