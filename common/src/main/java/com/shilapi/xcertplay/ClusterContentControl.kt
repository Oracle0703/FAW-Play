package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import java.util.concurrent.atomic.AtomicLong

/** Keep the local card until the controller delivers or retains the content switch. */
internal object ClusterContentControl {
    private val selection = AtomicLong()

    fun select(context: Context, content: CarPlayClusterDisplay.Content, complete: (Boolean) -> Unit) {
        val request = selection.incrementAndGet()
        val controller = CarPlayBackgroundSession.snapshot()?.controller
        if (controller == null) {
            AirPlayPersistence.saveClusterContent(context, content)
            complete(true)
            return
        }
        controller.showDashboardContent(content.url) { accepted ->
            // A reply from a replaced session must not overwrite the new phone's selection.
            val current = accepted && selection.get() == request &&
                CarPlayBackgroundSession.snapshot()?.controller === controller
            if (current) AirPlayPersistence.saveClusterContent(context, content)
            complete(current)
        }
    }

    fun displayedUrl(): String? = CarPlayBackgroundSession.snapshot()?.controller?.dashboardContentUrl()
}
