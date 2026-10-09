package com.shilapi.xcertplay

import android.graphics.Paint
import android.os.Looper
import android.view.TextureView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class LivePictureTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private class RecordingTexture : TextureView(RuntimeEnvironment.getApplication()) {
        var applied: Paint? = null
        var applications = 0
        override fun setLayerPaint(paint: Paint?) { applied = paint; applications++ }
    }

    @Before fun reset() { CarPlayPicture.preferences(context).edit().clear().commit() }

    @Test fun legacyPicturePreferencesCannotInstallAFilterOnEitherVideoTexture() {
        val main = RecordingTexture()
        val cluster = RecordingTexture()
        val mainBinding = CarPlayPicture.Binding(main)
        val clusterBinding = CarPlayPicture.Binding(cluster)
        try {
            CarPlayPicture.preferences(context).edit().putInt(CarPlayPicture.CONTRAST, 125).apply()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(main.applied)
            assertNull(cluster.applied)
            val beforeClose = main.applications
            mainBinding.close()
            CarPlayPicture.preferences(context).edit().putInt(CarPlayPicture.BRIGHTNESS, 50).apply()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(beforeClose, main.applications)
            assertNull(cluster.applied)
        } finally {
            mainBinding.close()
            clusterBinding.close()
        }
    }
}
