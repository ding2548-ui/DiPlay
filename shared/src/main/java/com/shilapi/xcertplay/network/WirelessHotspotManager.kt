package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.Closeable
import java.net.InetAddress

enum class WirelessHotspotBackend(val label: String) {
    WIFI_P2P("Wi-Fi P2P"),
    LOCAL_ONLY_HOTSPOT("LocalOnlyHotspot"),
    MANUAL_HOTSPOT("Manual hotspot"),
}

/** The live Wi-Fi credentials and interface details for one wireless CarPlay hotspot. */
class WirelessHotspotInfo(
    val ssid: String,
    val passphrase: String,
    val security: Iap2WirelessSecurity,
    val channel: Int,
    val frequencyMHz: Int?,
    val bssid: String?,
    val interfaceName: String?,
    val hostAddress: InetAddress?,
    val bandLabel: String,
    val backend: WirelessHotspotBackend,
) {
    override fun toString(): String =
        "WirelessHotspotInfo(backend=${backend.label}, ssid='$ssid', " +
            "passphrase=<redacted>, security=$security, channel=$channel, " +
            "frequencyMHz=$frequencyMHz, bssid='$bssid', interfaceName=$interfaceName, " +
            "hostAddress=$hostAddress, bandLabel='$bandLabel')"
}

/** Owns one Android Wi-Fi group and all resources needed to keep it alive. */
interface WirelessHotspotManager : Closeable {
    /**
     * Starts a hotspot and waits up to [timeoutMillis] for its live configuration and AP
     * interface. Implementations must not be called on the main thread.
     */
    fun start(timeoutMillis: Long): WirelessHotspotInfo

    /** The authenticated wireless session has rendered CarPlay; AP creation alone is insufficient. */
    fun onCarPlayConfirmed() {}

    /** Counts reported by the framework, when available; never contains station identities. */
    fun connectionDiagnosticSnapshot(): String = "association=not_exposed"
}

/**
 * Converts IEEE 802.11 frequency in MHz to its channel number.
 *
 * Android's [android.net.wifi.p2p.WifiP2pGroup.getFrequency] reports MHz, while the iAP2
 * wireless CarPlay payload expects a channel number.
 */
internal fun wifiFrequencyMhzToChannel(frequencyMHz: Int): Int? = when {
    frequencyMHz in 2412..2472 && (frequencyMHz - 2407) % 5 == 0 ->
        (frequencyMHz - 2407) / 5
    frequencyMHz == 2484 -> 14
    frequencyMHz in 5160..5895 && (frequencyMHz - 5000) % 5 == 0 ->
        (frequencyMHz - 5000) / 5
    frequencyMHz in 5955..7115 && (frequencyMHz - 5950) % 5 == 0 ->
        (frequencyMHz - 5950) / 5
    else -> null
}

/** Converts an IEEE 802.11 channel number to MHz when its band is known. */
internal fun wifiChannelToFrequencyMhz(channel: Int, band: Int? = null): Int? = when {
    channel == 0 -> null
    band == 1 && channel in 1..13 -> 2407 + channel * 5
    band == 1 && channel == 14 -> 2484
    band == 2 && channel in 32..177 -> 5000 + channel * 5
    band == 4 && channel in 1..233 -> 5950 + channel * 5
    else -> null
}

/** Maps Android's [android.net.wifi.SoftApConfiguration] band constants to a user-facing label. */
internal fun wifiBandLabel(band: Int?): String? = when (band) {
    1 -> "2.4 GHz"
    2 -> "5 GHz"
    3 -> "2.4 / 5 GHz (auto)"
    4 -> "6 GHz"
    else -> null
}

/** WifiConfiguration uses 0/1/-1; SoftApConfiguration uses a 1/2/4 bit mask. */
internal fun legacyHotspotBandToSoftApBand(band: Int?): Int? = when (band) {
    0 -> 1
    1 -> 2
    -1 -> 3
    else -> null
}

/**
 * Returns only a channel observed from Android. An unknown channel must stay unknown instead of
 * echoing the user's desired channel back to the iPhone in 0x5703.
 */
internal fun observedManualHotspotChannel(
    apChannel: Int,
    connectionFrequencyMHz: Int?,
    scanFrequencyMHz: Int?,
    apFrequencyMHz: Int?,
): Int {
    if (apChannel > 0) return apChannel
    connectionFrequencyMHz?.let(::wifiFrequencyMhzToChannel)?.let { return it }
    scanFrequencyMHz?.let(::wifiFrequencyMhzToChannel)?.let { return it }
    apFrequencyMHz?.let(::wifiFrequencyMhzToChannel)?.let { return it }
    return 0
}
