package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Replays the phone side of a recorded iOS 26 wireless bring-up (Bluetooth RFCOMM, then the
 * AirPlay type-130 tunnel). Message order and bodies follow the capture; every device identifier,
 * credential and certificate was replaced with a synthetic value.
 */
class Iap2WirelessSessionReplayTest {
    @Test(timeout = 15_000)
    fun bluetoothBringUpMatchesRecordedSession() {
        val replay = replay(
            phoneLink = Iap2LinkConfig(maxOutgoing = 4),
            phoneInitiates = true,
            open = Iap2Session::openWireless,
            locationProvider = null,
            // The controller closes the Bluetooth link itself once the Wi-Fi tunnel takes over.
            closeLocallyAfter = "iap2 rx=0xae01",
        )

        assertEquals(
            listOf(0x1d01, 0xaa01, 0xaa03, 0x5000, 0x5200, 0xae00, 0x4157, 0x4154, 0x6800, 0x5703, 0x5703, 0x4301),
            replay.sent.map { it.messageId },
        )
        val identification = parameters(replay.sent.first())
        assertEquals(RECORDED_SENT, u16Values(identification.single { it.id == 6 }.payload))
        assertEquals(RECORDED_RECEIVED, u16Values(identification.single { it.id == 7 }.payload))
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 13, 17, 18, 24), identification.map { it.id })

        assertArrayEquals(FAKE_CERTIFICATE, parameters(replay.sent[1]).single().payload)
        assertArrayEquals(Iap2WirelessControlClient.accessoryWiFiConfiguration(ENDPOINT).payload, replay.sent[9].payload)
        assertArrayEquals(Iap2WirelessControlClient.carPlayStartSession(ENDPOINT).payload, replay.sent.last().payload)
        assertEquals(listOf(0x5001, 0x4158, 0xae01), replay.forwarded.map { it.messageId })
        assertTrue(replay.progress.any { "bluetooth=02:00:00:00:00:0a usb=TEST-USB-ID" in it })
        assertEquals(
            Iap2WirelessControlResult(
                terminal = Iap2WirelessControlTerminal.CHANNEL_CLOSED,
                stage = Iap2WirelessControlStage.POST_TRANSPORT_WIFI_CONFIG_SENT,
                forwardedFrames = 3,
                wifiConfigurationsSent = 2,
                carPlayStartSessionsSent = 1,
                transportNotificationSeen = true,
                postTransportWiFiConfigurationsSent = 1,
                wirelessCarPlayAvailableSeen = true,
            ),
            replay.outcome.getOrThrow(),
        )
        assertEquals(listOf("ready", "stopped"), replay.lifecycle)
    }

    @Test(timeout = 15_000)
    fun tunnelBringUpAdvertisesAndServesLocation() {
        val location = FakeLocation()
        val replay = replay(
            phoneLink = Iap2LinkConfig(maxOutgoing = 4, zeroAcknowledgements = true),
            phoneInitiates = false,
            open = Iap2Session::openTunnel,
            locationProvider = location,
            closeLocallyAfter = null,
        )

        assertEquals(0xfffb, replay.sent.last().messageId)
        assertEquals(0x4301, replay.sent[replay.sent.size - 2].messageId)
        val identification = parameters(replay.sent.first())
        assertEquals(
            RECORDED_SENT.dropLast(1) + 0xfffb + RECORDED_SENT.last(),
            u16Values(identification.single { it.id == 6 }.payload),
        )
        assertEquals(
            RECORDED_RECEIVED.dropLast(3) + listOf(0xfffa, 0xfffc) + RECORDED_RECEIVED.takeLast(3),
            u16Values(identification.single { it.id == 7 }.payload),
        )
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 13, 17, 18, 22, 24), identification.map { it.id })

        assertEquals(listOf(setOf(1, 2)), location.requested)
        // One parameter 0 per subscribed sentence, each CRLF and NUL terminated.
        assertEquals(
            FAKE_NMEA.split("\r\n").filter { it.isNotEmpty() }.map { "$it\r\n\u0000" },
            parameters(replay.sent.last()).map { it.payload.decodeToString() },
        )
        // A phone-side hang-up is a link failure, not a clean CHANNEL_CLOSED, but still tears down.
        assertEquals("iAP2 link ended", replay.outcome.exceptionOrNull()?.message)
        assertEquals(listOf("ready", "stopped"), replay.lifecycle)
        assertTrue(location.stopped)
    }

    private class Replay(
        val sent: List<Iap2Frame>,
        val forwarded: List<Iap2Frame>,
        val progress: List<String>,
        val lifecycle: List<String>,
        val outcome: Result<Iap2WirelessControlResult>,
    )

    private fun replay(
        phoneLink: Iap2LinkConfig,
        phoneInitiates: Boolean,
        open: (BlockingDuplexByteStream, String, (String) -> Unit) -> Iap2Session,
        locationProvider: Iap2LocationProvider?,
        closeLocallyAfter: String?,
    ): Replay {
        var wifiConfigurations = 0
        val phone = ScriptedIap2Phone(
            phoneLink,
            phoneInitiates,
            onLinkUp = { send(frame(0x1d00) {}) },
        ) { frame ->
            when (frame.messageId) {
                0x1d01 -> {
                    send(frame(0x1d02) {})
                    send(frame(0xaa00) {})
                }
                0xaa01 -> send(frame(0xaa02) { bytes(0, ByteArray(32) { it.toByte() }) })
                0xaa03 -> send(frame(0xaa05) {})
                0x6800 -> send(frame(0x5702) {})
                0x5703 -> if (++wifiConfigurations == 1) {
                    send(frame(0x4e0d) { u8(0, 1) })
                    send(frame(0x4e0e) {
                        string(0, FAKE_BLUETOOTH)
                        string(1, FAKE_USB)
                    })
                } else {
                    send(frame(0x4300) {
                        group(0) { u8(0, 1); string(1, FAKE_USB) }
                        group(1) { u8(0, 1); string(1, FAKE_BLUETOOTH) }
                        group(2) { u8(0, 1) }
                    })
                }
                0x4301 -> if (locationProvider == null) {
                    // Updates for the subscriptions, forwarded untouched to the controller.
                    send(frame(0x5001) { bytes(0, byteArrayOf(0, 5, 0, 0x1a, 0x81.toByte())) })
                    send(frame(0x4158) { u8(0, 3); string(4, "Carrier"); u8(5, 1) })
                    send(frame(0xae01) { u16(6, 56) })
                } else {
                    send(frame(0xfffa) {
                        u32(0, 250); void(1); u32(0x8001, 250); void(2); u32(0x8002, 250)
                    })
                }
                0xfffb -> hangUp()
            }
        }.start()

        val progress = CopyOnWriteArrayList<String>()
        val forwarded = CopyOnWriteArrayList<Iap2Frame>()
        val lifecycle = CopyOnWriteArrayList<String>()
        val session = open(phone.accessoryStream, "replay") {}
        val runner = Thread.currentThread()
        val closer = closeLocallyAfter?.let { marker ->
            Thread {
                // Close only while run() is parked in recv, as the controller does on handoff.
                while (progress.none { it == marker } || runner.state != Thread.State.TIMED_WAITING) Thread.sleep(1)
                session.close()
            }.apply { isDaemon = true; start() }
        }
        val outcome = runCatching {
            Iap2WirelessControlClient(session, Iap2MfiAuthenticationClient(FakeMfi)).run(
                identification = IDENTIFICATION,
                endpoint = ENDPOINT,
                bringUpTimeoutMillis = 10_000,
                locationProvider = locationProvider,
                onReady = { lifecycle += "ready" },
                onStopped = { lifecycle += "stopped" },
                onIncoming = { frame -> forwarded += frame },
                onProgress = { message -> progress += message },
            )
        }
        session.close()
        closer?.join(5_000)
        phone.join(5_000)
        return Replay(phone.received.toList(), forwarded.toList(), progress, lifecycle, outcome)
    }

    private object FakeMfi : MfiAuthenticator {
        override fun protocolMajor(): Int = 3
        override fun readCertificate(maximumOutputLength: Int): ByteArray = FAKE_CERTIFICATE
        override fun signChallenge(challenge: ByteArray): ByteArray = ByteArray(64) { 0x5a }
    }

    private class FakeLocation : Iap2LocationProvider {
        val requested = CopyOnWriteArrayList<Set<Int>>()
        @Volatile var stopped = false
        override fun start(): Boolean = true
        override fun onRequested(components: Set<Int>) {
            requested += components
        }
        override fun stop() {
            stopped = true
        }
        override fun latestNmea(): String = FAKE_NMEA
    }

    private fun frame(messageId: Int, block: com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder.() -> Unit) =
        Iap2Messages.buildRaw(messageId, block)

    private fun parameters(frame: Iap2Frame) = Iap2ParameterList.parse(frame.payload).asList()

    private fun u16Values(bytes: ByteArray): List<Int> =
        List(bytes.size / 2) { ((bytes[it * 2].toInt() and 0xff) shl 8) or (bytes[it * 2 + 1].toInt() and 0xff) }

    private companion object {
        const val FAKE_BLUETOOTH = "02:00:00:00:00:0a"
        const val FAKE_USB = "TEST-USB-ID"
        const val FAKE_NMEA = "\$GPGGA,000000.00,0100.0000,N,00100.0000,E,1,08,1.0,0.0,M,0.0,M,,*00\r\n" +
            "\$GPRMC,000000.00,A,0100.0000,N,00100.0000,E,0.0,0.0,010170,,,A*00\r\n"
        val FAKE_CERTIFICATE = ByteArray(16) { (0xc0 + it).toByte() }

        /** Accessory message lists from the recorded IdentificationInformation without location. */
        val RECORDED_SENT = listOf(
            0x5000, 0x5002, 0x5200, 0x5203, 0xae00, 0xae02, 0x4157, 0x4159, 0x4154, 0x4156, 0x4301, 0x6800,
            0x6802, 0x6803, 0x5703,
        )
        val RECORDED_RECEIVED = listOf(
            0xea00, 0xea01, 0x5001, 0x5201, 0x5202, 0xae01, 0x4158, 0x4155, 0x4300, 0x6801, 0x4e0d, 0x4e0e, 0x5702,
        )

        val IDENTIFICATION = Iap2IdentificationConfig(
            name = "xcertplay-test",
            modelIdentifier = "xcertplay-test",
            manufacturer = "xcertplay",
            serialNumber = "0000001",
            firmwareVersion = "1.0.0",
            hardwareVersion = "1.0",
            wireless = Iap2WirelessIdentification(bluetoothMac = "02:00:00:00:00:01", ssid = "xcertplay-test"),
        )
        val ENDPOINT = Iap2WirelessCarPlayEndpoint(
            ssid = "xcertplay-test",
            passphrase = "test-passphrase",
            channel = 0,
            security = Iap2WirelessSecurity.WPA_WPA2,
            ipAddresses = listOf("192.168.50.1"),
            airPlayPort = 7000,
            deviceIdentifier = "02:00:00:00:00:02",
            publicKey = "00".repeat(32),
            sourceVersion = "366.0",
        )
    }
}
