package com.shilapi.xcertplay.media

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A request that the decoder stop rendering to [surface] (a SurfaceHolder destroy callback must not
 * return before that). The decoder worker completes it once its codec no longer renders there: moved to
 * another surface, parked on its own offscreen consumer, or released. A worker that shuts down completes
 * its pending requests after releasing the codec.
 */
internal class SurfaceDetachRequest(val surface: Any, val park: Boolean) {
    private val done = CountDownLatch(1)
    @Volatile var outcome: DetachOutcome? = null
        private set

    fun complete(result: DetachOutcome) {
        if (outcome == null) outcome = result
        done.countDown()
    }

    /**
     * Waits until the request completes or [workerAlive] turns false (its codec is then released), up to
     * [timeoutNanos]. An interrupt does not end the wait early; it is restored before returning. Returns
     * false only when neither happened in time.
     */
    fun await(timeoutNanos: Long, workerAlive: () -> Boolean, nanoTime: () -> Long = System::nanoTime): Boolean {
        val deadline = nanoTime() + timeoutNanos
        var interrupted = false
        try {
            while (true) {
                if (done.count == 0L || !workerAlive()) return true
                val left = deadline - nanoTime()
                if (left <= 0) return false
                try {
                    if (done.await(minOf(left, POLL_NANOS), TimeUnit.NANOSECONDS)) return true
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private companion object {
        // Re-check that the worker is still alive this often while waiting.
        const val POLL_NANOS = 50_000_000L
    }
}

internal enum class DetachOutcome { NOT_RENDERING, PARKED, RELEASED }

/**
 * What the worker does for a detach request, from the surface its codec actually renders to (not the
 * sink's latest wish, which can run ahead of the queue): nothing when that is another surface already,
 * otherwise park the codec when asked and possible, else release it.
 */
internal fun detachAction(current: Any?, leaving: Any, park: Boolean, hasDecoder: Boolean): DetachOutcome = when {
    current !== leaving -> DetachOutcome.NOT_RENDERING
    park && hasDecoder -> DetachOutcome.PARKED
    else -> DetachOutcome.RELEASED
}
