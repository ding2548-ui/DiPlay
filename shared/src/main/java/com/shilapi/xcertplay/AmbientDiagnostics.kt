package com.shilapi.xcertplay

import android.util.Log

/**
 * The single reporting path for the ambient-light ("氛围灯") subsystem: logcat **and** the exported
 * diagnostic report.
 *
 * The report is assembled from `CarPlayHostActivity.appendLog`, not from a logcat dump, so a line
 * that only calls `Log.*` never reaches it. Every ambient line used to go out on its own
 * `DiPlay-Ambient` tag and was therefore invisible in a report the driver sends back — which made
 * the "复测判据" in `RELEASE_NOTES.md` impossible to satisfy (it told the tester to grep the report
 * for `DiPlay-Ambient: ...`).
 *
 * Lines go out on `xcertplay-usb` (the tag `adb logcat -s` is documented with, so on-car debugging
 * keeps working) and are forwarded to [onDiagnostic] once a host has bound one.
 *
 * Field names must survive [DiagnosticRedactor]: it silently drops any line containing `pass`,
 * `token`, `ssid`, `certificate`, `body=`, `payload=` or `hex=`. None of the ambient fields do.
 */
object AmbientDiagnostics {
    /** Bound by `CarPlayHostActivity`; null while no host is up (e.g. from a unit test). */
    @Volatile var onDiagnostic: ((String) -> Unit)? = null

    fun log(message: String) {
        Log.i(TAG, message)
        runCatching { onDiagnostic?.invoke(message) }
    }

    fun warn(message: String, error: Throwable? = null) {
        if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
        val line = if (error == null) message else "$message (${error.javaClass.simpleName})"
        runCatching { onDiagnostic?.invoke(line) }
    }

    private const val TAG = "xcertplay-usb"
}
