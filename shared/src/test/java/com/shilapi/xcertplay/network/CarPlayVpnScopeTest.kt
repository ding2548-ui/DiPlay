package com.shilapi.xcertplay.network

import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Exercise the real attachment transaction while controlling only the Android VPN boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE, shadows = [VpnScopeBuilderShadow::class])
class CarPlayVpnScopeTest {
    @Before fun resetVpnBoundary() { VpnScopeBoundary.reset() }

    @Test fun allowlistRejectionNeverEstablishesAnUnrestrictedTunnel() {
        VpnScopeBoundary.allowFailure = PackageManager.NameNotFoundException("scope rejected")
        withService { service ->
            val result = attachWired(service)

            assertEquals(CarPlayVpnService.AttachResult.Failed("scope rejected"), result)
            assertEquals(listOf("allow:${service.packageName}"), VpnScopeBoundary.calls)
            assertReleased(service)
        }
    }

    @Test fun runtimePackageIsAllowedBeforeEstablishAndRevokedConsentCleansUp() {
        withService { service ->
            val result = attachWired(service)

            assertEquals(CarPlayVpnService.AttachResult.Failed("VpnService.establish returned null"), result)
            assertEquals(listOf("allow:${service.packageName}", "establish"), VpnScopeBoundary.calls)
            assertReleased(service)
        }
    }

    @Test fun platformEstablishmentFailureDoesNotRetryOrKeepAttachmentState() {
        VpnScopeBoundary.establishFailure = SecurityException("consent revoked")
        withService { service ->
            assertEquals(CarPlayVpnService.AttachResult.Failed("consent revoked"), attachWired(service))
            assertEquals(listOf("allow:${service.packageName}", "establish"), VpnScopeBoundary.calls)
            assertReleased(service)
        }
    }

    @Test fun rejectedWiredReplacementClosesWirelessListenerAndAllowsFreshWirelessAttach() {
        withService { service ->
            assertEquals(CarPlayVpnService.AttachResult.Started, attachWireless(service))
            assertTrue(service.isAttached())
            assertTrue(service.boundPort()!! > 0)
            assertTrue("Wireless must not establish a VPN", VpnScopeBoundary.calls.isEmpty())
            val oldServer = ReflectionHelpers.getField<ServerSocket>(service, "serverSocket")

            VpnScopeBoundary.allowFailure = PackageManager.NameNotFoundException("scope rejected")
            assertEquals(CarPlayVpnService.AttachResult.Failed("scope rejected"), attachWired(service))
            assertTrue("Replacement releases the previous listener", oldServer.isClosed)
            assertEquals(listOf("allow:${service.packageName}"), VpnScopeBoundary.calls)
            assertReleased(service)

            assertEquals(CarPlayVpnService.AttachResult.Started, attachWireless(service))
            assertTrue(service.isAttached())
            val newServer = ReflectionHelpers.getField<ServerSocket>(service, "serverSocket")
            service.detach()
            service.detach()
            assertTrue(newServer.isClosed)
            assertReleased(service)
        }
    }

    private fun withService(check: (CarPlayVpnService) -> Unit) {
        val controller = Robolectric.buildService(CarPlayVpnService::class.java).create()
        try { check(controller.get()) } finally { controller.destroy() }
    }

    private fun attachWired(service: CarPlayVpnService): CarPlayVpnService.AttachResult =
        service.attach(ncm(), "fe80::1234", ByteArray(6), config, identity, PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})

    private fun attachWireless(service: CarPlayVpnService): CarPlayVpnService.AttachResult =
        service.attachWireless(InetAddress.getLoopbackAddress(), config, identity, PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})

    private fun assertReleased(service: CarPlayVpnService) {
        assertFalse(service.isAttached())
        assertFalse(ReflectionHelpers.getField<AtomicBoolean>(service, "active").get())
        assertNull(service.boundPort())
        assertNull(ReflectionHelpers.getField<Any?>(service, "attachment"))
        assertNull(ReflectionHelpers.getField<Any?>(service, "serverSocket"))
        assertNull(ReflectionHelpers.getField<Any?>(service, "bridge"))
        assertNull(ReflectionHelpers.getField<Any?>(service, "tun"))
    }

    private fun ncm(): NcmUsbBridge {
        val connection = ReflectionHelpers.callConstructor(UsbDeviceConnection::class.java,
            ClassParameter.from(UsbDevice::class.java, null))
        fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(
            UsbEndpoint::class.java, ClassParameter.from(Int::class.javaPrimitiveType, address),
            ClassParameter.from(Int::class.javaPrimitiveType, 2),
            ClassParameter.from(Int::class.javaPrimitiveType, 512),
            ClassParameter.from(Int::class.javaPrimitiveType, 0))
        return NcmUsbBridge(connection, endpoint(0x04), endpoint(0x85), null, emptyList(), null)
    }

    private val config = AirPlayConfig("test", "00:00:00:00:00:01", "00:00:00:00:00:02",
        "1", AirPlayDisplayConfig(800, 480), port = 0)
    private val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test")
}

/** No TUN can be created here: rejected scoping must stop before this boundary is reached. */
object VpnScopeBoundary {
    val calls = mutableListOf<String>()
    var allowFailure: Exception? = null
    var establishFailure: Exception? = null

    fun reset() {
        calls.clear()
        allowFailure = null
        establishFailure = null
    }
}

@Implements(VpnService.Builder::class)
class VpnScopeBuilderShadow {
    @RealObject private lateinit var builder: VpnService.Builder

    // The Android implementation calls InetAddress.parseNumericAddress, which the host JVM lacks.
    // Control that platform plumbing while keeping scope/establish ordering in the real service.
    @Implementation fun addAddress(address: String, prefixLength: Int): VpnService.Builder = builder

    @Implementation fun addRoute(address: String, prefixLength: Int): VpnService.Builder = builder

    @Implementation fun addAllowedApplication(packageName: String): VpnService.Builder {
        VpnScopeBoundary.calls += "allow:$packageName"
        VpnScopeBoundary.allowFailure?.let { throw it }
        return builder
    }

    @Implementation fun establish(): ParcelFileDescriptor? {
        VpnScopeBoundary.calls += "establish"
        VpnScopeBoundary.establishFailure?.let { throw it }
        return null
    }
}
