package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Android 7.x has no timed requestWait or queue(ByteBuffer); both read pipes use the older calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE,
    shadows = [CompatibilityUsbConnectionShadow::class, CompatibilityUsbRequestShadow::class])
class Android7UsbReadTest {
    @Before fun reset() { UsbQueueReplay.reset() }

    @Test fun usbmuxReadsThroughTheBlockingWait() {
        val pipe = Iap2UsbSession(connection(), endpoint(0x04), endpoint(0x85))
        try {
            repeat(2) { assertArrayEquals(UsbReadQueueCompatibilityTest.payload, pipe.read(1_000)) }
        } finally { pipe.close() }
        assertEquals(listOf(65_536, 65_536), UsbQueueReplay.sizes)
        assertEquals(2, UsbQueueReplay.untimedWaits)
    }

    @Test fun ncmReadsThroughTheBlockingWaitAndKeepsTheFallbackSize() {
        UsbQueueReplay.outcomes.addAll(listOf(false, true, true))
        val diagnostics = mutableListOf<String>()
        val ncm = NcmUsbBridge(connection(), endpoint(0x06), endpoint(0x85), null, emptyList(), null, diagnostics::add)
        try {
            repeat(2) { assertEquals(UsbReadQueueCompatibilityTest.payload.size, readChunk(ncm)) }
        } finally { ncm.close() }
        assertEquals(listOf(32_768, 16_384, 16_384), UsbQueueReplay.sizes)
        assertEquals(2, UsbQueueReplay.untimedWaits)
        assertTrue(diagnostics.single().contains("api=25"))
    }

    private fun readChunk(ncm: NcmUsbBridge): Int? = try {
        NcmUsbBridge::class.java.getDeclaredMethod("readChunk", Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(ncm, 1_000L) as Int?
    } catch (error: InvocationTargetException) { throw error.targetException }

    private fun connection(): UsbDeviceConnection = ReflectionHelpers.callConstructor(
        UsbDeviceConnection::class.java, ClassParameter.from(UsbDevice::class.java, null))
    private fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(
        UsbEndpoint::class.java, ClassParameter.from(Int::class.javaPrimitiveType, address),
        ClassParameter.from(Int::class.javaPrimitiveType, 2),
        ClassParameter.from(Int::class.javaPrimitiveType, 512),
        ClassParameter.from(Int::class.javaPrimitiveType, 0))
}
