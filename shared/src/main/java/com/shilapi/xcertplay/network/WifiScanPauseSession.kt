package com.shilapi.xcertplay.network

/** What one attempt to switch the framework's automatic network search reported. */
internal enum class WifiScanSwitchResult(val reason: String, val changedNothing: Boolean) {
    DONE("done", false),

    /** Pause only: automatic joining was already off, so it is someone else's to restore. */
    ALREADY_OFF("already-off", true),
    ADB_OFF("adb-off", true),
    ADB_NOT_APPROVED("adb-not-approved", true),
    ADB_PAIRING_ONLY("adb-pairing-only", true),
    UNSUPPORTED("unsupported-firmware", true),

    /** The framework refused the caller before changing anything. */
    DENIED("denied", true),

    /** No usable reply: the change may or may not have happened. */
    UNKNOWN("no-reply", false),
}

/** SharedPreferences updates its memory cache even when commit fails; retain only durable state. */
internal class WifiScanPauseJournal(initialPending: Boolean, private val commit: (Boolean) -> Boolean) {
    var pending = initialPending
        private set

    fun save(value: Boolean): Boolean = commit(value).also { committed ->
        if (committed) pending = value
    }
}

/** Process-wide state used only by one serial worker; no restore can race a newer pause. */
internal class WifiScanPauseSession(
    private val setEnabled: (Boolean) -> WifiScanSwitchResult,
    private val loadJournal: () -> Boolean,
    private val saveJournal: (Boolean) -> Boolean,
) {
    /** [reason] is a [WifiScanSwitchResult.reason], or why no switch was attempted. */
    class Outcome(val paused: Boolean, val reason: String)

    private val owners = mutableSetOf<Any>()
    private var pauseConfirmed = false

    fun acquire(owner: Any, current: () -> Boolean): Outcome {
        if (!current()) return Outcome(false, CANCELLED)
        owners.add(owner)
        if (pauseConfirmed) return Outcome(true, WifiScanSwitchResult.DONE.reason)
        var reply: WifiScanSwitchResult? = null
        var acquired = false
        try {
            val earlierPause = loadJournal()
            if (!earlierPause && !saveJournal(true)) return Outcome(false, JOURNAL_FAILED)
            // Persist before the write: a lost shell reply or process death may follow a real mutation.
            val result = setEnabled(false).also { reply = it }
            // A pause left by an earlier process is still ours; its marker restores it later.
            pauseConfirmed = result == WifiScanSwitchResult.DONE ||
                (result == WifiScanSwitchResult.ALREADY_OFF && earlierPause)
            acquired = pauseConfirmed && current()
            // Nothing was switched and no older pause awaits restore: drop the marker instead of
            // retrying a restore every 30 s for a pause that never happened.
            if (!pauseConfirmed && result.changedNothing && !earlierPause) saveJournal(false)
        } finally {
            if (!acquired) owners.remove(owner)
        }
        if (acquired) return Outcome(true, WifiScanSwitchResult.DONE.reason)
        val reason = if (pauseConfirmed) CANCELLED else reply?.reason ?: WifiScanSwitchResult.UNKNOWN.reason
        recover()
        return Outcome(false, reason)
    }

    fun release(owner: Any): Boolean {
        owners.remove(owner)
        return recover()
    }

    fun recover(): Boolean {
        if (owners.isNotEmpty()) return true
        pauseConfirmed = false
        if (!loadJournal()) return true
        if (setEnabled(true) != WifiScanSwitchResult.DONE) return false
        return saveJournal(false)
    }

    fun recoveryPending(): Boolean = owners.isEmpty() && loadJournal()

    private companion object {
        const val CANCELLED = "cancelled"
        const val JOURNAL_FAILED = "journal-failed"
    }
}
