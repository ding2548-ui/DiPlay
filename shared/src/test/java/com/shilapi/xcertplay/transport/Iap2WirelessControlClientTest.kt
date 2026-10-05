package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2WirelessMessages
import com.shilapi.xcertplay.iap2.message.Iap2WirelessSessionParameters
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2Parameter
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class Iap2WirelessControlClientTest {
    @Test fun startSessionEventRequiresSuccessfulSendAndFollowsTheWrite() {
        var sent = false
        var events = 0
        sendStartSession(endpoint(), { frame ->
            assertEquals(0x4301, frame.messageId)
            sent = true
        }, { event ->
            assertTrue(sent)
            assertTrue(event.sentAtNanos > 0)
            events++
        })
        assertEquals(1, events)
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            sendStartSession(endpoint(), { throw java.io.IOException("write failed") }, { events++ })
        }
        assertEquals(1, events)
    }

    @Test fun availabilityFlagsDoNotExportTransportIdentifiers() {
        val wireless = Iap2ParameterList.of(
            Iap2Parameter(0, byteArrayOf(1)),
            Iap2Parameter(1, "Jane's iPhone\u0000".encodeToByteArray()),
        ).encode()
        val frame = Iap2Frame(0x4300, Iap2ParameterList.of(Iap2Parameter(1, wireless)).encode())
        assertEquals("iap2 availability wired=unknown wireless=true themeAssets=unknown",
            Iap2WirelessControlClient.carPlayAvailabilityDiagnostic(frame))
        val malformed = Iap2Frame(0x4300, byteArrayOf(0))
        val diagnostic = Iap2WirelessControlClient.carPlayAvailabilityDiagnostic(malformed)
        assertTrue(diagnostic.contains("decode=failed"))
        assertFalse(diagnostic.contains("Jane"))
    }

    @Test
    fun accessoryWiFiConfigurationMatchesLiviVector() {
        val frame = Iap2WirelessControlClient.accessoryWiFiConfiguration(endpoint())

        assertEquals(
            "404000275703000900014c49564900000e00027365637265743132330000050003030005000424",
            frame.encodedFrame().hex(),
        )
    }

    @Test
    fun carPlayStartSessionMatchesLiviWirelessVector() {
        val frame = Iap2WirelessControlClient.carPlayStartSession(endpoint())

        assertEquals(
            "40400060430100350001000900004c49564900000e000173656372657431323300000500022400100003" +
                "3139322e3136382e322e31000005000403000800020000c000000a00036465762d3100000b000461" +
                "61626263630000080005312e3000",
            frame.encodedFrame().hex(),
        )
    }

    @Test
    fun wirelessIdentificationAdvertisesTransportComponentsWithoutUsbHost() {
        val config = Iap2IdentificationConfig(
            name = "LIVI",
            modelIdentifier = "LIVI",
            manufacturer = "LIVI",
            serialNumber = "0123456",
            firmwareVersion = "1.0.0",
            hardwareVersion = "1.0",
            wireless = Iap2WirelessIdentification(
                bluetoothMac = "AA:BB:CC:DD:EE:FF",
                ssid = "LIVI",
            ),
        )
        val parameters = parameters(
            Iap2IdentificationClient.identificationInformation(config).payload,
        )

        assertNull(parameters.firstOrNull { it.id == 16 })
        assertArrayEquals(byteArrayOf(0), parameters.single { it.id == 8 }.payload)

        val bluetooth = parameters(parameters.single { it.id == 17 }.payload)
        assertEquals("blue\u0000", bluetooth.single { it.id == 1 }.payload.decodeToString())
        assertArrayEquals(
            byteArrayOf(0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte(), 0xdd.toByte(), 0xee.toByte(), 0xff.toByte()),
            bluetooth.single { it.id == 3 }.payload,
        )

        val wireless = parameters(parameters.single { it.id == 24 }.payload)
        assertEquals("LIVI\u0000", wireless.single { it.id == 1 }.payload.decodeToString())

        val sent = u16Values(parameters.single { it.id == 6 }.payload)
        val received = u16Values(parameters.single { it.id == 7 }.payload)
        assertTrue(0x5703 in sent)
        assertTrue(0x4301 in sent)
        assertFalse(0xae03 in sent)
        assertTrue(0x4300 in received)
        assertTrue(0x4e0d in received)
        assertTrue(0x4e0e in received)
        assertTrue(0x5702 in received)
    }

    @Test
    fun wirelessCarPlayUpdateReadsBooleanAvailability() {
        val frame = Iap2Frame(
            0x4e0d,
            Iap2ParameterList.of(Iap2Parameter(0, byteArrayOf(1))).encode(),
        )

        assertTrue(Iap2WirelessMessages.wirelessCarPlayAvailability(frame))
    }

    @Test
    fun wiredIdentificationKeepsCarPlayStartSessionDeclaration() {
        val config = Iap2IdentificationConfig(
            name = "wired",
            modelIdentifier = "wired",
            manufacturer = "test",
            serialNumber = "1",
            firmwareVersion = "1.0",
            hardwareVersion = "1.0",
            carPlayUsbInterfaceNumber = 4,
        )
        val parameters = parameters(
            Iap2IdentificationClient.identificationInformation(config).payload,
        )

        assertTrue(0x4301 in u16Values(parameters.single { it.id == 6 }.payload))
        assertTrue(0x4300 in u16Values(parameters.single { it.id == 7 }.payload))
    }

    @Test fun apHintIsEncodedOnlyInWifiConfigurationAndDoesNotChangeReceiverStartIdentity() {
        val ap = byteArrayOf(0x02, 0x11, 0x22, 0x33, 0x44, 0x55)
        val configured = endpoint(ap)
        ap[0] = 0x04 // Input mutation must not change the endpoint snapshot.
        val frame = Iap2WirelessControlClient.accessoryWiFiConfiguration(configured)
        assertArrayEquals(byteArrayOf(0x02, 0x11, 0x22, 0x33, 0x44, 0x55), parameters(frame.payload).single { it.id == 0 }.payload)
        assertArrayEquals(Iap2WirelessControlClient.carPlayStartSession(endpoint()).encodedFrame(),
            Iap2WirelessControlClient.carPlayStartSession(configured).encodedFrame())
        assertNull(parameters(Iap2WirelessControlClient.accessoryWiFiConfiguration(endpoint()).payload).firstOrNull { it.id == 0 })
    }

    @Test fun sameLanHintDoesNotLeakIntoSubsequentP2pFrames() {
        val p2p = endpoint()
        val before = Iap2WirelessControlClient.accessoryWiFiConfiguration(p2p).encodedFrame()
        val lan = endpoint(byteArrayOf(2, 3, 4, 5, 6, 7))
        repeat(2) {
            assertEquals(listOf(0, 1, 2, 3, 4), parameters(Iap2WirelessControlClient.accessoryWiFiConfiguration(lan).payload).map { it.id })
        }
        assertArrayEquals(before, Iap2WirelessControlClient.accessoryWiFiConfiguration(endpoint()).encodedFrame())
        assertEquals(listOf(1, 2, 3, 4), parameters(Iap2WirelessControlClient.accessoryWiFiConfiguration(endpoint()).payload).map { it.id })
    }

    @Test fun openNetworkEncodesAnEmptyCredentialInBothBootstrapMessages() {
        val open = Iap2WirelessCarPlayEndpoint("Guest", "", 36, Iap2WirelessSecurity.NONE,
            listOf("fe80::1234"), 7000, "dev-1", "aabbcc", "1.0")
        val configuration = parameters(Iap2WirelessControlClient.accessoryWiFiConfiguration(open).payload)
        assertArrayEquals(byteArrayOf(0), configuration.single { it.id == 2 }.payload)
        assertArrayEquals(byteArrayOf(0), configuration.single { it.id == 3 }.payload)
        val start = parameters(Iap2WirelessControlClient.carPlayStartSession(open).payload)
        val wireless = parameters(start.single { it.id == 1 }.payload)
        assertArrayEquals(byteArrayOf(0), wireless.single { it.id == 1 }.payload)
        assertArrayEquals(byteArrayOf(0), wireless.single { it.id == 4 }.payload)
    }

    @Test fun securedSessionStillRejectsAnEmptyCredential() {
        assertThrows(IllegalArgumentException::class.java) {
            Iap2WirelessSessionParameters("Guest", "", 36, listOf("fe80::1234"), 2)
        }
    }

    private fun endpoint(ap: ByteArray? = null): Iap2WirelessCarPlayEndpoint = Iap2WirelessCarPlayEndpoint(
        ssid = "LIVI",
        passphrase = "secret123",
        channel = 36,
        security = Iap2WirelessSecurity.WPA3_TRANSITION,
        ipAddresses = listOf("192.168.2.1"),
        airPlayPort = 49152,
        deviceIdentifier = "dev-1",
        publicKey = "aabbcc",
        sourceVersion = "1.0",
        accessPointBssid = ap,
    )

    private fun u16Values(bytes: ByteArray): List<Int> =
        List(bytes.size / 2) { index ->
            ((bytes[index * 2].toInt() and 0xff) shl 8) or (bytes[index * 2 + 1].toInt() and 0xff)
        }

    private fun parameters(bytes: ByteArray) = Iap2ParameterList.parse(bytes).asList()

    private fun ByteArray.hex(): String =
        joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
}
