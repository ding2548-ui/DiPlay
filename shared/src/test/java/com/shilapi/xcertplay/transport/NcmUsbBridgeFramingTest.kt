package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Replays USB completions through recv(), including boundaries invisible to an NTB byte stream. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE,
    shadows = [NcmFramingConnectionShadow::class, NcmFramingRequestShadow::class])
class NcmUsbBridgeFramingTest {
    @Before fun reset() { NcmFramingReplay.reset() }

    @Test fun completeAlignedBlockDoesNotWaitForAnExtraByte() {
        NcmFramingReplay.chunks.add(unpadded(alignedFrame, 1))
        withBridge { ncm -> assertArrayEquals(alignedFrame, ncm.recv(50)) }
        assertEquals(1, NcmFramingReplay.completions)
    }

    @Test fun adjacentAlignedBlocksKeepBothHeadersAndAllFrames() {
        val second = ByteArray(996) { (it * 17).toByte() }
        NcmFramingReplay.chunks.add(
            unpadded(alignedFrame, 1) + unpadded(second, 2) + Ntb16Codec.build(smallFrame, 3),
        )
        withBridge { ncm ->
            assertArrayEquals(alignedFrame, ncm.recv(50))
            assertArrayEquals(second, ncm.recv(50))
            assertArrayEquals(smallFrame, ncm.recv(50))
        }
        assertEquals(1, NcmFramingReplay.completions)
    }

    @Test fun optionalZeroPadAndFollowingBlockCanShareACompletion() {
        NcmFramingReplay.chunks.add(Ntb16Codec.build(alignedFrame, 1) + Ntb16Codec.build(smallFrame, 2))
        withBridge { ncm ->
            assertArrayEquals(alignedFrame, ncm.recv(50))
            assertArrayEquals(smallFrame, ncm.recv(50))
        }
    }

    @Test fun optionalPadAndNextHeaderCanArriveInSeparatePartialReads() {
        checkSplitFollowingBlock(padded = true)
    }

    @Test fun unpaddedNextHeaderCanArriveInSeparatePartialReads() {
        checkSplitFollowingBlock(padded = false)
    }

    @Test fun nonzeroGarbageAfterAlignedBlockIsSkippedAndTheNextBlockRecovered() {
        NcmFramingReplay.chunks.add(unpadded(alignedFrame, 1) + byteArrayOf(0x55))
        NcmFramingReplay.chunks.add(Ntb16Codec.build(smallFrame, 2))
        val diagnostics = withBridge { ncm ->
            assertArrayEquals(alignedFrame, ncm.recv(50))
            assertArrayEquals(smallFrame, ncm.recv(50))
        }
        assertEquals(listOf("NCM resynchronized after Invalid NTB16 short-packet pad; skipped=1 resyncs=1"), diagnostics)
    }

    @Test fun nextHeaderWithOnlyItsFirstSignatureByteCorrectIsDropped() {
        val malformed = Ntb16Codec.build(smallFrame, 2).also { it[1] = 0x55 }
        NcmFramingReplay.chunks.add(unpadded(alignedFrame, 1) + malformed)
        NcmFramingReplay.chunks.add(Ntb16Codec.build(alignedFrame, 3))
        withBridge { ncm ->
            assertArrayEquals(alignedFrame, ncm.recv(50))
            assertArrayEquals(alignedFrame, ncm.recv(50))
        }
    }

    @Test fun moreThanOnePadByteIsSkipped() {
        NcmFramingReplay.chunks.add(Ntb16Codec.build(alignedFrame, 1) + byteArrayOf(0) + Ntb16Codec.build(smallFrame, 2))
        withBridge { ncm ->
            assertArrayEquals(alignedFrame, ncm.recv(50))
            assertArrayEquals(smallFrame, ncm.recv(50))
        }
    }

    @Test fun unexpectedPaddingAfterAnUnalignedBlockIsSkipped() {
        NcmFramingReplay.chunks.add(Ntb16Codec.build(smallFrame, 1) + byteArrayOf(0) + Ntb16Codec.build(smallFrame, 2))
        withBridge { ncm ->
            assertArrayEquals(smallFrame, ncm.recv(50))
            assertArrayEquals(smallFrame, ncm.recv(50))
        }
    }

    @Test fun fullSpeedLinkAcceptsThePadAfterA64ByteAlignedBlock() {
        val frame64 = ByteArray(484 - 448) { (it * 7).toByte() } // 28 + 36 = 64 bytes
        val block = Ntb16Codec.build(frame64, 1)
        assertEquals(64, block.size)
        NcmFramingReplay.chunks.add(block + byteArrayOf(0) + Ntb16Codec.build(smallFrame, 2))
        val diagnostics = withBridge(packetSize = 64) { ncm ->
            assertArrayEquals(frame64, ncm.recv(50))
            assertArrayEquals(smallFrame, ncm.recv(50))
        }
        assertTrue("A 64-byte link pad is expected, not a resync: $diagnostics", diagnostics.isEmpty())
    }

