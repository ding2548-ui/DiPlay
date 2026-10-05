package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay

/** A negotiated alt stream alone does not mean that the driver can see a map on the dashboard. */
internal object DashboardMapEligibility {
    fun permits(initialUrl: String?, physicalOutputVisible: Boolean, stream: Int, uiShown: Boolean, closed: Boolean): Boolean =
        !closed && physicalOutputVisible && stream > 0 && uiShown &&
            (initialUrl == CarPlayClusterDisplay.MAP_URL || initialUrl == CarPlayClusterDisplay.Content.INSTRUMENTS.url)
}
