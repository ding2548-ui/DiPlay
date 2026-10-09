package com.shilapi.xcertplay.network

import android.net.wifi.p2p.WifiP2pManager
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object WifiP2pLegacyChannels {

    const val LISTEN_CHANNEL = 6

    private const val CALLBACK_TIMEOUT_MILLIS = 2_000L

    fun operatingChannelFor(frequencyMHz: Int): Int? = when {
        frequencyMHz in 5180..5240 || frequencyMHz in 5745..5825 ->
            wifiFrequencyMhzToChannel(frequencyMHz)
        frequencyMHz in 2412..2472 -> wifiFrequencyMhzToChannel(frequencyMHz)?.takeIf { it in 1..13 }
        else -> null
    }

    fun bandLabelFor(channel: Int): String = if (channel in 1..14) "2.4 GHz" else "5 GHz"

    fun apply(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        operatingChannel: Int,
        diagnostic: (String) -> Unit,
    ): Boolean {
        val method = try {
            WifiP2pManager::class.java.getMethod(
                "setWifiP2pChannels",
                WifiP2pManager.Channel::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                WifiP2pManager.ActionListener::class.java,
            )
        } catch (missing: Throwable) {
            diagnostic("Wi-Fi P2P legacy channel selection unavailable failureClass=${failureClass(missing)}")
            return false
        }
        val settled = CountDownLatch(1)
        val accepted = AtomicBoolean(false)
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                accepted.set(true)
                settled.countDown()
            }

            override fun onFailure(reason: Int) {
                diagnostic("Wi-Fi P2P legacy channel rejected code=$reason requestedChannel=$operatingChannel")
                settled.countDown()
            }
        }
        try {
            method.invoke(manager, channel, LISTEN_CHANNEL, operatingChannel, listener)
        } catch (failure: Throwable) {
            diagnostic("Wi-Fi P2P legacy channel call failed failureClass=${failureClass(failure)}")
            if (failure is InvocationTargetException) {
                throw LegacyChannelOutcomeUnknown("Wi-Fi Direct channel selection failed with an unknown outcome", failure.cause)
            }
            return false
        }
        val completed = try {
            settled.await(CALLBACK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw LegacyChannelOutcomeUnknown("Interrupted while selecting the Wi-Fi Direct channel", interrupted)
        }
        if (!completed) {
            diagnostic("Wi-Fi P2P legacy channel callback timeout requestedChannel=$operatingChannel")
            // The command may already have changed the shared supplicant state. A retry
            // cannot safely treat an unanswered asynchronous request as a rejection.
            throw LegacyChannelOutcomeUnknown("Wi-Fi Direct channel selection did not respond")
        }
        return completed && accepted.get()
    }

    private fun failureClass(failure: Throwable): String = (failure.cause ?: failure).javaClass.simpleName
}

internal class LegacyChannelOutcomeUnknown(message: String, cause: Throwable? = null) :
    IOException(message, cause)
