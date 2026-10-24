package com.shilapi.xcertplay.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmMonoResamplerTest {
    private fun tone(rate: Int, hz: Double, samples: Int): ByteArray = ByteArray(samples * 2).also { out ->
        for (i in 0 until samples) {
            val v = (10_000 * sin(2 * PI * hz * i / rate)).toInt()
            out[i * 2] = v.toByte()
            out[i * 2 + 1] = (v shr 8).toByte()
        }
    }

    private fun convert(input: ByteArray, from: Int, to: Int, chunk: Int): ByteArray {
        val resampler = PcmMonoResampler(from, to)
        val out = java.io.ByteArrayOutputStream()
        var offset = 0
        while (offset < input.size) {
            val count = minOf(chunk, input.size - offset)
            out.write(resampler.convert(input, offset, count))
            offset += count
        }
        return out.toByteArray()
    }

    private fun pcm(bytes: ByteArray) = DoubleArray(bytes.size / 2) { i ->
        ((bytes[i * 2].toInt() and 0xff) or (bytes[i * 2 + 1].toInt() shl 8)).toShort().toDouble()
    }

    /** Goertzel 幅度（相对满幅正弦，返回峰值幅度）。 */
    private fun amplitude(x: DoubleArray, rate: Int, hz: Double): Double {
        val w = 2 * PI * hz / rate
        var re = 0.0
        var im = 0.0
        for (i in x.indices) {
            re += x[i] * cos(w * i)
            im += x[i] * sin(w * i)
        }
        return 2 * sqrt(re * re + im * im) / x.size
    }

    private fun db(ratio: Double) = 20 * log10(maxOf(ratio, 1e-9))

    @Test
    fun outputIsIndependentOfHowInputIsChunked() {
        for ((from, to) in listOf(16_000 to 24_000, 16_000 to 48_000, 16_000 to 44_100, 16_000 to 8_000)) {
            val input = tone(from, 1_300.0, 16_000) + tone(from, 3_700.0, 16_000)
            val whole = convert(input, from, to, input.size)
            for (chunk in listOf(1, 3, 640, 2_047, 2_048)) {
                assertArrayEquals("$from->$to chunk=$chunk", whole, convert(input, from, to, chunk))
            }
        }
    }

    @Test
    fun outputLengthTracksRateRatio() {
        for (to in listOf(8_000, 16_000, 24_000, 32_000, 44_100, 48_000)) {
            val out = convert(tone(16_000, 1_000.0, 16_000), 16_000, to, 2_048).size / 2
            assertTrue("16000->$to produced $out", abs(out - to) <= 2)
        }
    }

    @Test
    fun upsamplingKeepsTheToneAndReportsImageLevel() {
        for (to in listOf(24_000, 48_000, 44_100)) {
            val out = pcm(convert(tone(16_000, 1_000.0, 32_000), 16_000, to, 2_048))
            val fundamental = amplitude(out, to, 1_000.0)
            val image = amplitude(out, to, 15_000.0)
            println("RESAMPLER up 16000->$to fundamental=${"%.0f".format(fundamental)} image15k=${"%.1f".format(db(image / fundamental))}dB")
            assertEquals(10_000.0, fundamental, 300.0)
        }
    }

    @Test
    fun downsamplingReportsAliasLevel() {
        val out = pcm(convert(tone(16_000, 6_000.0, 32_000), 16_000, 8_000, 2_048))
        val alias = amplitude(out, 8_000, 2_000.0)
        println("RESAMPLER down 16000->8000 6kHz tone aliases to 2kHz at ${"%.1f".format(db(alias / 10_000.0))}dB")
    }
}
