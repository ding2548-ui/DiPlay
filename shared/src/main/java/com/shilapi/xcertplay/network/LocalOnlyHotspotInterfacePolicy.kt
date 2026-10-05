package com.shilapi.xcertplay.network

/** onStarted can precede AP address assignment. Never fill that gap with another network. */
internal object LocalOnlyHotspotInterfacePolicy {
    data class Candidate(val name: String, val ipv4: Set<String>, val bssid: String?)

    fun select(
        candidates: List<Candidate>,
        previousAddresses: Set<String>,
        upstreamInterfaces: Set<String>,
        configuredBssid: String?,
    ): Candidate? {
        val possible = candidates.filter {
            it.name !in upstreamInterfaces && it.ipv4.isNotEmpty() &&
                it.name.matches(Regex("(?:ap|wlan|swlan|softap)[0-9]+"))
        }
        if (configuredBssid != null) {
            return possible.singleOrNull { it.bssid.equals(configuredBssid, ignoreCase = true) }
        }
        return possible.filter { candidate ->
            candidate.ipv4.any { it !in previousAddresses }
        }.singleOrNull()
    }
}
