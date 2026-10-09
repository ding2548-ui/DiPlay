package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ScreenCodecTest {
    @Test
    fun validLengthPrefixesBecomeAnnexBInPlace() {
        val first = byteArrayOf(0x40, 0x01)
        val second = byteArrayOf(0x42, 0x01, 0x02)
        val payload =
            byteArrayOf(0, 0, 0, first.size.toByte()) + first +
                byteArrayOf(0, 0, 0, second.size.toByte()) + second

        val converted = ScreenCodec.lengthPrefixedToAnnexB(payload)

        assertSame(payload, converted)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1) + first +
                byteArrayOf(0, 0, 0, 1) + second,
            converted,
        )
    }

    @Test
    fun malformedLengthsLeavePayloadUntouched() {
        val payload = byteArrayOf(0, 0, 0, 5, 0x40, 0x01)
        val original = payload.copyOf()

        assertSame(payload, ScreenCodec.lengthPrefixedToAnnexB(payload))
        assertArrayEquals(original, payload)
    }

    @Test fun readsTheFrameTimeFromTheScreenHeader() {
        val header = ByteArray(128)
        // NTP 32.32 little-endian at byte 8: 3 s + 0x40000000/2^32 s = 3.25 s.
        val raw = (3L shl 32) or 0x4000_0000L
        for (i in 0 until 8) header[8 + i] = (raw ushr (8 * i)).toByte()
        assertEquals(3_250_000_000L, ScreenCodec.senderNanos(header))
        // One 1/60 s step, as the iPhone stamps consecutive main-screen frames.
        val next = raw + 0x0444_4444L
        for (i in 0 until 8) header[8 + i] = (next ushr (8 * i)).toByte()
        assertEquals(16_666_666L, ScreenCodec.senderNanos(header) - 3_250_000_000L)
        assertEquals(0L, ScreenCodec.senderNanos(ByteArray(12)))
    }
}
