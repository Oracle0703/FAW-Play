package com.shilapi.xcertplay

import android.os.Looper
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommand
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommandPayload
import com.shilapi.xcertplay.airplay.rcs.uisync.HongqiHs5UiLayouts
import com.shilapi.xcertplay.airplay.rcs.uisync.HongqiHs5UiState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class Hs5UltraPresentationHostTest {
    private class Renderer : Hs5UltraRenderer {
        var accepted = true
        var clears = 0
        val applied = mutableListOf<HongqiHs5UiState>()
        override fun applyUltraState(state: HongqiHs5UiState): Boolean {
            if (!accepted) return false
            applied += state
            return true
        }
        override fun clearUltraState() { clears++ }
    }
    private val layout = CarPlayUiSyncCommandPayload(CarPlayUiSyncCommand.REQUEST_LAYOUT,
        mapOf("layout" to HongqiHs5UiLayouts.NAVIGATION))

    @Test fun absentRendererAcceptsResetButCannotAcknowledgeLayout() {
        val target = Hs5UltraPresentationHost.target(Hs5UltraPresentationHost.beginProfile())
        assertTrue(target.reset())
        assertFalse(target.apply(layout))
        assertFalse(target.apply(CarPlayUiSyncCommandPayload(CarPlayUiSyncCommand.REQUEST_FOCUS,
            mapOf("focusToken" to "no-renderer"))))
        target.inactive()
    }

    @Test fun layoutRequiresSynchronousRendererAcceptanceAndInactiveRestoresIt() {
        val renderer = Renderer()
        Hs5UltraPresentationHost.attach(renderer)
        try {
            val target = Hs5UltraPresentationHost.target(Hs5UltraPresentationHost.beginProfile())
            assertTrue(target.reset())
            renderer.accepted = false
            assertFalse(target.apply(layout))
            assertTrue(renderer.applied.isEmpty())
            renderer.accepted = true
            assertTrue(target.apply(layout))
            assertEquals(HongqiHs5UiLayouts.NAVIGATION, renderer.applied.single().layoutId)
            val before = renderer.clears
            target.inactive()
            assertTrue(renderer.clears > before)
        } finally { Hs5UltraPresentationHost.detach(renderer) }
    }

    @Test fun newChannelRetiresOldCommandsAndLateCloseCannotClearNewLayout() {
        val renderer = Renderer()
        Hs5UltraPresentationHost.attach(renderer)
        try {
            val profile = Hs5UltraPresentationHost.beginProfile()
            val old = Hs5UltraPresentationHost.target(profile)
            assertTrue(old.apply(layout))
            val current = Hs5UltraPresentationHost.target(profile)
            assertTrue(current.apply(layout))
            assertFalse(old.apply(layout))
            val before = renderer.clears
            old.inactive()
            assertEquals(before, renderer.clears)
            current.inactive()
        } finally { Hs5UltraPresentationHost.detach(renderer) }
    }

    @Test fun oldProfileCannotReclaimRendererAfterResume() {
        val renderer = Renderer()
        Hs5UltraPresentationHost.attach(renderer)
        try {
            val oldProfile = Hs5UltraPresentationHost.beginProfile()
            val old = Hs5UltraPresentationHost.target(oldProfile)
            val newProfile = Hs5UltraPresentationHost.beginProfile()
            val current = Hs5UltraPresentationHost.target(newProfile)
            assertFalse(old.apply(layout))
            assertFalse(Hs5UltraPresentationHost.target(oldProfile).reset())
            assertTrue(current.apply(layout))
            val before = renderer.clears
            old.inactive()
            assertEquals(before, renderer.clears)
            current.inactive()
        } finally { Hs5UltraPresentationHost.detach(renderer) }
    }

    @Test fun stalePresentationDetachCannotRemoveReplacement() {
        val old = Renderer()
        val current = Renderer()
        Hs5UltraPresentationHost.attach(old)
        Hs5UltraPresentationHost.attach(current)
        try {
            Hs5UltraPresentationHost.detach(old)
            val target = Hs5UltraPresentationHost.target(Hs5UltraPresentationHost.beginProfile())
            assertTrue(target.apply(layout))
            assertEquals(1, current.applied.size)
            target.inactive()
        } finally { Hs5UltraPresentationHost.detach(current) }
    }

    @Test fun timedOutBackgroundCommandCannotApplyWhenMainQueueEventuallyRuns() {
        val renderer = Renderer()
        Hs5UltraPresentationHost.attach(renderer)
        val target = Hs5UltraPresentationHost.target(Hs5UltraPresentationHost.beginProfile())
        val accepted = AtomicReference<Boolean?>()
        val worker = Thread { accepted.set(target.apply(layout)) }.apply { isDaemon = true }
        try {
            worker.start()
            // The main looper is deliberately paused: the queued apply must time out first.
            worker.join(3_000L)
            assertFalse("Background caller did not finish its bounded wait", worker.isAlive)
            assertEquals(false, accepted.get())
            assertTrue(renderer.applied.isEmpty())
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Rejected request applied after its caller timed out", renderer.applied.isEmpty())
        } finally {
            worker.interrupt()
            target.inactive()
            Hs5UltraPresentationHost.detach(renderer)
        }
    }

    @Test fun backgroundProfileReplacementAlwaysClearsOldStateWhenMainResumes() {
        val renderer = Renderer()
        Hs5UltraPresentationHost.attach(renderer)
        val old = Hs5UltraPresentationHost.target(Hs5UltraPresentationHost.beginProfile())
        val replacement = Renderer()
        val profile = AtomicLong()
        val worker = Thread { profile.set(Hs5UltraPresentationHost.beginProfile()) }.apply { isDaemon = true }
        try {
            assertTrue(old.apply(layout))
            val before = renderer.clears
            worker.start()
            worker.join(1_000L)
            assertFalse(worker.isAlive)
            assertTrue(profile.get() > 0)
            assertEquals(before, renderer.clears)
            // An old stream close must neither claim the new profile nor cancel its cleanup.
            old.inactive()
            assertEquals(before, renderer.clears)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(renderer.clears > before)
            Hs5UltraPresentationHost.attach(replacement)
            assertTrue("Old layout survived profile replacement", replacement.applied.isEmpty())
            val current = Hs5UltraPresentationHost.target(profile.get())
            assertTrue(current.apply(layout))
            assertEquals(1, replacement.applied.size)
            current.inactive()
        } finally {
            worker.interrupt()
            Hs5UltraPresentationHost.detach(renderer)
            Hs5UltraPresentationHost.detach(replacement)
        }
    }
}
