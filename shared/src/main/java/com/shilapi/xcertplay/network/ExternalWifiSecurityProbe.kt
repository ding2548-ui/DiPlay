// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager

/**
 * Live snapshot of the car's Wi-Fi station join, used by the settings UI to gate the
 * wireless connect on a password when the joined network is encrypted.
 *
 * `open == null` means the joined network could not be classified (not in the saved
 * configurations on this Android level) — the caller must not block in that case.
 */
object ExternalWifiSecurityProbe {
    data class Result(val ssid: String, val open: Boolean?)

    fun probe(context: Context): Result? {
        val wifi = context.getSystemService(WifiManager::class.java) ?: return null
        val info = runCatching { wifi.connectionInfo }.getOrNull() ?: return null
        val ssid = info?.ssid?.removePrefix("\"")?.removeSuffix("\"")?.trim().orEmpty()
        if (ssid.isEmpty() || ssid == "<unknown ssid>") return null
        val networks = runCatching { wifi.configuredNetworks }.getOrNull().orEmpty()
        val match = networks.firstOrNull { network ->
            network.SSID?.removePrefix("\"")?.removeSuffix("\"") == ssid
        } ?: return Result(ssid, null)
        val open = match.allowedKeyManagement?.get(WifiConfiguration.KeyMgmt.NONE) == true
        return Result(ssid, open)
    }
}
