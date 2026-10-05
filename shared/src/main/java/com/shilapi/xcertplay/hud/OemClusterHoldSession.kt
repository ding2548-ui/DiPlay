package com.shilapi.xcertplay.hud

/** Used only on one serial worker. The journal is durable before the first OEM write. */
internal class OemClusterHoldSession(
    private val readState: (Target) -> Int?,
    private val setState: (Target, Int) -> Boolean,
    private val loadJournal: () -> Journal?,
    private val saveJournal: (Journal?) -> Boolean,
) {
    enum class Target { COMPONENT, PACKAGE }
    data class Journal(val target: Target, val originalState: Int, val lease: String)
    private var ownedLease: String? = null

    fun acquire(mode: BydOemClusterHold, lease: String, current: () -> Boolean): Boolean {
        if (!current()) return false
        val target = when (mode) {
            BydOemClusterHold.OFF -> return release()
            BydOemClusterHold.COMPONENT -> Target.COMPONENT
            BydOemClusterHold.PACKAGE -> Target.PACKAGE
        }
        val pending = loadJournal()
        if (pending != null && ownedLease == lease && pending.target == target &&
            readState(target) == DISABLED_USER) return current()
        if (pending != null && !release()) return false
        if (!current()) return false
        val previous = readState(target)?.takeIf { it in 0..4 } ?: return false
        if (!saveJournal(Journal(target, previous, lease))) return false
        ownedLease = lease
        if (current() && setState(target, DISABLED_USER) && readState(target) == DISABLED_USER && current()) return true
        // Failed commands can still have mutated the OEM state. Keep recovery evidence on failure.
        release(lease)
        return false
    }

    fun release(lease: String? = null): Boolean {
        val pending = loadJournal() ?: return true
        // A late failed launch must not undo a newer activity's hold.
        if (lease != null && lease != pending.lease) return true
        if (readState(pending.target) != pending.originalState &&
            !setState(pending.target, pending.originalState)) return false
        if (readState(pending.target) != pending.originalState || !saveJournal(null)) return false
        ownedLease = null
        return true
    }

    companion object { const val DISABLED_USER = 3 }
}
