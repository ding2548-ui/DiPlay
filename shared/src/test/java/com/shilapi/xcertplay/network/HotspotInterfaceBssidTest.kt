package com.shilapi.xcertplay.network

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotInterfaceBssidTest {
    @Test fun decodesBydBuiltInHotspotAddress() {
        assertEquals("4E:B1:C7:94:48:3F", HotspotInterfaceBssid.decode(bytes("fe80::4cb1:c7ff:fe94:483f")))
    }

    private fun bytes(ip: String) = InetAddress.getByName(ip).address

    @Test fun decodesLocalAndUniversalMacs() {
        assertEquals("DA:12:34:56:78:9A", HotspotInterfaceBssid.decode(bytes("fe80::d812:34ff:fe56:789a")))
        assertEquals("00:12:34:56:78:9A", HotspotInterfaceBssid.decode(bytes("fe80::212:34ff:fe56:789a")))
    }

    @Test fun rejectsOpaqueAndNonLinkLocalAddresses() {
        for (ip in listOf("fe80::1234:5678:abcd:ef01", "2001:db8::d812:34ff:fe56:789a",
            "fe80:1::d812:34ff:fe56:789a", "::1", "192.168.49.1")) {
            assertNull(ip, HotspotInterfaceBssid.decode(bytes(ip)))
        }
        assertNull(HotspotInterfaceBssid.decode(ByteArray(0)))
    }

    @Test fun rejectsMaskedZeroAndMulticastMacs() {
        for (ip in listOf("fe80::ff:fe00:0", "fe80::200:ff:fe00:0", "fe80::d912:34ff:fe56:789a",
            "fe80::fdff:ffff:feff:ffff")) {
            assertNull(ip, HotspotInterfaceBssid.decode(bytes(ip)))
        }
    }

    @Test fun refusesConflictingCandidatesAndIgnoresUnrelatedAddresses() {
        val first = bytes("fe80::d812:34ff:fe56:789a")
        val second = bytes("fe80::d812:34ff:fe56:789b")
        assertNull(HotspotInterfaceBssid.fromAddresses(listOf(first, second)))
        assertNull(HotspotInterfaceBssid.fromAddresses(emptyList()))
        assertEquals("DA:12:34:56:78:9A", HotspotInterfaceBssid.fromAddresses(
            listOf(first, first, bytes("fe80::1234:5678:abcd:ef01"))))
    }

    @Test fun requiresAnExplicitInterface() {
        assertNull(HotspotInterfaceBssid.read(null))
        assertNull(HotspotInterfaceBssid.read(""))
        assertNull(HotspotInterfaceBssid.read("does-not-exist"))
    }

    // Captured from a HiBy R4 (Android 12, P2P MAC randomization) hosting the group: p2p0
    // reported link/ether 4a:4d:90:cb:ad:5c, and a phone's scan listed the group at that BSSID.
    @Test fun decodesAddressCapturedFromARandomizedP2pGroup() {
        assertEquals("4A:4D:90:CB:AD:5C", HotspotInterfaceBssid.decode(bytes("fe80::484d:90ff:fecb:ad5c")))
    }

    @Test fun describesWhyNothingWasRecovered() {
        assertTrue(HotspotInterfaceBssid.describe(null).contains("could not be named"))
        assertTrue(HotspotInterfaceBssid.describe("does-not-exist").contains("gone"))
        assertTrue(HotspotInterfaceBssid.describeLinkLocal("p2p0", emptyList()).contains("no IPv6 link-local"))
        assertTrue(HotspotInterfaceBssid.describeLinkLocal(
            "p2p0", listOf(bytes("fe80::1234:5678:abcd:ef01"))).contains("opaque"))
        assertTrue(HotspotInterfaceBssid.describeLinkLocal("p2p0", listOf(
            bytes("fe80::d812:34ff:fe56:789a"), bytes("fe80::d812:34ff:fe56:789b"))).contains("conflicting"))
    }
}
