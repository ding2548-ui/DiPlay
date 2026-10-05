package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardMapEligibilityTest {
    private fun eligible(url: String? = CarPlayClusterDisplay.MAP_URL, physical: Boolean = true,
        stream: Int = 1, shown: Boolean = true, closed: Boolean = false): Boolean =
        DashboardMapEligibility.permits(url, physical, stream, shown, closed)

    @Test fun requiresPhysicalOutputAndUnpausedStream() {
        assertTrue(eligible())
        assertFalse(eligible(physical = false)) // Virtual centre map alone never owns wheel volume.
        assertFalse(eligible(stream = 0))
        assertFalse(eligible(shown = false)) // stopUI retains a negotiated cluster stream.
        assertFalse(eligible(closed = true))
    }

    @Test fun supportsMapAndCombinedInstrumentsButNotOnlyATurnCard() {
        assertTrue(eligible(url = CarPlayClusterDisplay.Content.MAP_WITH_CUSTOM_CARD.url))
        assertTrue(eligible(url = CarPlayClusterDisplay.Content.INSTRUMENTS.url))
        assertFalse(eligible(url = CarPlayClusterDisplay.Content.TURN_CARD.url))
        assertFalse(eligible(url = null))
        assertFalse(eligible(url = "unrecognized:/content"))
    }
}
