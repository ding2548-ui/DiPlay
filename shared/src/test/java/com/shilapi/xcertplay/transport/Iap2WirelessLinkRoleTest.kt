package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Leapmotor adaptation of the upstream 0.2.11 test (BYD vehicle-status fields not ported). */
class Iap2WirelessLinkRoleTest {
    private val wireless = Iap2WirelessIdentification("AA:BB:CC:DD:EE:FF", "DiPlay")
    private val full = Iap2IdentificationConfig(
        name = "DiPlay",
        modelIdentifier = "DiPlay",
        manufacturer = "Leapmotor",
        serialNumber = "test",
        firmwareVersion = "1",
        hardwareVersion = "1",
        carPlayUsbInterfaceNumber = 3,
        locationInformationEnabled = true,
    )

    @Test
    fun bluetoothBootstrapOmitsLocation() {
        val bootstrap = full.forWirelessLink(Iap2WirelessLinkRole.BLUETOOTH_BOOTSTRAP, wireless)
        val parameters = identificationParameters(bootstrap)
        val sent = u16Values(parameters.first(6)!!.payload)
        val received = u16Values(parameters.first(7)!!.payload)

        assertFalse(bootstrap.locationInformationEnabled)
        assertFalse(Iap2LocationMessages.LOCATION_INFORMATION in sent)
        assertFalse(Iap2LocationMessages.START_LOCATION_INFORMATION in received)
        assertTrue(0x5703 in sent) // Wi-Fi bootstrap remains advertised.
    }

    @Test
    fun wifiRuntimeRetainsLocation() {
        val runtime = full.forWirelessLink(Iap2WirelessLinkRole.RUNTIME_TUNNEL, wireless)
        val parameters = identificationParameters(runtime)
        val sent = u16Values(parameters.first(6)!!.payload)
        val received = u16Values(parameters.first(7)!!.payload)

        assertTrue(runtime.locationInformationEnabled)
        assertTrue(Iap2LocationMessages.LOCATION_INFORMATION in sent)
        assertTrue(Iap2LocationMessages.START_LOCATION_INFORMATION in received)
        assertTrue(0x5703 in sent)
    }

    private fun identificationParameters(config: Iap2IdentificationConfig) =
        Iap2ParameterList.parse(Iap2IdentificationClient.identificationInformation(config).payload)

    private fun u16Values(bytes: ByteArray): List<Int> =
        List(bytes.size / 2) { index ->
            ((bytes[index * 2].toInt() and 0xff) shl 8) or (bytes[index * 2 + 1].toInt() and 0xff)
        }
}
