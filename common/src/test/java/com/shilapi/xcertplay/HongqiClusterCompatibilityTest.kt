package com.shilapi.xcertplay

import android.os.Build
import android.os.Handler
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class HongqiClusterCompatibilityTest {
    private lateinit var activity: CarPlayHostActivity

    @Before fun setup() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "HONG QI")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "t1c_acrn")
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        field("airPlayIdentity").set(activity, AirPlayIdentity.generate())
        activity.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        AirPlayPersistence.saveClusterMapEnabled(activity, true)
        AirPlayPersistence.saveClusterContent(activity, CarPlayClusterDisplay.Content.INSTRUMENTS)
    }

    @After fun cleanup() {
        (field("mainHandler").get(activity) as Handler).removeCallbacksAndMessages(null)
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            (field(name).get(activity) as ExecutorService).shutdownNow()
        }
        AirPlayPersistence.overlaySettingsListener = null
    }

    @Test fun supportedHs5WithClusterKeepsNativeEtaUrlAndUsesCompatibilityNegotiation() {
        val config = config()
        assertNotNull(config.cluster)
        assertEquals(CarPlayClusterDisplay.Content.INSTRUMENTS.url, config.cluster!!.initialUrl)
        assertFalse(config.receiverManagedClusterUi)
    }

    @Test fun supportedHs5WithoutClusterKeepsModernNegotiation() {
        AirPlayPersistence.saveClusterMapEnabled(activity, false)
        val config = config()
        assertNull(config.cluster)
        assertTrue(config.receiverManagedClusterUi)
    }

    @Test fun anotherModelWithClusterKeepsModernNegotiation() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "Android phone")
        val config = config()
        assertNotNull(config.cluster)
        assertTrue(config.receiverManagedClusterUi)
        assertEquals(CarPlayClusterDisplay.Content.INSTRUMENTS.url, config.cluster!!.initialUrl)
    }

    @Test fun unverifiedBoardWithHongqiModelKeepsModernNegotiation() {
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "unverified")
        val config = config()
        assertNotNull(config.cluster)
        assertTrue(config.receiverManagedClusterUi)
    }

    @Test @Config(sdk = [33]) fun unverifiedAndroidVersionKeepsModernNegotiation() {
        val config = config()
        assertNotNull(config.cluster)
        assertTrue(config.receiverManagedClusterUi)
    }

    private fun config(): AirPlayConfig {
        val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val size = sizeClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(1920, 720)
        return activity.javaClass.getDeclaredMethod("createAirPlayConfig", sizeClass)
            .apply { isAccessible = true }.invoke(activity, size) as AirPlayConfig
    }

    private fun field(name: String) = activity.javaClass.getDeclaredField(name).apply { isAccessible = true }
}
