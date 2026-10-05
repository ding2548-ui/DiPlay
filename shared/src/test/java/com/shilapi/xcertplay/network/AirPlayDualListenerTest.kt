package com.shilapi.xcertplay.network

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

class AirPlayDualListenerTest {
    private val addresses = listOf(InetAddress.getByName("::1"), InetAddress.getByName("127.0.0.1"))

    @Test fun acceptsBothFamiliesOnTheSamePort() {
        val servers = AirPlayPortSelector.bindAll(addresses, 0)
        try {
            val port = servers.first().localPort
            assertEquals(listOf(port, port), servers.map { it.localPort })
            servers.forEachIndexed { index, server ->
                server.soTimeout = 1000
                Socket(addresses[index], port).use { client ->
                    server.accept().use { peer ->
                        assertEquals(addresses[index], peer.inetAddress)
                        assertTrue(client.isConnected)
                    }
                }
            }
        } finally { servers.forEach { it.close() } }
    }

    @Test fun collisionOnSecondFamilyClosesPartialBindAndFallsBackTogether() {
        ServerSocket(0, 50, addresses[1]).use { busy ->
            val servers = AirPlayPortSelector.bindAll(addresses, busy.localPort, emptyList())
            try {
                assertEquals(1, servers.map { it.localPort }.distinct().size)
                assertNotEquals(busy.localPort, servers.first().localPort)
                ServerSocket(busy.localPort, 50, addresses[0]).use { assertTrue(it.isBound) }
            } finally { servers.forEach { it.close() } }
        }
    }

    @Test fun failedFallbackNotificationReleasesBothListeners() {
        ServerSocket(0, 50, addresses[1]).use { busy ->
            var port = 0
            assertThrows(IllegalStateException::class.java) {
                AirPlayPortSelector.bindAll(addresses, busy.localPort, emptyList()) { _, bound ->
                    port = bound
                    throw IllegalStateException("notification failed")
                }
            }
            assertTrue(port > 0)
            addresses.forEach { address -> ServerSocket(port, 50, address).use { assertTrue(it.isBound) } }
        }
    }
}
