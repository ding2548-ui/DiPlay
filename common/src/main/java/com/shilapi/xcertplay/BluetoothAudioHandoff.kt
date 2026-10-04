// SPDX-License-Identifier: GPL-3.0-only
// CarPlay audio takeover: disconnect the phone's Bluetooth audio profiles so the sound
// cannot keep flowing through the car's A2DP sink while CarPlay carries the audio.
// Ported from EasyPlay's LegacyBluetoothAudioHandoff: profile proxies + reflective
// disconnect (the disconnect methods are hidden), with every skip reason reported.
package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object BluetoothAudioHandoff {
    private const val TAG = "DiPlay-A2dpHandoff"

    @Volatile
    var onDiagnostic: ((String) -> Unit)? = null

    @Volatile
    private var lastAttemptUptimeMillis = 0L

    /**
     * Entry point: the car audio profiles of every connected device are disconnected once
     * per takeover. Runs off the main thread (profile proxies must not be resolved there);
     * all failures are reported, none are fatal.
     */
    fun onCarPlayMediaActive(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAttemptUptimeMillis < ATTEMPT_COOLDOWN_MILLIS) return
        lastAttemptUptimeMillis = now
        if (!DiPlayPreferences.a2dpHandoff(context)) {
            report("a2dp handoff disabled by setting")
            return
        }
        val appContext = context.applicationContext
        Thread {
            runCatching { disconnectCarAudioProfiles(appContext) }
                .onFailure { report("a2dp handoff failed: ${it.message}") }
        }.start()
    }

    private fun disconnectCarAudioProfiles(context: Context) {
        val manager = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
        val adapter = manager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) return skip("adapter-off")
        if (!adapter.isEnabled) return skip("adapter-off")

        for (profile in PROFILE_NAMES) {
            val constant = profileConstant(profile)
            if (constant == null) {
                report("BT audio profile=$profile unavailable on this platform")
                continue
            }
            disconnectProfile(context, adapter, constant, profile)
        }
    }

    /** Resolves a BluetoothProfile constant by field name; missing ones report as unavailable. */
    private fun profileConstant(name: String): Int? = runCatching {
        BluetoothProfile::class.java.getField(name).getInt(null)
    }.getOrNull()

    private fun disconnectProfile(context: Context, adapter: BluetoothAdapter, constant: Int, label: String) {
        val done = CountDownLatch(1)
        var attempted = 0
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                try {
                    val devices = runCatching { proxy.connectedDevices }.getOrDefault(emptyList())
                    if (devices.isEmpty()) {
                        report("BT audio profile=$label no connected device")
                        return
                    }
                    val disconnect = runCatching {
                        proxy.javaClass.getMethod("disconnect", android.bluetooth.BluetoothDevice::class.java)
                    }.getOrNull()
                    if (disconnect == null) {
                        report("BT audio profile=$label platform api=missing disconnect method")
                        return
                    }
                    for (device in devices) {
                        attempted += 1
                        runCatching { disconnect.invoke(proxy, device) }
                            .onSuccess { report("BT audio profile=$label disconnect sent ${device.address}") }
                            .onFailure { report("BT audio profile=$label disconnect failed: ${it.cause?.message ?: it.message}") }
                    }
                } finally {
                    runCatching { adapter.closeProfileProxy(constant, proxy) }
                    done.countDown()
                }
            }

            override fun onServiceDisconnected(profile: Int) = Unit
        }
        val acquired = runCatching { adapter.getProfileProxy(context, listener, constant) }.getOrDefault(false)
        if (!acquired) {
            report("BT audio profile=$label proxy unavailable")
            return
        }
        // The proxy callback races the shutdown of a short CarPlay takeover; never wait long.
        if (!done.await(PROXY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            report("BT audio profile=$label timeout (attempted=$attempted)")
        }
    }

    private fun skip(reason: String) {
        report("a2dp handoff skipped: $reason")
    }

    private fun report(message: String) {
        Log.i(TAG, message)
        runCatching { onDiagnostic?.invoke(message) }
    }

    /** Car-side and phone-side names across stacks, as in EasyPlay's Profile enum. */
    private val PROFILE_NAMES = listOf("A2DP_SINK", "A2DP", "HFP_CLIENT", "HEADSET")
    private const val PROXY_TIMEOUT_SECONDS = 3L
    private const val ATTEMPT_COOLDOWN_MILLIS = 10_000L
}
