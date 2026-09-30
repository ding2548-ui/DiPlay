package com.shilapi.xcertplay.network

import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.Locale

/**
 * Some head units mask hardwareAddress but assign their P2P or hotspot interface a MAC-derived
 * IPv6 link-local address. Reverse the modified EUI-64 encoding (RFC 4291 Appendix A).
 * Opaque/privacy IPv6 identifiers cannot be decoded and must not become a BSSID.
 */
internal object HotspotInterfaceBssid {
    fun read(interfaceName: String?): String? {
        // The caller must pass the active local GO/AP interface, never a station or peer.
        if (interfaceName.isNullOrBlank()) return null
        return try {
            val network = NetworkInterface.getByName(interfaceName) ?: return null
            if (!network.isUp || network.isLoopback) return null
            val addresses = network.inetAddresses.toList()
                .filterIsInstance<Inet6Address>()
                .map { it.address }
            fromAddresses(addresses)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Why [read] found nothing, for the log a reporter sends: no interface name, no IPv6
     * link-local at all, or only opaque (RFC 7217 / privacy) identifiers that carry no MAC.
     */
    fun describe(interfaceName: String?): String {
        if (interfaceName.isNullOrBlank()) return "the group interface could not be named"
        return try {
            val network = NetworkInterface.getByName(interfaceName)
                ?: return "interface $interfaceName is gone"
            val linkLocal = network.inetAddresses.toList()
                .filterIsInstance<Inet6Address>()
                .filter { it.isLinkLocalAddress }
            describeLinkLocal(interfaceName, linkLocal.map { it.address })
        } catch (e: Exception) {
            "interface $interfaceName could not be read (${e.message})"
        }
    }

    fun describeLinkLocal(interfaceName: String, linkLocal: List<ByteArray>): String = when {
        linkLocal.isEmpty() -> "$interfaceName has no IPv6 link-local address"
        linkLocal.mapNotNull(::decode).distinct().size > 1 ->
            "$interfaceName has conflicting MAC-derived IPv6 link-local addresses"
        else -> "$interfaceName has only opaque IPv6 link-local identifiers (no EUI-64 ff:fe), " +
            "so its MAC cannot be derived"
    }

    fun fromAddresses(addresses: List<ByteArray>): String? =
        addresses.mapNotNull(::decode).distinct().singleOrNull()

    fun decode(address: ByteArray): String? {
        if (address.size != 16) return null
        val bytes = address.map { it.toInt() and 0xff }
        // Accept only fe80::/64, not a global address or arbitrary link-local subnet bits.
        if (bytes[0] != 0xfe || bytes[1] != 0x80 || (2..7).any { bytes[it] != 0 }) return null
        if (bytes[11] != 0xff || bytes[12] != 0xfe) return null
        val mac = listOf(bytes[8] xor 0x02, bytes[9], bytes[10], bytes[13], bytes[14], bytes[15])
        if ((mac[0] and 1) != 0 || mac.all { it == 0 }) return null
        if (mac == listOf(2, 0, 0, 0, 0, 0)) return null
        return mac.joinToString(":") { String.format(Locale.ROOT, "%02X", it) }
    }
}
