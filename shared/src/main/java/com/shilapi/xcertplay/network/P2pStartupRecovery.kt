package com.shilapi.xcertplay.network

import android.net.wifi.p2p.WifiP2pManager
import java.io.IOException

internal enum class P2pCreationMode { ALIGNED_5_GHZ, ALIGNED_2_GHZ, FIXED_5_GHZ, FIXED_2_GHZ, SYSTEM_DEFAULT, PREFERRED_CHANNEL }

internal data class P2pCreationRequest(val mode: P2pCreationMode, val frequencyMHz: Int? = null)

internal class P2pCreateRejected(val reason: Int, message: String) : IOException(message)

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

    /** A band-only request still needs channel selection, which some BYD drivers cannot do. */
    fun plan(stationFrequency: Int?, preferred: P2pCreationRequest? = null,
             preferredChannel: Int = WifiP2pChannels.AUTO): List<P2pCreationRequest> = buildList {
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
        if (preferred?.mode == P2pCreationMode.SYSTEM_DEFAULT && preferred.frequencyMHz == null) add(preferred)
        else preferred?.frequencyMHz?.let(::rememberedFrequency)?.let { channel(it.mode, it.frequencyMHz!!) }
        if (aligned24) channel(P2pCreationMode.ALIGNED_2_GHZ, requireNotNull(stationFrequency))
        if (aligned5) channel(P2pCreationMode.ALIGNED_5_GHZ, requireNotNull(stationFrequency))
        fun twoGhz() = listOf(2437, 2412, 2462).forEach { channel(P2pCreationMode.FIXED_2_GHZ, it) }
        fun fiveGhz() = listOf(5180, 5745).forEach { channel(P2pCreationMode.FIXED_5_GHZ, it) }
        // Keep a shared radio on its existing station channel when possible. Otherwise prefer
        // explicit non-DFS 5 GHz, then channels 6/1/11. The platform enforces regulatory limits.
        if (aligned24) { twoGhz(); fiveGhz() } else { fiveGhz(); twoGhz() }
        // Some vendors only implement the default-configuration API. Use it last, after every
        // explicit-frequency option has been rejected, never before the 2.4 GHz attempts.
        if (none { it.mode == P2pCreationMode.SYSTEM_DEFAULT }) add(P2pCreationRequest(P2pCreationMode.SYSTEM_DEFAULT))
    }

    fun create(
        stationFrequency: Int?,
        beforeRetry: () -> Unit,
        preferred: P2pCreationRequest? = null,
        preferredChannel: Int = WifiP2pChannels.AUTO,
        request: (P2pCreationRequest) -> Unit,
    ): P2pCreationRequest {
        val modes = plan(stationFrequency, preferred, preferredChannel)
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
