package com.shilapi.xcertplay

import android.net.wifi.WifiManager
import android.net.wifi.SoftApConfiguration
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.network.LocalOnlyHotspotManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWifiManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE,
    shadows = [LocalOnlyHotspotManagerTest.Radio::class, LocalOnlyHotspotManagerTest.Reservation::class])
class LocalOnlyHotspotManagerTest {
    @Test fun cancelledStartupStillClosesALateSystemReservation() = lateReservation(cancel = true)
    @Test fun timedOutStartupStillClosesALateSystemReservation() = lateReservation(cancel = false)

    @Test @Config(sdk = [33])
    fun android13RequestsFiveGhzAndClosesLateCustomReservation() {
        val radio = shadowOf(RuntimeEnvironment.getApplication().getSystemService(WifiManager::class.java)) as Radio
        lateReservation(cancel = true)
        assertEquals(1, radio.customRequests)
        assertEquals(0, radio.standardRequests)
        assertEquals(SoftApConfiguration.BAND_5GHZ,
            radio.configuration!!.javaClass.getMethod("getBand").invoke(radio.configuration))
        assertEquals(36, radio.configuration!!.javaClass.getMethod("getChannel").invoke(radio.configuration))
        assertEquals(SoftApConfiguration.SECURITY_TYPE_WPA2_PSK, radio.configuration!!.securityType)
        assertEquals(20, radio.configuration!!.passphrase!!.length)
    }

    @Test @Config(sdk = [33])
    fun firmwarePermissionDenialFallsBackWithoutRequestingPrivilege() {
        val radio = shadowOf(RuntimeEnvironment.getApplication().getSystemService(WifiManager::class.java)) as Radio
        radio.denyCustom = true
        lateReservation(cancel = true)
        assertEquals(1, radio.customRequests)
        assertEquals(1, radio.standardRequests)
    }

    private fun lateReservation(cancel: Boolean) {
        val context = RuntimeEnvironment.getApplication()
        val radio = shadowOf(context.getSystemService(WifiManager::class.java)) as Radio
        val manager = LocalOnlyHotspotManager(context)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Boolean> { runCatching { manager.start(if (cancel) 5000 else 100) }.isFailure }
            assertTrue(radio.requested.await(2, TimeUnit.SECONDS))
            if (cancel) manager.close()
            assertTrue(result.get(2, TimeUnit.SECONDS))
            // The framework completes after cancellation/timeout. Callback delivery must not
            // have died with a private HandlerThread, leaving the system AP running forever.
            assertSame(Looper.getMainLooper(), radio.handler.looper)
            val reservation = Shadow.newInstanceOf(WifiManager.LocalOnlyHotspotReservation::class.java)
            radio.handler.post { radio.callback.onStarted(reservation) }
            shadowOf(Looper.getMainLooper()).idle()
            val state = Shadow.extract<Reservation>(reservation)
            assertEquals(1, state.closes)
        } finally { manager.close(); worker.shutdownNow() }
    }

    @Implements(WifiManager::class)
    class Radio : ShadowWifiManager() {
        val requested = CountDownLatch(1)
        lateinit var callback: WifiManager.LocalOnlyHotspotCallback
        lateinit var handler: Handler
        var customRequests = 0
        var standardRequests = 0
        var denyCustom = false
        var configuration: SoftApConfiguration? = null
        @Implementation fun startLocalOnlyHotspot(value: WifiManager.LocalOnlyHotspotCallback, delivery: Handler) {
            standardRequests++
            callback = value; handler = delivery; requested.countDown()
        }
        @Implementation fun startLocalOnlyHotspot(config: SoftApConfiguration, delivery: Executor, value: WifiManager.LocalOnlyHotspotCallback) {
            customRequests++
            if (denyCustom) throw SecurityException("test firmware rejects custom config")
            configuration = config
            callback = value; handler = Handler(Looper.getMainLooper()); requested.countDown()
        }
    }

    @Implements(WifiManager.LocalOnlyHotspotReservation::class)
    class Reservation {
        var closes = 0
        @Implementation fun close() { closes++ }
    }
}
