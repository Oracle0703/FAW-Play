package com.shilapi.xcertplay

import android.os.Build
import android.os.Handler
import android.view.MotionEvent
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.mockito.Mockito.*
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.hud.HongqiNavigation
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
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class HongqiInstrumentMapActivationTest {
    private lateinit var host: CarPlayHostActivity

    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "HONG QI")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "t1c_acrn")
        ReflectionHelpers.setStaticField(HongqiNavigation::class.java, "guidance",
            ClusterTurnGuidance(2, 0, 150, "测试道路"))
        ReflectionHelpers.setStaticField(HongqiNavigation::class.java, "routeActive", true)
        ShadowSettings.setCanDrawOverlays(true)
        host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        host.setTheme(android.R.style.Theme_Material_NoActionBar)
        CarPlayBackgroundSession.requestConnection(host)
        val controller = mock(CarPlayController::class.java)
        doAnswer { call ->
            call.getArgument<(CarPlayController.CloseResult) -> Unit>(0)(CarPlayController.CloseResult(true)); null
        }.`when`(controller).whenClosed(any<(CarPlayController.CloseResult) -> Unit>() ?: {})
        ReflectionHelpers.setField(host, "controller", controller)
        CarPlayBackgroundSession.store(controller, mock(AndroidMediaSink::class.java), 800, 480, host,
            CarPlaySessionDisplay(800, 480, 0, true, true, 800, 480)) { it() }
        host.getSharedPreferences("xcertplay_airplay", 0).edit()
            .putBoolean("cluster_map_enabled", false).commit()
        host.getSharedPreferences("diplay_byd_outputs", 0).edit()
            .putBoolean("hongqi_cluster_visible", false).commit()
    }

    @After fun cleanup() {
        ReflectionHelpers.getField<Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            ReflectionHelpers.getField<ExecutorService>(host, name).shutdownNow()
        }
        ReflectionHelpers.setStaticField(HongqiNavigation::class.java, "guidance", null)
        ReflectionHelpers.setStaticField(HongqiNavigation::class.java, "routeActive", false)
        ShadowSettings.setCanDrawOverlays(false)
        CarPlayBackgroundSession.clear()
    }

    @Test fun firstMapSwipeNegotiatesTheStreamAndSchedulesHostReconnect() {
        assertTrue(swipe(0f))
        assertTrue(host.getSharedPreferences("xcertplay_airplay", 0).getBoolean("cluster_map_enabled", false))
        assertTrue(HongqiNavigation.mapShown(host))
        assertTrue(ReflectionHelpers.getField(host, "reconnectScheduled"))
        assertEquals(1, ReflectionHelpers.getField<Int>(host, "reconnectAttempts"))
    }

    @Test fun anAlreadyNegotiatedMapOnlyChangesVisibility() {
        host.getSharedPreferences("xcertplay_airplay", 0).edit()
            .putBoolean("cluster_map_enabled", true).commit()
        assertTrue(swipe(0f))
        assertTrue(HongqiNavigation.mapShown(host))
        assertFalse(ReflectionHelpers.getField(host, "reconnectScheduled"))
        assertEquals(0, ReflectionHelpers.getField<Int>(host, "reconnectAttempts"))
    }

    @Test fun hidingAnUnprovisionedMapDoesNotNegotiateOrReconnect() {
        assertTrue(swipe(200f))
        assertFalse(host.getSharedPreferences("xcertplay_airplay", 0).getBoolean("cluster_map_enabled", true))
        assertFalse(HongqiNavigation.mapShown(host))
        assertFalse(ReflectionHelpers.getField(host, "reconnectScheduled"))
    }

    private fun swipe(destination: Float): Boolean {
        touch(MotionEvent.ACTION_DOWN, 1, 100f)
        touch(MotionEvent.ACTION_POINTER_DOWN, 2, 100f)
        touch(MotionEvent.ACTION_POINTER_DOWN, 3, 100f)
        return touch(MotionEvent.ACTION_MOVE, 3, destination)
    }

    private fun touch(action: Int, count: Int, x: Float): Boolean {
        val pointers = Array(count) { index -> MotionEvent.PointerProperties().apply {
            id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        val positions = Array(count) { index -> MotionEvent.PointerCoords().apply {
            this.x = x + index * 50f; y = 100f; pressure = 1f; size = 1f
        } }
        val indexed = if (action == MotionEvent.ACTION_POINTER_DOWN)
            action or ((count - 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else action
        val event = MotionEvent.obtain(0, 10, indexed, count, pointers, positions, 0, 0, 1f, 1f, 0, 0, 0, 0)
        return try { HongqiNavigationControls.onTouch(host, event) } finally { event.recycle() }
    }
}
