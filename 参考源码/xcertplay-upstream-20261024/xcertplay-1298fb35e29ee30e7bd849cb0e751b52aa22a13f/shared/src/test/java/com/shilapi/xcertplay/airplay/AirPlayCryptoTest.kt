package com.shilapi.xcertplay.airplay

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AirPlayCryptoTest {
    private val key = ByteArray(32) { (it * 7 + 3).toByte() }

    @Test
    fun openMatchesBouncyCastleWithAndWithoutAad() {
        for (aad in listOf(ByteArray(0), byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))) {
            for (counter in listOf(0L, 1L, 0x0102030405060708L)) {
                val nonce = AirPlayCrypto.nonce64(counter)
                val plain = ByteArray(1500) { (it xor counter.toInt()).toByte() }
                val sealed = bouncyCastleSeal(nonce, plain, aad)
                assertArrayEquals(plain, AirPlayCrypto.chachaOpen(key, nonce, sealed, aad))
            }
        }
    }

    @Test
    fun openRejectsTamperedFramesAndWrongAad() {
        val nonce = AirPlayCrypto.nonce64(9)
        val aad = byteArrayOf(9, 9, 9, 9)
        val sealed = bouncyCastleSeal(nonce, ByteArray(64) { it.toByte() }, aad)
        val tampered = sealed.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { AirPlayCrypto.chachaOpen(key, nonce, tampered, aad) }
        assertThrows(Exception::class.java) { AirPlayCrypto.chachaOpen(key, nonce, sealed, byteArrayOf(1)) }
        // A failed frame must not poison the reused per-thread cipher.
        assertArrayEquals(ByteArray(64) { it.toByte() }, AirPlayCrypto.chachaOpen(key, nonce, sealed, aad))
    }

    private fun bouncyCastleSeal(nonce: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val output = ByteArray(cipher.getOutputSize(plain.size))
        val length = cipher.processBytes(plain, 0, plain.size, output, 0)
        cipher.doFinal(output, length)
        return output
    }
}
