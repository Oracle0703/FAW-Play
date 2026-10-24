package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay

/** A negotiated alt stream alone does not mean that the driver can see a map on the dashboard. */
internal object DashboardMapEligibility {
    fun permits(initialUrl: String?, physicalOutputVisible: Boolean, stream: Int, uiShown: Boolean, closed: Boolean): Boolean {
        if (closed || !physicalOutputVisible || stream <= 0 || !uiShown) return false
        // Display options such as showETA change the rendering, not whether the content is a map.
        return when (initialUrl?.substringBefore('?')) {
            CarPlayClusterDisplay.MAP_URL, CarPlayClusterDisplay.INSTRUMENTS_URL -> true
            else -> false
        }
    }
}
