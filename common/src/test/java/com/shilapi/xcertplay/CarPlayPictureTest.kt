package com.shilapi.xcertplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class CarPlayPictureTest {
    private val prefs get() = CarPlayPicture.preferences(RuntimeEnvironment.getApplication())
    @Before fun reset() { prefs.edit().clear().commit() }

    @Test fun defaultsPreservePixelsAndAlpha() {
        assertArrayEquals(floatArrayOf(42f, 128f, 219f, 71f), pixel(42f, 128f, 219f, 71f), 0.001f)
    }

    @Test fun allLegacyPictureAdjustmentsLeavePixelsAndAlphaUnchanged() {
        for (key in CarPlayPicture.keys) {
            for (value in listOf(-100, 0, 50, 125, 200, 999)) {
                prefs.edit().putInt(key, value).commit()
                assertEquals(CarPlayPicture.defaultValue(key), CarPlayPicture.value(prefs, key))
                assertArrayEquals(floatArrayOf(255f, 0f, 100f, 71f), pixel(255f, 0f, 100f, 71f), 0.001f)
            }
        }
    }

    private fun pixel(red: Float, green: Float, blue: Float, alpha: Float): FloatArray {
        val input = floatArrayOf(red, green, blue, alpha)
        val matrix = CarPlayPicture.matrix(prefs).array
        return FloatArray(4) { row ->
            matrix[row * 5 + 4] + (0..3).sumOf { col ->
                (matrix[row * 5 + col] * input[col]).toDouble()
            }.toFloat()
        }
    }
}
