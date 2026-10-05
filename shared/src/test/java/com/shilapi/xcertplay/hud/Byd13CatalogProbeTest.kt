package com.shilapi.xcertplay.hud

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

class Byd13CatalogProbeTest {
    @Test fun parsesController13FirmwareSymbolsAndDevices() {
        val catalog = BydFirmwareCatalog.parse(
            """
            ${Byd13CatalogProbeMain.HEADER}
            BYDAutoConstants.BYDAUTO_DEVICE_SPEED=1013
            BYDAutoConstants.BYDAUTO_DEVICE_GEARBOX=1011
            BYDAutoConstants.BYDAUTO_DEVICE_STATISTIC=1014
            BYDAutoConstants.BYDAUTO_DEVICE_POWER=1005
            BYDAutoConstants.BYDAUTO_DEVICE_CHARGING=1009
            Speed.SPEED_AUTO_SPEED=-1176502256
            Gearbox.GEARBOX_AUTO_MODE_TYPE=555745336
            Statistic.STATISTIC_ELEC_PERCENTAGE=1033543720
            Statistic.STATISTIC_ELEC_DRIVING_RANGE=1033543766
            Power.POWER_BATTERY_REMAIN_ELECTRICITY=1320165408
            Charging.CHARGING_BATTERRY_DEVICE_STATE=1231032336
            """.trimIndent()
        )!!

        assertEquals(-1176502256, catalog.fid(BydVehicleField.SPEED))
        assertEquals(1033543720, catalog.fid(BydVehicleField.SOC))
        assertEquals(1231032336, catalog.fid(BydVehicleField.BMS_STATE))
        assertEquals(1013, catalog.device(BydVehicleField.SPEED))
        assertEquals(1009, catalog.device(BydVehicleField.BMS_STATE))
    }

    @Test fun rejectsOutputWithoutProbeHeader() {
        assertEquals(null, BydFirmwareCatalog.parse("Speed.SPEED_AUTO_SPEED=-1176502256"))
    }

    @Test fun firmwareResolvedController13FieldsAreLiveValidated() {
        val catalogOutput = """
            ${Byd13CatalogProbeMain.HEADER}
            BYDAutoConstants.BYDAUTO_DEVICE_SPEED=1013
            BYDAutoConstants.BYDAUTO_DEVICE_GEARBOX=1011
            BYDAutoConstants.BYDAUTO_DEVICE_STATISTIC=1014
            BYDAutoConstants.BYDAUTO_DEVICE_POWER=1005
            BYDAutoConstants.BYDAUTO_DEVICE_CHARGING=1009
            Speed.SPEED_AUTO_SPEED=-1176502256
            Gearbox.GEARBOX_AUTO_MODE_TYPE=555745336
            Statistic.STATISTIC_ELEC_PERCENTAGE=1033543720
            Statistic.STATISTIC_ELEC_DRIVING_RANGE=1033543766
            Power.POWER_BATTERY_REMAIN_ELECTRICITY=1320165408
            Charging.CHARGING_BATTERRY_DEVICE_STATE=1231032336
            """.trimIndent()
        val replies = mapOf(
            -1176502256 to "42100000", // 36 km/h
            555745336 to "00000001", // P
            1033543720 to "41c80000", // 25 %
            1033543766 to "00000096", // 150 km
            1320165408 to "41c8cccd", // 25.1 kWh
            1231032336 to "00000001", // charging
        )

        val batch = BydVehicleCapabilityProbe.probeFields(catalogOutput, 29, { command ->
            replies[command.substringAfterLast(' ').toInt()]?.let {
                "Result: Parcel(00000000 $it   '........')"
            }
        }, firmwareKey = "test")

        assertEquals(6, batch.transportReplies)
        assertEquals(6, batch.repliedFields)
        assertTrue(batch.complete)
        assertTrue(batch.capabilities.motionSupported)
        assertTrue(batch.capabilities.batterySupported)
        assertTrue(batch.capabilities.chargingSupported)
        assertEquals(36.0, batch.capabilities.result(BydVehicleField.SPEED).value!!, 0.001)
        assertEquals(BydFieldSource.FIRMWARE, batch.capabilities.result(BydVehicleField.SOC).address!!.source)
    }

