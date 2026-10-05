package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class Iap2VehicleStatusTest {
    private val reading = VehicleStatusSnapshot(
        rangeKm = 150,
        rangeWarning = false,
        batteryPercent = 25.0,
        currentChargeWh = 25_100,
        maxChargeWh = 100_400,
        maxRangeKm = 600,
    )

    @Test
    fun anElectricVehicleIsDeclaredOnlyWithABatteryReading() {
        val enabled = identification(vehicleStatusEnabled = true)

        // Switch on, but no provider at all.
        assertFalse(enabled.withVehicleStatusFrom(null).vehicleStatusEnabled)
        // ADB off, not approved, or a car without these properties: the provider has no reading.
        assertFalse(enabled.withVehicleStatusFrom { null }.vehicleStatusEnabled)
        assertTrue(enabled.withVehicleStatusFrom { reading }.vehicleStatusEnabled)
        // Switch off: never declared, whatever the provider has.
        assertFalse(identification(vehicleStatusEnabled = false).withVehicleStatusFrom { reading }.vehicleStatusEnabled)
    }

    @Test
    fun withoutAReadingNoVehicleComponentsOrMessagesAreSent() {
        val parameters = parameters(identification(vehicleStatusEnabled = true).withVehicleStatusFrom { null })

        assertNull(parameters.first(20))
        assertNull(parameters.first(21))
        assertFalse(Iap2VehicleStatus.VEHICLE_STATUS_UPDATE in u16Values(parameters.first(6)!!.payload))
        assertFalse(Iap2VehicleStatus.START_VEHICLE_STATUS_UPDATES in u16Values(parameters.first(7)!!.payload))
    }

    @Test
    fun declaresTheChosenChargingConnectors() {
        assertEquals(listOf(1, 4), connectors(EvChargingConnectors.CCS2_TYPE2))
        assertEquals(listOf(5, 6), connectors(EvChargingConnectors.GB_T))
        assertEquals(listOf(0, 2), connectors(EvChargingConnectors.CCS1_J1772))
    }

    @Test
    fun unknownEnergyOmitsWattHoursButPreservesSocRangeAndCharging() {
        val status = reading.copy(batteryPercent = 51.0, rangeKm = 36, currentChargeWh = null, maxChargeWh = null, charging = true)
        val parameters = Iap2ParameterList.parse(Iap2VehicleStatus.update(status).payload)
        for (id in 21..23) assertNull(parameters.first(id))
        assertEquals(51_000, ByteBuffer.wrap(parameters.first(24)!!.payload).int)
        assertEquals(36, ByteBuffer.wrap(parameters.first(11)!!.payload).short.toInt())
        assertEquals(1, parameters.first(25)!!.payload.single().toInt())
    }

    @Test
    fun knownEnergyStillUsesWattHours() {
        val parameters = Iap2ParameterList.parse(Iap2VehicleStatus.update(reading).payload)
        assertEquals(0, ByteBuffer.wrap(parameters.first(21)!!.payload).int)
        assertEquals(25_100, ByteBuffer.wrap(parameters.first(22)!!.payload).int)
        assertEquals(100_400, ByteBuffer.wrap(parameters.first(23)!!.payload).int)
        assertEquals(25_000, ByteBuffer.wrap(parameters.first(24)!!.payload).int)
    }

    private fun connectors(choice: EvChargingConnectors): List<Int> {
        val config = identification(vehicleStatusEnabled = true).copy(chargingConnectors = choice)
        val vehicle = Iap2ParameterList.parse(parameters(config).first(20)!!.payload)
        return vehicle.all(11).map { it.payload.single().toInt() }
    }

    private fun identification(vehicleStatusEnabled: Boolean) = Iap2IdentificationConfig(
        name = "xcertplay",
        modelIdentifier = "xcertplay",
        manufacturer = "xcertplay",
        serialNumber = "xcertplay",
        firmwareVersion = "1.0.0",
        hardwareVersion = "1.0",
        carPlayUsbInterfaceNumber = 3,
        vehicleStatusEnabled = vehicleStatusEnabled,
    )

    private fun parameters(config: Iap2IdentificationConfig) =
        Iap2ParameterList.parse(Iap2IdentificationClient.identificationInformation(config).payload)

    private fun u16Values(bytes: ByteArray): List<Int> =
        List(bytes.size / 2) { index ->
            ((bytes[index * 2].toInt() and 0xff) shl 8) or (bytes[index * 2 + 1].toInt() and 0xff)
        }
}
