package com.shilapi.xcertplay.airplay

import org.bouncycastle.crypto.InvalidCipherTextException
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PlatformVideoCryptoTest {
    @Test fun platformAndFallbackAgreeForVideoAadAndRejectModifiedTags() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = AirPlayCrypto.nonce64(42)
        val aad = ByteArray(128) { (it * 3).toByte() }
        val plain = ByteArray(100_000) { (it * 7).toByte() }
        val wire = AirPlayCrypto.chachaSeal(key, nonce, plain, aad)
        assertArrayEquals(plain, AirPlayCrypto.chachaOpen(key, nonce, wire, aad))
        assertArrayEquals(plain, AirPlayCrypto.chachaOpenBouncyCastle(key, nonce, wire, aad))
        val bad = wire.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(InvalidCipherTextException::class.java) { AirPlayCrypto.chachaOpen(key, nonce, bad, aad) }
        assertThrows(InvalidCipherTextException::class.java) { AirPlayCrypto.chachaOpen(key, nonce, wire, aad.copyOf().also { it[0] = 1 }) }
    }

    @Test fun independentReceiveThreadsDoNotShareMutableCipherState() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0..63).map { n -> pool.submit<Boolean> {
                val key = ByteArray(32) { (n + it).toByte() }
                val plain = ByteArray(1000) { (n * it).toByte() }
                val nonce = AirPlayCrypto.nonce64(n.toLong())
                plain.contentEquals(AirPlayCrypto.chachaOpen(key, nonce, AirPlayCrypto.chachaSeal(key, nonce, plain)))
            } }
            tasks.forEach { assertTrue(it.get(3, TimeUnit.SECONDS)) }
        } finally { pool.shutdownNow() }
    }
}