    @Test fun aPartialTransportResponseIsNotACompleteProbe() {
        val batch = BydVehicleCapabilityProbe.probeFields("catalog unavailable", 29, { command ->
            if (command.contains(" i32 1013 ")) "Result: Parcel(00000000 00000000 '........')" else null
        }, firmwareKey = "test")

        assertFalse(batch.complete)
        assertTrue(batch.repliedFields < BydVehicleField.entries.size)
    }

    @Test fun aNullCatalogOutputMakesAnOtherwiseCompleteFallbackProbeIncomplete() {
        var reads = 0
        val batch = BydVehicleCapabilityProbe.probeFields(
            null,
            29,
            { reads++; "Result: Parcel(00000000 00000001 '........')" },
            firmwareKey = "test",
        )

        assertFalse(batch.complete)
        assertTrue(batch.failedReads > 0)
        // Already incomplete, so no field is read.
        assertEquals(0, reads)
    }

    @Test fun aNullPreferredReadCannotBeHiddenByADecodableFallback() {
        val catalogOutput = """
            ${Byd13CatalogProbeMain.HEADER}
            BYDAutoConstants.BYDAUTO_DEVICE_SPEED=1013
            Speed.SPEED_AUTO_SPEED=-42
        """.trimIndent()
        var reads = 0
        val batch = BydVehicleCapabilityProbe.probeFields(
            catalogOutput,
            29,
            { command ->
                reads++
                if (command.endsWith(" i32 -42")) null
                else "Result: Parcel(00000000 00000001 '........')"
            },
            firmwareKey = "test",
        )

        assertFalse(batch.complete)
        assertEquals(1, batch.failedReads)
        // The probe stops at the failed read instead of waiting on the fallback and later fields.
        assertEquals(1, reads)
    }

