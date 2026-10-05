package com.shilapi.xcertplay.network

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarHotspotStatusTest {
    private fun sticky(state: Int?, mode: Int? = null) = Intent("android.net.wifi.WIFI_AP_STATE_CHANGED").apply {
        state?.let { putExtra("wifi_state", it) }
        mode?.let { putExtra("wifi_ap_mode", it) }
    }

    @Test fun hiddenGettersStillUseAValidStickyTetheredState() {
        assertEquals(true, CarHotspotStatus.read({ throw SecurityException() }, { throw NoSuchMethodException() }, { sticky(13, 1) }))
        assertEquals(false, CarHotspotStatus.read({ null }, { null }, { sticky(11, 1) }))
    }

    @Test fun invalidReflectionStateDoesNotTurnUnknownIntoHotspotOff() {
        assertNull(CarHotspotStatus.read({ -1 }, { null }, { sticky(99) }))
        assertEquals(true, CarHotspotStatus.read({ 99 }, { null }, { sticky(13) }))
    }

    @Test fun localOnlyOrUnspecifiedApBroadcastDoesNotClaimTheCarHotspotIsOnOrOff() {
        for (state in listOf(11, 13)) {
            assertNull(CarHotspotStatus.read({ null }, { null }, { sticky(state, 2) }))
            assertNull(CarHotspotStatus.read({ null }, { null }, { sticky(state, -1) }))
        }
    }

    @Test fun authoritativeReflectionStateWinsOverAnOlderStickyBroadcast() {
        assertEquals(false, CarHotspotStatus.read({ 11 }, { null }, { fail("Do not read stale broadcast"); sticky(13) }))
    }

    @Test fun absentManagerStillAllowsStickyStateButAbsentExtraDoesNotInventOff() {
        assertEquals(true, CarHotspotStatus.read({ null }, { null }, { sticky(13) }))
        assertNull(CarHotspotStatus.read({ null }, { null }, { sticky(null) }))
        assertNull(CarHotspotStatus.read({ null }, { null }, { throw SecurityException() }))
    }
}
