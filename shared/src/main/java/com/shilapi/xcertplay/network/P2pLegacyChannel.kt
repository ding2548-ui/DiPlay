package com.shilapi.xcertplay.network

import android.net.wifi.p2p.WifiP2pManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Arms the group-owner operating channel through the hidden setWifiP2pChannels API, which has
 *  existed since early releases. On Android 7.x this is the only way to keep the GO on the
 *  station's channel — single-radio head units pay for every band switch otherwise. */
internal object P2pLegacyChannel {
    enum class Outcome { OK, FAILED, NO_REPLY, UNAVAILABLE }

    data class Frequency(val mhz: Int?, val source: String)

    private const val CALL_TIMEOUT_MILLIS = 2_000L
    private val PREFERRED = listOf(149, 36, 6, 1, 11)

    /** The station's own channel first (single-radio alignment), then the proven GO channels. */
    fun candidates(stationMHz: Int?): List<Int> =
        (listOfNotNull(stationMHz?.let(::channelOf)) + PREFERRED).distinct()

    fun channelOf(frequencyMHz: Int): Int? =
        wifiFrequencyMhzToChannel(frequencyMHz)?.takeIf { frequencyOf(it) == frequencyMHz }

    fun frequencyOf(channel: Int): Int? = when {
        channel in 1..13 -> 2412 + (channel - 1) * 5
        channel == 14 -> 2484
        channel in 36..166 -> 5000 + channel * 5
        else -> null
    }

    /** A known driver frequency wins; the armed channel only fills in when the driver query came
     *  up empty. [source] is diagnostic ("driver=forced" / "driver!=forced" / "forced"). */
    fun resolve(driverMHz: Int?, forcedChannel: Int?): Frequency {
        val forced = forcedChannel?.let(::frequencyOf)
        if (driverMHz != null && driverMHz > 0) {
            val source = if (forced == driverMHz) "driver=forced" else "driver!=forced"
            return Frequency(driverMHz, source)
        }
        return forced?.let { Frequency(it, "forced") } ?: Frequency(null, "unknown")
    }

    fun set(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        goChannel: Int,
        timeoutMillis: Long = CALL_TIMEOUT_MILLIS.toLong(),
    ): Pair<Outcome, String> {
        val settled = AtomicReference<Pair<Outcome, String>?>(null)
        val latch = CountDownLatch(1)
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                settled.compareAndSet(null, Pair(Outcome.OK, "ok"))
                latch.countDown()
            }

            override fun onFailure(reason: Int) {
                settled.compareAndSet(null, Pair(Outcome.FAILED, "code=$reason"))
                latch.countDown()
            }
        }
        val invoked = try { invokeHidden(manager, channel, goChannel, listener) } catch (_: Throwable) { false }
        if (!invoked) return Pair(Outcome.UNAVAILABLE, "method=missing")
        return when {
            latch.await(timeoutMillis, TimeUnit.MILLISECONDS) ->
                settled.get() ?: Pair(Outcome.NO_REPLY, "no-reply")
            else -> Pair(Outcome.NO_REPLY, "timeout")
        }
    }

    private fun invokeHidden(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        goChannel: Int,
        listener: WifiP2pManager.ActionListener,
    ): Boolean {
        val method = runCatching {
            WifiP2pManager::class.java.getMethod(
                "setWifiP2pChannels",
                WifiP2pManager.Channel::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                WifiP2pManager.ActionListener::class.java,
            )
        }.getOrNull() ?: return false
        // Listen channel 0 leaves the station's listen behaviour to the framework; only the GO
        // operating channel is armed here.
        method.invoke(manager, channel, 0, goChannel, listener)
        return true
    }
}