    @Test fun nonNullCatalogOutputWithoutTheHeaderAllowsFallbacks() {
        val batch = BydVehicleCapabilityProbe.probeFields(
            "catalog unavailable",
            29,
            { "Result: Parcel(00000000 00000001 '........')" },
            firmwareKey = "test",
        )

        assertTrue(batch.complete)
        assertFalse(batch.capabilities.catalogAvailable)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class BydVehicleFieldStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        context.getSharedPreferences("diplay_byd_vehicle_fields", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("diplay_byd_outputs", Context.MODE_PRIVATE).edit().clear().commit()
        BydVehicleFieldStore.clearMemoryForTests()
    }

    @After fun tearDown() {
        setUp()
    }

    @Test fun legacyFeaturesStayInactiveUntilAProbeIsCached() {
        BydOutputSettings.setLegacyVehicleProbe(context, true)
        BydOutputSettings.setBatteryToIphone(context, true)
        BydOutputSettings.setWheelSpeedToIphone(context, true)
        BydOutputSettings.setVideoWhileParked(context, true)

        assertFalse(BydOutputSettings.batteryToIphoneActive(context))
        assertFalse(BydOutputSettings.wheelSpeedToIphoneActive(context))
        assertFalse(BydOutputSettings.videoWhileParkedActive(context))

        BydVehicleFieldStore.save(context, supportedCapabilities())
        // Model a killed/restarted app process: the persisted probe, not the singleton, must win.
        BydVehicleFieldStore.clearMemoryForTests()

        assertTrue(BydOutputSettings.batteryToIphoneActive(context))
        assertTrue(BydOutputSettings.wheelSpeedToIphoneActive(context))
        assertTrue(BydOutputSettings.videoWhileParkedActive(context))
        assertEquals(-1176502256, BydVehicleFieldStore.address(context, BydVehicleField.SPEED).fid)
        assertEquals(BydFieldSource.FIRMWARE, BydVehicleFieldStore.address(context, BydVehicleField.SPEED).source)
    }

    @Test fun socAndRangeRemainUsableWhenController13HasNoRemainingKwhField() {
        BydVehicleFieldStore.save(context, supportedCapabilities(without = setOf(BydVehicleField.REMAINING_KWH)))
        val replies = mapOf(
            1033543720 to "41c80000", // 25 %
            1033543766 to "00000096", // 150 km
            1231032336 to "00000001", // charging
        )
        val reading = BydBattery.read(context) { command ->
            replies[command.substringAfterLast(' ').toInt()]?.let {
                "Result: Parcel(00000000 $it   '........')"
            }
        }!!

        assertNull(reading.remainingKwh)
        assertTrue(reading.charging)
        BydOutputSettings.setBatteryToIphone(context, true)
        assertTrue(BydOutputSettings.batteryToIphoneActive(context))
    }

    @Test fun aConfirmedEnergyFieldMustRemainReadable() {
        BydVehicleFieldStore.save(context, supportedCapabilities())
        val replies = mapOf(
            1033543720 to "41c80000",
            1033543766 to "00000096",
            1231032336 to "00000001",
        )

        assertNull(BydBattery.read(context) { command ->
            replies[command.substringAfterLast(' ').toInt()]?.let {
                "Result: Parcel(00000000 $it   '........')"
            }
        })
    }

    @Test fun freshInstallUsesTheDefaultDiLink5Commands() {
        assertFalse(BydOutputSettings.legacyVehicleProbe(context))
        assertEquals(BydWheelSpeed.SPEED, BydWheelSpeed.speedCommand(context))
        assertEquals(BydWheelSpeed.GEAR, BydWheelSpeed.gearCommand(context))

        BydOutputSettings.setBatteryToIphone(context, true)
        BydOutputSettings.setWheelSpeedToIphone(context, true)
        BydOutputSettings.setVideoWhileParked(context, true)
        assertTrue(BydOutputSettings.batteryToIphoneActive(context))
        assertTrue(BydOutputSettings.wheelSpeedToIphoneActive(context))
        assertTrue(BydOutputSettings.videoWhileParkedActive(context))
    }

    @Test fun savedProbeMigratesToLegacyModeButCanSwitchBackWithoutDeletingIt() {
        BydVehicleFieldStore.save(context, supportedCapabilities())

        assertTrue(BydOutputSettings.legacyVehicleProbe(context))
        assertEquals(-1176502256, BydWheelSpeed.speedCommand(context).substringAfterLast(' ').toInt())

        BydOutputSettings.setLegacyVehicleProbe(context, false)

        assertFalse(BydOutputSettings.legacyVehicleProbe(context))
        assertEquals(BydWheelSpeed.SPEED, BydWheelSpeed.speedCommand(context))
        assertTrue(BydVehicleFieldStore.load(context)!!.motionSupported)
    }

    @Test fun aSavedProbeSurvivesFirmwareMetadataChanges() {
        BydVehicleFieldStore.save(context, supportedCapabilities())
        context.getSharedPreferences("diplay_byd_vehicle_fields", Context.MODE_PRIVATE)
            .edit().putString("firmware", "previous-controller-13-build").commit()
        BydVehicleFieldStore.clearMemoryForTests()

        val loaded = BydVehicleFieldStore.load(context)!!
        assertEquals("previous-controller-13-build", loaded.firmwareKey)
        assertTrue(loaded.motionSupported)
        assertTrue(loaded.batterySupported)
    }

    @Test fun hiddenCapabilitiesDoNotEraseTheirSavedSwitches() {
        BydOutputSettings.setWheelSpeedToIphone(context, true)
        BydVehicleFieldStore.save(
            context,
            supportedCapabilities(without = setOf(BydVehicleField.SPEED, BydVehicleField.GEAR)),
        )

        assertTrue(BydOutputSettings.wheelSpeedToIphone(context))
        assertFalse(BydOutputSettings.wheelSpeedToIphoneActive(context))

        BydVehicleFieldStore.save(context, supportedCapabilities())
        assertTrue(BydOutputSettings.wheelSpeedToIphone(context))
        assertTrue(BydOutputSettings.wheelSpeedToIphoneActive(context))
    }

    @Test fun automaticReplacementCannotDropPreviouslyConfirmedFields() {
        BydVehicleFieldStore.save(context, supportedCapabilities())
        val expected = BydVehicleFieldStore.load(context)
        val candidate = supportedCapabilities(without = setOf(BydVehicleField.GEAR))

        val result = BydVehicleFieldStore.replaceAutomatically(context, expected, candidate)

        assertEquals(setOf(BydVehicleField.GEAR), result.lostFields)
        assertFalse(result.saved)
        assertTrue(BydVehicleFieldStore.load(context)!!.gearSupported)
    }

    @Test fun anExplicitReplacementMayDropFieldsButNotOverwriteANewerSnapshot() {
        BydVehicleFieldStore.save(context, supportedCapabilities())
        val expected = BydVehicleFieldStore.load(context)!!
        val candidate = supportedCapabilities(without = setOf(BydVehicleField.GEAR))
        val newer = supportedCapabilities().copy(detectedAtMillis = expected.detectedAtMillis + 1)
        BydVehicleFieldStore.save(context, newer)

        assertFalse(BydVehicleFieldStore.replaceAnyway(context, expected, candidate))
        assertEquals(newer, BydVehicleFieldStore.load(context))

        assertTrue(BydVehicleFieldStore.replaceAnyway(context, newer, candidate))
        assertFalse(BydVehicleFieldStore.load(context)!!.gearSupported)
    }

    @Test fun lateAutomaticReplacementCannotOverwriteANewerSnapshot() {
        BydVehicleFieldStore.save(context, supportedCapabilities())
        val expected = BydVehicleFieldStore.load(context)!!
        val newer = supportedCapabilities().copy(detectedAtMillis = expected.detectedAtMillis + 1)
        BydVehicleFieldStore.save(context, newer)

        val result = BydVehicleFieldStore.replaceAutomatically(
            context,
            expected,
            supportedCapabilities().copy(detectedAtMillis = expected.detectedAtMillis + 2),
        )

        assertTrue(result.snapshotChanged)
        assertFalse(result.saved)
        assertEquals(newer.detectedAtMillis, BydVehicleFieldStore.load(context)!!.detectedAtMillis)
    }

    @Test fun incompleteNullCatalogProbeCannotReplaceTheSavedSnapshot() {
        val saved = supportedCapabilities()
        BydVehicleFieldStore.save(context, saved)
        val batch = BydVehicleCapabilityProbe.probeFields(
            null,
            29,
            { "Result: Parcel(00000000 00000001 '........')" },
        )

        val outcome = BydVehicleCapabilityProbe.finishProbe(
            context,
            BydAdbAccess.State.READY,
            batch,
            persist = true,
        )

        assertNull(outcome.capabilities)
        assertEquals(saved.detectedAtMillis, BydVehicleFieldStore.load(context)!!.detectedAtMillis)
    }

    @Test fun incompletePreferredReadCannotReplaceTheSavedSnapshot() {
        val saved = supportedCapabilities()
        BydVehicleFieldStore.save(context, saved)
        val catalogOutput = """
            ${Byd13CatalogProbeMain.HEADER}
            BYDAutoConstants.BYDAUTO_DEVICE_SPEED=1013
            Speed.SPEED_AUTO_SPEED=-42
        """.trimIndent()
        val batch = BydVehicleCapabilityProbe.probeFields(
            catalogOutput,
            29,
            { command ->
                if (command.endsWith(" i32 -42")) null
                else "Result: Parcel(00000000 00000001 '........')"
            },
        )

        val outcome = BydVehicleCapabilityProbe.finishProbe(
            context,
            BydAdbAccess.State.READY,
            batch,
            persist = true,
        )

        assertNull(outcome.capabilities)
        assertEquals(saved.detectedAtMillis, BydVehicleFieldStore.load(context)!!.detectedAtMillis)
    }

    private fun supportedCapabilities(without: Set<BydVehicleField> = emptySet()): BydVehicleCapabilities {
        val fids = mapOf(
            BydVehicleField.SPEED to -1176502256,
            BydVehicleField.GEAR to 555745336,
            BydVehicleField.SOC to 1033543720,
            BydVehicleField.RANGE to 1033543766,
            BydVehicleField.REMAINING_KWH to 1320165408,
            BydVehicleField.BMS_STATE to 1231032336,
        )
        return BydVehicleCapabilities(
            fields = BydVehicleField.entries.associateWith { field ->
                if (field in without) BydFieldProbeResult(field, supported = false, address = null)
                else BydFieldProbeResult(
                        field,
                        supported = true,
                        address = BydReadAddress(field.defaultDevice, fids.getValue(field), field.transaction, BydFieldSource.FIRMWARE),
                    )
            },
            catalogAvailable = true,
            firmwareKey = BydVehicleFieldStore.firmwareKey(),
        )
    }
}
