package com.shilapi.xcertplay.hud

/** One serial worker owns all DiLink 3 writes. Persist recovery before a command can change the car. */
internal class DiLink3ClusterModeSession(
    private val run: (String) -> String?,
    private val loadRecovery: () -> Boolean,
    private val saveRecovery: (Boolean) -> Boolean,
) {
    private var owned = false
    private var restorePending = false
    private var applied: BydDiLink3ClusterMode.Mode? = null

    /** A new process must restore an interrupted output before starting another one. */
    fun recoverInterrupted(): Boolean =
        if (restorePending || (!owned && loadRecovery())) restoreStock() else true

    fun apply(mode: BydDiLink3ClusterMode.Mode?): Boolean {
        if (!recoverInterrupted()) return false
        if (mode == null || mode == BydDiLink3ClusterMode.Mode.STOCK) return restoreStock()
        if (applied == mode && loadRecovery()) return true
        if (!saveRecovery(true)) return false
        owned = true // A missing reply can still mean the command changed the car.
        val accepted = BydDiLink3ClusterMode.accepted(run(mode.command))
        applied = if (accepted) mode else null
        return accepted
    }

    /** Failure of any preparation step must compensate the earlier full-screen projection. */
    fun prepareDisplay(
        displayPresent: () -> Boolean,
        currentMode: () -> BydDiLink3ClusterMode.Mode?,
        stillWanted: () -> Boolean,
        stepDelay: () -> Unit,
    ): Boolean {
        if (!recoverInterrupted()) return false
        if (displayPresent()) return true
        if (!stillWanted() || !saveRecovery(true)) return false
        owned = true
        applied = null
        var prepared = false
        var compensated = false
        try {
            var completedSteps = 0
            for (command in BydDiLink3ClusterMode.CREATE_DISPLAY.take(2)) {
                if (!stillWanted() || !BydDiLink3ClusterMode.accepted(run(command))) {
                    break
                }
                stepDelay()
                completedSteps++
            }
            prepared = completedSteps == 2
        } catch (_: Exception) {
            prepared = false
        } finally {
            // On failure restore STOCK even if a map request arrived during preparation. The next
            // queued request may retry that map, after compensation/recovery has completed.
            compensated = runCatching {
                val mode = if (prepared && stillWanted()) currentMode() else null
                if (mode == null) restoreStock()
                else if (runCatching { apply(mode) }.getOrDefault(false)) true
                else {
                    prepared = false
                    restoreStock()
                }
            }.getOrDefault(false)
        }
        return prepared && compensated && displayPresent()
    }

    private fun restoreStock(): Boolean {
        if (!owned && !loadRecovery()) return true
        restorePending = true
        if (!BydDiLink3ClusterMode.accepted(run(BydDiLink3ClusterMode.Mode.STOCK.command))) return false
        if (!saveRecovery(false)) return false
        owned = false
        restorePending = false
        applied = null
        return true
    }
}
