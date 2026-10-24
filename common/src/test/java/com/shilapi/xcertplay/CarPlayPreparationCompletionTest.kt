package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.View
import android.view.ViewPropertyAnimator
import org.mockito.ArgumentCaptor
import org.robolectric.shadow.api.Shadow
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE,
    shadows = [CarPlayHostAutomaticEntryForegroundTest.WindowFocus::class])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayPreparationCompletionTest {
    private lateinit var host: CarPlayHostActivity
    private lateinit var listener: AirPlaySessionListener
    private lateinit var session: AirPlaySession
    private lateinit var controller: CarPlayController
    private lateinit var sink: AndroidMediaSink
    private lateinit var surface: Surface
    private lateinit var panel: View

    @Before fun setUp() {
        host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        host.setTheme(android.R.style.Theme_Material_NoActionBar)
        panel = View(host)
        session = mock(AirPlaySession::class.java)
        controller = mock(CarPlayController::class.java)
        sink = mock(AndroidMediaSink::class.java)
        surface = mock(Surface::class.java)
        val video = mock(VideoSurfaceView::class.java)
        `when`(surface.isValid).thenReturn(true)
        `when`(video.isShown).thenReturn(true)
        `when`(controller.currentActiveAirPlaySession()).thenReturn(session)
        set("connectionPanel", panel)
        set("activeAirPlaySession", session)
        set("controller", controller)
        set("sink", sink)
        set("currentSurface", surface)
        set("videoView", video)
        listener = ReflectionHelpers.callInstanceMethod(host, "createSessionListener",
            from(Int::class.javaPrimitiveType, 0))
    }

    @After fun tearDown() {
        ReflectionHelpers.getField<Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor"))
            ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(host, name).shutdownNow()
    }

    @Test fun instrumentFramesAndAnAcceptedMainStreamKeepThePreparationVisibleUntilTheMainRenders() {
        stream(111, true)
        listener.onVideoFrameRendered(session, 111)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, panel.visibility)

        stream(110, true)
        assertEquals(View.VISIBLE, panel.visibility)
        `when`(session.hasRenderedMainScreen()).thenReturn(true)
        `when`(sink.hasRenderedVideoFrame(110, surface)).thenReturn(true)
        listener.onVideoFrameRendered(session, 110)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, panel.visibility)
    }

    @Test fun replacingThePrimarySurfaceRequiresItsOwnFrameAndRejectsAStaleSessionCallback() {
        stream(110, true)
        `when`(session.hasRenderedMainScreen()).thenReturn(true)
        `when`(sink.hasRenderedVideoFrame(110, surface)).thenReturn(true)
        listener.onVideoFrameRendered(session, 110)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, panel.visibility)

        val replacement = mock(Surface::class.java)
        `when`(replacement.isValid).thenReturn(true)
        set("currentSurface", replacement)
        update()
        assertEquals(View.VISIBLE, panel.visibility)

        `when`(sink.hasRenderedVideoFrame(110, replacement)).thenReturn(true)
        listener.onVideoFrameRendered(mock(AirPlaySession::class.java), 110)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, panel.visibility)
        listener.onVideoFrameRendered(session, 110)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, panel.visibility)

        stream(110, false)
        assertEquals(View.VISIBLE, panel.visibility)
    }

    @Test fun currentPrimaryPresentationRevealsTheCoverBeforeInstrumentWindowsAreCreated() {
        val animator = prepareVisibleReveal()
        set("deferClusterForEntry", true)
        ReflectionHelpers.callInstanceMethod<Unit>(host, "ensureClusterPresentation")
        assertTrue(ReflectionHelpers.getField(host, "clusterEntryPending"))
        assertNull(ReflectionHelpers.getField(host, "clusterPresentation"))
        update()
        assertEquals(View.VISIBLE, panel.visibility)
        verify(animator).setDuration(160)
        assertTrue(ReflectionHelpers.getField(host, "deferClusterForEntry"))
        revealCompletion(animator).run()
        assertEquals(View.GONE, panel.visibility)
        assertFalse(ReflectionHelpers.getField(host, "deferClusterForEntry"))
        assertFalse(ReflectionHelpers.getField(host, "clusterEntryPending"))
    }

    @Test fun aSurfaceReplacementCancelsTheFadeAndItsLateEndCannotHideTheNewPreparation() {
        val animator = prepareVisibleReveal()
        update()
        val completion = revealCompletion(animator)
        val replacement = mock(Surface::class.java)
        `when`(replacement.isValid).thenReturn(true)
        set("currentSurface", replacement)
        update()
        completion.run()
        verify(animator).cancel()
        assertEquals(1f, panel.alpha)
        assertEquals(View.VISIBLE, panel.visibility)
        assertNull(ReflectionHelpers.getField(host, "preparationRevealPanel"))
    }

    @Test fun lossOfResumeCancelsTheFadeAndFocusMustReturnBeforeAnotherReveal() {
        val animator = prepareVisibleReveal()
        update()
        val completion = revealCompletion(animator)
        set("isActivityResumed", false)
        update()
        completion.run()
        assertEquals(View.VISIBLE, panel.visibility)
        assertEquals(1f, panel.alpha)
        set("isActivityResumed", true)
        Shadow.extract<CarPlayHostAutomaticEntryForegroundTest.WindowFocus>(host).focused = false
        update()
        assertNull(ReflectionHelpers.getField(host, "preparationRevealPanel"))
        Shadow.extract<CarPlayHostAutomaticEntryForegroundTest.WindowFocus>(host).focused = true
        update()
        assertSame(panel, ReflectionHelpers.getField(host, "preparationRevealPanel"))
    }

    private fun prepareVisibleReveal(): ViewPropertyAnimator {
        panel = spy(panel)
        set("connectionPanel", panel)
        doReturn(true).`when`(panel).isAttachedToWindow
        val animator = mock(ViewPropertyAnimator::class.java, RETURNS_SELF)
        doReturn(animator).`when`(panel).animate()
        set("isActivityResumed", true)
        Shadow.extract<CarPlayHostAutomaticEntryForegroundTest.WindowFocus>(host).focused = true
        ReflectionHelpers.getField<MutableSet<Int>>(host, "activeScreenStreamTypes").add(110)
        `when`(session.hasRenderedMainScreen()).thenReturn(true)
        `when`(sink.hasRenderedVideoFrame(110, surface)).thenReturn(true)
        return animator
    }

    private fun revealCompletion(animator: ViewPropertyAnimator): Runnable {
        val completion = ArgumentCaptor.forClass(Runnable::class.java)
        verify(animator).withEndAction(completion.capture())
        return completion.value
    }

    private fun stream(type: Int, active: Boolean) {
        ReflectionHelpers.callInstanceMethod<Unit>(host, "onScreenStreamStateChanged",
            from(Int::class.javaPrimitiveType, 0), from(Int::class.javaPrimitiveType, type),
            from(Boolean::class.javaPrimitiveType, active))
    }

    private fun update() = ReflectionHelpers.callInstanceMethod<Unit>(host, "updateDebugOverlays")
    private fun set(name: String, value: Any) = ReflectionHelpers.setField(host, name, value)
}
