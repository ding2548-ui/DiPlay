package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class Iap2LocationClientTest {
    @Test
    fun locationInformationEncodesNulTerminatedNmeaSentence() {
        val frame = Iap2LocationMessages.locationInformation("\$GPGGA")

        assertEquals(Iap2LocationMessages.LOCATION_INFORMATION, frame.messageId)
        val parameter = Iap2BodyReader.of(frame).list().single()
        assertEquals(0, parameter.id)
        assertTrue(parameter.payload.contentEquals("\$GPGGA\u0000".encodeToByteArray()))
    }

    @Test
    fun nmeaEncoderBuildsGgaAndRmcPairWithValidChecksums() {
        val encoded = NmeaLocationEncoder.encode(
            CarPlayLocationFix(
                latitudeDegrees = 48.1173,
                longitudeDegrees = 11.5166667,
                altitudeMeters = 545.4,
                bearingDegrees = 84.4,
                speedMetersPerSecond = 11.524,
                accuracyMeters = 4.5,
                timestampMillis = Instant.parse("2026-03-23T12:35:19Z").toEpochMilli(),
            ),
        )

        val sentences = encoded.split("\r\n").filter { it.isNotBlank() }
        assertEquals(2, sentences.size)
        assertTrue(sentences[0].startsWith("\$GPGGA,123519.00,4807.0380,N,01131.0000,E"))
        assertTrue(sentences[1].startsWith("\$GPRMC,123519.00,A,4807.0380,N,01131.0000,E"))
        sentences.forEach { sentence ->
            val star = sentence.lastIndexOf('*')
            assertTrue(star > 0)
            assertEquals(checksum(sentence.substring(1, star)), sentence.substring(star + 1))
        }
    }

    @Test
    fun rmcCourseIsEmptyWithoutAGpsDirection() {
        fun rmcFields(bearing: Double?) = NmeaLocationEncoder.encode(
            CarPlayLocationFix(
                latitudeDegrees = 48.1173,
                longitudeDegrees = 11.5166667,
                bearingDegrees = bearing,
                speedMetersPerSecond = 0.0,
                timestampMillis = Instant.parse("2026-03-23T12:35:19Z").toEpochMilli(),
            ),
        ).split("\r\n").single { it.startsWith("\$GPRMC") }.substringBefore('*').split(',')

        // $GPRMC,time,status,lat,N/S,lon,E/W,speed,course,date,...
        assertEquals("", rmcFields(null)[8])
        assertEquals("", rmcFields(Double.NaN)[8])
        assertEquals("84.40", rmcFields(84.4)[8])
        assertEquals("230326", rmcFields(null)[9])
    }

    @Test
    fun identificationAdvertisesLocationMessagesAndComponentOnlyWhenEnabled() {
        val base = wiredIdentification(locationInformationEnabled = false)
        val enabled = wiredIdentification(locationInformationEnabled = true)

        val baseParameters = parameters(
            Iap2IdentificationClient.identificationInformation(base).payload,
        )
        val enabledParameters = parameters(
            Iap2IdentificationClient.identificationInformation(enabled).payload,
        )

        assertNull(baseParameters.firstOrNull { it.id == 22 })
        assertFalse(0xfffb in u16Values(baseParameters.single { it.id == 6 }.payload))
        assertFalse(0xfffa in u16Values(baseParameters.single { it.id == 7 }.payload))

        val component = enabledParameters.single { it.id == 22 }
        assertNotNull(component)
        val fields = parameters(component.payload)
        assertTrue(fields.single { it.id == 0 }.payload.contentEquals(byteArrayOf(0, 0)))
        assertTrue(fields.single { it.id == 1 }.payload.contentEquals("xcertplay\u0000".encodeToByteArray()))
        assertTrue(fields.any { it.id == 17 && it.payload.isEmpty() })
        assertTrue(fields.any { it.id == 18 && it.payload.isEmpty() })
        assertTrue(0xfffb in u16Values(enabledParameters.single { it.id == 6 }.payload))
        assertTrue(0xfffa in u16Values(enabledParameters.single { it.id == 7 }.payload))
        assertTrue(0xfffc in u16Values(enabledParameters.single { it.id == 7 }.payload))
    }

    @Test
    fun wirelessIdentificationKeepsLocationMessagesAndOmitsWiredPowerSource() {
        val config = wiredIdentification(locationInformationEnabled = true).copy(
            wireless = Iap2WirelessIdentification(
                bluetoothMac = "AA:BB:CC:DD:EE:FF",
                ssid = "LIVI",
            ),
        )

        val parameters = parameters(
            Iap2IdentificationClient.identificationInformation(config).payload,
        )
        val sent = u16Values(parameters.single { it.id == 6 }.payload)
        val received = u16Values(parameters.single { it.id == 7 }.payload)

        assertTrue(0xfffb in sent)
        assertTrue(0x5703 in sent)
        assertFalse(0xae03 in sent)
        assertTrue(0xfffa in received)
        assertTrue(0xfffc in received)
    }

    private fun wiredIdentification(locationInformationEnabled: Boolean) = Iap2IdentificationConfig(
        name = "xcertplay",
        modelIdentifier = "xcertplay",
        manufacturer = "xcertplay",
        serialNumber = "xcertplay",
        firmwareVersion = "1.0.0",
        hardwareVersion = "1.0",
        carPlayUsbInterfaceNumber = 3,
        locationInformationEnabled = locationInformationEnabled,
    )

    private fun u16Values(bytes: ByteArray): List<Int> =
        List(bytes.size / 2) { index ->
            ((bytes[index * 2].toInt() and 0xff) shl 8) or
                (bytes[index * 2 + 1].toInt() and 0xff)
        }

    private fun parameters(bytes: ByteArray) = Iap2ParameterList.parse(bytes).asList()

    private fun checksum(body: String): String {
        var value = 0
        for (character in body) value = value xor character.code
        return "%02X".format(value)
    }
}
