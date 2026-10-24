package com.shilapi.xcertplay.iap2.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class Iap2FileTransferHandlerTest {
    @Test fun nowPlayingArtworkCompletesOnTheXcertplaySessionPath() {
        val handler = Iap2FileTransferHandler(nowMillis = { 0 })
        val setup = byteArrayOf(
            7, 0x04, 0, 0, 0, 0, 0, 0, 0, 4, 0, 2,
        )
        val started = handler.handle(setup)
        assertNotNull(started.response)
        assertEquals(0x01, started.response!![1].toInt() and 0xff)

        val payload = byteArrayOf(1, 2, 3, 4)
        val done = handler.handle(byteArrayOf(7, 0xc0.toByte(), *payload))
        val artwork = requireNotNull(done.artwork)
        assertEquals(7, artwork.fileTransferId)
        assertArrayEquals(payload, artwork.bytes)
        assertEquals(0x05, done.response!![1].toInt() and 0xff)
    }
}
