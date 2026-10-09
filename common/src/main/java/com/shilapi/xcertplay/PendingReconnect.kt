package com.shilapi.xcertplay

import java.lang.ref.WeakReference

/**
 * Remembers that a setting was saved during a CarPlay session but only applies after it reconnects.
 * Keyed by the session's controller, so a new session reads as not pending without any callback.
 * Weak, so an ended session's controller is not kept alive by this marker.
 */
internal object PendingReconnect {
    private var session = WeakReference<Any>(null)

    fun mark(session: Any) { this.session = WeakReference(session) }

    fun isPending(current: Any?): Boolean = current != null && current === session.get()

    fun clear() { session = WeakReference(null) }
}
