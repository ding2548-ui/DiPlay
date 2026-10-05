package com.shilapi.xcertplay.media

/** A ROM can reject a legacy stream by throwing or returning an uninitialized track. */
internal object LegacyAudioFallback {
    fun <T> build(
        createLegacy: () -> T,
        isInitialized: (T) -> Boolean,
        release: (T) -> Unit,
        createFallback: () -> T,
    ): T {
        val candidate = try { createLegacy() } catch (_: RuntimeException) { null }
        if (candidate != null) {
            if (isInitialized(candidate)) return candidate
            release(candidate)
        }
        return createFallback()
    }
}
