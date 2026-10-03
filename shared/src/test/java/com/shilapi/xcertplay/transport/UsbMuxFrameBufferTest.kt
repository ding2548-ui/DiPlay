package com.shilapi.xcertplay.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbMuxFrameBufferTest {
    @Test fun versionPaddingWorksAcrossEveryTransferSplit() {
        assertEverySplitPreservesFrames(version, zeroPadding, tcp(data = data))
    }

    @Test fun synAckPaddingWorksAcrossEveryTransferSplit() {
        assertEverySplitPreservesFrames(synAck, capturedPadding, tcp(data = data))
    }

    @Test fun unpaddedFramesAndUnknownNormalProtocolsArePreserved() {
        val unknown = mux(73, byteArrayOf(1, 2, 3), magic = 9)
        val buffer = UsbMuxFrameBuffer()
        buffer.append(version + synAck + unknown + tcp(data = data))
        assertEquals(0, buffer.takeFrame()!!.protocol)
        assertEquals(6, buffer.takeFrame()!!.protocol)
        val parsedUnknown = buffer.takeFrame()!!
        assertEquals(73, parsedUnknown.protocol)
        assertArrayEquals(byteArrayOf(1, 2, 3), parsedUnknown.payload)
        assertArrayEquals(tcp(data = data).copyOfRange(16, 16 + 20 + data.size), buffer.takeFrame()!!.payload)
        assertNull(buffer.takeFrame())
        assertEquals(0, buffer.bufferedBytes)
    }

    @Test fun coalescedPaddedAndUnpaddedRepliesKeepAllFollowingPayloads() {
        val buffer = UsbMuxFrameBuffer()
        buffer.append(version + zeroPadding + synAck + capturedPadding + tcp(data = data) + tcp(data = data))
        assertEquals(20, buffer.takeFrame()!!.length)
        assertEquals(36, buffer.takeFrame()!!.length)
        repeat(2) { assertArrayEquals(data, buffer.takeFrame()!!.payload.copyOfRange(20, 20 + data.size)) }
        assertNull(buffer.takeFrame())
        assertEquals(0, buffer.bufferedBytes)
    }

    @Test fun incompleteNextMuxAndTcpHeadersRemainBuffered() {
        val next = tcp(data = data)
        val buffer = after(synAck)
        buffer.append(capturedPadding.copyOfRange(0, 2))
        assertNull(buffer.takeFrame())
        buffer.append(capturedPadding.copyOfRange(2, 4) + next.copyOfRange(0, 12))
        assertNull(buffer.takeFrame())
        assertEquals(16, buffer.bufferedBytes)
        buffer.append(next.copyOfRange(12, 16))
        assertNull(buffer.takeFrame())
        assertEquals(20, buffer.bufferedBytes)
        buffer.append(next.copyOfRange(16, 25))
        assertNull(buffer.takeFrame())
        assertEquals(29, buffer.bufferedBytes)
        buffer.append(next.copyOfRange(25, next.size))
        assertArrayEquals(data, buffer.takeFrame()!!.payload.copyOfRange(20, 20 + data.size))
        assertEquals(0, buffer.bufferedBytes)
    }

    @Test fun paddingWithoutFurtherDataDoesNotDisappear() {
        val buffer = after(version)
        buffer.append(zeroPadding)
        repeat(10) { assertNull(buffer.takeFrame()) }
        assertEquals(4, buffer.bufferedBytes)
    }

    @Test fun tcpOptionsInTheFollowingFrameArePreserved() {
        val next = tcp(data = data, headerBytes = 24)
        val buffer = after(synAck)
        buffer.append(capturedPadding + next)
        assertArrayEquals(next.copyOfRange(16, next.size), buffer.takeFrame()!!.payload)
        assertEquals(0, buffer.bufferedBytes)
    }

    @Test fun validPayloadContainingHeaderLikeBytesIsNeverResynchronized() {
        val payload = data + capturedPadding + synAck + data
        val buffer = after(version)
        buffer.append(tcp(data = payload))
        assertArrayEquals(payload, buffer.takeFrame()!!.payload.copyOfRange(20, 20 + payload.size))
        assertEquals(0, buffer.bufferedBytes)
    }

    @Test fun incorrectMagicIsNotAcceptedAsAPaddingRecoveryTarget() {
        rejectAfter(version, capturedPadding + tcp(data = data, magic = 0xfeedface.toInt()))
    }

    @Test fun unknownProtocolIsNotAcceptedAsAPaddingRecoveryTarget() {
        rejectAfter(version, capturedPadding + mux(7, ByteArray(20)))
    }

    @Test fun invalidTcpPortsDoNotQualifyAsARecoveryTarget() {
        val invalid = tcp(data = data).also { it[18] = 0; it[19] = 0 }
        rejectAfter(version, capturedPadding + invalid)
    }

    @Test fun unrecognizedSynAckMagicDoesNotEnableRecovery() {
        rejectAfter(tcp(flags = 0x12, magic = 7), capturedPadding + tcp(data = data))
    }

    @Test fun tooShortTcpHeaderIsNotAcceptedAsAPaddingRecoveryTarget() {
        val invalid = tcp(data = data).also { it[28] = 0x30 }
        rejectAfter(synAck, capturedPadding + invalid)
    }

    @Test fun tcpHeaderBeyondDeclaredLengthIsNotAcceptedAsAPaddingRecoveryTarget() {
        val invalid = synAck.copyOf().also { it[28] = 0xf0.toByte() }
        rejectAfter(synAck, capturedPadding + invalid)
    }

    @Test fun overlargeNextFrameIsNotAcceptedAsAPaddingRecoveryTarget() {
        val invalid = synAck.copyOf().also { putU32(it, 4, 65_537) }
        rejectAfter(version, capturedPadding + invalid)
    }

    @Test fun invalidNormalTcpLengthStillFailsImmediately() {
        val invalid = synAck.copyOf(16).also { putU32(it, 4, 6) }
        rejectAfter(version, invalid)
    }

    @Test fun arbitraryLeadingJunkDoesNotTriggerResynchronization() {
        val buffer = UsbMuxFrameBuffer()
        buffer.append(capturedPadding + tcp(data = data))
        assertProtocolFailure { buffer.takeFrame() }
    }

    @Test fun eightPaddingBytesAreNotScannedForANextFrame() {
        rejectAfter(synAck, zeroPadding + capturedPadding + tcp(data = data))
    }

    @Test fun paddingAfterAMediaOrLockdownDataFrameIsNotDiscarded() {
        rejectAfter(tcp(data = data), capturedPadding + tcp(data = data))
    }

    @Test fun paddingAfterAPayloadFreeAckIsNotDiscarded() {
        rejectAfter(tcp(flags = 0x10), capturedPadding + tcp(data = data))
    }

    @Test fun synAckWithDataDoesNotEnablePaddingRecovery() {
        rejectAfter(tcp(flags = 0x12, data = data), capturedPadding + tcp(data = data))
    }

    @Test fun nonVersionTwoReplyDoesNotEnablePaddingRecovery() {
        val invalidVersion = version.copyOf().also { putU32(it, 8, 1) }
        rejectAfter(invalidVersion, capturedPadding + tcp(data = data))
    }

    @Test fun invalidLengthWithoutAnEligibleReplyStillFails() {
        val invalid = ByteArray(16)
        val buffer = UsbMuxFrameBuffer()
        buffer.append(invalid)
        assertProtocolFailure { buffer.takeFrame() }
    }

    @Test fun aMaximumSizeDataFrameRemainsIntact() {
        val payload = ByteArray(65_536 - 36) { (it % 251).toByte() }
        val next = tcp(data = payload)
        val buffer = after(version)
        buffer.append(zeroPadding + next.copyOfRange(0, 25))
        assertNull(buffer.takeFrame())
        buffer.append(next.copyOfRange(25, next.size))
        val frame = buffer.takeFrame()!!
        assertEquals(65_536, frame.length)
        assertArrayEquals(payload, frame.payload.copyOfRange(20, frame.payload.size))
        assertEquals(0, buffer.bufferedBytes)
    }

    @Test fun paddingDiagnosticsAreCappedAndContainOnlyFramingMetadata() {
        val reports = mutableListOf<String>()
        val buffer = UsbMuxFrameBuffer(reports::add)
        repeat(10) {
            buffer.append(synAck + capturedPadding + tcp(data = data))
            buffer.takeFrame(); buffer.takeFrame()
        }
        assertEquals(4, reports.size)
        assertTrue(reports.all { it.startsWith("USBMUX optional reply padding skipped bytes=4 ") })
        assertTrue(reports.none { it.contains("mCon") || it.contains("private-sample") || it.contains("62078") })
    }

    @Test fun aDiagnosticCallbackFailureDoesNotBreakFraming() {
        val buffer = UsbMuxFrameBuffer { throw IllegalStateException("logger unavailable") }
        buffer.append(version + zeroPadding + tcp(data = data))
        buffer.takeFrame()
        assertArrayEquals(data, buffer.takeFrame()!!.payload.copyOfRange(20, 20 + data.size))
    }

    private fun assertEverySplitPreservesFrames(first: ByteArray, padding: ByteArray, next: ByteArray) {
        val wire = first + padding + next
        for (split in 0..wire.size) {
            val buffer = UsbMuxFrameBuffer()
            val frames = mutableListOf<UsbMuxFrame>()
            fun drain() { while (true) frames.add(buffer.takeFrame() ?: break) }
            buffer.append(wire.copyOfRange(0, split)); drain()
            buffer.append(wire.copyOfRange(split, wire.size)); drain()
            assertEquals("transfer split at $split", 2, frames.size)
            assertArrayEquals(first.copyOfRange(16, first.size), frames[0].payload)
            assertArrayEquals(next.copyOfRange(16, next.size), frames[1].payload)
            assertEquals(0, buffer.bufferedBytes)
        }
    }

    private fun after(frame: ByteArray): UsbMuxFrameBuffer = UsbMuxFrameBuffer().also {
        it.append(frame); assertEquals(frame.size, it.takeFrame()!!.length)
    }

    private fun rejectAfter(previous: ByteArray, suffix: ByteArray) {
        val buffer = after(previous)
        buffer.append(suffix)
        assertProtocolFailure { buffer.takeFrame() }
        assertEquals(suffix.size, buffer.bufferedBytes)
    }

    private fun assertProtocolFailure(block: () -> Unit) {
        try { block(); throw AssertionError("Expected malformed framing to remain a protocol error") }
        catch (_: IphoneUsbException.Protocol) { }
    }

    private companion object {
        val zeroPadding = ByteArray(4)
        val capturedPadding = byteArrayOf(0x6d, 0x43, 0x6f, 0x6e)
        val data = "private-sample payload mCon".toByteArray()
        val version = mux(0, byteArrayOf(0x49, 0x28, 0x73, 0), magic = 2)
        val synAck = tcp(flags = 0x12)

        fun tcp(flags: Int = 0x10, data: ByteArray = ByteArray(0), headerBytes: Int = 20,
            magic: Int = 0xfaceface.toInt()): ByteArray {
            val payload = ByteArray(headerBytes + data.size)
            payload[0] = 0xf2.toByte(); payload[1] = 0x7e; payload[3] = 1
            payload[12] = ((headerBytes / 4) shl 4).toByte(); payload[13] = flags.toByte()
            data.copyInto(payload, headerBytes)
            return mux(6, payload, magic)
        }

        fun mux(protocol: Int, payload: ByteArray, magic: Int = 0xfaceface.toInt()): ByteArray =
            ByteArray(16 + payload.size).also {
                putU32(it, 0, protocol); putU32(it, 4, it.size); putU32(it, 8, magic)
                it[13] = 1; payload.copyInto(it, 16)
            }

        fun putU32(bytes: ByteArray, offset: Int, value: Int) {
            for (i in 0..3) bytes[offset + i] = (value ushr (24 - i * 8)).toByte()
        }
    }
}
