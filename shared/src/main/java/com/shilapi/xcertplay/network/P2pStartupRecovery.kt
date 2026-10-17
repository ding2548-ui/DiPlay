package com.shilapi.xcertplay.network

import android.net.wifi.p2p.WifiP2pManager
import java.io.IOException

internal enum class P2pCreationMode { ALIGNED_5_GHZ, ALIGNED_2_GHZ, FIXED_5_GHZ, FIXED_2_GHZ, SYSTEM_DEFAULT, PREFERRED_CHANNEL }

internal data class P2pCreationRequest(val mode: P2pCreationMode, val frequencyMHz: Int? = null)

/** [replyMillis]: from createGroup to this answer, when known. */
internal class P2pCreateRejected(val reason: Int, message: String, val replyMillis: Long? = null) : IOException(message)

/**
 * Android 13 can ask the driver before Wi-Fi Direct stops another Wi-Fi role, such as the car
 * hotspot, and answers "no" with BUSY. Every further createGroup then asks again: one prompt per
 * channel in the plan and per retry. The prompt waits for a person; a firmware's own per-channel
 * BUSY does not, and keeps walking the plan.
 */
internal object P2pHotspotConflict {
    const val PROMPT_REPLY_MILLIS = 1_000L

    fun failure(sdk: Int, hotspotOn: Boolean?, rejection: P2pCreateRejected): WirelessStartupException? {
        if (sdk < 33 || hotspotOn != true || rejection.reason != WifiP2pManager.BUSY) return null
        if ((rejection.replyMillis ?: 0L) < PROMPT_REPLY_MILLIS) return null
        return WirelessStartupException(WirelessStartupFailure.WIFI_DIRECT_BESIDE_HOTSPOT,
            "Android was not allowed to turn the car hotspot off for Wi-Fi Direct").apply { initCause(rejection) }
    }
}

/** Raised only for a recognized framework defect before createGroup has been called. */
internal class P2pConfigBuildCompatibilityFailure(cause: NoSuchMethodError) :
    IOException("Wi-Fi P2P custom configuration unavailable on this Android 10 framework", cause)

/** Only a rejection or recognized pre-create defect permits retry; timeout may still create a group. */
internal object P2pStartupRecovery {
    fun rememberedFrequency(frequency: Int): P2pCreationRequest? = when {
        frequency in 2412..2462 && (frequency - 2412) % 5 == 0 ->
            P2pCreationRequest(P2pCreationMode.FIXED_2_GHZ, frequency)
        frequency in listOf(5180, 5200, 5220, 5240, 5745, 5765, 5785, 5805, 5825) ->
            P2pCreationRequest(P2pCreationMode.FIXED_5_GHZ, frequency)
        else -> null
    }

    /**
     * A band-only request still needs channel selection, which some BYD drivers cannot do. Auto skips
     * 5 GHz when [fiveGhz] is false, so a 2.4 GHz-only head unit is not asked for channel 36 first (#582).
     */
    fun plan(stationFrequency: Int?, preferred: P2pCreationRequest? = null,
             preferredChannel: Int = WifiP2pChannels.AUTO,
             bandRequests: Boolean = true,
             fiveGhz: Boolean = true): List<P2pCreationRequest> = buildList {
        WifiP2pChannels.frequencyMhz(preferredChannel)?.let {
            add(P2pCreationRequest(P2pCreationMode.PREFERRED_CHANNEL, it))
            return@buildList
        }
        val aligned24 = stationFrequency != null && stationFrequency in 2412..2462 &&
            (stationFrequency - 2412) % 5 == 0
        val aligned5 = stationFrequency in listOf(5180, 5200, 5220, 5240, 5745, 5765, 5785, 5805, 5825)
        val frequencies = mutableSetOf<Int>()
        fun channel(mode: P2pCreationMode, frequency: Int) {
            if (frequencies.add(frequency)) add(P2pCreationRequest(mode, frequency))
        }
        // A station on 5 GHz shares the radio with the group: another 5 GHz channel makes the radio
        // switch between the two, which on a BYD Tang (station 5200, group 5745) stalled video
        // for 0.6-2 s and starved music. A 2.4 GHz group runs alongside without switching.
        val stationOnFiveGhz = stationFrequency != null && stationFrequency in 5150..5895
        val remembered = preferred?.frequencyMHz?.let(::rememberedFrequency)
            ?.takeIf { fiveGhz || it.frequencyMHz!! !in 5150..5895 }
        val rememberedSwitches = stationOnFiveGhz && remembered != null &&
            remembered.frequencyMHz!! in 5150..5895 && remembered.frequencyMHz != stationFrequency
        // The default API does not pin a channel. A cached success cannot prove it will avoid
        // hopping beside the current 5 GHz station, so keep it behind explicit channels there.
        if (preferred?.mode == P2pCreationMode.SYSTEM_DEFAULT && preferred.frequencyMHz == null &&
            !stationOnFiveGhz) add(preferred)
        else if (remembered != null && !rememberedSwitches) channel(remembered.mode, remembered.frequencyMHz!!)
        if (aligned24) channel(P2pCreationMode.ALIGNED_2_GHZ, requireNotNull(stationFrequency))
        if (aligned5) channel(P2pCreationMode.ALIGNED_5_GHZ, requireNotNull(stationFrequency))
        fun twoGhz() = listOf(2437, 2412, 2462).forEach { channel(P2pCreationMode.FIXED_2_GHZ, it) }
        fun fiveGhzChannels() = listOf(5180, 5745).filter { fiveGhz }.forEach { channel(P2pCreationMode.FIXED_5_GHZ, it) }
        // Keep a shared radio on its existing station channel when possible. Otherwise prefer
        // explicit non-DFS 5 GHz, then channels 6/1/11, except next to a 5 GHz station, where
        // 2.4 GHz comes first (see above). The platform enforces regulatory limits.
        if (aligned24 || stationOnFiveGhz) { twoGhz(); fiveGhzChannels() } else { fiveGhzChannels(); twoGhz() }
        if (rememberedSwitches) channel(remembered!!.mode, remembered.frequencyMHz!!)
        // Some vendors only implement the default-configuration API. Use it last, after every
        // explicit-frequency option has been rejected, never before the 2.4 GHz attempts.
        if (none { it.mode == P2pCreationMode.SYSTEM_DEFAULT }) add(P2pCreationRequest(P2pCreationMode.SYSTEM_DEFAULT))
    }

