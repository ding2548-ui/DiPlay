package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayNowPlaying

/** One send attempt for each identifiable playing media of a playback owner. */
internal class InitialAlbumRecovery {
    private var reference: Int? = null
    private var sourceApp: String? = null
    private var bound = false
    private var colorComplete = false
    private var attempted = false
    private var scheduled = false
    private var inFlight = false
    private var generation = 0L
    private val events = ArrayDeque<String>()

    val pendingToken: Long get() = generation
    val timerPending: Boolean get() = scheduled

    fun takeEvents(): List<String> = events.toList().also { events.clear() }

    fun observe(info: CarPlayNowPlaying, hasColor: Boolean, allowRetry: () -> Boolean = { true }): Boolean {
        if (!eligible(info, hasColor, allowRetry)) return false
        if (scheduled || inFlight || attempted) return false
        scheduled = true
        generation++
        return true
    }

    /** A cancelled timer/worker cannot resume under a newer generation. */
    fun beginRequest(token: Long, info: CarPlayNowPlaying, hasColor: Boolean,
                     allowRetry: () -> Boolean = { true }): Boolean {
        if (token != generation || !scheduled || !eligible(info, hasColor, allowRetry)) return false
        if (token != generation || !scheduled) return false
        scheduled = false
        inFlight = true
        return true
    }

    fun stillNeeded(token: Long, info: CarPlayNowPlaying, hasColor: Boolean,
                    allowRetry: () -> Boolean = { true }): Boolean =
        token == generation && inFlight && !attempted && eligible(info, hasColor, allowRetry) &&
            token == generation && inFlight

    /** Called immediately before channel.send; a failed send also spends the attempt. */
    fun claimSend(token: Long, info: CarPlayNowPlaying, hasColor: Boolean,
                  allowRetry: () -> Boolean = { true }): Boolean {
        if (!stillNeeded(token, info, hasColor, allowRetry)) return false
        attempted = true
        return true
    }

    /** Preflight skips release the worker but never start another timer by themselves. */
    fun finished(token: Long) {
        if (token == generation) inFlight = false
    }

    private fun eligible(info: CarPlayNowPlaying, hasColor: Boolean, allowRetry: () -> Boolean): Boolean {
        val changed = when {
            bound && info.artworkTransferId != reference -> "referenceChanged"
            bound && sourceApp != null && info.sourceApp != sourceApp -> "sourceChanged"
            else -> null
        }
        if (changed != null) {
            cancelPending(changed)
            bound = false
            colorComplete = false
            attempted = false
        }
        if (attempted) return false
        // Paused connection metadata never chooses a media item or spends the send budget.
        if (!info.playing) {
            cancelPending("paused")
            return false
        }
        if (info.artworkTransferId == null) return false
        if (!bound) {
            bound = true
            reference = info.artworkTransferId
            sourceApp = info.sourceApp
        }
        // The app field can arrive after the first reference in an incremental update.
        if (sourceApp == null) sourceApp = info.sourceApp
        if (hasColor) {
            if (!colorComplete) cancelPending("colorReady", report = true)
            colorComplete = true
        }
        if (colorComplete) return false
        if (!allowRetry()) {
            cancelPending("settingDisabled")
            return false
        }
        return true
    }

    private fun cancelPending(reason: String, report: Boolean = false) {
        if (scheduled || inFlight || report) events.addLast("cancelled reason=$reason")
        if (scheduled || inFlight) generation++
        scheduled = false
        inFlight = false
    }
}
