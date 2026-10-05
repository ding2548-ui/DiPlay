package com.shilapi.xcertplay.hud

/** SharedPreferences updates its memory cache even when commit fails. Only confirmed commits count. */
internal class DiLink3ClusterRecoveryJournal(
    initial: Boolean,
    private val persist: (Boolean) -> Boolean,
) {
    var pending: Boolean = initial
        private set

    fun save(next: Boolean): Boolean {
        if (!persist(next)) return false
        pending = next
        return true
    }
}
