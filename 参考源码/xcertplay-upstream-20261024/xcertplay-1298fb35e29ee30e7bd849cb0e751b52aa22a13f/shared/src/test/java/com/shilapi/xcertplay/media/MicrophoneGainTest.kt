package com.shilapi.xcertplay.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MicrophoneGainTest {
    @Test
    fun gainRangeIsClamped() {
        assertEquals(50, MicrophoneGain.sanitize(20))
        assertEquals(130, MicrophoneGain.sanitize(130))
        assertEquals(1000, MicrophoneGain.sanitize(5000))
    }

    @Test
    fun sliderStepsAreLogarithmic() {
        assertEquals(27, MicrophoneGain.STEP_COUNT)
        assertEquals(50, MicrophoneGain.percentForStep(0))
        assertEquals(100, MicrophoneGain.percentForStep(6))
        assertEquals(200, MicrophoneGain.percentForStep(12))
        assertEquals(1000, MicrophoneGain.percentForStep(26))
        for (step in 0 until MicrophoneGain.STEP_COUNT) {
            assertEquals(step, MicrophoneGain.stepForPercent(MicrophoneGain.percentForStep(step)))
        }
        assertEquals(6, MicrophoneGain.stepForPercent(MicrophoneGain.DEFAULT_PERCENT))
        assertEquals(0, MicrophoneGain.stepForPercent(20))
        assertEquals(26, MicrophoneGain.stepForPercent(5000))
    }

    @Test
    fun pcmGainAmplifiesAndSaturatesInPlace() {
        val pcm = pcm(-20_000, -1_000, 0, 1_000, 20_000)

        val peak = MicrophoneGain.applyPcm16InPlace(pcm, pcm.size, 200)

        assertArrayEquals(pcm(-32_768, -2_000, 0, 2_000, 32_767), pcm)
        assertEquals(32_768, peak)
        assertEquals(100, MicrophoneGain.peakPercent(peak))
    }

    @Test
    fun maximumGainAmplifiesTenfold() {
        val pcm = pcm(-1_000, 1_000, 32_767)

        val peak = MicrophoneGain.applyPcm16InPlace(pcm, pcm.size, 1000)

        assertArrayEquals(pcm(-10_000, 10_000, 32_767), pcm)
        assertEquals(32_767, peak)
    }

    @Test
    fun minimumGainHalvesSamples() {
        val pcm = pcm(-10_000, 10_000)

        val peak = MicrophoneGain.applyPcm16InPlace(pcm, pcm.size, 50)

        assertArrayEquals(pcm(-5_000, 5_000), pcm)
        assertEquals(5_000, peak)
    }

    private fun pcm(vararg samples: Int): ByteArray = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { index, value ->
            bytes[index * 2] = value.toByte()
            bytes[index * 2 + 1] = (value ushr 8).toByte()
        }
    }
}
