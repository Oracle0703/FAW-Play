package com.shilapi.xcertplay

import android.os.Handler
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.ExecutorService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayHostResolutionTest {
    private lateinit var activity: CarPlayHostActivity

    @Before fun setup() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        field("airPlayIdentity").set(activity, AirPlayIdentity.generate())
    }

    @After fun cleanup() {
        (field("mainHandler").get(activity) as Handler).removeCallbacksAndMessages(null)
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            (field(name).get(activity) as ExecutorService).shutdownNow()
        }
    }

    @Test fun legacyScalingCannotChangeTheHs5AdvertisedNativeResolution() {
        AirPlayPersistence.saveDisplayScalePercent(activity, 160)
        AirPlayPersistence.saveUiScalePercent(activity, 75)
        invoke("loadPersistedSettings")
        val display = config(1920, 720).main
        assertEquals(1920, display.widthPixels)
        assertEquals(720, display.heightPixels)
        assertEquals(100, field("displayScalePercent").get(activity))
        assertEquals(100, field("uiScalePercent").get(activity))
    }

    @Test fun selectedFrameRatesReachTheAdvertisedDisplayWithoutChangingItsCanvas() {
        for (fps in listOf(30, 50, 60)) {
            FawSystemParameters.saveFps(activity, fps)
            invoke("loadPersistedSettings")
            val display = config(1920, 720).main
            assertEquals(fps, display.fps)
            assertEquals(1920, display.widthPixels)
            assertEquals(720, display.heightPixels)
        }
    }

    @Test fun nativeResolutionWorksWithoutDecoderMetadata() {
        invoke("loadPersistedSettings")
        val display = config(1920, 720).main
        assertEquals(1920, display.widthPixels)
        assertEquals(720, display.heightPixels)
        assertFalse(field("hevcEnabled").get(activity) as Boolean)
    }

    private fun config(width: Int, height: Int): AirPlayConfig {
        val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val size = sizeClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(width, height)
        return activity.javaClass.getDeclaredMethod("createAirPlayConfig", sizeClass)
            .apply { isAccessible = true }.invoke(activity, size) as AirPlayConfig
    }
    private fun field(name: String) = activity.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun invoke(name: String) = activity.javaClass.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)
}
