package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test

class HotspotJoinFirmwareTest {
    @Test fun vendorAndWifiModuleChangesInvalidateOldParcelsEvenWithTheSameAndroidBuild() {
        val properties = mapOf("framework" to "build-a", "vendor" to "vendor-a", "api" to "33")
        val modules = mapOf("framework-wifi.jar" to "synthetic-module-a")
        val original = HotspotJoinFirmware.identity(properties, modules)
        assertNotNull(original)
        assertEquals(original, HotspotJoinFirmware.identity(properties.toSortedMap(), modules))
        assertNotEquals(original, HotspotJoinFirmware.identity(properties + ("vendor" to "vendor-b"), modules))
        assertNotEquals(original, HotspotJoinFirmware.identity(properties, modules + ("framework-wifi.jar" to "module-b")))
        assertNull(HotspotJoinFirmware.identity(properties, emptyMap()))
    }
}
