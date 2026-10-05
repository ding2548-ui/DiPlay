package com.shilapi.xcertplay.network

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HotspotAddressPolicyTest {
    private fun address(text: String): InetAddress = InetAddress.getByName(text)

    @Test
    fun theReachableIpv4AddressWinsOverTheLinkLocalOneTheFrameworkListsFirst() {
        val linkLocal = address("fe80::e0dc:ffff:fe5b:c6d1")
        val ipv4 = address("192.168.49.1")

        assertEquals(ipv4, HotspotAddressPolicy.select(listOf(linkLocal, ipv4)))
    }

    @Test
    fun addressesAnIphoneCannotReachAreIgnored() {
        val ipv4 = address("192.168.49.1")

        assertEquals(
            ipv4,
            HotspotAddressPolicy.select(
                listOf(address("127.0.0.1"), address("0.0.0.0"), address("169.254.11.4"), ipv4),
            ),
        )
    }

    @Test
    fun linkLocalIpv6IsStillBetterThanNothingOnAFrameworkWithoutIpv4() {
        val linkLocal = address("fe80::e0dc:ffff:fe5b:c6d1")

        assertEquals(linkLocal, HotspotAddressPolicy.select(listOf(linkLocal)))
    }

    @Test
    fun theFirstIpv4AddressIsKeptWhenTheInterfaceHasSeveral() {
        val first = address("192.168.49.1")
        val second = address("192.168.49.2")

        assertEquals(first, HotspotAddressPolicy.select(listOf(first, second)))
    }

    @Test
    fun anEmptyInterfaceHasNoAddress() {
        assertNull(HotspotAddressPolicy.select(emptyList()))
    }
}
