package com.shilapi.xcertplay

import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.hud.ClusterLane
import com.shilapi.xcertplay.hud.ClusterLaneGuidance
import com.shilapi.xcertplay.hud.ClusterLaneStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.ExecutorService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class HongqiLaneOverlayIntegrationTest {
    private lateinit var host: CarPlayHostActivity
    private lateinit var presentation: ClusterMapPresentation
    private var displayId = -1

    @Before fun setup() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "HONG QI")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "t1c_acrn")
        val context = RuntimeEnvironment.getApplication()
        host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ReflectionHelpers.setField(host, "airPlayIdentity", AirPlayIdentity.generate())
        host.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        AirPlayPersistence.saveClusterContent(host, CarPlayClusterDisplay.Content.INSTRUMENTS)
        displayId = ShadowDisplayManager.addDisplay("w1920dp-h720dp", 5)
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        presentation = ClusterMapPresentation(context, display, onSurface = {})
        presentation.show()
        ReflectionHelpers.setField(host, "clusterPresentation", presentation)
    }

    @After fun cleanup() {
        presentation.dismiss()
        ShadowDisplayManager.removeDisplay(displayId)
        ReflectionHelpers.getField<Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            ReflectionHelpers.getField<ExecutorService>(host, name).shutdownNow()
        }
    }

    @Test fun nativeInstrumentsReceivesLanesWhileTheCustomTurnCardRemainsOff() {
        val lanes = ClusterLaneGuidance(7, listOf(ClusterLane(0, ClusterLaneStatus.PREFERRED, listOf(0), null)))
        ReflectionHelpers.setField(host, "clusterLaneGuidance", lanes)
        apply()
        val view = ReflectionHelpers.getField<ClusterLaneGuidanceView>(presentation, "laneView")
        assertEquals(View.VISIBLE, view.visibility)
        val turn = ReflectionHelpers.getField<ClusterTurnCardView>(presentation, "turnCardView")
        assertEquals(View.GONE, turn.visibility)
        ReflectionHelpers.setField(host, "clusterLaneGuidance", null)
        apply()
        assertEquals(View.GONE, view.visibility)
    }

    @Test fun onlyTheSupportedHs5RuntimeClaimsLaneGuidance() {
        assertTrue(runtime().identification.laneGuidanceEnabled)
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "Android phone")
        assertFalse(runtime().identification.laneGuidanceEnabled)
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "HONG QI")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "unverified")
        assertFalse(runtime().identification.laneGuidanceEnabled)
    }

    @Test fun aQueuedLaneCallbackCannotRestoreGuidanceAfterTheHostDetaches() {
        val lanes = ClusterLaneGuidance(7, listOf(ClusterLane(0, ClusterLaneStatus.PREFERRED, listOf(0), null)))
        ReflectionHelpers.setField(host, "navigationOverlaysAttached", true)
        val listener = ReflectionHelpers.getField<(ClusterLaneGuidance?) -> Unit>(host, "clusterLaneOverlayListener")
        Thread { listener(lanes) }.apply { start(); join() }
        ReflectionHelpers.setField(host, "navigationOverlaysAttached", false)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(ReflectionHelpers.getField<ClusterLaneGuidance?>(host, "clusterLaneGuidance"))
    }

    private fun runtime() = host.javaClass.getDeclaredMethod("createRuntimeConfig")
        .apply { isAccessible = true }.invoke(host) as CarPlayRuntimeConfig

    private fun apply() {
        host.javaClass.getDeclaredMethod("applyClusterTurnOverlay").apply { isAccessible = true }.invoke(host)
    }
}