    @Test fun aBlockCutShortInTheMiddleOfAStreamLosesOnlyThatBlock() {
        val second = ByteArray(996) { (it * 17).toByte() }
        val damaged = Ntb16Codec.build(second, 2)
        NcmFramingReplay.chunks.add(Ntb16Codec.build(smallFrame, 1) + damaged.copyOf(300))
        val following = (3 until 43).map { Ntb16Codec.build(smallFrame, it) }.reduce(ByteArray::plus)
        NcmFramingReplay.chunks.add(damaged.copyOfRange(700, damaged.size) + following)
        withBridge { ncm ->
            assertArrayEquals(smallFrame, ncm.recv(50))
            // The cut block's header claims more bytes than arrived, so it swallows the start of
            // the traffic behind it; the IP stack drops what it yields, and the stream recovers.
            val received = generateSequence { ncm.recv(50) }.toList()
            assertTrue(received.size >= 20)
            assertTrue(received.takeLast(20).all { it.contentEquals(smallFrame) })
        }
    }

    @Test fun damageThatKeepsRecurringStillFailsTheTransport() {
        repeat(20) { NcmFramingReplay.chunks.add(Ntb16Codec.build(smallFrame, it) + byteArrayOf(0x55)) }
        withBridge { ncm ->
            val error = expectFailure { repeat(40) { ncm.recv(50) } }
            assertTrue(error.message!!.contains("(repeated)"))
            assertTrue(error === expectFailure { ncm.recv(50) })
        }
    }

    private fun checkSplitFollowingBlock(padded: Boolean) {
        NcmFramingReplay.chunks.add(unpadded(alignedFrame, 1))
        if (padded) NcmFramingReplay.chunks.add(byteArrayOf(0))
        val following = Ntb16Codec.build(smallFrame, 2)
        NcmFramingReplay.chunks.addAll(listOf(
            following.copyOfRange(0, 1), following.copyOfRange(1, 3),
            following.copyOfRange(3, 12), following.copyOfRange(12, following.size),
        ))
        withBridge { ncm ->
            assertArrayEquals(alignedFrame, ncm.recv(50))
            assertEquals(1, NcmFramingReplay.completions)
            assertArrayEquals(smallFrame, ncm.recv(50))
        }
        assertTrue(NcmFramingReplay.chunks.isEmpty())
    }

    private fun unpadded(frame: ByteArray, sequence: Int): ByteArray {
        val block = Ntb16Codec.build(frame, sequence)
        assertEquals(1, block.size % 512)
        return block.copyOf(block.size - 1)
    }

    private fun expectFailure(block: () -> Unit): IphoneUsbException.DeviceUnavailable {
        try { block(); throw AssertionError("Expected invalid NTB framing to fail") }
        catch (error: IphoneUsbException.DeviceUnavailable) { return error }
    }

    private fun withBridge(packetSize: Int = 512, block: (NcmUsbBridge) -> Unit): List<String> {
        val connection = ReflectionHelpers.callConstructor(UsbDeviceConnection::class.java,
            ClassParameter.from(UsbDevice::class.java, null))
        fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(UsbEndpoint::class.java,
            ClassParameter.from(Int::class.javaPrimitiveType, address),
            ClassParameter.from(Int::class.javaPrimitiveType, 2),
            ClassParameter.from(Int::class.javaPrimitiveType, packetSize),
            ClassParameter.from(Int::class.javaPrimitiveType, 0))
        val diagnostics = mutableListOf<String>()
        val ncm = NcmUsbBridge(connection, endpoint(0x06), endpoint(0x85), null, emptyList(), null, diagnostics::add)
        try { block(ncm) } finally { ncm.close() }
        return diagnostics
    }

    companion object {
        private val alignedFrame = ByteArray(484) { (it * 31).toByte() }
        private val smallFrame = byteArrayOf(0x33, 0x33, 0, 0, 0, 1, 0x86.toByte(), 0xdd.toByte())
    }
}

object NcmFramingReplay {
    val chunks = ArrayDeque<ByteArray>()
    var request: UsbRequest? = null
    var buffer: ByteBuffer? = null
    var completions = 0
    fun reset() { chunks.clear(); request = null; buffer = null; completions = 0 }
}

@Implements(UsbRequest::class)
class NcmFramingRequestShadow {
    @RealObject lateinit var request: UsbRequest
    @Implementation fun initialize(connection: UsbDeviceConnection, endpoint: UsbEndpoint) = true
    @Implementation fun queue(buffer: ByteBuffer): Boolean {
        NcmFramingReplay.request = request
        NcmFramingReplay.buffer = buffer
        return true
    }
    @Implementation fun cancel() = true
    @Implementation fun close() = Unit
}

@Implements(UsbDeviceConnection::class)
class NcmFramingConnectionShadow {
    @Implementation fun requestWait(timeoutMillis: Long): UsbRequest {
        val chunk = NcmFramingReplay.chunks.pollFirst() ?: throw TimeoutException()
        NcmFramingReplay.buffer!!.put(chunk)
        NcmFramingReplay.completions += 1
        return NcmFramingReplay.request!!
    }
    @Implementation fun close() = Unit
}
