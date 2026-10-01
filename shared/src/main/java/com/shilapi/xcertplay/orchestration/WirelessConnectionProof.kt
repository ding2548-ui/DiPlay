package com.shilapi.xcertplay.orchestration

/** Confirms one current session only after authenticated tunnel readiness AND a rendered frame. */
internal class WirelessConnectionProof<S : Any> {
    private var generation = -1
    private var session: S? = null
    private var authenticated = false
    private var rendered = false
    private var confirmation: (() -> Unit)? = null

    @Synchronized fun begin(generation: Int, confirmation: () -> Unit) {
        clear()
        this.generation = generation
        this.confirmation = confirmation
    }

    @Synchronized fun activate(generation: Int, session: S) {
        if (this.generation != generation || this.session === session) return
        this.session = session
        authenticated = false
        rendered = false
    }

    @Synchronized fun authenticated(generation: Int) {
        if (this.generation != generation || session == null) return
        authenticated = true
        confirmIfReady()
    }

    @Synchronized fun rendered(generation: Int, session: S) {
        if (this.generation != generation || this.session !== session) return
        rendered = true
        confirmIfReady()
    }

    @Synchronized fun hasRenderedFrame(generation: Int): Boolean =
        this.generation == generation && session != null && rendered

    @Synchronized fun end(generation: Int, session: S) {
        if (this.generation != generation || this.session !== session) return
        this.session = null
        authenticated = false
        rendered = false
    }

    @Synchronized fun clear() {
        generation = -1
        session = null
        authenticated = false
        rendered = false
        confirmation = null
    }

    private fun confirmIfReady() {
        if (!authenticated || !rendered) return
        val callback = confirmation ?: return
        confirmation = null
        callback()
    }
}
