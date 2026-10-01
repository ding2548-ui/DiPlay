package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.VideoInCar
import java.io.Closeable

/** iOS 27 video in car, played by the host in the car's own player (see [VideoInCar]). */
interface CarPlayVideoListener {
    /** Whether video may play now (Leapmotor: N 挡), or null when it cannot tell. Blocking; read once a second while CarPlay runs. */
    fun readVideoAllowed(): Boolean?

    /** Video became allowed or not; when not, the player must close. Any thread. */
    fun onVideoAllowedChanged(allowed: Boolean)

    /** A playback message from the iPhone, e.g. insertPlayQueueItem, setRate, seek, stop. Any thread. */
    fun onVideoMessage(streamId: Long, message: Map<String, Any?>)

    /** The iPhone asks the car to show its video player (requestUI "videoplayback:"). Any thread. */
    fun onVideoUiRequested()

    /** The CarPlay session ended; the player must close. Any thread. */
    fun onVideoSessionEnded()
}

/**
 * Keeps [VideoInCar.allowed] in step with the car: allowed only while the gear reads N (Leapmotor
 * has no P gear reading), so an unknown gear (no CAN data) keeps video off. Changes go to [onChanged].
 */
internal class VideoInCarGate(
    private val readVideoAllowed: () -> Boolean?,
    private val onChanged: (Boolean) -> Unit,
) : Closeable {
    @Volatile private var closed = false

    fun start() {
        Thread({
            while (!closed) {
                update(runCatching(readVideoAllowed).getOrNull())
                try {
                    Thread.sleep(POLL_MILLIS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "diplay-video-gate").apply { isDaemon = true }.start()
    }

    internal fun update(allowed: Boolean?) {
        val gate = allowed == true
        if (gate == VideoInCar.allowed || closed) return
        VideoInCar.allowed = gate
        onChanged(gate)
    }

    override fun close() {
        closed = true
        VideoInCar.allowed = false
    }

    private companion object {
        const val POLL_MILLIS = 1_000L
    }
}
