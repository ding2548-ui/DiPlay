package com.shilapi.xcertplay.media

/**
 * Smooth video's display delay over a frame's local time ([FramePacer.localTime]).
 *
 * For each frame the decoder releases, the delay it needed is how long after its local time it came out.
 * The delay follows the [percentile] of the last [window] such needs plus [marginNanos], so about that
 * share of frames is ready before its display time; the rest are shown at once. The margin is a refresh
 * plus 4 ms because SurfaceFlinger takes a buffer about one refresh before the vsync it is shown at: on
 * my Tang, frames released less than about 16 ms before that vsync missed it. It starts at
 * [initialNanos] and holds it until [refreshEvery] needs are known. A changed goal is approached by at
 * most [risePerFrame] or [fallPerFrame] per frame, so a few milliseconds move a frame by at most one
 * refresh instead of shifting every later frame at once. It rises faster than it falls: frames that miss
 * their time are already shown unevenly.
 */
internal class PacingDelay(
    initialNanos: Long,
    private val minNanos: Long = 30_000_000L,
    private val maxNanos: Long = 200_000_000L,
    private val window: Int = 120,
    private val percentile: Int = 90,
    private val refreshEvery: Int = 15,
    private val marginNanos: Long = 20_000_000L,
    private val risePerFrame: Long = 1_000_000L,
    private val fallPerFrame: Long = 500_000L,
) {
    private val needs = LongArray(window)
    private var count = 0
    private var next = 0
    private var frames = 0

    var nanos: Long = initialNanos.coerceIn(minNanos, maxNanos)
        private set
    private var goal = nanos

    /** Records that a frame came out of the decoder [neededNanos] after its local time. */
    fun onFrame(neededNanos: Long) {
        needs[next] = neededNanos.coerceIn(0L, maxNanos)
        next = (next + 1) % window
        if (count < window) count++
        if (++frames % refreshEvery == 0) {
            val sorted = needs.copyOf(count).also { it.sort() }
            goal = (sorted[(count - 1) * percentile / 100] + marginNanos).coerceIn(minNanos, maxNanos)
        }
        nanos += (goal - nanos).coerceIn(-fallPerFrame, risePerFrame)
    }
}