    /** FreeAuto-style legacy path (API < 29): arm each candidate GO channel through the hidden
     *  channel API, ask the framework for its default configuration, and let the manager's
     *  request callback reject groups that landed elsewhere. The plain system default stays the
     *  last resort (channels=null). */
    fun createLegacyForced(
        channels: List<Int>,
        force: (Int) -> Boolean,
        beforeRetry: () -> Unit,
        request: (P2pCreationRequest) -> Unit,
    ): P2pCreationRequest {
        val plans: List<Int?> = channels + listOf<Int?>(null)
        var lastRejection: P2pCreateRejected? = null
        for ((index, channel) in plans.withIndex()) {
            if (channel != null && !force(channel)) {
                val rejection = P2pCreateRejected(
                    WifiP2pManager.ERROR,
                    "Wi-Fi P2P legacy channel set rejected channel=$channel",
                )
                lastRejection = rejection
                if (index < plans.lastIndex) { beforeRetry(); continue }
                throw rejection
            }
            val mode = when (val frequency = channel?.let(P2pLegacyChannel::frequencyOf)) {
                null -> P2pCreationRequest(P2pCreationMode.SYSTEM_DEFAULT)
                else -> if (frequency > 4900) P2pCreationRequest(P2pCreationMode.FIXED_5_GHZ, frequency)
                    else P2pCreationRequest(P2pCreationMode.FIXED_2_GHZ, frequency)
            }
            try {
                request(mode)
                return mode
            } catch (failure: P2pCreateRejected) {
                when {
                    failure.reason == WifiP2pManager.NO_PERMISSION ||
                        failure.reason == WifiP2pManager.P2P_UNSUPPORTED -> throw failure
                    else -> {
                        lastRejection = failure
                        if (index < plans.lastIndex) { beforeRetry(); continue }
                        throw failure
                    }
                }
            }
        }
        throw (lastRejection ?: error("No Wi-Fi Direct creation mode attempted"))
    }

    fun create(
        stationFrequency: Int?,
        beforeRetry: () -> Unit,
        preferred: P2pCreationRequest? = null,
        preferredChannel: Int = WifiP2pChannels.AUTO,
        bandRequests: Boolean = true,
        fiveGhz: Boolean = true,
        busyCause: (P2pCreateRejected) -> IOException? = { null },
        request: (P2pCreationRequest) -> Unit,
    ): P2pCreationRequest {
        val modes = plan(stationFrequency, preferred, preferredChannel, bandRequests, fiveGhz)
        var lastRejection: P2pCreateRejected? = null
        for ((index, mode) in modes.withIndex()) {
            var retriedBusy = false
            while (true) {
                try {
                    request(mode)
                    return mode
                } catch (failure: P2pConfigBuildCompatibilityFailure) {
                    if (preferredChannel != WifiP2pChannels.AUTO) {
                        throw P2pChannelUnavailableException(preferredChannel, failure.message.orEmpty(), failure)
                    }
                    if (mode.mode == P2pCreationMode.SYSTEM_DEFAULT) throw failure
                    // No creation request was issued. Keep the existing foreign-group and
                    // prerequisite interlock, then try the API 29 null-config overload once.
                    beforeRetry()
                    val systemDefault = P2pCreationRequest(P2pCreationMode.SYSTEM_DEFAULT)
                    request(systemDefault)
                    // Its generated credentials must be read from the real returned group.
                    // A failed default request exits directly instead of reentering this plan.
                    return systemDefault
                } catch (failure: P2pCreateRejected) {
                    lastRejection = failure
                    // A BUSY with a known cause that no other channel removes ends the plan here.
                    if (failure.reason == WifiP2pManager.BUSY) busyCause(failure)?.let { throw it }
                    when {
                        // A rejection that is not tied to one channel cannot be fixed by asking
                        // for a different channel, and retrying would only stall the bring-up.
                        failure.reason == WifiP2pManager.NO_PERMISSION ||
                            failure.reason == WifiP2pManager.P2P_UNSUPPORTED -> throw failure
                        failure.reason == WifiP2pManager.BUSY && !retriedBusy -> {
                            retriedBusy = true
                            beforeRetry()
                        }
                        // BUSY is a per-channel outcome, not a verdict on Wi-Fi Direct: several
                        // firmwares reject every 5 GHz request while 2.4 GHz or the system
                        // default configuration still succeeds. Keep walking the plan instead
                        // of giving up on the first channel the driver refuses.
                        index < modes.lastIndex -> {
                            beforeRetry()
                            break
                        }
                        else -> if (preferredChannel != WifiP2pChannels.AUTO) {
                            throw P2pChannelUnavailableException(preferredChannel, failure.message.orEmpty(), failure)
                        } else throw failure
                    }
                }
            }
        }
        throw lastRejection ?: error("No Wi-Fi Direct creation mode attempted")
    }
}
