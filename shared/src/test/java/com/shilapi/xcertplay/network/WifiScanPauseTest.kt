package com.shilapi.xcertplay.network

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class WifiScanPauseTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val ok = "Result: Parcel(00000000    '....')"

    @Test
    fun controllerCloseInvalidatesQueuedPausesAndReleasesItsOwnLeaseOnce() {
        val acquisitions = mutableListOf<Pair<Any, () -> Boolean>>()
        val releases = mutableListOf<Any>()
        val control = object : WifiScanPauseControl {
            override fun acquire(app: Context, owner: Any, current: () -> Boolean, log: (String) -> Unit) {
                acquisitions.add(owner to current)
            }

            override fun release(app: Context, owner: Any, log: (String) -> Unit) {
                releases.add(owner)
            }
        }
        val pause = WifiScanPause(context, {}, control)
        pause.pause()
        pause.pause()
        assertEquals(2, acquisitions.size)
        assertEquals(acquisitions[0].first, acquisitions[1].first)
        assertTrue(acquisitions.all { it.second() })
        pause.close()
        pause.close()
        pause.pause()
        assertEquals(2, acquisitions.size)
        assertEquals(listOf(acquisitions[0].first), releases)
        assertTrue(acquisitions.none { it.second() })
    }

    @Test
    fun onlyAndroid10HotspotBackendsAreEligibleSoSameLanKeepsStationScanning() {
        for (backend in WirelessHotspotBackend.entries) {
            assertEquals(backend != WirelessHotspotBackend.EXISTING_WIFI, WifiScanPause.eligible(backend, 29))
            assertFalse(WifiScanPause.eligible(backend, 28))
            assertFalse(WifiScanPause.eligible(backend, 30))
        }
    }

    @Test
    fun acceptsOnlyAVoidReplyWithoutException() {
        assertTrue(WifiScanPause.accepted(ok))
        assertFalse(WifiScanPause.accepted("Result: Parcel(ffffffec 00000000 '........')"))
        assertFalse(WifiScanPause.accepted("service: Service wifi does not exist"))
        assertFalse(WifiScanPause.accepted(null))
    }

    @Test
    fun readsTheTransactionCodeFromTheFramework() {
        val expected = Class.forName("android.net.wifi.IWifiManager\$Stub")
            .getDeclaredField("TRANSACTION_enableWifiConnectivityManager")
            .apply { isAccessible = true }
            .getInt(null)
        assertEquals(expected, WifiScanPause.enableConnectivityManagerTransaction())
    }

    @Test
    @Config(sdk = [30])
    fun skipsReleasesAfterAndroid10() {
        assertNull(WifiScanPause.enableConnectivityManagerTransaction())
    }
}
