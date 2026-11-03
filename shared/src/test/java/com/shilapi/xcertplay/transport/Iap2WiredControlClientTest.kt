package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Iap2WiredControlClientTest {
    @Test
    fun carPlayStartSessionNestsAddressesInsideWiredAttributes() {
        val frame = Iap2WiredControlClient.carPlayStartSession(
            Iap2WiredCarPlayEndpoint(
                ipv6Addresses = listOf("fe80::2"),
                airPlayPort = 7000,
                deviceIdentifier = "02:00:00:00:00:02",
                publicKey = "abcd",
                sourceVersion = "1.0",
            ),
        )

        assertEquals(0x4301, frame.messageId)
        val outer = Iap2BodyReader.of(frame).list()
        val wired = outer.single { it.id == 0 }
        val addresses = Iap2ParameterList.parse(wired.payload).asList()
        assertEquals(1, addresses.size)
        assertEquals(0, addresses.single().id)
        assertArrayEquals("fe80::2\u0000".encodeToByteArray(), addresses.single().payload)
    }

    @Test
    fun powerSourceUpdateOffersTheConfiguredChargingCurrent() {
        listOf(2400, 1500, 500).forEach { milliAmps ->
            val frame = Iap2WiredControlClient.powerSourceUpdate(milliAmps)

            assertEquals(0xae03, frame.messageId)
            val current = Iap2BodyReader.of(frame).list().single { it.id == 0 }.payload
            assertArrayEquals(byteArrayOf((milliAmps shr 8).toByte(), milliAmps.toByte()), current)
        }
    }
}
