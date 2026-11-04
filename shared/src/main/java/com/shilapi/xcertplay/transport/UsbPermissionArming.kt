package com.shilapi.xcertplay.transport

import android.os.SystemClock

/**
 * Time-bounded hand-off between our own USB permission request and the accessibility service that
 * clicks the system prompt (`UsbAutoConfirmService`).
 *
 * The accessibility service stays enabled from boot, so without this it would treat any window
 * that merely mentions this app and USB as a prompt — a settings page, another player's dialog,
 * a chat message — and click its "OK". Arming narrows the service to the few seconds that follow
 * an actual `UsbManager.requestPermission()` call.
 */
object UsbPermissionArming {
    /**
     * Covers a wired re-enumeration cycle (two rounds, about 10 s measured on the head unit) plus
     * the prompt appearing. Anything longer only widens the window in which a wrong click is
     * possible.
     */
    private const val ARM_MILLIS = 45_000L

    @Volatile
    private var armedUntil = 0L

    /** Device that triggered the request; diagnostics only. */
    @Volatile
    var armedDevice: String? = null
        private set

    fun arm(device: String) {
        armedDevice = device
        armedUntil = SystemClock.uptimeMillis() + ARM_MILLIS
    }

    fun disarm() {
        armedUntil = 0L
        armedDevice = null
    }

    fun isArmed(now: Long = SystemClock.uptimeMillis()): Boolean = now < armedUntil
}
