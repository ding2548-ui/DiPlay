package com.shilapi.xcertplay

import com.shilapi.xcertplay.network.WifiP2pGroupManager
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Exercises the production startup wait against changing interface snapshots. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class WifiP2pAddressWaitTest {
    private val ipv6 = Inet6Address.getByAddress(null,
        InetAddress.getByName("fe80::1234").address, 7)
    private val ipv4 = InetAddress.getByName("192.168.49.1")

    @Test fun anAddressRemovedDuringTheWaitCannotBePublished() {
        assertNull(waitWith(listOf(listOf(ipv6), emptyList())))
    }

    @Test fun ipv4AppearingAfterLinkLocalWinsWithoutWaitingForTheDeadline() {
        assertEquals(ipv4, waitWith(listOf(listOf(ipv6), listOf(ipv6, ipv4))))
    }

    @Test fun currentScopedLinkLocalRemainsTheFallbackAtTheDeadline() {
        assertEquals(ipv6, waitWith(listOf(listOf(ipv6))))
    }

    private fun waitWith(snapshots: List<List<InetAddress>>): InetAddress? {
        val manager = WifiP2pGroupManager(RuntimeEnvironment.getApplication())
        val attemptClass = Class.forName(
            "com.shilapi.xcertplay.network.WifiP2pGroupManager\$StartAttempt")
        val attempt = attemptClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        ReflectionHelpers.setField(manager, "startAttempt", attempt)
        val network = mock(NetworkInterface::class.java)
        `when`(network.index).thenReturn(7)
        var read = 0
        `when`(network.inetAddresses).thenAnswer {
            Collections.enumeration(snapshots[minOf(read++, snapshots.lastIndex)])
        }
        try {
            mockStatic(NetworkInterface::class.java).use { factory ->
                factory.`when`<NetworkInterface> { NetworkInterface.getByName("p2p-test") }
                    .thenReturn(network)
                val method = WifiP2pGroupManager::class.java.getDeclaredMethod(
                    "awaitInterfaceAddress", attemptClass, String::class.java, Long::class.javaPrimitiveType)
                method.isAccessible = true
                return method.invoke(manager, attempt, "p2p-test",
                    System.nanoTime() + 200_000_000L) as InetAddress?
            }
        } finally { manager.close() }
    }
}
