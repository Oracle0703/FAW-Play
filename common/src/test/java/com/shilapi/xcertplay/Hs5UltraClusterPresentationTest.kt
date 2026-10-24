package com.shilapi.xcertplay

import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.shilapi.xcertplay.airplay.rcs.uisync.HongqiHs5UiLayouts
import com.shilapi.xcertplay.airplay.rcs.uisync.HongqiHs5UiState
import com.shilapi.xcertplay.hud.HongqiNavigation
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class Hs5UltraClusterPresentationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var presentation: ClusterMapPresentation
    private var displayId = 0

    @Before fun prepare() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "HONG QI")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "t1c_acrn")
        displayId = ShadowDisplayManager.addDisplay("w1920dp-h720dp-mdpi", 5)
        val display = app.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        shadowOf(display).setName("Cluster-Display")
        shadowOf(display).setFlags(Display.FLAG_PRESENTATION)
        presentation = ClusterMapPresentation(app, display) {}
        presentation.show()
        settle()
    }

    @After fun cleanup() {
        presentation.dismiss()
        settle()
        ReflectionHelpers.getStaticField<Handler>(HongqiNavigation::class.java, "MAIN").removeCallbacksAndMessages(null)
        ShadowDisplayManager.removeDisplay(displayId)
    }

    @Test fun overviewChangesOnlyTheLocalViewportAndResetRestoresTheOriginalVideoLayout() {
        val video = video()
        val classic = FrameLayout.LayoutParams(video.layoutParams as FrameLayout.LayoutParams)
        assertTrue(presentation.applyUltraState(state(HongqiHs5UiLayouts.OVERVIEW)))
        assertSame(video, video())
        assertEquals(listOf(1280, 480, 320, 120), rect(video))
        assertNotNull(find<Hs5UltraClusterView>(presentation.window!!.decorView))
        assertTrue(presentation.applyUltraState(HongqiHs5UiState()))
        assertEquals(listOf(classic.width, classic.height, classic.leftMargin, classic.topMargin), rect(video))
        assertNull(find<Hs5UltraClusterView>(presentation.window!!.decorView))
    }

    @Test fun knownAppearanceIsRenderedAndOpaqueConfigurationCannotMutateTheCurrentLayout() {
        assertTrue(presentation.applyUltraState(state(HongqiHs5UiLayouts.NAVIGATION).copy(appearanceMode = "light")))
        assertEquals(listOf(1920, 720, 0, 0), rect(video()))
        val invalid = listOf(
            state(HongqiHs5UiLayouts.OVERVIEW).copy(appearanceMode = "vendor-mystery"),
            state(HongqiHs5UiLayouts.OVERVIEW).copy(uiConfiguration = "unknown"),
            state(HongqiHs5UiLayouts.OVERVIEW).copy(uiConfigurationData = byteArrayOf(1)),
            state(HongqiHs5UiLayouts.OVERVIEW).copy(activeComponents = setOf("rpm")),
            state(HongqiHs5UiLayouts.OVERVIEW).copy(focusToken = "unimplemented-focus"),
            state(HongqiHs5UiLayouts.OVERVIEW).copy(appearanceOverride = "opaque"),
        )
        for (next in invalid) {
            assertFalse(presentation.applyUltraState(next))
            assertEquals(listOf(1920, 720, 0, 0), rect(video()))
        }
    }

    @Test fun closingThePresentationReleasesTheUltraLeaseAndKeepsTheOriginalSurfaceView() {
        val video = video()
        assertTrue(presentation.applyUltraState(state(HongqiHs5UiLayouts.OVERVIEW)))
        presentation.setStreamActive(true)
        presentation.dismiss()
        settle()
        assertNull(ReflectionHelpers.getStaticField<Any?>(HongqiNavigation::class.java, "ultraPresentation"))
        assertSame(video, video())
    }

    @Test fun realLocalOverviewDoesNotWaitForNavigationVideoButNavigationOnlyDoes() {
        presentation.setStreamActive(false)
        assertTrue(presentation.applyUltraState(state(HongqiHs5UiLayouts.OVERVIEW)))
        assertTrue(presentation.ultraRendererReady)
        assertTrue(presentation.applyUltraState(state(HongqiHs5UiLayouts.NAVIGATION)))
        assertFalse(presentation.ultraRendererReady)
        presentation.setStreamActive(true)
        assertTrue(presentation.ultraRendererReady)
        presentation.setStreamActive(false)
        assertFalse(presentation.ultraRendererReady)
        presentation.clearUltraState()
        assertFalse(presentation.ultraRendererReady)
    }

    @Test fun repeatedResetCannotExposeAStoppedOrUnownedHs5Window() {
        assertTrue(presentation.applyUltraState(state(HongqiHs5UiLayouts.OVERVIEW)))
        presentation.clearUltraState()
        settle()
        assertEquals(0f, presentation.window!!.attributes.alpha)
        presentation.clearUltraState()
        presentation.clearUltraState()
        settle()
        assertEquals(0f, presentation.window!!.attributes.alpha)
    }

    private fun state(id: String): HongqiHs5UiState {
        val layout = HongqiHs5UiLayouts.ALL.first { it.id == id }
        return HongqiHs5UiState(layoutId = id, activeComponents = layout.components)
    }

    private fun video(): VideoSurfaceView = find<VideoSurfaceView>(presentation.window!!.decorView)!!
    private fun rect(view: View): List<Int> = (view.layoutParams as FrameLayout.LayoutParams).let {
        listOf(it.width, it.height, it.leftMargin, it.topMargin)
    }
    private inline fun <reified T : View> find(view: View): T? = find(view, T::class.java)
    private fun <T : View> find(view: View, type: Class<T>): T? {
        if (type.isInstance(view)) return type.cast(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), type)?.let { return it }
        return null
    }
    private fun settle() {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            ReflectionHelpers.getStaticField<ExecutorService>(HongqiNavigation::class.java, "WRITER")
                .submit {}.get(3, TimeUnit.SECONDS)
        }
    }
}
