package com.shilapi.xcertplay.media

import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class VideoSurfaceHandoffTest {
    @Test fun allSurfaceOwnersAreRevokedBeforeAnyWaitAndShareOneDeadline() {
        val surface = Surface(android.graphics.SurfaceTexture(0))
        var revoked = 0
        val deadlines = mutableListOf<Long>()
        fun owner(result: Boolean) = object : VideoSurfaceHandoff.Owner {
            override fun beginRelease(target: Surface): Boolean { assertSame(surface, target); revoked++; return true }
            override fun awaitRelease(deadlineNs: Long): Boolean { assertEquals(2, revoked); deadlines += deadlineNs; return result }
        }
        val first = owner(false); val second = owner(true)
        VideoSurfaceHandoff.register(first); VideoSurfaceHandoff.register(second)
        try {
            assertFalse(VideoSurfaceHandoff.releaseSurface(surface))
            assertEquals(2, deadlines.size)
            assertEquals(deadlines[0], deadlines[1])
        } finally { VideoSurfaceHandoff.unregister(first); VideoSurfaceHandoff.unregister(second); surface.release() }
    }
}
