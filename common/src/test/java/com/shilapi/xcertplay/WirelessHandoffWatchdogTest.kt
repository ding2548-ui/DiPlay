package com.shilapi.xcertplay

import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotManager
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WirelessHandoffWatchdogTest {
    private lateinit var controller: CarPlayController
    private lateinit var session: AirPlaySession
    private lateinit var bootstrap: BluetoothSocket
    private val statuses = mutableListOf<CarPlayStatus>()
    private val diagnostics = mutableListOf<String>()
    private var hotspotCloses = 0
    private var confirmations = 0
    private lateinit var proof: Any

    @Before fun setUp() {
        val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480))
        val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test")
        val listener = object : AirPlaySessionListener {
            override fun onDebugLog(message: String) { diagnostics += message }
        }
        controller = CarPlayController(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean = false
        }, CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRELESS,
            identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
            config, identity, PairingStore(), listener, object : AirPlayMediaHandler {}, statuses::add)
        session = AirPlaySession(Socket(), config, identity, PairingStore(), null, listener, object : AirPlayMediaHandler {})
        val phase = controller.javaClass.getDeclaredField("phase").apply { isAccessible = true }
        phase.set(controller, phase.type.enumConstants.first { it.toString() == "WIRELESS" })
        flag("wirelessHandoffRequested").set(true)
        ReflectionHelpers.setField(controller, "activeSession", session)
        proof = ReflectionHelpers.getField(controller, "wirelessConnectionProof")
        proofCall("begin", 0, { confirmations++ })
        proofCall("activate", 0, session)
        bootstrap = mock(BluetoothSocket::class.java)
        ReflectionHelpers.setField(controller, "bluetoothSocket", bootstrap)
        ReflectionHelpers.setField(controller, "hotspot", object : WirelessHotspotManager {
            override fun start(timeoutMillis: Long): WirelessHotspotInfo = error("Not started")
            override fun close() { hotspotCloses++ }
        })
    }

    @After fun tearDown() {
        controller.close()
        controller.awaitClosed(2_000)
        session.close()
    }

    @Test fun establishedSessionWithoutVideoStillFailsAndCleansUp() {
        timeout()
        assertFalse(flag("wirelessActiveReported").get())
        assertEquals(1, hotspotCloses)
        assertTrue(statuses.single() is CarPlayStatus.Failed)
        verify(bootstrap).close()
        assertEquals(0, confirmations)
    }

    @Test fun renderedVideoWithoutTunnelPreservesBootstrapAndReportsFallbackOnce() {
        proofCall("rendered", 0, session)
        timeout()
        timeout()
        assertTrue(flag("wirelessActiveReported").get())
        assertEquals(0, hotspotCloses)
        assertSame(session, ReflectionHelpers.getField(controller, "activeSession"))
        assertEquals(listOf(CarPlayStatus.WirelessActiveFallback), statuses)
        verify(bootstrap, never()).close()
        assertTrue(diagnostics.any { it.startsWith("STEP handoff/fallback:") && it.contains("tunnel iAP2 unavailable") })
        assertFalse(diagnostics.any { it.startsWith("STEP handoff/complete:") })
        assertEquals(0, confirmations)
    }

    @Test fun delayedTunnelReadinessWinsOverFallback() {
        val closed = CountDownLatch(1)
        val release = CountDownLatch(1)
        doAnswer { closed.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); null }.`when`(bootstrap).close()
        controller.javaClass.getDeclaredMethod("onWirelessTunnelReady", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(controller, 0)
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        val handoff = Thread.getAllStackTraces().keys.single { it.name == "xcertplay-wireless-handoff" }
        release.countDown()
        handoff.join(2_000)
        assertFalse("Normal handoff did not finish", handoff.isAlive)
        timeout()
        assertEquals(0, hotspotCloses)
        assertEquals(listOf(CarPlayStatus.WirelessActive), statuses)
        verify(bootstrap, times(1)).close()
    }

    @Test fun aLateTunnelPromotesFallbackAndClosesBluetoothOnlyOnce() {
        proofCall("rendered", 0, session)
        timeout()
        assertEquals(listOf(CarPlayStatus.WirelessActiveFallback), statuses)
        val closing = CountDownLatch(1)
        doAnswer { closing.countDown(); null }.`when`(bootstrap).close()
        tunnelReady(0)
        tunnelReady(0)
        assertTrue(closing.await(2, TimeUnit.SECONDS))
        awaitHandoff()
        assertEquals(listOf(CarPlayStatus.WirelessActiveFallback, CarPlayStatus.WirelessActive), statuses)
        verify(bootstrap, times(1)).close()
        assertFalse(flag("wirelessHandoffFellBack").get())
        assertEquals(0, hotspotCloses)
    }

    @Test fun aQueuedTunnelCompletionCannotCloseTheNextGenerationsBootstrap() {
        val replacement = mock(BluetoothSocket::class.java)
        val lock = ReflectionHelpers.getField<Any>(controller, "wirelessResourceLock")
        synchronized(lock) {
            tunnelReady(0)
            // Force replacement to win before the queued completion can claim its resources.
            ReflectionHelpers.getField<AtomicInteger>(controller, "wirelessGeneration").set(1)
            ReflectionHelpers.setField(controller, "bluetoothSocket", replacement)
            flag("wirelessActiveReported").set(false)
            flag("wirelessTunnelReady").set(false)
        }
        awaitHandoff()
        verify(bootstrap, never()).close()
        verify(replacement, never()).close()
        assertTrue(statuses.isEmpty())
    }

    @Test fun tunnelReadinessWithoutAHandoffRequestDoesNotReleaseBluetooth() {
        flag("wirelessHandoffRequested").set(false)
        tunnelReady(0)
        awaitHandoff()
        verify(bootstrap, never()).close()
        assertTrue(statuses.isEmpty())
        assertTrue(flag("wirelessTunnelReady").get())
    }

    @Test fun onlyTheCurrentRenderedSessionMayKeepTheBluetoothControlLoopAlive() {
        assertFalse(keepControlAlive(0))
        proofCall("rendered", 0, session)
        assertTrue(keepControlAlive(0))
        assertFalse(keepControlAlive(1))
        proofCall("end", 0, session)
        assertFalse(keepControlAlive(0))
        proofCall("activate", 0, session)
        proofCall("rendered", 0, session)
        flag("wirelessFailureReported").set(true)
        assertFalse(keepControlAlive(0))
    }

    @Test fun endedSessionCannotUseItsOldRenderedFrame() {
        proofCall("rendered", 0, session)
        proofCall("end", 0, session)
        ReflectionHelpers.setField(controller, "activeSession", null)
        timeout()
        assertTrue(statuses.single() is CarPlayStatus.Failed)
        assertEquals(1, hotspotCloses)
    }

    @Test fun oldGenerationCannotCloseReplacementResources() {
        proofCall("rendered", 0, session)
        ReflectionHelpers.getField<AtomicInteger>(controller, "wirelessGeneration").set(1)
        timeout()
        assertFalse(flag("wirelessActiveReported").get())
        assertEquals(0, hotspotCloses)
        assertTrue(statuses.isEmpty())
        verify(bootstrap, never()).close()
    }

    @Test fun withdrawnHandoffRequestLeavesSessionAndBootstrapUntouched() {
        proofCall("rendered", 0, session)
        flag("wirelessHandoffRequested").set(false)
        timeout()
        assertEquals(0, hotspotCloses)
        assertTrue(statuses.isEmpty())
        verify(bootstrap, never()).close()
    }

    @Test fun priorServiceFailureCannotBeReplacedByVideoFallback() {
        proofCall("rendered", 0, session)
        controller.javaClass.getDeclaredMethod("fail", Throwable::class.java, Int::class.javaObjectType)
            .apply { isAccessible = true }.invoke(controller, java.io.IOException("AirPlay service disconnected"), 0)
        assertTrue(flag("wirelessFailureReported").get())
        timeout()
        assertTrue(statuses.single() is CarPlayStatus.Failed)
        assertFalse(flag("wirelessActiveReported").get())
        assertEquals(0, hotspotCloses)
        verify(bootstrap, never()).close()
    }

    private fun timeout() {
        controller.javaClass.getDeclaredMethod("handleWirelessHandoffTimeout", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(controller, 0)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun flag(name: String): AtomicBoolean = ReflectionHelpers.getField(controller, name)
    private fun tunnelReady(generation: Int) {
        controller.javaClass.getDeclaredMethod("onWirelessTunnelReady", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(controller, generation)
    }
    private fun keepControlAlive(generation: Int): Boolean =
        controller.javaClass.getDeclaredMethod("keepBluetoothControlAlive", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(controller, generation) as Boolean
    private fun awaitHandoff() {
        for (thread in Thread.getAllStackTraces().keys.filter { it.name == "xcertplay-wireless-handoff" }) {
            thread.join(2_000)
            assertFalse("Handoff worker did not finish", thread.isAlive)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun proofCall(name: String, vararg args: Any) {
        proof.javaClass.declaredMethods.single { it.name == name }.apply { isAccessible = true }.invoke(proof, *args)
    }
}
