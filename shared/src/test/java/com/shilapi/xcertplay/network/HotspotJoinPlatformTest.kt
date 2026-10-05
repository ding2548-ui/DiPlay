package com.shilapi.xcertplay.network

import android.net.wifi.SoftApConfiguration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class HotspotJoinPlatformTest {
    class WifiService(var config: SoftApConfiguration) {
        var stationFiveGhz = true
        fun getSoftApConfiguration() = config
        fun setSoftApConfiguration(value: SoftApConfiguration, caller: String): Boolean {
            check(caller == "com.android.shell"); config = value; return true
        }
        fun getWifiApEnabledState() = 11
        fun is5GHzBandSupported() = stationFiveGhz
    }
    @Test fun platformCopyAndFullParcelRoundtripPreserveEveryOtherStoredField() {
        val builder = SoftApConfiguration.Builder()
            .setPassphrase("synthetic-password", SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
        builder.javaClass.getMethod("setSsid", String::class.java).invoke(builder, "synthetic-network")
        builder.javaClass.getMethod("setHiddenSsid", Boolean::class.javaPrimitiveType).invoke(builder, true)
        builder.javaClass.getMethod("setMaxNumberOfClients", Int::class.javaPrimitiveType).invoke(builder, 3)
        builder.javaClass.getMethod("setAutoShutdownEnabled", Boolean::class.javaPrimitiveType).invoke(builder, false)
        builder.javaClass.getMethod("setBand", Int::class.javaPrimitiveType).invoke(builder, 2)
        val elementType = Class.forName("android.net.wifi.ScanResult\$InformationElement")
        val other = elementType.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            ByteArray::class.java).newInstance(221, 0, byteArrayOf(0, 0x50, 0xf2.toByte(), 2, 1, 1))
        builder.javaClass.getMethod("setVendorElements", List::class.java).invoke(builder, listOf(other))
        val config = builder.build()
        val port = HotspotJoinPlatform(WifiService(config), 33, "synthetic-firmware") {
            HotspotJoinCapability.Snapshot(true, setOf(36, 44))
        }
        val next = port.repaired(config)!!
        assertNotEquals(config, next)
        val copied = builder.javaClass.getConstructor(SoftApConfiguration::class.java).newInstance(next)
        builder.javaClass.getMethod("setVendorElements", List::class.java).invoke(copied, listOf(other))
        assertEquals(config, copied.build())
        assertEquals(next, port.codec.decode(port.codec.encode(next)))
        assertEquals(next, port.repaired(next))
        assertTrue(port.write(next)); assertEquals(next, port.read())
    }
    @Test fun hotspotCapabilityMustSupportTheStoredBandAndChannelRegardlessOfStationSupport() {
        fun config(channel: Int = 0): SoftApConfiguration {
            val builder = SoftApConfiguration.Builder()
                .setPassphrase("synthetic-password", SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
            builder.javaClass.getMethod("setSsid", String::class.java).invoke(builder, "synthetic-network")
            if (channel == 0) builder.javaClass.getMethod("setBand", Int::class.javaPrimitiveType)
                .invoke(builder, 2)
            else builder.javaClass.getMethod("setChannel", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType).invoke(builder, channel, 2)
            return builder.build()
        }
        val current = config()
        for (capability in listOf(null, HotspotJoinCapability.Snapshot(false, setOf(36)),
            HotspotJoinCapability.Snapshot(true, emptySet()))) {
            // Station 5 GHz support on this fake is true; the AP capability must still refuse.
            val port = HotspotJoinPlatform(WifiService(current), 33, "synthetic-firmware") { capability }
            assertNull(port.repaired(current))
        }
        val explicit = config(44)
        assertNull(HotspotJoinPlatform(WifiService(explicit), 33, "synthetic-firmware") {
            HotspotJoinCapability.Snapshot(true, setOf(36))
        }.repaired(explicit))
        assertNotNull(HotspotJoinPlatform(WifiService(explicit), 33, "synthetic-firmware") {
            HotspotJoinCapability.Snapshot(true, setOf(36, 44))
        }.repaired(explicit))
        assertNotNull(HotspotJoinPlatform(WifiService(current), 33, "synthetic-firmware") {
            HotspotJoinCapability.Snapshot(true, setOf(36))
        }.repaired(current))
        assertNotNull(HotspotJoinPlatform(WifiService(current).apply { stationFiveGhz = false },
            33, "synthetic-firmware") { HotspotJoinCapability.Snapshot(true, setOf(36)) }.repaired(current))
    }
    @Test fun lockRefusesOverlappingOperationsAndReleasesAfterFailure() {
        val directory = Files.createTempDirectory("hotspot-lock-test").toFile()
        try {
            assertEquals("outer", HotspotJoinLock.run(directory) {
                assertNull(HotspotJoinLock.run(directory) { "inner" }); "outer"
            })
            try { HotspotJoinLock.run(directory) { throw IllegalStateException() } }
            catch (_: IllegalStateException) { }
            assertEquals("released", HotspotJoinLock.run(directory) { "released" })
        } finally { directory.deleteRecursively() }
    }
}
