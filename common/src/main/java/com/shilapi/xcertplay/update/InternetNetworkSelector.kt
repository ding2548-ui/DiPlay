package com.shilapi.xcertplay.update

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** Finds an internet route without changing the process-wide network used by CarPlay. */
internal object InternetNetworkSelector {
    fun select(connectivity: ConnectivityManager): Network? {
        val active = connectivity.activeNetwork
        val candidates = buildList {
            if (active != null) add(active)
            connectivity.allNetworks.forEach { if (it != active) add(it) }
        }
        return candidates.firstOrNull { network ->
            connectivity.getNetworkCapabilities(network)?.let(::isValidatedInternet) == true
        }
    }

    fun isValidatedInternet(capabilities: NetworkCapabilities): Boolean =
        isValidatedInternet(
            hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )

    internal fun isValidatedInternet(hasInternet: Boolean, validated: Boolean): Boolean =
        hasInternet && validated
}
