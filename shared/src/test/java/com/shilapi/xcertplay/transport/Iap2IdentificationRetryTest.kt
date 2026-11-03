package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2Parameter
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** iOS 13.6.1 rejected the CarPlay pair as unknown (#556); a corrected identification is retried once. */
class Iap2IdentificationRetryTest {
    private val wireless = Iap2WirelessIdentification("AA:BB:CC:DD:EE:FF", "DiPlay")
    private val config = Iap2IdentificationConfig(
        name = "DiPlay",
        modelIdentifier = "DiPlay",
        manufacturer = "BYD",
        serialNumber = "test",
        firmwareVersion = "1",
        hardwareVersion = "1",
        carPlayUsbInterfaceNumber = 3,
    )
    private val olderIos = rejected(Iap2Parameter(6, byteArrayOf(0x43, 0x01)), Iap2Parameter(7, byteArrayOf(0x43, 0x00)))

    @Test
    fun onlyMessageListRejectionsCanBeCorrected() {
        assertEquals(setOf(0x4300, 0x4301), rejection(olderIos).omittable())
        assertNull(rejection(rejected(Iap2Parameter(16, byteArrayOf()))).omittable())
        assertNull(rejection(rejected(Iap2Parameter(6, byteArrayOf(0x43, 0x01)), Iap2Parameter(16, byteArrayOf()))).omittable())
        assertNull(rejection(rejected(Iap2Parameter(6, byteArrayOf(0xaa.toByte(), 0x01)))).omittable())
        assertNull(rejection(rejected(Iap2Parameter(7, byteArrayOf(0x43, 0x00, 0x50)))).omittable())
        assertNull(rejection(rejected(Iap2Parameter(6, byteArrayOf()))).omittable())
    }

    @Test
    fun omittedMessagesLeaveBothListsInEveryTransport() {
        for (identity in listOf(config, config.copy(wireless = wireless))) {
            val parameters = Iap2ParameterList.parse(
                Iap2IdentificationClient.identificationInformation(identity, setOf(0x4300, 0x4301)).payload,
            )
            val sent = u16Values(parameters.first(6)!!.payload)
            val received = u16Values(parameters.first(7)!!.payload)
            assertFalse(0x4301 in sent)
            assertFalse(0x4300 in received)
            assertTrue(0xaa03 in sent)
            assertTrue(0xaa05 in received)
        }
    }

    @Test
    fun olderIosIsIdentifiedOnTheSecondAttempt() {
        val phone = Phone(start(), olderIos, accepted())
        val progress = mutableListOf<String>()
        phone.identify(progress::add)
        assertEquals(2, phone.sent.size)
        assertTrue(0x4301 in phone.sentMessages(0))
        assertFalse(0x4301 in phone.sentMessages(1))
        assertEquals(listOf("iap2 identification retry without unsupported messages [0x4300, 0x4301]"), progress)
    }

    @Test
    fun aSecondRejectionStillFails() {
        val phone = Phone(start(), olderIos, olderIos)
        try {
            phone.identify {}
            fail("Expected the second rejection to fail identification")
        } catch (_: Iap2IdentificationException.Rejected) {
            assertEquals(2, phone.sent.size)
        }
    }

    @Test
    fun aRejectionThatCannotBeCorrectedFailsWithoutResending() {
        val phone = Phone(start(), rejected(Iap2Parameter(16, byteArrayOf())), accepted())
        try {
            phone.identify {}
            fail("Expected the rejection to fail identification")
        } catch (_: Iap2IdentificationException.Rejected) {
            assertEquals(1, phone.sent.size)
        }
    }

    @Test
    fun aRestartedIdentificationKeepsTheCorrection() {
        val phone = Phone(start(), olderIos, start(), accepted())
        phone.identify {}
        assertEquals(3, phone.sent.size)
        assertFalse(0x4301 in phone.sentMessages(2))
    }

    private inner class Phone(vararg replies: Iap2Frame) {
        private val queue = ArrayDeque(replies.toList())
        val sent = mutableListOf<Iap2Frame>()

        fun identify(onProgress: (String) -> Unit) = Iap2IdentificationClient.exchange(
            awaitReady = { true },
            recv = { queue.removeFirstOrNull() },
            send = { frame, _ -> sent += frame },
            config = config,
            timeoutMillis = 1_000,
            onProgress = onProgress,
        )

        fun sentMessages(index: Int): List<Int> =
            u16Values(Iap2ParameterList.parse(sent[index].payload).first(6)!!.payload)
    }

    private fun start() = Iap2Frame(Iap2IdentificationClient.START_IDENTIFICATION, ByteArray(0))
    private fun accepted() = Iap2Frame(Iap2IdentificationClient.IDENTIFICATION_ACCEPTED, ByteArray(0))
    private fun rejected(vararg parameters: Iap2Parameter) =
        Iap2Frame(Iap2IdentificationClient.IDENTIFICATION_REJECTED, Iap2ParameterList.of(*parameters).encode())

    private fun rejection(frame: Iap2Frame) = Iap2IdentificationClient.identificationRejection(frame)

    private fun Iap2IdentificationException.Rejected.omittable(): Set<Int>? =
        with(Iap2IdentificationClient) { omittableMessages() }

    private fun u16Values(bytes: ByteArray): List<Int> =
        List(bytes.size / 2) { index ->
            ((bytes[index * 2].toInt() and 0xff) shl 8) or (bytes[index * 2 + 1].toInt() and 0xff)
        }
}
