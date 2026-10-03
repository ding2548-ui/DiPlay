package com.shilapi.xcertplay.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2LinkEngineFileTransferTest {
    @Test
    fun routesAndRepliesOnTheNegotiatedFileTransferSession() {
        val engine = Iap2LinkEngine()
        engine.start(wiredInitiator = true, nowMillis = 0)
        engine.takeOutput()
        val synchronization = Iap2LinkEngine.SynchronizationPayload(
            maxOutgoing = 8,
            maxLength = 4096,
            retransmissionTimeoutMillis = 2_000,
            acknowledgementTimeoutMillis = 500,
            maxRetransmissions = 4,
            maxAcknowledgements = 3,
            sessions = listOf(
                Iap2LinkEngine.SessionDescriptor(Iap2LinkEngine.CONTROL_SESSION_ID, 0, 2),
                Iap2LinkEngine.SessionDescriptor(Iap2LinkEngine.FILE_TRANSFER_SESSION_ID, 1, 2),
            ),
        )
        engine.feed(packet(0xc0, 1, 99, 0, synchronization.encode()), 1)
        assertTrue(engine.writable())
        while (engine.pollEvent() != null) Unit
        engine.takeOutput()

        val datagram = byteArrayOf(0x81.toByte(), 4, 0, 0, 0, 0, 0, 0, 0, 5, 0, 2)
        engine.feed(packet(0x40, 2, 99, Iap2LinkEngine.FILE_TRANSFER_SESSION_ID, datagram), 2)
        val event = engine.pollEvent() as Iap2LinkEngine.Event.Session
        assertEquals(Iap2LinkEngine.FILE_TRANSFER_SESSION_ID, event.sessionId)
        assertArrayEquals(datagram, event.bytes)
        engine.takeOutput()

        val start = byteArrayOf(0x81.toByte(), 1)
        engine.sendSession(Iap2LinkEngine.FILE_TRANSFER_SESSION_ID, start, 3)
        val reply = engine.takeOutput()
        assertEquals(Iap2LinkEngine.FILE_TRANSFER_SESSION_ID, reply[7].toInt() and 0xff)
        assertArrayEquals(start, reply.copyOfRange(9, 11))
    }

    private fun packet(
        control: Int,
        sequence: Int,
        acknowledgement: Int,
        sessionId: Int,
        payload: ByteArray,
    ): ByteArray {
        val length = 10 + payload.size
        val header = byteArrayOf(
            0xff.toByte(), 0x5a, (length ushr 8).toByte(), length.toByte(),
            control.toByte(), sequence.toByte(), acknowledgement.toByte(), sessionId.toByte(), 0,
        )
        header[8] = checksum(header.copyOf(8)).toByte()
        return header + payload + checksum(payload).toByte()
    }

    private fun checksum(bytes: ByteArray): Int =
        (-bytes.sumOf { it.toInt() and 0xff }) and 0xff
}
