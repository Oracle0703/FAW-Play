package com.shilapi.xcertplay

import android.os.Handler
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ScrollView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutorService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi", manifest = Config.NONE)
class PreparationLayoutTest {
    @Test fun hs5PreparationScreenFitsTheVehicleViewportWithoutVerticalScrolling() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        try {
            val root = CarPlayHostActivity::class.java.getDeclaredMethod("buildContentView")
                .apply { isAccessible = true }.invoke(activity) as FrameLayout
            val viewport = descendants(root).single { it.tag == "autunn_connection_preparation" }
            val width = 1920
            val height = 720
            repeat(3) {
                descendants(root).forEach { it.forceLayout() }
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
            }
            assertEquals(width, viewport.width)
            assertEquals(height, viewport.height)
            assertTrue(descendants(viewport).none { it is ScrollView })
            val settings = descendants(viewport).filterIsInstance<Button>().single { it.text.toString() == "设置" }
            assertTrue(settings.width > 0)
            assertTrue(settings.height > 0)
            var bottom = settings.bottom
            var parent = settings.parent
            while (parent is View && parent !== viewport) {
                bottom += parent.top
                parent = parent.parent
            }
            assertTrue("settings bottom=$bottom viewport=$height", bottom <= height)
            assertEquals(0, viewport.scrollY)
        } finally {
            (CarPlayHostActivity::class.java.getDeclaredField("mainHandler").apply { isAccessible = true }
                .get(activity) as Handler).removeCallbacksAndMessages(null)
            for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
                (CarPlayHostActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
                    .get(activity) as ExecutorService).shutdownNow()
            }
        }
    }
}
