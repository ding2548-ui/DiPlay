package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.network.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WirelessStartupControllerTest {
    private val statuses = mutableListOf<CarPlayStatus>()
    private fun controller() = CarPlayController(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getApplicationContext(): Context = this
        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean = false
    },
        CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRELESS,
            identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
        AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
        AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(),
        object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, statuses::add)

    private fun fail(controller: CarPlayController, generation: Int, error: Throwable) {
        controller.javaClass.getDeclaredMethod("fail", Throwable::class.java, Int::class.javaObjectType)
            .apply { isAccessible = true }.invoke(controller, error, generation)
    }

    @Test fun timeoutAndCleanupExceptionProduceOnlyOneTypedFailure() {
        val controller = controller()
        try {
            fail(controller, 0, WirelessStartupException(WirelessStartupFailure.FIRST_TCP_TIMEOUT, "timeout"))
            fail(controller, 0, IOException("Bluetooth socket closed"))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, statuses.size)
            assertEquals(WirelessStartupFailure.FIRST_TCP_TIMEOUT, (statuses.single() as CarPlayStatus.Failed).startupFailure)
        } finally { controller.close(); controller.awaitClosed(2_000) }
    }

    @Test fun queuedOldFailureAndOldCleanupDoNotAffectNewGeneration() {
        val controller = controller()
        var closed = 0
        val hotspot = object : WirelessHotspotManager {
            override fun start(timeoutMillis: Long): WirelessHotspotInfo = error("Not started")
            override fun close() { closed++ }
        }
        try {
            fail(controller, 0, WirelessStartupException(WirelessStartupFailure.FIRST_TCP_TIMEOUT, "timeout"))
            ReflectionHelpers.getField<AtomicInteger>(controller, "wirelessGeneration").set(1)
            ReflectionHelpers.setField(controller, "hotspot", hotspot)
            controller.javaClass.getDeclaredMethod("closeWirelessStack", CarPlayVpnService::class.java, Int::class.javaObjectType)
                .apply { isAccessible = true }.invoke(controller, null, 0)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(statuses.isEmpty())
            assertEquals(0, closed)
            assertSame(hotspot, ReflectionHelpers.getField(controller, "hotspot"))
            fail(controller, 0, IOException("late old failure"))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(statuses.isEmpty())
        } finally { controller.close(); controller.awaitClosed(2_000) }
    }
}
