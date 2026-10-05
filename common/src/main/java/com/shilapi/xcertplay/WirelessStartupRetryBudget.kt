package com.shilapi.xcertplay

import com.shilapi.xcertplay.network.WirelessStartupPolicy

internal class WirelessStartupRetryBudget {
    var retries = 0
        private set
    private var frameSession: Any? = null
    private var firstFrameMillis = 0L

    fun nextDelayMillis(): Long? {
        if (retries >= WirelessStartupPolicy.MAX_STARTUP_RETRIES) return null
        return (2_000L * (1L shl retries++)).coerceAtMost(30_000L)
    }

    fun firstFrame(session: Any, nowMillis: Long): Boolean {
        if (frameSession === session) return false
        frameSession = session
        firstFrameMillis = nowMillis
        return true
    }

    fun resetIfStable(session: Any, nowMillis: Long): Boolean {
        if (frameSession !== session || nowMillis - firstFrameMillis < WirelessStartupPolicy.STABLE_SESSION_MILLIS) return false
        retries = 0
        return true
    }

    fun disconnected() { frameSession = null }
    fun manualRetry() { retries = 0; disconnected() }
}
