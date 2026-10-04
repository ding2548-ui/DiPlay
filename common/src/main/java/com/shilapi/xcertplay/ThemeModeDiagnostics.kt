package com.shilapi.xcertplay

import android.content.res.Configuration

/** Samples only Android's existing theme signal; never infers a theme from vehicle state. */
internal class ThemeModeDiagnostics {
    enum class Source(val label: String, val force: Boolean = false) {
        CREATE("create", true),
        START("start", true),
        STOP("stop", true),
        POLL("poll"),
        CALLBACK("configuration-callback"),
        WINDOW_FOCUS("window-focus"),
        SESSION_ACTIVE("session-active", true),
    }

    private data class State(val uiMode: Int, val appliedNight: Boolean, val sessionActive: Boolean)

    private var lastLoggedState: State? = null
    private var lastLoggedAt = 0L
    private var polls = 0
    private var callbacks = 0

    fun observe(
        source: Source,
        uiMode: Int,
        appliedNight: Boolean,
        sessionActive: Boolean,
        elapsedMillis: Long,
    ): String? {
        if (source == Source.POLL) polls++
        if (source == Source.CALLBACK) callbacks++
        val state = State(uiMode, appliedNight, sessionActive)
        if (!source.force && state == lastLoggedState && elapsedMillis - lastLoggedAt < 60_000L) return null

        val reported = when (nightModeOrNull(uiMode)) {
            true -> "dark"
            false -> "light"
            null -> "undefined"
        }
        val nightMask = uiMode and Configuration.UI_MODE_NIGHT_MASK
        val message = "THEME_DIAGNOSTIC sample source=${source.label} uiMode=0x${uiMode.toString(16)} " +
            "nightMask=0x${nightMask.toString(16)} reported=$reported " +
            "applied=${if (appliedNight) "dark" else "light"} sessionActive=$sessionActive " +
            "pollsSinceSample=$polls callbacksSinceSample=$callbacks"
        lastLoggedState = state
        lastLoggedAt = elapsedMillis
        polls = 0
        callbacks = 0
        return message
    }
}
