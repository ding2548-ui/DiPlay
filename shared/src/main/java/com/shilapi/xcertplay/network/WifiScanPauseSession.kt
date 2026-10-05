package com.shilapi.xcertplay.network

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
    private val transactionCode: () -> Int?,
    private val setEnabled: (Int, Boolean) -> Boolean,
    private val loadJournal: () -> Boolean,
    private val saveJournal: (Boolean) -> Boolean,
) {
    private val owners = mutableSetOf<Any>()
    private var pauseConfirmed = false

    fun acquire(owner: Any, current: () -> Boolean): Boolean {
        if (!current()) return false
        owners.add(owner)
        if (pauseConfirmed) return true
        var acquired = false
        try {
            val code = transactionCode() ?: return false
            if (!loadJournal() && !saveJournal(true)) return false
            // Persist before the write: a lost shell reply or process death may follow a real mutation.
            pauseConfirmed = setEnabled(code, false)
            acquired = pauseConfirmed && current()
        } finally {
            if (!acquired) owners.remove(owner)
        }
        if (acquired) return true
        recover()
        return false
    }

    fun release(owner: Any): Boolean {
        owners.remove(owner)
        return recover()
    }

    fun recover(): Boolean {
        if (owners.isNotEmpty()) return true
        pauseConfirmed = false
        if (!loadJournal()) return true
        // Always resolve the current framework method; never replay an old firmware's binder number.
        val code = transactionCode() ?: return false
        if (!setEnabled(code, true)) return false
        return saveJournal(false)
    }

    fun recoveryPending(): Boolean = owners.isEmpty() && loadJournal()
}
